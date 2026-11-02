package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

/** Deferred mathematical replay of actual list edits, independent of the editor's draw order. */
class WorldListEngineTest {
  private static ProjectData fixture() {
    var p = new ProjectData();
    p.project.minY = -2;
    p.project.maxY = 2;
    for (String id : List.of("stone", "snow")) {
      var type = new VoxelType();
      type.id = id;
      p.voxelTypes.add(type);
    }
    var noise = new Noise();
    noise.id = "x";
    noise.dimension = 3;
    noise.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "x-expression", 3);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x"));
    noise.processing.add(expression);
    noise.outputBinding = Binding.earlier(expression.uuid);
    p.noise3D.add(noise);
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = p.voxelTypes.getFirst().uuid;
    var op = new Operation();
    op.id = "placement";
    op.interpretation = "SELECTION";
    op.noiseGeneratorUuid = noise.uuid;
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    return p;
  }

  private static <T> void edit(
      List<T> rows, int index, WorldListEdits.Action action, int limit, Class<T> type) {
    assertTrue(
        WorldListEdits.apply(rows, WorldListEdits.request(rows, index, action), limit, type));
  }

  private static void expect(ProjectData p, IntToDoubleFunction field, IntUnaryOperator result) {
    assertEquals(List.of(), Validator.validate(p));
    String before = ProjectIO.JSON.valueToTree(p).toString();
    try (var engine = new NoiseGeneratorEngine()) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                new CompiledProject(p),
                new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (int z = -1; z <= 1; z++)
            for (int y = -1; y <= 1; y++)
              for (int x = -4; x <= 10; x++) {
                var position = new BlockPos(x, y, z);
                assertEquals(
                    result.applyAsInt(x), world.getVoxelTypeId(position), position.toString());
                assertEquals(
                    field.applyAsDouble(x), world.trace(position).steps().getFirst().field());
              }
        }
    }
    assertEquals(before, ProjectIO.JSON.valueToTree(p).toString());
  }

  @Test
  void transformMovesCopiesAndRemovalFollowNoncommutativeAffineMath() {
    var p = fixture();
    var rows = p.worldGenerator.layers.getFirst().defaults.samplingTransforms;
    var translation = new SamplingTransform();
    translation.x = 3;
    var scale = new SamplingTransform();
    scale.kind = "SCALE";
    scale.x = 2;
    scale.y = scale.z = 1;
    rows.addAll(List.of(translation, scale));
    expect(p, x -> 2 * (x + 3), x -> 2 * (x + 3) >= 0 ? 1 : 0);
    edit(rows, 0, WorldListEdits.Action.DOWN, 64, SamplingTransform.class);
    expect(p, x -> 2 * x + 3, x -> 2 * x + 3 >= 0 ? 1 : 0);
    edit(rows, 0, WorldListEdits.Action.DUPLICATE, 64, SamplingTransform.class);
    expect(p, x -> 4 * x + 3, x -> 4 * x + 3 >= 0 ? 1 : 0);
    rows.get(1).x = -1;
    assertEquals(2, rows.getFirst().x);
    expect(p, x -> -2 * x + 3, x -> -2 * x + 3 >= 0 ? 1 : 0);
    edit(rows, 1, WorldListEdits.Action.REMOVE, 64, SamplingTransform.class);
    expect(p, x -> 2 * x + 3, x -> 2 * x + 3 >= 0 ? 1 : 0);
  }

  private static NumericDriver driver(String combination, double value) {
    var d = new NumericDriver();
    d.enabled = true;
    d.destination = "threshold";
    d.combination = combination;
    d.outputMin = d.outputMax = value;
    return d;
  }

  @Test
  void numericDriverOrderAndDuplicationHaveExplicitAlgebraicEffects() {
    var p = fixture();
    var settings = p.worldGenerator.layers.getFirst().defaults;
    settings.threshold = 2;
    var rows = settings.parameterDrivers;
    rows.addAll(List.of(driver("ADD", 1), driver("MULTIPLY", 3)));
    expect(p, x -> x, x -> x >= 9 ? 1 : 0); // (2+1)*3
    edit(rows, 0, WorldListEdits.Action.DOWN, 32, NumericDriver.class);
    expect(p, x -> x, x -> x >= 7 ? 1 : 0); // 2*3+1
    edit(rows, 1, WorldListEdits.Action.DUPLICATE, 32, NumericDriver.class);
    expect(p, x -> x, x -> x >= 8 ? 1 : 0); // 2*3+1+1
    edit(rows, 0, WorldListEdits.Action.REMOVE, 32, NumericDriver.class);
    expect(p, x -> x, x -> x >= 4 ? 1 : 0); // 2+1+1
  }

  @Test
  void nonoverlappingIntervalReorderPreservesTypesAndRemovalLeavesGapsEmpty() {
    var p = fixture();
    var layer = p.worldGenerator.layers.getFirst();
    layer.operations.getFirst().interpretation = "TYPE_SELECTION";
    layer.defaults.typeSource = "INTERVALS";
    var rows = layer.defaults.typeIntervals;
    var a = new TypeInterval();
    a.min = -2;
    a.max = 0;
    a.voxelTypeUuid = p.voxelTypes.getFirst().uuid;
    var b = new TypeInterval();
    b.min = 0;
    b.max = 2;
    b.includeMax = true;
    b.voxelTypeUuid = p.voxelTypes.getLast().uuid;
    rows.addAll(List.of(a, b));
    IntUnaryOperator original = x -> x < -2 || x > 2 ? 0 : x < 0 ? 1 : 2;
    expect(p, x -> x, original);
    edit(rows, 1, WorldListEdits.Action.UP, 256, TypeInterval.class);
    expect(p, x -> x, original);
    edit(rows, 1, WorldListEdits.Action.REMOVE, 256, TypeInterval.class);
    expect(p, x -> x, x -> x >= 0 && x <= 2 ? 2 : 0);
  }
}
