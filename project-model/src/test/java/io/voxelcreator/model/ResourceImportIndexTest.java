package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Deferred indexed conflict lookup parity: UUID precedence, exact names, roots and final targets.
 */
class ResourceImportIndexTest {
  private static Noise noise(ProjectData p, String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    p.noise2D.add(n);
    return n;
  }

  private static ResourceImport.Row row(ResourceImport.Plan plan, UUID uuid) {
    return plan.rows().stream().filter(r -> r.uuid().equals(uuid)).findFirst().orElseThrow();
  }

  @Test
  void aLargeReuseCatalogHasCompleteStableTargetsWithoutChangingEitherInput() {
    var destination = new ProjectData();
    for (int i = 0; i < 2048; i++) {
      var type = new VoxelType();
      type.id = "type-" + i;
      destination.voxelTypes.add(type);
    }
    var incoming = ProjectIO.copy(destination);
    var choices = new HashMap<UUID, ResourceImport.Choice>();
    incoming.voxelTypes.forEach(t -> choices.put(t.uuid, ResourceImport.Choice.REUSE));
    var before = ProjectIO.JSON.valueToTree(destination);
    var source = ProjectIO.JSON.valueToTree(incoming);
    var plan = ResourceImport.plan(destination, incoming, choices);
    assertTrue(plan.valid(), plan.diagnostics().toString());
    assertEquals(2048, plan.rows().size());
    assertTrue(
        plan.rows().stream()
            .allMatch(
                r ->
                    r.compatible()
                        && r.uuid().equals(r.targetUuid())
                        && r.id().equals(r.targetId())));
    assertEquals(before, ProjectIO.JSON.valueToTree(plan.candidate()));
    assertEquals(before, ProjectIO.JSON.valueToTree(destination));
    assertEquals(source, ProjectIO.JSON.valueToTree(incoming));
  }

  @Test
  void identityMatchWinsEvenWhenAnEarlierDifferentResourceHasTheIncomingName() {
    var destination = new ProjectData();
    noise(destination, "field", 9);
    var target = noise(destination, "renamed", .25);
    var incoming = new ProjectData();
    var source = noise(incoming, "field", .25);
    source.uuid = target.uuid;
    var plan =
        ResourceImport.plan(
            destination, incoming, Map.of(source.uuid, ResourceImport.Choice.REUSE));
    assertTrue(plan.valid());
    assertEquals(target.uuid, row(plan, source.uuid).existing());
    assertEquals("renamed", row(plan, source.uuid).targetId());
  }

  @Test
  void visibleNamesRemainCaseSensitiveAndMatchOnlyTheSameKindAndRoot() {
    var destination = new ProjectData();
    var upper = noise(destination, "Field", .25);
    var lower = noise(destination, "field", .25);
    var three = new Noise();
    three.id = "field";
    three.dimension = 3;
    destination.noise3D.add(three);
    var incoming = new ProjectData();
    var source = noise(incoming, "field", .25);
    var plan =
        ResourceImport.plan(
            destination, incoming, Map.of(source.uuid, ResourceImport.Choice.REUSE));
    assertTrue(plan.valid());
    assertEquals(lower.uuid, row(plan, source.uuid).targetUuid());
    assertNotEquals(upper.uuid, row(plan, source.uuid).existing());
    assertNotEquals(three.uuid, row(plan, source.uuid).existing());
  }

  @Test
  void folderNameMatchKeepsTheFirstAuthoredCompatibleRootCandidate() {
    var destination = new ProjectData();
    var parent = new Folder();
    parent.id = "parent";
    var nested = new Folder();
    nested.id = "folder";
    nested.parentFolderUuid = parent.uuid;
    var root = new Folder();
    root.id = "folder";
    var world = new Folder();
    world.id = "folder";
    world.rootKind = "world";
    destination.folders.addAll(List.of(parent, nested, root, world));
    var incoming = new ProjectData();
    var source = new Folder();
    source.id = "folder";
    incoming.folders.add(source);
    var plan =
        ResourceImport.plan(
            destination, incoming, Map.of(source.uuid, ResourceImport.Choice.REUSE));
    assertTrue(plan.valid(), plan.diagnostics().toString());
    assertEquals(nested.uuid, row(plan, source.uuid).existing());
    assertEquals(nested.uuid, row(plan, source.uuid).targetUuid());
  }

  @Test
  void copiedSkippedAndReusedRowsRetainTheirExactTargetMetadata() {
    var destination = new ProjectData();
    var existing = noise(destination, "field", .25);
    var incoming = new ProjectData();
    var reuse = noise(incoming, "field", .25);
    var copy = noise(incoming, "other", .5);
    var skip = noise(incoming, "skip", .75);
    var plan =
        ResourceImport.plan(
            destination,
            incoming,
            Map.of(reuse.uuid, ResourceImport.Choice.REUSE, skip.uuid, ResourceImport.Choice.SKIP));
    assertTrue(plan.valid());
    assertEquals(existing.uuid, row(plan, reuse.uuid).targetUuid());
    assertEquals("field", row(plan, reuse.uuid).targetId());
    assertNotEquals(copy.uuid, row(plan, copy.uuid).targetUuid());
    assertEquals("other", row(plan, copy.uuid).targetId());
    assertNull(row(plan, skip.uuid).targetUuid());
    assertNull(row(plan, skip.uuid).targetId());
  }

  @Test
  void sessionCopyIdentityAndOneTransactionHistoryRemainStableAcrossIndexedPlans() {
    var document = new DocumentSession();
    assertTrue(document.edit(p -> noise(p, "field", .25)).isEmpty());
    var incoming = new ProjectData();
    var source = noise(incoming, "field", .75);
    var session = new ResourceImport.Session(document.data(), incoming);
    var target = row(session.preview(), source.uuid).targetUuid();
    session.choose(source.uuid, ResourceImport.Choice.SKIP);
    assertTrue(session.preview().valid());
    session.choose(source.uuid, ResourceImport.Choice.COPY);
    assertEquals(target, row(session.preview(), source.uuid).targetUuid());
    var before = ProjectIO.JSON.valueToTree(document.data());
    var expected = ProjectIO.JSON.valueToTree(session.preview().candidate());
    assertTrue(document.edit(session::applyTo).isEmpty());
    assertEquals(expected, ProjectIO.JSON.valueToTree(document.data()));
    document.undo();
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    document.redo();
    assertEquals(expected, ProjectIO.JSON.valueToTree(document.data()));
  }
}
