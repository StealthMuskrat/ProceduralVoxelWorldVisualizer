package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred indexed dependency closure with exact payload and source ownership contracts. */
class ResourceDependencyClosureTest {
  private static Noise noise(ProjectData p, String id) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", .25);
    p.noise2D.add(n);
    return n;
  }

  private static void reference(Noise source, UUID target) {
    var item = new Item();
    item.id = "reference-" + source.processing.size();
    item.kind = "EXPRESSION";
    item.enabled = false;
    var b = new Binding();
    b.sourceKind = "Noise Generator Reference";
    b.noiseGeneratorUuid = target;
    item.inputs.put("uuid", b);
    source.processing.add(item);
  }

  private static ProjectData chain(int count) {
    var p = new ProjectData();
    for (int i = 0; i < count; i++) noise(p, "field-" + i);
    for (int i = 0; i + 1 < count; i++) reference(p.noise2D.get(i), p.noise2D.get(i + 1).uuid);
    Collections.reverse(p.noise2D);
    return p;
  }

  @Test
  void reverseOrderedDeepClosureIsCompleteAndRetainsAuthoredArrayOrder() {
    var p = chain(4097);
    var root = p.noise2D.getLast();
    var unrelated = new Noise();
    unrelated.id = "unrelated";
    unrelated.dimension = 3;
    p.noise3D.add(unrelated);
    var before = ProjectIO.JSON.valueToTree(p);
    var payload = ResourceGraph.selection(p, List.of(root.uuid), true);
    assertEquals(
        p.noise2D.stream().map(n -> n.uuid).toList(),
        payload.noise2D.stream().map(n -> n.uuid).toList());
    assertTrue(payload.noise3D.isEmpty());
    assertTrue(Validator.validate(payload).isEmpty());
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }

  @Test
  void clipboardStyleSelectionRetainsExternalUuidWithoutExpandingDependencies() {
    var p = chain(3);
    var source = p.noise2D.getLast();
    var payload = ResourceGraph.selection(p, List.of(source.uuid), false);
    assertEquals(1, payload.noise2D.size());
    assertEquals(
        source.processing.getFirst().inputs.get("uuid").noiseGeneratorUuid,
        payload.noise2D.getFirst().processing.getFirst().inputs.get("uuid").noiseGeneratorUuid);
  }

  @Test
  void sharedDiamondAndIdentityNamedInputsDoNotDuplicateResources() {
    var p = new ProjectData();
    var a = noise(p, "a");
    var b = noise(p, "b");
    var c = noise(p, "c");
    var d = noise(p, "d");
    noise(p, "unrelated");
    reference(a, b.uuid);
    reference(a, c.uuid);
    reference(b, d.uuid);
    reference(c, d.uuid);
    var payload = ResourceGraph.selection(p, List.of(a.uuid), true);
    assertEquals(
        List.of(a.uuid, b.uuid, c.uuid, d.uuid),
        payload.noise2D.stream().map(n -> n.uuid).toList());
  }

  @Test
  void dependencyFolderContextDoesNotPullInItsUnrelatedSiblingsOrMutateSourceOrders() {
    var p = new ProjectData();
    var selected = new Folder();
    selected.id = "selected";
    p.folders.add(selected);
    var context = new Folder();
    context.id = "context";
    p.folders.add(context);
    var a = noise(p, "a");
    a.parentFolderUuid = selected.uuid;
    var b = noise(p, "b");
    b.parentFolderUuid = context.uuid;
    var sibling = noise(p, "sibling");
    sibling.parentFolderUuid = context.uuid;
    selected.childOrder.add(a.uuid);
    context.childOrder.addAll(List.of(sibling.uuid, b.uuid));
    reference(a, b.uuid);
    var before = ProjectIO.JSON.valueToTree(p);
    var payload = ResourceGraph.selection(p, List.of(selected.uuid), true);
    assertEquals(List.of(a.uuid, b.uuid), payload.noise2D.stream().map(n -> n.uuid).toList());
    assertEquals(2, payload.folders.size());
    assertEquals(List.of(b.uuid), payload.folders.get(1).childOrder);
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }

  @Test
  void selectedPlacementsKeepCarrierDefaultsAndOrderWithoutUnselectedPlacements() {
    var p = new ProjectData();
    var first = noise(p, "first");
    var second = noise(p, "second");
    var third = noise(p, "third");
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var parent = new Folder();
    parent.id = "world";
    parent.rootKind = "world";
    p.folders.add(parent);
    var layer = new Layer();
    layer.id = "layer";
    layer.parentFolderUuid = parent.uuid;
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    for (var n : List.of(first, second, third)) {
      var op = new Operation();
      op.id = n.id;
      op.noiseGeneratorUuid = n.uuid;
      layer.operations.add(op);
    }
    p.worldGenerator.layers.add(layer);
    parent.childOrder.add(layer.uuid);
    var payload =
        ResourceGraph.selection(
            p, List.of(layer.operations.get(2).uuid, layer.operations.getFirst().uuid), true);
    var carrier = payload.worldGenerator.layers.getFirst();
    assertEquals(List.of("first", "third"), carrier.operations.stream().map(op -> op.id).toList());
    assertTrue(carrier.operations.stream().allMatch(op -> op.overrides.isEmpty()));
    assertEquals(
        ProjectIO.JSON.valueToTree(layer.defaults), ProjectIO.JSON.valueToTree(carrier.defaults));
    assertEquals(
        List.of(first.uuid, third.uuid), payload.noise2D.stream().map(n -> n.uuid).toList());
    assertEquals(type.uuid, payload.voxelTypes.getFirst().uuid);
    assertEquals(parent.uuid, payload.folders.getFirst().uuid);
    assertTrue(Validator.validate(payload).isEmpty());
  }

  @Test
  void aSelectedLayerStillIncludesItsWholeStructuralSubtree() {
    var p = new ProjectData();
    var a = noise(p, "a");
    var b = noise(p, "b");
    var layer = new Layer();
    layer.id = "layer";
    for (var n : List.of(a, b)) {
      var op = new Operation();
      op.id = n.id;
      op.noiseGeneratorUuid = n.uuid;
      layer.operations.add(op);
    }
    p.worldGenerator.layers.add(layer);
    var payload = ResourceGraph.selection(p, List.of(layer.uuid), true);
    assertEquals(2, payload.worldGenerator.layers.getFirst().operations.size());
    assertEquals(2, payload.noise2D.size());
  }

  @Test
  void cyclesTerminateWithoutBeingSilentlyRepaired() {
    var p = chain(3);
    reference(p.noise2D.getFirst(), p.noise2D.getLast().uuid);
    var payload = ResourceGraph.selection(p, List.of(p.noise2D.getLast().uuid), true);
    assertEquals(3, payload.noise2D.size());
    assertTrue(Validator.validate(payload).stream().anyMatch(d -> d.code().equals("NOISE_CYCLE")));
  }

  @Test
  void unresolvedReferencesRemainExplicitWithoutInventedResourceRows() {
    var p = new ProjectData();
    var n = noise(p, "field");
    var missing = UUID.randomUUID();
    reference(n, missing);
    var payload = ResourceGraph.selection(p, List.of(n.uuid), true);
    assertEquals(1, payload.noise2D.size());
    assertEquals(
        missing,
        payload.noise2D.getFirst().processing.getFirst().inputs.get("uuid").noiseGeneratorUuid);
    assertTrue(
        Validator.validate(payload).stream().anyMatch(d -> d.code().equals("INVALID_REFERENCE")));
  }

  @Test
  void duplicateSelectionMembershipAndRootOrderingKeepTheirExistingMeanings() {
    var p = new ProjectData();
    var a = noise(p, "a");
    var b = noise(p, "b");
    var c = noise(p, "c");
    p.project.rootOrder.put("noise2D", new ArrayList<>(List.of(c.uuid, b.uuid, a.uuid)));
    var payload = ResourceGraph.selection(p, List.of(a.uuid, c.uuid, a.uuid), true);
    assertEquals(List.of(a.uuid, c.uuid), payload.noise2D.stream().map(n -> n.uuid).toList());
    assertEquals(List.of(c.uuid, a.uuid), payload.project.rootOrder.get("noise2D"));
    assertEquals(List.of(c.uuid, b.uuid, a.uuid), p.project.rootOrder.get("noise2D"));
  }

  @Test
  void payloadMutationsCannotChangeSourceBindingsOrSettings() {
    var p = chain(3);
    var original = ProjectIO.JSON.valueToTree(p);
    var payload = ResourceGraph.selection(p, List.of(p.noise2D.getLast().uuid), true);
    payload.noise2D.getLast().processing.getFirst().inputs.clear();
    payload.noise2D.getFirst().id = "changed";
    payload.project.seed = 101;
    assertEquals(original, ProjectIO.JSON.valueToTree(p));
  }
}
