package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectData.Predicate;
import io.voxelcreator.storage.BlockPos;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorldConditionEditsTest {
  private static String json(Object value) {
    return ProjectIO.JSON.valueToTree(value).toString();
  }

  private static Gate compare(String comparison, double threshold) {
    var gate = new Gate();
    gate.kind = "COMPARE";
    gate.comparison = comparison;
    gate.threshold = threshold;
    return gate;
  }

  @Test
  void predicateGroupingRetainsReferencesAndLeafChangesRespectTheirReferenceFamily() {
    var predicate = new Predicate();
    predicate.kind = "TYPE_IS";
    predicate.reference = UUID.randomUUID();
    String leaf = json(predicate);
    assertTrue(WorldConditionEdits.kind(predicate, "AND", 0));
    assertEquals(leaf, json(predicate.children.getFirst()));
    assertNull(predicate.reference);
    assertTrue(WorldConditionEdits.kind(predicate, "OR", 0));
    assertEquals(leaf, json(predicate.children.getFirst()));
    assertTrue(WorldConditionEdits.unwrap(predicate));
    assertEquals(leaf, json(predicate));
    UUID reference = predicate.reference;
    WorldConditionEdits.kind(predicate, "TYPE_IS_NOT", 0);
    assertEquals(reference, predicate.reference);
    WorldConditionEdits.kind(predicate, "IN_SET", 0);
    assertNull(predicate.reference);
    predicate.reference = UUID.randomUUID();
    reference = predicate.reference;
    WorldConditionEdits.kind(predicate, "NOT_IN_SET", 0);
    assertEquals(reference, predicate.reference);
    WorldConditionEdits.kind(predicate, "AND", 0);
    WorldConditionEdits.kind(predicate, "EMPTY", 0);
    assertTrue(predicate.children.isEmpty());
    assertNull(predicate.reference);
  }

  @Test
  void notWrapsTheWholeGroupAndChangingToAndRetainsNegation() {
    var gate = compare("GT", -2.5);
    gate.noiseGeneratorUuid = UUID.randomUUID();
    WorldConditionEdits.kind(gate, "AND", 0);
    gate.children.add(compare("LT", 7.25));
    String group = json(gate);
    WorldConditionEdits.kind(gate, "NOT", 0);
    assertEquals(1, gate.children.size());
    assertEquals(group, json(gate.children.getFirst()));
    String negation = json(gate);
    WorldConditionEdits.kind(gate, "OR", 0);
    assertEquals(negation, json(gate.children.getFirst()));
    assertTrue(WorldConditionEdits.unwrap(gate));
    assertEquals(negation, json(gate));
    assertTrue(WorldConditionEdits.unwrap(gate));
    assertEquals(group, json(gate));
  }

  @Test
  void probabilitySeedsAndNestedCollectionsAreOwnedAcrossWrapAndUnwrap() {
    for (long seed : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
      var gate = new Gate();
      gate.kind = "PROBABILITY";
      gate.probability = .1234567890123456;
      gate.seedOffset = seed;
      WorldConditionEdits.kind(gate, "AND", 0);
      Gate savedChild = gate.children.getFirst();
      String before = json(savedChild);
      WorldConditionEdits.kind(gate, "NOT", 0);
      savedChild.seedOffset = 17;
      assertEquals(before, json(gate.children.getFirst().children.getFirst()));
      Gate oldGroup = gate.children.getFirst();
      WorldConditionEdits.unwrap(gate);
      oldGroup.children.getFirst().seedOffset = 42;
      assertEquals(seed, gate.children.getFirst().seedOffset);
      assertEquals(.1234567890123456, gate.children.getFirst().probability);
    }
    var predicate = new Predicate();
    WorldConditionEdits.kind(predicate, "AND", 0);
    var child = predicate.children.getFirst();
    WorldConditionEdits.unwrap(predicate);
    child.kind = "EMPTY";
    assertEquals("ANY", predicate.kind);
  }

  @Test
  void logicalArityCannotBeBrokenAndMalformedNotCanBeRepaired() {
    var gate = new Gate();
    assertFalse(WorldConditionEdits.add(gate, 0));
    WorldConditionEdits.kind(gate, "NOT", 0);
    assertFalse(WorldConditionEdits.add(gate, 0));
    assertFalse(WorldConditionEdits.remove(gate, 0));
    gate.children.add(new Gate());
    assertTrue(WorldConditionEdits.remove(gate, 1));
    assertFalse(WorldConditionEdits.remove(gate, -1));
    gate.children.clear();
    assertTrue(WorldConditionEdits.add(gate, 0));
    var predicate = new Predicate();
    WorldConditionEdits.kind(predicate, "AND", 0);
    assertFalse(WorldConditionEdits.remove(predicate, 0));
    assertTrue(WorldConditionEdits.add(predicate, 0));
    assertFalse(WorldConditionEdits.unwrap(predicate));
    assertFalse(WorldConditionEdits.remove(predicate, 2));
    assertTrue(WorldConditionEdits.remove(predicate, 0));
    assertEquals(1, predicate.children.size());
  }

  @Test
  void wrappingChecksTheWholeSubtreeAndRejectsBeforeMutationAtTheExactDepthLimit() {
    var gate = new Gate();
    Gate cursor = gate;
    for (int depth = 0; depth < 31; depth++) {
      cursor.kind = "AND";
      cursor.children.add(new Gate());
      cursor = cursor.children.getFirst();
    }
    assertTrue(WorldConditionEdits.kind(gate, "NOT", 0));
    String before = json(gate);
    assertThrows(IllegalArgumentException.class, () -> WorldConditionEdits.kind(gate, "OR", 0));
    assertEquals(before, json(gate));
    var leaf = new Predicate();
    assertTrue(WorldConditionEdits.kind(leaf, "AND", 31));
    before = json(leaf);
    assertThrows(
        IllegalArgumentException.class,
        () -> WorldConditionEdits.kind(leaf.children.getFirst(), "OR", 32));
    assertThrows(IllegalArgumentException.class, () -> WorldConditionEdits.add(leaf, 32));
    assertEquals(before, json(leaf));
    assertTrue(WorldConditionEdits.kind(leaf, "OR", 31));
    assertTrue(WorldConditionEdits.unwrap(leaf));
  }

  @Test
  void rejectedKindsAndNoOpChoicesPreserveTheEntireAuthoredTree() {
    var gate = compare("LE", 9);
    gate.seedOffset = Long.MIN_VALUE;
    String before = json(gate);
    assertFalse(WorldConditionEdits.kind(gate, "COMPARE", 0));
    assertThrows(
        IllegalArgumentException.class, () -> WorldConditionEdits.kind(gate, "unknown", 0));
    assertFalse(WorldConditionEdits.unwrap(gate));
    assertEquals(before, json(gate));
    var predicate = new Predicate();
    before = json(predicate);
    assertFalse(WorldConditionEdits.kind(predicate, "ANY", 0));
    assertThrows(
        IllegalArgumentException.class, () -> WorldConditionEdits.kind(predicate, "NOT", 0));
    assertEquals(before, json(predicate));
  }

  @Test
  void privateGroupingKeepsRawInvalidSeedsAndPublishesOnlyOneApplyTransaction() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var layer = new Layer();
                layer.id = "layer";
                layer.defaults.fieldGates.kind = "PROBABILITY";
                layer.defaults.fieldGates.seedOffset = Long.MIN_VALUE;
                p.worldGenerator.layers.add(layer);
              }));
      state.select("layer", state.project().worldGenerator.layers.getFirst().uuid);
      String committed = json(state.project());
      long revision = state.revision;
      var gate = ((Layer) state.draft).defaults.fieldGates;
      assertTrue(
          state.seedInputs.edit(state, "settings/fieldGates/seedOffset", "Seed", "-").isEmpty());
      WorldConditionEdits.kind(gate, "AND", 0);
      state.seedInputs.orphan(state, "settings/fieldGates");
      assertEquals(committed, json(state.project()));
      assertEquals(revision, state.revision);
      assertFalse(state.applyDraft());
      var problem = state.seedInputs.problems(state).getFirst();
      assertTrue(problem.orphan());
      assertEquals("-", problem.text());
      state.seedInputs.discard(state, problem.id());
      assertTrue(state.applyDraft());
      String applied = json(state.project());
      assertNotEquals(committed, applied);
      assertEquals(
          Long.MIN_VALUE,
          state
              .project()
              .worldGenerator
              .layers
              .getFirst()
              .defaults
              .fieldGates
              .children
              .getFirst()
              .seedOffset);
      state.document.undo();
      assertEquals(committed, json(state.project()));
      state.document.redo();
      assertEquals(applied, json(state.project()));
    }
  }

  private static ProjectData fixture() {
    var p = new ProjectData();
    p.project.minY = -2;
    p.project.maxY = 2;
    for (String id : List.of("base", "replacement")) {
      var type = new VoxelType();
      type.id = id;
      p.voxelTypes.add(type);
    }
    var noise = new Noise();
    noise.id = "x-field";
    noise.dimension = 3;
    noise.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "x");
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x"));
    noise.processing.add(expression);
    noise.outputBinding = Binding.earlier(expression.uuid);
    p.noise3D.add(noise);
    for (int i = 0; i < 2; i++) {
      var layer = new Layer();
      layer.id = "layer-" + i;
      layer.defaults.action = "SET";
      layer.defaults.voxelTypeUuid = p.voxelTypes.get(i).uuid;
      var op = new Operation();
      op.id = "placement-" + i;
      op.noiseGeneratorUuid = noise.uuid;
      op.interpretation = i == 0 ? "SELECTION" : "ALL";
      layer.operations.add(op);
      p.worldGenerator.layers.add(layer);
    }
    return p;
  }

  private static void expect(ProjectData p, java.util.function.IntPredicate selected) {
    assertEquals(List.of(), Validator.validate(p));
    try (var engine = new NoiseGeneratorEngine()) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                new CompiledProject(p),
                new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (int z = -1; z <= 1; z++)
            for (int y = -3; y <= 3; y++)
              for (int x = -3; x <= 3; x++) {
                int expected = y < -2 || y > 2 ? 0 : selected.test(x) ? 2 : x >= 0 ? 1 : 0;
                assertEquals(
                    expected,
                    world.getVoxelTypeId(new BlockPos(x, y, z)),
                    root + ":" + x + "," + y + "," + z);
              }
        }
    }
  }

  @Test
  void deterministicFieldRegroupingMatchesIndependentBooleanIntervals() {
    var p = fixture();
    var gate = p.worldGenerator.layers.getLast().defaults.fieldGates;
    gate.kind = "AND";
    gate.children.add(compare("GE", -.5));
    gate.children.add(compare("LE", 1.5));
    expect(p, x -> x >= 0 && x <= 1);
    WorldConditionEdits.kind(gate, "NOT", 0);
    expect(p, x -> x < 0 || x > 1);
    WorldConditionEdits.kind(gate, "OR", 0);
    expect(p, x -> x < 0 || x > 1);
    gate.children.add(compare("GE", .5));
    expect(p, x -> x != 0);
    WorldConditionEdits.kind(gate, "AND", 0);
    expect(p, x -> x > 1);
  }

  @Test
  void previousStateNegationExcludesEmptyUntilAnExplicitEmptyClauseAndOverridesStaySparse() {
    var p = fixture();
    var layer = p.worldGenerator.layers.getLast();
    var predicate = layer.defaults.voxelStatePredicate;
    predicate.kind = "TYPE_IS_NOT";
    predicate.reference = p.voxelTypes.getLast().uuid;
    expect(p, x -> x >= 0);
    WorldConditionEdits.kind(predicate, "OR", 0);
    expect(p, x -> x >= 0);
    var empty = new Predicate();
    empty.kind = "EMPTY";
    predicate.children.add(empty);
    expect(p, x -> true);
    var op = layer.operations.getFirst();
    assertTrue(op.overrides.isEmpty());
    var owned = SettingsResolver.resolve(layer, op);
    WorldConditionEdits.kind(owned.voxelStatePredicate, "OCCUPIED", 0);
    op.overrides.put("voxelStatePredicate", ProjectIO.JSON.valueToTree(owned.voxelStatePredicate));
    assertEquals(Set.of("voxelStatePredicate"), op.overrides.keySet());
    expect(p, x -> x >= 0);
    op.overrides.remove("voxelStatePredicate");
    expect(p, x -> true);
  }
}
