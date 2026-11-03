package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred graph-level Reuse equality, distinct from definition-local identity normalization. */
class ResourceImportDependencyTest {
  private static Noise constant(ProjectData project, String id, double value) {
    var noise = new Noise();
    noise.id = id;
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", value);
    project.noise2D.add(noise);
    return noise;
  }

  private static Binding reference(UUID uuid) {
    var binding = new Binding();
    binding.sourceKind = "Noise Generator Reference";
    binding.noiseGeneratorUuid = uuid;
    return binding;
  }

  private static Noise alias(ProjectData project, String id, UUID dependency) {
    var noise = constant(project, id, 0);
    link(noise, dependency);
    return noise;
  }

  private static void link(Noise noise, UUID dependency) {
    var item = new Item();
    item.id = "alias";
    item.kind = "ADD";
    item.inputs.put("a", reference(dependency));
    item.inputs.put("b", Binding.constant(0));
    noise.processing.add(item);
    noise.outputBinding = Binding.earlier(item.uuid);
  }

  private static ProjectData chain() {
    var project = new ProjectData();
    var leaf = constant(project, "leaf", 2);
    var middle = alias(project, "middle", leaf.uuid);
    alias(project, "field", middle.uuid);
    return project;
  }

  private static boolean compatible(ProjectData incoming, ProjectData destination, UUID id) {
    return ResourceImport.compatible(
        ResourceGraph.find(incoming, id),
        incoming,
        ResourceGraph.find(destination, id),
        destination);
  }

  private static ResourceImport.Row row(ResourceImport.Plan plan, UUID uuid) {
    return plan.rows().stream().filter(r -> r.uuid().equals(uuid)).findFirst().orElseThrow();
  }

  @Test
  void sameUuidChangedDependencyRejectsBothIdentityAndVisibleNameRootMatches() {
    for (boolean sameIdentity : List.of(false, true)) {
      var destination = chain();
      var incoming = ProjectIO.copy(destination);
      var root = incoming.noise2D.getLast();
      if (!sameIdentity) root.uuid = UUID.randomUUID();
      incoming.noise2D.getFirst().algorithm.parameters.put("value", 5.0);
      var before = ProjectIO.JSON.valueToTree(destination);
      var source = ProjectIO.JSON.valueToTree(incoming);
      var plan =
          ResourceImport.plan(
              destination, incoming, Map.of(root.uuid, ResourceImport.Choice.REUSE));
      assertFalse(plan.valid());
      assertFalse(row(plan, root.uuid).compatible());
      assertTrue(plan.diagnostics().stream().anyMatch(d -> d.code().equals("INCOMPATIBLE_REUSE")));
      assertEquals(before, ProjectIO.JSON.valueToTree(destination));
      assertEquals(source, ProjectIO.JSON.valueToTree(incoming));
    }
  }

  @Test
  void identicalTransitiveDefinitionsPermitReuseWithDifferentLocalItems() {
    var destination = chain();
    var incoming = ProjectIO.copy(destination);
    for (var project : List.of(destination, incoming)) {
      var item = new Item();
      item.id = "sum";
      item.kind = "ADD";
      item.inputs.put("a", reference(project.noise2D.getFirst().uuid));
      item.inputs.put("b", Binding.constant(1));
      var middle = project.noise2D.get(1);
      middle.processing.add(item);
      middle.outputBinding = Binding.earlier(item.uuid);
    }
    assertTrue(compatible(incoming, destination, incoming.noise2D.getLast().uuid));
  }

  @Test
  void identityNamedInputDependencyRemainsInTheTransitiveComparison() {
    var destination = chain();
    var middle = destination.noise2D.get(1);
    var item = new Item();
    item.id = "formula";
    item.kind = "EXPRESSION";
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("uuid"));
    item.inputs.put("uuid", reference(destination.noise2D.getFirst().uuid));
    middle.processing.add(item);
    middle.outputBinding = Binding.earlier(item.uuid);
    var incoming = ProjectIO.copy(destination);
    incoming.noise2D.getFirst().algorithm.parameters.put("value", 5.0);
    assertFalse(compatible(incoming, destination, middle.uuid));
    assertFalse(compatible(incoming, destination, incoming.noise2D.getLast().uuid));
  }

  @Test
  void unrelatedChangesDoNotInvalidateAnOtherwiseIdenticalClosure() {
    var destination = chain();
    constant(destination, "unrelated", 1);
    var incoming = ProjectIO.copy(destination);
    incoming.noise2D.getLast().algorithm.parameters.put("value", 9.0);
    incoming.project.seed = 19;
    incoming.project.minY = -99;
    assertTrue(compatible(incoming, destination, incoming.noise2D.get(2).uuid));
  }

  @Test
  void membershipReusePreservesReferencedVoxelSemanticIdsAndColors() {
    for (boolean rename : List.of(false, true)) {
      var destination = new ProjectData();
      var type = new VoxelType();
      type.id = "stone";
      destination.voxelTypes.add(type);
      var set = new Membership();
      set.id = "set";
      set.voxelTypeUuids.add(type.uuid);
      destination.voxelSets.add(set);
      var incoming = ProjectIO.copy(destination);
      if (rename) incoming.voxelTypes.getFirst().id = "soil";
      else incoming.voxelTypes.getFirst().color.r = 17;
      assertFalse(compatible(incoming, destination, set.uuid));
      var plan =
          ResourceImport.plan(destination, incoming, Map.of(set.uuid, ResourceImport.Choice.REUSE));
      assertFalse(row(plan, set.uuid).compatible());
      assertFalse(plan.valid());
    }
  }

  @Test
  void layerComparisonIncludesPlacementDependencyDefinitions() {
    var destination = chain();
    var layer = new Layer();
    layer.id = "layer";
    var operation = new Operation();
    operation.id = "placement";
    operation.noiseGeneratorUuid = destination.noise2D.getLast().uuid;
    layer.operations.add(operation);
    destination.worldGenerator.layers.add(layer);
    var incoming = ProjectIO.copy(destination);
    assertTrue(compatible(incoming, destination, layer.uuid));
    incoming.noise2D.getFirst().algorithm.parameters.put("value", 5.0);
    assertFalse(compatible(incoming, destination, layer.uuid));
  }

  @Test
  void explicitlyExternalDestinationReferenceMayOmitItsDefinition() {
    var destination = chain();
    var incoming = ProjectIO.copy(destination);
    incoming.noise2D.removeFirst();
    assertTrue(compatible(incoming, destination, incoming.noise2D.getLast().uuid));
  }

  @Test
  void absentDestinationDependencyCannotBeReusedEvenIfItsDefinitionIsOmitted() {
    var destination = chain();
    var incoming = ProjectIO.copy(destination);
    destination.noise2D.removeFirst();
    incoming.noise2D.removeFirst();
    assertFalse(compatible(incoming, destination, incoming.noise2D.getLast().uuid));
  }

  @Test
  void matchingDependencyUuidCannotHideAChangedResourceKindOrDimension() {
    var destination = chain();
    var incoming = ProjectIO.copy(destination);
    var leaf = incoming.noise2D.removeFirst();
    leaf.dimension = 3;
    incoming.noise3D.add(leaf);
    assertFalse(compatible(incoming, destination, incoming.noise2D.getLast().uuid));
  }

  @Test
  void organizationalFoldersDoNotBecomeProceduralDependencies() {
    var destination = chain();
    var incoming = ProjectIO.copy(destination);
    for (var project : List.of(destination, incoming)) {
      var folder = new Folder();
      folder.id = "folder";
      project.folders.add(folder);
      for (var noise : project.noise2D) noise.parentFolderUuid = folder.uuid;
    }
    assertTrue(compatible(incoming, destination, incoming.noise2D.getLast().uuid));
  }

  @Test
  void cyclicComparisonTerminatesButInvalidCandidateStillCannotPublish() {
    var destination = chain();
    link(destination.noise2D.getFirst(), destination.noise2D.getLast().uuid);
    var incoming = ProjectIO.copy(destination);
    assertTrue(compatible(incoming, destination, incoming.noise2D.getLast().uuid));
    var choices = new HashMap<UUID, ResourceImport.Choice>();
    incoming.noise2D.forEach(n -> choices.put(n.uuid, ResourceImport.Choice.REUSE));
    assertFalse(ResourceImport.plan(destination, incoming, choices).valid());
  }

  @Test
  void mismatchPropagatesAroundCyclesAndSharedBranchesInEitherRowOrder() {
    for (boolean reverse : List.of(false, true)) {
      var destination = chain();
      var leaf = destination.noise2D.getFirst();
      link(leaf, destination.noise2D.getLast().uuid);
      alias(destination, "branch", destination.noise2D.get(1).uuid);
      var incoming = ProjectIO.copy(destination);
      incoming.noise2D.getFirst().algorithm.parameters.put("value", 5.0);
      if (reverse) Collections.reverse(incoming.noise2D);
      var plan = ResourceImport.plan(destination, incoming, Map.of());
      for (var noise : incoming.noise2D) assertFalse(row(plan, noise.uuid).compatible());
    }
  }

  @Test
  void longDependencyChainsDoNotConsumeRecursiveComparisonStack() {
    var destination = new ProjectData();
    var previous = constant(destination, "leaf", 2);
    for (int i = 0; i < 4096; i++) previous = alias(destination, "alias-" + i, previous.uuid);
    var incoming = ProjectIO.copy(destination);
    assertTrue(compatible(incoming, destination, previous.uuid));
    incoming.noise2D.getFirst().algorithm.parameters.put("value", 5.0);
    assertFalse(compatible(incoming, destination, previous.uuid));
  }

  @Test
  void rejectedTransitiveReuseCanPublishStableCopyWithOneUndoAndRedo() {
    var document = new DocumentSession();
    assertTrue(document.edit(p -> ResourceGraph.assign(p, chain())).isEmpty());
    var before = ProjectIO.JSON.valueToTree(document.data());
    var incoming = ProjectIO.copy(document.data());
    var source = incoming.noise2D.getLast();
    incoming.noise2D.getFirst().algorithm.parameters.put("value", 5.0);
    var session = new ResourceImport.Session(document.data(), incoming);
    var copied = row(session.preview(), source.uuid).targetUuid();
    var active = document.data();
    long history = document.historyBytes();
    session.choose(source.uuid, ResourceImport.Choice.REUSE);
    assertThrows(IllegalStateException.class, () -> document.edit(session::applyTo));
    assertSame(active, document.data());
    assertEquals(history, document.historyBytes());
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    session.choose(source.uuid, ResourceImport.Choice.COPY);
    assertEquals(copied, row(session.preview(), source.uuid).targetUuid());
    var expected = ProjectIO.JSON.valueToTree(session.preview().candidate());
    assertTrue(document.edit(session::applyTo).isEmpty());
    assertEquals(expected, ProjectIO.JSON.valueToTree(document.data()));
    var imported = document.data().noise2D.getLast();
    assertNotEquals(
        source.processing.getFirst().inputs.get("a").noiseGeneratorUuid,
        imported.processing.getFirst().inputs.get("a").noiseGeneratorUuid);
    document.undo();
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    document.redo();
    assertEquals(expected, ProjectIO.JSON.valueToTree(document.data()));
  }
}
