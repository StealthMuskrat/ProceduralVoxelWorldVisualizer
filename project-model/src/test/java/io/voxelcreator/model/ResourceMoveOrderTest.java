package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred ordering contracts: selection membership never supplies execution order. */
class ResourceMoveOrderTest {
  private ProjectData fixture() {
    var p = new ProjectData();
    var noise = new Noise();
    noise.id = "field";
    p.noise2D.add(noise);
    for (String id : List.of("source", "target")) {
      var layer = new Layer();
      layer.id = id;
      p.worldGenerator.layers.add(layer);
    }
    for (String id : List.of("first", "middle", "last")) {
      var operation = new Operation();
      operation.id = id;
      operation.noiseGeneratorUuid = noise.uuid;
      p.worldGenerator.layers.getFirst().operations.add(operation);
    }
    var anchor = new Operation();
    anchor.id = "anchor";
    anchor.noiseGeneratorUuid = noise.uuid;
    p.worldGenerator.layers.getLast().operations.add(anchor);
    return p;
  }

  private List<UUID> ids(Layer layer) {
    return layer.operations.stream().map(o -> o.uuid).toList();
  }

  @Test
  void crossLayerMoveUsesLiveOrderForEverySelectionPermutationAndDuplicate() {
    for (List<Integer> selection : List.of(List.of(2, 0), List.of(0, 2), List.of(2, 0, 2))) {
      var p = fixture();
      var source = p.worldGenerator.layers.getFirst();
      var target = p.worldGenerator.layers.getLast();
      var first = source.operations.getFirst();
      var middle = source.operations.get(1);
      var last = source.operations.getLast();
      var anchor = target.operations.getFirst();
      var beforeFirst = ProjectIO.JSON.valueToTree(first);
      var beforeLast = ProjectIO.JSON.valueToTree(last);
      var requested = selection.stream().map(i -> source.operations.get(i).uuid).toList();
      ResourceGraph.move(p, requested, "operation", target.uuid);
      assertEquals(List.of(middle.uuid), ids(source));
      assertEquals(List.of(anchor.uuid, first.uuid, last.uuid), ids(target));
      assertSame(first, target.operations.get(1));
      assertSame(last, target.operations.getLast());
      assertEquals(beforeFirst, ProjectIO.JSON.valueToTree(first));
      assertEquals(beforeLast, ProjectIO.JSON.valueToTree(last));
      assertTrue(Validator.validate(p).isEmpty());
    }
  }

  @Test
  void sameLayerAppendMovesOneOrderedBlockWithoutReversingIt() {
    var p = fixture();
    var source = p.worldGenerator.layers.getFirst();
    var original = ids(source);
    ResourceGraph.move(
        p, List.of(original.getLast(), original.getFirst()), "operation", source.uuid);
    assertEquals(List.of(original.get(1), original.getFirst(), original.getLast()), ids(source));
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void reorderBeforeRetainsTheOrderedBlockWithinAndAcrossLayers() {
    for (boolean sameLayer : List.of(false, true)) {
      var p = fixture();
      var source = p.worldGenerator.layers.getFirst();
      var target = sameLayer ? source : p.worldGenerator.layers.getLast();
      var first = source.operations.getFirst();
      var last = source.operations.getLast();
      var anchor = sameLayer ? source.operations.get(1) : target.operations.getFirst();
      ResourceGraph.reorderBefore(p, List.of(last.uuid, first.uuid), anchor.uuid);
      assertEquals(List.of(first.uuid, last.uuid, anchor.uuid), ids(target));
      assertTrue(Validator.validate(p).isEmpty());
    }
  }

  @Test
  void cutFollowsLiveSiblingOrderAndDefinitionsAfterTheCutWasCreated() {
    var p = fixture();
    var source = p.worldGenerator.layers.getFirst();
    var target = p.worldGenerator.layers.getLast();
    var first = source.operations.getFirst();
    var last = source.operations.getLast();
    var anchor = target.operations.getFirst();
    var cut = new ResourceClipboard(p, List.of(first.uuid, last.uuid), true);
    Collections.swap(source.operations, 0, 2);
    first.overrides.put("threshold", ProjectIO.JSON.valueToTree(3.25));
    cut.paste(p, "operation", target.uuid);
    assertEquals(List.of(anchor.uuid, last.uuid, first.uuid), ids(target));
    assertSame(first, target.operations.getLast());
    assertEquals(3.25, target.operations.getLast().overrides.get("threshold").doubleValue());
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void copyRetainsCapturedOrderAndValuesWhileRemappingOnlyCopiedIdentities() {
    var p = fixture();
    var source = p.worldGenerator.layers.getFirst();
    var target = p.worldGenerator.layers.getLast();
    var first = source.operations.getFirst();
    var last = source.operations.getLast();
    var field = first.noiseGeneratorUuid;
    first.overrides.put("threshold", ProjectIO.JSON.valueToTree(.125));
    var copy = new ResourceClipboard(p, List.of(last.uuid, first.uuid), false);
    Collections.swap(source.operations, 0, 2);
    first.overrides.put("threshold", ProjectIO.JSON.valueToTree(3.25));
    copy.paste(p, "operation", target.uuid);
    assertEquals(
        List.of("anchor", "first", "last"), target.operations.stream().map(o -> o.id).toList());
    assertNotEquals(first.uuid, target.operations.get(1).uuid);
    assertNotEquals(last.uuid, target.operations.getLast().uuid);
    assertEquals(field, target.operations.get(1).noiseGeneratorUuid);
    assertEquals(.125, target.operations.get(1).overrides.get("threshold").doubleValue());
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void collisionsAreResolvedWithoutChangingTheMovedBlockOrExistingDefinitions() {
    var p = fixture();
    var source = p.worldGenerator.layers.getFirst();
    var target = p.worldGenerator.layers.getLast();
    var first = source.operations.getFirst();
    var last = source.operations.getLast();
    target.operations.getFirst().id = "first";
    var existing = ProjectIO.JSON.valueToTree(target.operations);
    ResourceGraph.move(p, List.of(last.uuid, first.uuid), "operation", target.uuid);
    assertEquals(
        List.of("first", "first-copy", "last"), target.operations.stream().map(o -> o.id).toList());
    assertEquals(existing.get(0), ProjectIO.JSON.valueToTree(target.operations.getFirst()));
    assertSame(first, target.operations.get(1));
    assertSame(last, target.operations.getLast());
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void flatResourceMovesAlsoUseCurrentOrderWithoutChangingMembershipReferences() {
    var p = fixture();
    for (String id : List.of("first", "middle", "last")) {
      var type = new VoxelType();
      type.id = id;
      p.voxelTypes.add(type);
    }
    var first = p.voxelTypes.getFirst();
    var middle = p.voxelTypes.get(1);
    var last = p.voxelTypes.getLast();
    var group = new Membership();
    group.id = "members";
    group.voxelTypeUuids.addAll(List.of(first.uuid, last.uuid));
    p.voxelGroups.add(group);
    var before = ProjectIO.JSON.valueToTree(group);
    ResourceGraph.move(p, List.of(last.uuid, first.uuid), "voxel", null);
    assertEquals(
        List.of(middle.uuid, first.uuid, last.uuid),
        p.voxelTypes.stream().map(t -> t.uuid).toList());
    assertEquals(before, ProjectIO.JSON.valueToTree(group));
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void staleMixedAndInvalidDestinationSelectionsRejectBeforeAnyMutation() {
    for (String failure : List.of("missing", "mixed", "root", "parent", "cut")) {
      var p = fixture();
      var source = p.worldGenerator.layers.getFirst();
      var target = p.worldGenerator.layers.getLast();
      var first = source.operations.getFirst();
      var last = source.operations.getLast();
      var cut = new ResourceClipboard(p, List.of(first.uuid, last.uuid), true);
      if (failure.equals("cut")) source.operations.remove(last);
      var before = ProjectIO.JSON.valueToTree(p);
      assertThrows(
          RuntimeException.class,
          () -> {
            switch (failure) {
              case "missing" ->
                  ResourceGraph.move(
                      p, List.of(first.uuid, UUID.randomUUID()), "operation", target.uuid);
              case "mixed" ->
                  ResourceGraph.move(
                      p,
                      List.of(first.uuid, target.operations.getFirst().uuid),
                      "operation",
                      target.uuid);
              case "root" -> ResourceGraph.move(p, List.of(first.uuid), "world", null);
              case "parent" ->
                  ResourceGraph.move(
                      p, List.of(first.uuid), "operation", p.noise2D.getFirst().uuid);
              case "cut" -> cut.paste(p, "operation", target.uuid);
              default -> throw new AssertionError();
            }
          });
      assertEquals(before, ProjectIO.JSON.valueToTree(p));
    }
  }
}
