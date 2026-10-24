package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class NumericParameterTest {
  private static ProjectData fixture(String interpretation) {
    var p = new ProjectData();
    p.project.minY = -16;
    p.project.maxY = 16;
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var n = new Noise();
    n.id = "primary";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", .5);
    p.noise2D.add(n);
    var l = new Layer();
    l.id = "layer";
    l.defaults.action = "SET";
    l.defaults.voxelTypeUuid = type.uuid;
    l.defaults.baseHeight = 1;
    l.defaults.heightScale = 0;
    l.defaults.rounding = "FLOOR";
    p.worldGenerator.layers.add(l);
    var op = new Operation();
    op.id = "placement";
    op.noiseGeneratorUuid = n.uuid;
    op.interpretation = interpretation;
    l.operations.add(op);
    return p;
  }

  private static Settings settings(ProjectData p) {
    return p.worldGenerator.layers.getFirst().defaults;
  }

  private static Operation operation(ProjectData p) {
    return p.worldGenerator.layers.getFirst().operations.getFirst();
  }

  private static NumericDriver driver(String target, String combination, double min, double max) {
    var d = new NumericDriver();
    d.enabled = true;
    d.destination = target;
    d.combination = combination;
    d.outputMin = min;
    d.outputMax = max;
    return d;
  }

  private static Noise field(ProjectData p, String expression, int dimension) {
    var n = new Noise();
    n.id = "field-" + (p.noise2D.size() + p.noise3D.size());
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    var e = ProcessingRegistry.create("EXPRESSION", "expression");
    e.parameters.put("expression", ProjectIO.JSON.valueToTree(expression));
    n.processing.add(e);
    n.outputBinding = Binding.earlier(e.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    return n;
  }

  private static WorldContext world(NoiseGeneratorEngine e, ProjectData p, int root) {
    return e.openWorld(
        new CompiledProject(p), new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  @Test
  void thresholdMappingUsesPrimarySampleAndExactInclusiveComparison() {
    var p = fixture("SELECTION");
    var d = driver("threshold", "REPLACE", -1, 1);
    settings(p).parameterDrivers.add(d);
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 4)) {
      var step = w.trace(new BlockPos(0, 0, 0)).steps().getFirst();
      assertEquals(1, step.result());
      var row = step.details().parameters().getFirst();
      assertEquals(.5, row.sample());
      assertEquals(0, row.effective());
      assertEquals(0, row.previous());
    }
    d.outputMin = d.outputMax = .5;
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 4)) {
      assertEquals(1, w.getVoxelTypeId(new BlockPos(0, 0, 0)));
    }
    d.outputMin = d.outputMax = Math.nextUp(.5);
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 4)) {
      assertEquals(0, w.getVoxelTypeId(new BlockPos(0, 0, 0)));
    }
  }

  @Test
  void repeatedDestinationsFollowStoredFloatingPointOrder() {
    var p = fixture("HEIGHT_BOUNDARY");
    var s = settings(p);
    s.parameterDrivers.add(driver("baseHeight", "ADD", 2, 2));
    s.parameterDrivers.add(driver("baseHeight", "MULTIPLY", 3, 3));
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 4)) {
      var t = w.trace(new BlockPos(0, 9, 0));
      assertEquals(1, t.finalType());
      var rows = t.steps().getFirst().details().parameters();
      assertEquals(1, rows.get(0).previous());
      assertEquals(3, rows.get(0).effective());
      assertEquals(9, rows.get(1).effective());
      assertEquals(0, w.getVoxelTypeId(new BlockPos(0, 5, 0)));
      assertThrows(UnsupportedOperationException.class, () -> rows.clear());
    }
    Collections.reverse(s.parameterDrivers);
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 4)) {
      assertEquals(1, w.getVoxelTypeId(new BlockPos(0, 5, 0)));
      assertEquals(0, w.getVoxelTypeId(new BlockPos(0, 9, 0)));
    }
  }

  @Test
  void heightScaleAndBaseHeightResolveBeforeRounding() {
    var p = fixture("HEIGHT_BOUNDARY");
    var s = settings(p);
    s.rounding = "ROUND";
    s.parameterDrivers.add(driver("baseHeight", "REPLACE", -2.25, -2.25));
    s.parameterDrivers.add(driver("heightScale", "REPLACE", 5, 5)); // -2.25 + .5*5 = .25 -> 0
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 4)) {
      assertEquals(1, w.getVoxelTypeId(new BlockPos(0, 0, 0)));
      assertEquals(0, w.getVoxelTypeId(new BlockPos(0, 1, 0)));
    }
  }

  @Test
  void threeDimensionalDriverIsNotAccidentallyColumnCached() {
    var p = fixture("DENSITY");
    p.noise2D.getFirst().algorithm.parameters.put("value", 0.);
    var source = field(p, "y", 3);
    var d = driver("threshold", "REPLACE", -16, 16);
    d.inputMin = -16;
    d.inputMax = 16;
    d.noiseGeneratorUuid = source.uuid;
    settings(p).parameterDrivers.add(d);
    try (var e = new NoiseGeneratorEngine()) {
      for (int root : new int[] {2, 4, 8, 16})
        try (var w = world(e, p, root)) {
          for (int y = -4; y <= 4; y++)
            assertEquals(
                y <= 0 ? 1 : 0,
                w.getVoxelTypeId(new BlockPos(-1, y, -1)),
                "root=" + root + " y=" + y);
          assertEquals(
              1,
              w.trace(new BlockPos(-1, 1, -1))
                  .steps()
                  .getFirst()
                  .details()
                  .parameters()
                  .getFirst()
                  .effective());
        }
    }
  }

  @Test
  void explicitTwoDimensionalDriverUsesPlacementAxesAndItsOwnProjection() {
    var p = fixture("DENSITY");
    var source = field(p, "x", 2);
    var d = driver("threshold", "REPLACE", -16, 16);
    d.inputMin = -16;
    d.inputMax = 16;
    d.noiseGeneratorUuid = source.uuid;
    settings(p).parameterDrivers.add(d);
    settings(p).axisX = "Y";
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 8)) {
      for (int y = -3; y < 4; y++)
        assertEquals(y <= .5 ? 1 : 0, w.getVoxelTypeId(new BlockPos(9, y, 2)));
      assertEquals(
          2,
          w.trace(new BlockPos(9, 2, 2))
              .steps()
              .getFirst()
              .details()
              .parameters()
              .getFirst()
              .sample());
    }
  }

  @Test
  void unusedDisabledAndOutsideYRowsNeverSampleInvalidFields() {
    var p = fixture("ALL");
    var invalid = field(p, "1/(x-x)", 3);
    var d = driver("threshold", "REPLACE", 0, 1);
    d.noiseGeneratorUuid = invalid.uuid;
    settings(p).parameterDrivers.add(d);
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 4)) {
      assertEquals(1, w.getVoxelTypeId(new BlockPos(0, 0, 0)));
      assertTrue(
          w.trace(new BlockPos(0, 0, 0)).steps().getFirst().details().parameters().isEmpty());
    }
    operation(p).interpretation = "DENSITY";
    d.enabled = false;
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 4)) {
      assertEquals(1, w.getVoxelTypeId(new BlockPos(0, 0, 0)));
    }
    d.enabled = true;
    settings(p).minY = 2;
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 4)) {
      assertEquals(0, w.trace(new BlockPos(0, 0, 0)).finalType());
    }
  }

  @Test
  void interpretationPrecedesGatesAndOverflowFailsWithoutPublishingChunk() {
    var p = fixture("HEIGHT_BELOW");
    settings(p).baseHeight = Double.MAX_VALUE;
    settings(p).parameterDrivers.add(driver("baseHeight", "MULTIPLY", 2, 2));
    settings(p).fieldGates.kind = "PROBABILITY";
    settings(p).fieldGates.probability = 0;
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, p, 4)) {
      var failure =
          assertThrows(EvaluationFailure.class, () -> w.generateChunk(new ChunkPos(0, 0, 0)));
      assertTrue(failure.getMessage().contains("parameterDrivers[0]"));
      assertEquals(operation(p).uuid, failure.itemUuid);
      assertEquals(0, w.cacheStats().chunks());
    }
  }

  @Test
  void stageSnapshotsAndCompiledProjectAreIndependentOfLaterAuthoringEdits() {
    var p = fixture("HEIGHT_BELOW");
    var d = driver("baseHeight", "REPLACE", 3, 3);
    settings(p).parameterDrivers.add(d);
    var compiled = new CompiledProject(p);
    d.outputMin = d.outputMax = -8;
    try (var e = new NoiseGeneratorEngine();
        var w =
            e.openWorld(
                compiled, new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var stage = w.generateStageChunk(new ChunkPos(0, 0, 0), operation(p).uuid);
      assertTrue(stage.before().isEmpty());
      assertEquals(1, stage.after().getVoxelTypeId(0, 3, 0));
      assertEquals(
          3,
          w.trace(new BlockPos(0, 3, 0))
              .steps()
              .getFirst()
              .details()
              .parameters()
              .getFirst()
              .effective());
    }
  }

  @Test
  void workerRootAndTraceResultsAgreeWithIndependentHeightFormula() throws Exception {
    var p = fixture("HEIGHT_BELOW");
    var n = field(p, "x+z", 3);
    var d = driver("baseHeight", "REPLACE", -8, 8);
    d.noiseGeneratorUuid = n.uuid;
    d.inputMin = -8;
    d.inputMax = 8;
    settings(p).parameterDrivers.add(d);
    try (var e = new NoiseGeneratorEngine(3, 32, 512L << 20);
        var a = world(e, p, 4);
        var b = world(e, p, 8)) {
      var futures = new ArrayList<CompletableFuture<VoxelChunk>>();
      for (int x = -1; x <= 1; x++) futures.add(a.generateChunkAsync(new ChunkPos(x, 0, -1)));
      for (var f : futures) f.get(10, TimeUnit.SECONDS);
      for (int x = -3; x <= 3; x++)
        for (int z = -3; z <= 3; z++)
          for (int y = -3; y <= 3; y++) {
            int expected = y <= x + z ? 1 : 0;
            var pos = new BlockPos(x, y, z);
            assertEquals(expected, a.getVoxelTypeId(pos));
            assertEquals(expected, b.getVoxelTypeId(pos));
            assertEquals(expected, a.trace(pos).finalType());
          }
    }
  }

  @Test
  void historicalDetailsConstructorsHaveEmptyParameterReports() {
    var minimal = new GenerationTrace.Details(Map.of(), List.of(), null, null, 1, 0, true);
    var oldFull =
        new GenerationTrace.Details(
            Map.of(), List.of(), true, true, 1, 0, true, null, null, 1, List.of());
    assertTrue(minimal.parameters().isEmpty());
    assertTrue(oldFull.parameters().isEmpty());
  }
}
