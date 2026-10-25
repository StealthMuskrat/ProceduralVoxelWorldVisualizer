package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceImportSessionTest {
  static Noise noise(ProjectData p, String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", value);
    p.noise2D.add(n);
    return n;
  }

  static ResourceImport.Row row(ResourceImport.Plan plan, UUID id) {
    return plan.rows().stream().filter(r -> r.uuid().equals(id)).findFirst().orElseThrow();
  }

  static Operation operation(Layer layer, String id, UUID noise) {
    var op = new Operation();
    op.id = id;
    op.noiseGeneratorUuid = noise;
    layer.operations.add(op);
    return op;
  }

  @Test
  void copyIdentitiesStayStableAcrossChoiceChangesAndCachedReads() {
    var destination = new ProjectData();
    var incoming = new ProjectData();
    var a = noise(incoming, "a", 2);
    var b = noise(incoming, "b", 3);
    var session = new ResourceImport.Session(destination, incoming);
    var first = session.preview();
    assertSame(first, session.preview());
    UUID target = row(first, a.uuid).targetUuid();
    session.choose(b.uuid, ResourceImport.Choice.SKIP);
    var second = session.preview();
    assertNotSame(first, second);
    assertEquals(target, row(second, a.uuid).targetUuid());
    assertNull(row(second, b.uuid).targetUuid());
    session.choose(b.uuid, ResourceImport.Choice.COPY);
    assertEquals(target, row(session.preview(), a.uuid).targetUuid());
    assertEquals(row(first, b.uuid).targetUuid(), row(session.preview(), b.uuid).targetUuid());
  }

  @Test
  void planOwnsConstructorAndAccessorSnapshots() {
    var candidate = new ProjectData();
    noise(candidate, "field", 2);
    var rows = new ArrayList<ResourceImport.Row>();
    var diagnostics = new ArrayList<Diagnostic>();
    var plan = new ResourceImport.Plan(candidate, rows, diagnostics);
    candidate.noise2D.clear();
    plan.candidate().noise2D.clear();
    rows.add(new ResourceImport.Row(UUID.randomUUID(), "noise2D", "x", "COPY", false, null));
    diagnostics.add(Diagnostic.error("TEST", "/", "test"));
    assertEquals(1, plan.candidate().noise2D.size());
    assertTrue(plan.rows().isEmpty());
    assertTrue(plan.valid());
    assertThrows(UnsupportedOperationException.class, () -> plan.rows().clear());
  }

  @Test
  void sessionOwnsBothInputsAndPublishedCandidates() {
    var destination = new ProjectData();
    noise(destination, "existing", 1);
    var incoming = new ProjectData();
    var source = noise(incoming, "source", 2);
    var original = ProjectIO.copy(destination);
    var session = new ResourceImport.Session(destination, incoming);
    source.algorithm.parameters.put("value", 99.);
    destination.noise2D.clear();
    assertFalse(session.current(destination));
    session.preview().candidate().noise2D.clear();
    session.applyTo(original);
    assertEquals(2, original.noise2D.size());
    assertEquals(2., original.noise2D.getLast().algorithm.parameters.get("value"));
    original.noise2D.getLast().id = "changed";
    assertEquals("source", session.preview().candidate().noise2D.getLast().id);
  }

  @Test
  void staleDestinationRejectsBeforeAnyMutation() {
    var destination = new ProjectData();
    var incoming = new ProjectData();
    noise(incoming, "new", 1);
    var session = new ResourceImport.Session(destination, incoming);
    destination.project.seed = 77;
    var before = ProjectIO.JSON.valueToTree(destination);
    assertThrows(IllegalStateException.class, () -> session.applyTo(destination));
    assertEquals(before, ProjectIO.JSON.valueToTree(destination));
  }

  @Test
  void foreignProjectAndChangedBookmarksAreStale() {
    var destination = new ProjectData();
    var session = new ResourceImport.Session(destination, new ProjectData());
    var foreign = ProjectIO.copy(destination);
    foreign.project.uuid = UUID.randomUUID();
    assertFalse(session.current(foreign));
    var bookmark = new Bookmark();
    bookmark.name = "origin";
    destination.bookmarks.world.add(bookmark);
    assertFalse(session.current(destination));
  }

  @Test
  void revertingExactlyToThePreviewBaseMakesItCurrentAgain() {
    var destination = new ProjectData();
    var session = new ResourceImport.Session(destination, new ProjectData());
    destination.project.seed++;
    assertFalse(session.current(destination));
    destination.project.seed--;
    assertTrue(session.current(destination));
  }

  @Test
  void unknownNullAndNestedOperationChoicesAreRejected() {
    var incoming = new ProjectData();
    var n = noise(incoming, "field", 1);
    var layer = new Layer();
    layer.id = "layer";
    var op = operation(layer, "op", n.uuid);
    incoming.worldGenerator.layers.add(layer);
    var session = new ResourceImport.Session(new ProjectData(), incoming);
    assertThrows(IllegalArgumentException.class,
        () -> session.choose(UUID.randomUUID(), ResourceImport.Choice.SKIP));
    assertThrows(IllegalArgumentException.class, () -> session.choose(n.uuid, null));
    assertThrows(IllegalArgumentException.class,
        () -> session.choose(op.uuid, ResourceImport.Choice.SKIP));
    assertThrows(UnsupportedOperationException.class,
        () -> session.choices().put(n.uuid, ResourceImport.Choice.SKIP));
    var invalid = ResourceImport.plan(new ProjectData(), incoming,
        Map.of(op.uuid, ResourceImport.Choice.SKIP));
    assertFalse(invalid.valid());
    assertTrue(invalid.diagnostics().stream().anyMatch(d -> d.code().equals("INVALID_IMPORT_CHOICE")));
    var nullChoice = new HashMap<UUID, ResourceImport.Choice>();
    nullChoice.put(n.uuid, null);
    assertFalse(ResourceImport.plan(new ProjectData(), incoming, nullChoice).valid());
  }

  @Test
  void identityMatchWinsOverAnEarlierVisibleNameMatch() {
    var destination = new ProjectData();
    var byName = noise(destination, "field", 9);
    var byIdentity = noise(destination, "renamed", 2);
    var incoming = new ProjectData();
    var source = noise(incoming, "field", 2);
    source.uuid = byIdentity.uuid;
    var plan = ResourceImport.plan(destination, incoming,
        Map.of(source.uuid, ResourceImport.Choice.REUSE));
    assertTrue(plan.valid(), plan.diagnostics().toString());
    assertEquals(byIdentity.uuid, row(plan, source.uuid).existing());
    assertNotEquals(byName.uuid, row(plan, source.uuid).targetUuid());
    assertEquals(2, plan.candidate().noise2D.size());
  }

  @Test
  void foldersInDifferentProtectedRootsCannotBeReused() {
    var destination = new ProjectData();
    var a = new Folder();
    a.id = "folder";
    a.rootKind = "noise2D";
    destination.folders.add(a);
    var incoming = new ProjectData();
    var b = new Folder();
    b.id = "folder";
    b.rootKind = "world";
    incoming.folders.add(b);
    var plan = ResourceImport.plan(destination, incoming,
        Map.of(b.uuid, ResourceImport.Choice.REUSE));
    assertFalse(plan.valid());
    assertNull(row(plan, b.uuid).existing());
    assertFalse(ResourceImport.compatible(ResourceGraph.find(incoming, b.uuid), incoming,
        ResourceGraph.find(destination, a.uuid), destination));
  }

  @Test
  void skippingOnePlacementDependencyKeepsItsLayerAndOtherPlacements() {
    var incoming = new ProjectData();
    var a = noise(incoming, "skip", 1);
    var b = noise(incoming, "keep", 2);
    var layer = new Layer();
    layer.id = "layer";
    operation(layer, "removed", a.uuid);
    var kept = operation(layer, "kept", b.uuid);
    incoming.worldGenerator.layers.add(layer);
    var session = new ResourceImport.Session(new ProjectData(), incoming);
    session.choose(a.uuid, ResourceImport.Choice.SKIP);
    var plan = session.preview();
    assertTrue(plan.valid(), plan.diagnostics().toString());
    var copied = plan.candidate().worldGenerator.layers.getFirst();
    assertEquals(1, copied.operations.size());
    assertEquals(kept.id, copied.operations.getFirst().id);
    assertTrue(copied.operations.getFirst().overrides.isEmpty());
    assertEquals("COPY", row(plan, layer.uuid).resolution());
  }

  @Test
  void skippedLayerDefaultDependencyExcludesTheLayerAndItsPlacements() {
    var incoming = new ProjectData();
    var field = noise(incoming, "field", 1);
    var type = new VoxelType();
    type.id = "solid";
    incoming.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    operation(layer, "placement", field.uuid);
    incoming.worldGenerator.layers.add(layer);
    var plan = ResourceImport.plan(new ProjectData(), incoming,
        Map.of(type.uuid, ResourceImport.Choice.SKIP));
    assertTrue(plan.valid(), plan.diagnostics().toString());
    assertTrue(plan.candidate().worldGenerator.layers.isEmpty());
    assertEquals(1, plan.candidate().noise2D.size());
    assertTrue(row(plan, layer.uuid).resolution().startsWith("SKIP"));
  }

  @Test
  void structuralSkipOverridesIncompatibleReuseOnDescendants() {
    var incoming = new ProjectData();
    var folder = new Folder();
    folder.id = "folder";
    incoming.folders.add(folder);
    var child = noise(incoming, "child", 1);
    child.parentFolderUuid = folder.uuid;
    var plan = ResourceImport.plan(new ProjectData(), incoming,
        Map.of(folder.uuid, ResourceImport.Choice.SKIP, child.uuid, ResourceImport.Choice.REUSE));
    assertTrue(plan.valid(), plan.diagnostics().toString());
    assertTrue(plan.candidate().folders.isEmpty());
    assertTrue(plan.candidate().noise2D.isEmpty());
    assertNull(row(plan, child.uuid).targetUuid());
  }

  @Test
  void compatibleReuseUsesExistingDependenciesEvenWhenIncomingDependencyIsSkipped() {
    var destination = new ProjectData();
    var type = new VoxelType();
    type.id = "solid";
    destination.voxelTypes.add(type);
    var set = new Membership();
    set.id = "set";
    set.voxelTypeUuids.add(type.uuid);
    destination.voxelSets.add(set);
    var incoming = ProjectIO.copy(destination);
    var plan = ResourceImport.plan(destination, incoming,
        Map.of(type.uuid, ResourceImport.Choice.SKIP, set.uuid, ResourceImport.Choice.REUSE));
    assertTrue(plan.valid(), plan.diagnostics().toString());
    assertEquals("REUSE", row(plan, set.uuid).resolution());
    assertEquals(set.uuid, row(plan, set.uuid).targetUuid());
    assertEquals(1, plan.candidate().voxelTypes.size());
    assertEquals(1, plan.candidate().voxelSets.size());
  }

  @Test
  void applyIsOneUndoTransactionAndPreservesDestinationSettings() {
    var document = new DocumentSession();
    var incoming = new ProjectData();
    noise(incoming, "field", 2);
    document.data().project.seed = 91;
    document.data().project.minY = -7;
    UUID project = document.data().project.uuid;
    UUID world = document.data().worldGenerator.uuid;
    var session = new ResourceImport.Session(document.data(), incoming);
    var preview = session.preview().candidate();
    assertTrue(document.edit(session::applyTo).isEmpty());
    assertEquals(project, document.data().project.uuid);
    assertEquals(world, document.data().worldGenerator.uuid);
    assertEquals(91, document.data().project.seed);
    assertEquals(-7, document.data().project.minY);
    assertEquals(preview.noise2D.getFirst().uuid, document.data().noise2D.getFirst().uuid);
    document.undo();
    assertTrue(document.data().noise2D.isEmpty());
    assertFalse(document.canUndo());
    document.redo();
    assertEquals(preview.noise2D.getFirst().uuid, document.data().noise2D.getFirst().uuid);
  }

  @Test
  void invalidReuseCannotPublishAndDoesNotMutateTheDocument() {
    var document = new DocumentSession();
    var incoming = new ProjectData();
    var n = noise(incoming, "missing", 2);
    var session = new ResourceImport.Session(document.data(), incoming);
    session.choose(n.uuid, ResourceImport.Choice.REUSE);
    var before = ProjectIO.JSON.valueToTree(document.data());
    assertThrows(IllegalStateException.class, () -> document.edit(session::applyTo));
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    assertFalse(document.dirty());
    assertFalse(document.canUndo());
    session.choose(n.uuid, ResourceImport.Choice.COPY);
    assertTrue(document.edit(session::applyTo).isEmpty());
  }
}
