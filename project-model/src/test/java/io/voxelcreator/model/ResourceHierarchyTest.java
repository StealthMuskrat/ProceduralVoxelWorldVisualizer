package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceHierarchyTest {
  @Test
  void ordinaryProcessingInputNamedRootOrderStillRemapsItsProceduralReferences() {
    var p = fixture();
    var item = new Item();
    item.id = "formula";
    item.kind = "EXPRESSION";
    var binding = new Binding();
    binding.sourceKind = "Noise Generator Reference";
    binding.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
    item.inputs.put("rootOrder", binding);
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("rootOrder"));
    p.noise2D.getLast().processing.add(item);
    p.project.rootOrder.put("noise2D", new ArrayList<>(List.of(p.noise2D.getFirst().uuid)));
    var copy = ResourceTransactions.duplicateMapped(p);
    var expected = copy.identities().get(p.noise2D.getFirst().uuid);
    assertEquals(
        expected,
        copy.project()
            .noise2D
            .getLast()
            .processing
            .getFirst()
            .inputs
            .get("rootOrder")
            .noiseGeneratorUuid);
    assertEquals(List.of(expected), copy.project().project.rootOrder.get("noise2D"));
  }

  @Test
  void iterativeSubtreeIncludesStructuralWorldDescendantsWithoutFollowingNoiseReferences() {
    var p = fixture();
    var folder = p.folders.get(2);
    var layer = p.worldGenerator.layers.getFirst();
    layer.parentFolderUuid = folder.uuid;
    var op = new Operation();
    op.id = "use";
    op.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
    layer.operations.add(op);
    assertEquals(
        Set.of(folder.uuid, layer.uuid, op.uuid), ResourceGraph.subtree(p, List.of(folder.uuid)));
  }

  @Test
  void mixedCopyIntoFolderRetainsDisplayedOrderRegardlessOfInputSelectionOrder() {
    var p = fixture();
    var noise = p.noise2D.getFirst();
    var folder = p.folders.getFirst();
    var target = p.folders.get(1);
    ResourceGraph.reorderBefore(p, List.of(noise.uuid), folder.uuid);
    new ResourceClipboard(p, List.of(folder.uuid, noise.uuid), false)
        .paste(p, "noise2D", target.uuid);
    assertEquals(List.of("alpha-copy", "one"), ids(p, "noise2D", target.uuid));
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void mixedMoveIntoFolderUsesSourceDisplayOrderAndPrunesOldRootEntries() {
    var p = fixture();
    var noise = p.noise2D.getFirst();
    var folder = p.folders.getFirst();
    var target = p.folders.get(1);
    ResourceGraph.reorderBefore(p, List.of(noise.uuid), folder.uuid);
    ResourceGraph.move(p, List.of(folder.uuid, noise.uuid), "noise2D", target.uuid);
    assertEquals(List.of("alpha", "one"), ids(p, "noise2D", target.uuid));
    assertFalse(p.project.rootOrder.get("noise2D").contains(noise.uuid));
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void mixedCopyBackToRootPreservesExistingOrderAndAppendsTheCopiedBlock() {
    var p = fixture();
    var noise = p.noise2D.getFirst();
    var folder = p.folders.getFirst();
    ResourceGraph.reorderBefore(p, List.of(noise.uuid), folder.uuid);
    new ResourceClipboard(p, List.of(folder.uuid, noise.uuid), false).paste(p, "noise2D", null);
    assertEquals(
        List.of("alpha", "one", "two", "beta", "alpha-copy", "one-copy"), ids(p, "noise2D", null));
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void mergeCopiesPreservesIncomingRootMetadataAndDestinationProjectSettings() {
    var destination = fixture();
    destination.project.seed = 123;
    var incoming = fixture();
    ResourceGraph.reorderBefore(
        incoming, List.of(incoming.noise2D.getFirst().uuid), incoming.folders.getFirst().uuid);
    ResourceTransactions.mergeCopies(destination, incoming);
    assertEquals(123, destination.project.seed);
    assertEquals(
        List.of("one", "two", "alpha", "beta", "alpha-copy", "one-copy", "two-copy", "beta-copy"),
        ids(destination, "noise2D", null));
    assertTrue(Validator.validate(destination).isEmpty());
  }

  @Test
  void rootOrderIsSparseAndReadRoundTripsPreserveOwnedIndependentMaps() throws Exception {
    var p = fixture();
    p.project.rootOrder.put(
        "world", new ArrayList<>(List.of(p.worldGenerator.layers.getLast().uuid)));
    var restored =
        ProjectIO.JSON.readValue(ProjectIO.JSON.writeValueAsString(p), ProjectData.class);
    assertEquals(List.of("last", "world-one", "world-two", "first"), ids(restored, "world", null));
    restored.project.rootOrder.get("world").clear();
    assertEquals(1, p.project.rootOrder.get("world").size());
  }

  @Test
  void assignCopiesOrganizationWithoutReplacingDestinationProjectIdentityOrSeed() {
    var destination = fixture();
    var source = fixture();
    var id = destination.project.uuid;
    destination.project.seed = 17;
    source.project.rootOrder.put(
        "noise2D", new ArrayList<>(List.of(source.noise2D.getLast().uuid)));
    ResourceGraph.assign(destination, source);
    assertEquals(id, destination.project.uuid);
    assertEquals(17, destination.project.seed);
    assertEquals(source.project.rootOrder, destination.project.rootOrder);
    source.project.rootOrder.get("noise2D").clear();
    assertEquals(1, destination.project.rootOrder.get("noise2D").size());
  }

  static ProjectData fixture() {
    var p = new ProjectData();
    for (String id : List.of("alpha", "beta")) {
      var n = new Noise();
      n.id = id;
      p.noise2D.add(n);
    }
    for (String id : List.of("one", "two")) {
      var f = new Folder();
      f.id = id;
      f.rootKind = "noise2D";
      p.folders.add(f);
    }
    for (String id : List.of("world-one", "world-two")) {
      var f = new Folder();
      f.id = id;
      f.rootKind = "world";
      p.folders.add(f);
    }
    for (String id : List.of("first", "last")) {
      var l = new Layer();
      l.id = id;
      p.worldGenerator.layers.add(l);
    }
    return p;
  }

  private static List<String> ids(ProjectData p, String root, UUID parent) {
    return ResourceGraph.children(p, root, parent).stream().map(e -> e.resource().id).toList();
  }

  @Test
  void mixedRootOrderSupportsNoiseBeforeFoldersAndStableUnspecifiedChildren() {
    var p = fixture();
    var noise = p.noise2D.getFirst();
    ResourceGraph.reorderBefore(p, List.of(noise.uuid), p.folders.getFirst().uuid);
    assertEquals(List.of("alpha", "one", "two", "beta"), ids(p, "noise2D", null));
    assertEquals(List.of("alpha", "beta"), p.noise2D.stream().map(n -> n.id).toList());
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void mixedSiblingBlockShiftsPreserveRelativeOrderInBothDirections() {
    var p = fixture();
    var selected = List.of(p.folders.get(1).uuid, p.noise2D.getFirst().uuid);
    ResourceGraph.shift(p, selected, -1);
    assertEquals(List.of("two", "alpha", "one", "beta"), ids(p, "noise2D", null));
    ResourceGraph.shift(p, selected, 1);
    assertEquals(List.of("one", "two", "alpha", "beta"), ids(p, "noise2D", null));
  }

  @Test
  void sparseRootOrderRejectsUnknownDuplicateNestedAndCrossRootEntries() {
    var p = fixture();
    var noise = p.noise2D.getFirst();
    var folder = p.folders.getFirst();
    for (List<UUID> order :
        List.of(
            List.of(UUID.randomUUID()),
            List.of(noise.uuid, noise.uuid),
            List.of(p.worldGenerator.layers.getFirst().uuid))) {
      p.project.rootOrder.put("noise2D", new ArrayList<>(order));
      assertFalse(Validator.validate(p).isEmpty());
    }
    p.project.rootOrder.clear();
    noise.parentFolderUuid = folder.uuid;
    p.project.rootOrder.put("noise2D", new ArrayList<>(List.of(noise.uuid)));
    assertFalse(Validator.validate(p).isEmpty());
    p.project.rootOrder.clear();
    p.project.rootOrder.put("unknown", new ArrayList<>());
    assertFalse(Validator.validate(p).isEmpty());
  }

  @Test
  void worldFoldersContainLayersAndOrganizationDoesNotMoveExecutionArray() {
    var p = fixture();
    var layer = p.worldGenerator.layers.getFirst();
    var before = p.worldGenerator.layers.stream().map(l -> l.uuid).toList();
    var folder = p.folders.get(2);
    ResourceGraph.move(p, List.of(layer.uuid), "world", folder.uuid);
    assertEquals(folder.uuid, layer.parentFolderUuid);
    assertEquals(List.of("first"), ids(p, "world", folder.uuid));
    ResourceGraph.move(p, List.of(layer.uuid), "world", null);
    assertEquals(before, p.worldGenerator.layers.stream().map(l -> l.uuid).toList());
    assertNull(layer.parentFolderUuid);
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void treeReorderingWorldFoldersAndLayersIsIndependentFromExecutionChanges() {
    var p = fixture();
    var last = p.worldGenerator.layers.getLast();
    ResourceGraph.reorderBefore(p, List.of(last.uuid), p.folders.get(2).uuid);
    assertEquals(List.of("last", "world-one", "world-two", "first"), ids(p, "world", null));
    assertEquals(
        List.of("first", "last"), p.worldGenerator.layers.stream().map(l -> l.id).toList());
    ResourceGraph.shiftExecution(p, List.of(last.uuid), -1);
    assertEquals(
        List.of("last", "first"), p.worldGenerator.layers.stream().map(l -> l.id).toList());
  }

  @Test
  void worldLayerParentsMustExistInTheWorldRoot() {
    var p = fixture();
    var layer = p.worldGenerator.layers.getFirst();
    layer.parentFolderUuid = p.folders.getFirst().uuid;
    assertFalse(Validator.validate(p).isEmpty());
    layer.parentFolderUuid = UUID.randomUUID();
    assertFalse(Validator.validate(p).isEmpty());
    layer.parentFolderUuid = p.folders.get(2).uuid;
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void cycleAndCrossRootMovesRejectBeforeMutatingTheSource() {
    var p = fixture();
    var outer = p.folders.get(2);
    var inner = p.folders.get(3);
    inner.parentFolderUuid = outer.uuid;
    var before = ProjectIO.JSON.valueToTree(p);
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceGraph.move(p, List.of(outer.uuid), "world", inner.uuid));
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ResourceGraph.move(
                p,
                List.of(p.worldGenerator.layers.getFirst().uuid),
                "noise2D",
                p.folders.getFirst().uuid));
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }

  @Test
  void worldFolderCopyRemapsLayerHierarchyChildOrderAndOperationIdentities() {
    var p = fixture();
    var folder = p.folders.get(2);
    var layer = p.worldGenerator.layers.getFirst();
    layer.parentFolderUuid = folder.uuid;
    folder.childOrder.add(layer.uuid);
    p.project.rootOrder.put("world", new ArrayList<>(List.of(folder.uuid)));
    var clip = new ResourceClipboard(p, List.of(folder.uuid), false);
    clip.paste(p, "world", null);
    var copy =
        p.folders.stream().filter(f -> f.id.equals("world-one-copy")).findFirst().orElseThrow();
    var copied = ResourceGraph.children(p, "world", copy.uuid).getFirst();
    assertNotEquals(layer.uuid, copied.uuid());
    assertEquals(copy.uuid, ((Layer) copied.resource()).parentFolderUuid);
    assertEquals(List.of(copied.uuid()), copy.childOrder);
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void layerCopyIntoAnotherWorldFolderAndCutPreserveScopeAndExecutionContract() {
    var p = fixture();
    var original = p.worldGenerator.layers.getFirst();
    var folder = p.folders.get(2);
    original.parentFolderUuid = folder.uuid;
    new ResourceClipboard(p, List.of(original.uuid), false)
        .paste(p, "world", p.folders.get(3).uuid);
    var copied = p.worldGenerator.layers.getLast();
    assertNotEquals(original.uuid, copied.uuid);
    assertEquals(p.folders.get(3).uuid, copied.parentFolderUuid);
    var execution = p.worldGenerator.layers.stream().map(l -> l.uuid).toList();
    new ResourceClipboard(p, List.of(original.uuid), true).paste(p, "world", null);
    assertEquals(execution, p.worldGenerator.layers.stream().map(l -> l.uuid).toList());
    assertNull(original.parentFolderUuid);
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void rootOrderUuidsRemapDuringDuplicationAndNeverBecomeSemanticDependencies() {
    var p = fixture();
    p.project.rootOrder.put(
        "noise2D", new ArrayList<>(List.of(p.noise2D.getFirst().uuid, p.folders.getFirst().uuid)));
    var duplicated = ResourceTransactions.duplicateMapped(p);
    var copy = duplicated.project();
    assertEquals(
        p.project.rootOrder.get("noise2D").stream().map(duplicated.identities()::get).toList(),
        copy.project.rootOrder.get("noise2D"));
    assertTrue(Validator.validate(copy).isEmpty());
    assertFalse(ResourceGraph.references(p).contains(p.noise2D.getFirst().uuid));
  }

  @Test
  void exportAncestorPruningDoesNotModifySourceFolderOrders() {
    var p = fixture();
    var folder = p.folders.getFirst();
    p.noise2D.forEach(n -> n.parentFolderUuid = folder.uuid);
    folder.childOrder.addAll(p.noise2D.stream().map(n -> n.uuid).toList());
    var before = ProjectIO.JSON.valueToTree(p);
    var subset = ResourceGraph.selection(p, List.of(p.noise2D.getFirst().uuid), true);
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
    assertEquals(1, subset.folders.getFirst().childOrder.size());
    assertTrue(Validator.validate(subset).isEmpty());
  }

  @Test
  void operationOnlyExportsKeepWorldFolderAncestorsAndCarrierDefaults() {
    var p = fixture();
    var layer = p.worldGenerator.layers.getFirst();
    layer.parentFolderUuid = p.folders.get(2).uuid;
    var op = new Operation();
    op.id = "placement";
    op.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
    layer.operations.add(op);
    p.folders
        .get(2)
        .childOrder
        .addAll(
            p.worldGenerator.layers.stream()
                .map(l -> l.uuid)
                .filter(id -> id.equals(layer.uuid))
                .toList());
    var out = ResourceGraph.selection(p, List.of(op.uuid), true);
    assertEquals(1, out.worldGenerator.layers.size());
    assertEquals(List.of("world-one"), out.folders.stream().map(f -> f.id).toList());
    assertEquals(layer.parentFolderUuid, out.worldGenerator.layers.getFirst().parentFolderUuid);
    assertTrue(Validator.validate(out).isEmpty());
  }

  @Test
  void deletionReconcilesRootAndFolderOrdersWithoutLeavingDanglingUuids() {
    var p = fixture();
    var root = p.noise2D.getFirst();
    p.project.rootOrder.put(
        "noise2D", new ArrayList<>(List.of(root.uuid, p.folders.getFirst().uuid)));
    ResourceGraph.delete(p, root.uuid, null);
    assertEquals(List.of(p.folders.getFirst().uuid), p.project.rootOrder.get("noise2D"));
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void invalidExecutionSelectionsAndPasteDestinationsPreserveAllData() {
    var p = fixture();
    var before = ProjectIO.JSON.valueToTree(p);
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceGraph.shiftExecution(p, List.of(p.folders.get(2).uuid), -1));
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
    var clip = new ResourceClipboard(p, List.of(p.worldGenerator.layers.getFirst().uuid), false);
    assertThrows(
        IllegalArgumentException.class, () -> clip.paste(p, "world", p.folders.getFirst().uuid));
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }

  @Test
  void appendPreservesExistingOrderAndAppendsIncomingMixedOrderAsOwnedValues() {
    var into = fixture();
    into.project.rootOrder.put(
        "noise2D",
        new ArrayList<>(List.of(into.noise2D.getFirst().uuid, into.folders.getFirst().uuid)));
    var from = fixture();
    from.project.rootOrder.put(
        "noise2D",
        new ArrayList<>(List.of(from.noise2D.getLast().uuid, from.folders.getFirst().uuid)));
    var before =
        ResourceGraph.children(into, "noise2D", null).stream()
            .map(ResourceGraph.Entry::uuid)
            .toList();
    var incoming =
        ResourceGraph.children(from, "noise2D", null).stream()
            .map(ResourceGraph.Entry::uuid)
            .toList();
    ResourceGraph.append(into, from);
    var expected = new ArrayList<>(before);
    expected.addAll(incoming);
    assertEquals(
        expected,
        ResourceGraph.children(into, "noise2D", null).stream()
            .map(ResourceGraph.Entry::uuid)
            .toList());
    from.project.rootOrder.get("noise2D").clear();
    assertEquals(expected, into.project.rootOrder.get("noise2D"));
    assertTrue(Validator.validate(into).isEmpty());
  }
}
