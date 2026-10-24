package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldUseTest {
  private static UUID id(int value) {
    return new UUID(0, value);
  }

  private record Fixture(ProjectData project, Layer layer, Operation operation, Noise noise) {}

  private static Fixture fixture(int dimension, String expression) {
    var p = new ProjectData();
    p.project.uuid = id(1);
    p.worldGenerator.uuid = id(2);
    p.project.seed = 7;
    p.project.minY = -32;
    p.project.maxY = 31;
    for (int i : new int[] {3, 5, 8}) {
      var t = new VoxelType();
      t.uuid = id(i);
      t.id = "type-" + i;
      p.voxelTypes.add(t);
    }
    var n = new Noise();
    n.uuid = id(4);
    n.id = "primary";
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    var e = ProcessingRegistry.create("EXPRESSION", "expression");
    e.uuid = id(9);
    e.parameters.put("expression", ProjectIO.JSON.valueToTree(expression));
    n.processing.add(e);
    n.outputBinding = Binding.earlier(e.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    var l = new Layer();
    l.uuid = id(6);
    l.id = "layer";
    l.defaults.action = "SET";
    l.defaults.voxelTypeUuid = id(3);
    p.worldGenerator.layers.add(l);
    var op = new Operation();
    op.uuid = id(7);
    op.id = "placement";
    op.noiseGeneratorUuid = n.uuid;
    op.interpretation = "ALL";
    l.operations.add(op);
    return new Fixture(p, l, op, n);
  }

  private static WorldOptions options(int size) {
    return new WorldOptions(null, size, WorldOptions.CachePolicy.NO_CACHE, 0);
  }

  private static TypeInterval range(
      double min, double max, boolean includeMin, boolean includeMax, int target) {
    var r = new TypeInterval();
    r.min = min;
    r.max = max;
    r.includeMin = includeMin;
    r.includeMax = includeMax;
    r.voxelTypeUuid = id(target);
    return r;
  }

  private static void valid(ProjectData p) {
    assertEquals(List.of(), Validator.validate(p));
  }

  private static void sameChunk(VoxelChunk a, VoxelChunk b) {
    assertEquals(a.size(), b.size());
    assertEquals(a.position(), b.position());
    for (int z = 0; z < a.size(); z++)
      for (int y = 0; y < a.size(); y++)
        for (int x = 0; x < a.size(); x++)
          assertEquals(a.getVoxelTypeId(x, y, z), b.getVoxelTypeId(x, y, z));
  }

  @Test
  void intervalsSelectAtDeclaredEdgesAndGapsPreserveCommittedState() {
    var f = fixture(2, "x");
    var p = f.project;
    var l = f.layer;
    var fill = new Layer();
    fill.uuid = id(20);
    fill.id = "initial";
    fill.defaults.voxelTypeUuid = id(3);
    fill.defaults.action = "SET";
    var first = new Operation();
    first.uuid = id(21);
    first.id = "fill";
    first.noiseGeneratorUuid = f.noise.uuid;
    first.interpretation = "ALL";
    fill.operations.add(first);
    p.worldGenerator.layers.addFirst(fill);
    l.defaults.typeSource = "INTERVALS";
    l.defaults.voxelTypeUuid = null;
    l.defaults.typeIntervals.addAll(
        List.of(range(-2, 0, true, false, 5), range(0, 2, true, true, 8)));
    f.operation.interpretation = "TYPE_SELECTION";
    valid(p);
    try (var engine = new NoiseGeneratorEngine();
        var world = engine.openWorld(new CompiledProject(p), options(4))) {
      for (int x = -3; x <= 3; x++)
        assertEquals(
            x < -2 || x > 2 ? 1 : x < 0 ? 2 : 3,
            world.getVoxelTypeId(new BlockPos(x, 0, 0)),
            "x=" + x);
      var trace = world.trace(new BlockPos(0, 0, 0)).steps().getLast();
      assertEquals(1, trace.details().matchedInterval());
      assertEquals(3, trace.details().candidateType());
      assertTrue(
          world
              .trace(new BlockPos(3, 0, 0))
              .steps()
              .getLast()
              .reason()
              .contains("No type interval"));
    }
    l.defaults.action = "CLEAR";
    try (var engine = new NoiseGeneratorEngine();
        var world = engine.openWorld(new CompiledProject(p), options(4))) {
      assertEquals(1, world.getVoxelTypeId(new BlockPos(3, 0, 0)));
      assertEquals(0, world.getVoxelTypeId(new BlockPos(0, 0, 0)));
    }
  }

  @Test
  void validationRejectsOverlapsAndEmptyMappingsWithPrecisePaths() {
    var f = fixture(2, "x");
    var s = f.layer.defaults;
    s.typeSource = "INTERVALS";
    s.typeIntervals.addAll(List.of(range(0, 1, false, true, 3), range(0, 0, true, true, 5)));
    valid(f.project);
    s.typeIntervals.getFirst().includeMin = true;
    assertTrue(
        Validator.validate(f.project).stream()
            .anyMatch(
                d ->
                    d.code().equals("OVERLAPPING_INTERVAL")
                        && d.jsonPointer().contains("typeIntervals")));
    s.typeIntervals.getFirst().includeMin = false;
    s.typeIntervals.getLast().includeMax = false;
    assertTrue(
        Validator.validate(f.project).stream().anyMatch(d -> d.code().equals("EMPTY_INTERVAL")));
    s.typeIntervals.clear();
    assertTrue(
        Validator.validate(f.project).stream().anyMatch(d -> d.code().equals("EMPTY_INTERVALS")));
  }

  @Test
  void transformsApplyAxesComponentsOrderAndPivotBeforeCanonicalField() {
    var f = fixture(3, "x+10*y+100*z");
    var s = f.layer.defaults;
    s.axisX = "-Z";
    s.axisY = "X";
    s.axisZ = "Y";
    s.scaleX = 2;
    s.scaleY = 3;
    s.scaleZ = 4;
    s.offsetX = 1;
    s.offsetY = 2;
    s.offsetZ = 3;
    var translate = new SamplingTransform();
    translate.x = 2;
    translate.y = -1;
    translate.z = 3;
    var scale = new SamplingTransform();
    scale.kind = "SCALE";
    scale.x = 2;
    scale.y = 3;
    scale.z = 4;
    scale.pivotX = 1;
    scale.pivotY = 2;
    scale.pivotZ = 3;
    var rotate = new SamplingTransform();
    rotate.kind = "ROTATE";
    rotate.z = 90;
    rotate.pivotX = 1;
    rotate.pivotY = 2;
    rotate.pivotZ = 3;
    s.samplingTransforms.addAll(List.of(translate, scale, rotate));
    try (var engine = new NoiseGeneratorEngine();
        var world = engine.openWorld(new CompiledProject(f.project), options(4))) {
      var step = world.trace(new BlockPos(2, 3, 4)).steps().getFirst();
      var c = step.details().sampleCoordinates();
      assertEquals(-14, c.x(), 1e-12);
      assertEquals(-10, c.y(), 1e-12);
      assertEquals(63, c.z(), 1e-12);
      assertEquals(6186, step.field(), 1e-10);
    }
    Collections.swap(s.samplingTransforms, 0, 1);
    try (var engine = new NoiseGeneratorEngine();
        var world = engine.openWorld(new CompiledProject(f.project), options(4))) {
      assertNotEquals(6186, world.trace(new BlockPos(2, 3, 4)).steps().getFirst().field());
    }
  }

  @Test
  void rotationOrderAndDisabledTransformsAreExplicit() {
    var f = fixture(3, "x+10*y+100*z");
    var t = new SamplingTransform();
    t.kind = "ROTATE";
    t.x = t.y = t.z = 90;
    f.layer.defaults.samplingTransforms.add(t);
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      assertEquals(-77, w.trace(new BlockPos(1, 2, 3)).steps().getFirst().field(), 1e-10);
    }
    t.rotationOrder = "ZYX";
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      assertEquals(83, w.trace(new BlockPos(1, 2, 3)).steps().getFirst().field(), 1e-10);
    }
    t.enabled = false;
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      assertEquals(321, w.trace(new BlockPos(1, 2, 3)).steps().getFirst().field());
    }
  }

  @Test
  void yDependentTwoDimensionalPlacementsNeverReuseAColumn() {
    for (boolean rotate : new boolean[] {false, true}) {
      var f = fixture(2, rotate ? "z" : "x");
      f.operation.interpretation = "SELECTION";
      if (rotate) {
        var t = new SamplingTransform();
        t.kind = "ROTATE";
        t.x = 90;
        f.layer.defaults.samplingTransforms.add(t);
      } else f.layer.defaults.axisX = "Y";
      var compiled = new CompiledProject(f.project);
      for (int size : new int[] {2, 4, 8})
        try (var e = new NoiseGeneratorEngine(2, 16, 64L << 20);
            var w = e.openWorld(compiled, options(size))) {
          for (int y = -7; y <= 7; y++) {
            var p = new BlockPos(0, y, 0);
            int expected = y >= 0 ? 1 : 0;
            assertEquals(expected, w.getVoxelTypeId(p), "root=" + size + " y=" + y);
            assertEquals(y, w.trace(p).steps().getFirst().field(), 1e-12);
          }
        }
    }
  }

  @Test
  void projectionDiscardsUnusedYWhileReferencedThreeDimensionalFieldsRequireIt() {
    var f = fixture(2, "x");
    f.layer.defaults.scaleY = Double.MAX_VALUE;
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      assertEquals(1, w.getVoxelTypeId(new BlockPos(1, 2, 0)));
      assertEquals(
          0, w.trace(new BlockPos(1, 2, 0)).steps().getFirst().details().sampleCoordinates().y());
    }
    var driver = new Noise();
    driver.uuid = id(10);
    driver.id = "three-dimensional";
    driver.dimension = 3;
    driver.algorithm.key = "CONSTANT";
    f.project.noise3D.add(driver);
    f.layer.defaults.strengthDriver.enabled = true;
    f.layer.defaults.strengthDriver.noiseGeneratorUuid = driver.uuid;
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      assertThrows(EvaluationFailure.class, () -> w.trace(new BlockPos(1, 2, 0)));
    }
  }

  @Test
  void driversMultiplyConstantsAndRespectReferencedFieldDimension() {
    var f = fixture(2, "0.25");
    var s = f.layer.defaults;
    s.probability = .5;
    s.strength = .8;
    s.probabilityDriver.enabled = true;
    var n = new Noise();
    n.uuid = id(10);
    n.id = "y-driver";
    n.dimension = 3;
    n.algorithm.key = "CONSTANT";
    var item = ProcessingRegistry.create("EXPRESSION", "driver");
    item.uuid = id(11);
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("y/10"));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    f.project.noise3D.add(n);
    s.strengthDriver.enabled = true;
    s.strengthDriver.noiseGeneratorUuid = n.uuid;
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      var d = w.trace(new BlockPos(-1, 5, 3)).steps().getFirst().details();
      assertEquals(.05, d.probability(), 1e-15);
      assertEquals(.6844348840668086, d.random());
      assertEquals(2, d.drivers().size());
      assertEquals(.5, d.drivers().getLast().sample());
      assertFalse(d.selected());
    }
    s.probabilityDriver.outputMin = s.probabilityDriver.outputMax = 1;
    s.strengthDriver.outputMin = s.strengthDriver.outputMax = 1;
    s.probability = s.strength = 1;
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      assertEquals(1, w.getVoxelTypeId(new BlockPos(-1, 5, 3)));
    }
    s.strengthDriver.outputMin = s.strengthDriver.outputMax = 0;
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      assertEquals(0, w.getVoxelTypeId(new BlockPos(-1, 5, 3)));
    }
  }

  @Test
  void probabilityGatesHaveIndependentFixedVectorsAndExactEdges() {
    var f = fixture(2, "0");
    var g = f.layer.defaults.fieldGates;
    g.kind = "PROBABILITY";
    g.probability = .5;
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      var d = w.trace(new BlockPos(0, 0, 0)).steps().getFirst().details();
      assertEquals(.3770430744532288, d.random());
      assertEquals(.3253917293423707, d.gates().getFirst().random());
      assertTrue(d.selected());
      assertEquals(
          .04984097978231161,
          w.trace(new BlockPos(-33, 0, 32))
              .steps()
              .getFirst()
              .details()
              .gates()
              .getFirst()
              .random());
    }
    g.probability = 0;
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      assertEquals(0, w.getVoxelTypeId(new BlockPos(0, 0, 0)));
    }
    g.probability = 1;
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      assertEquals(1, w.getVoxelTypeId(new BlockPos(0, 0, 0)));
    }
    assertEquals(
        0xb730ab588a0e60e8L,
        CoordinateHash.hash(0xe0c3d7fd7038d39cL, Integer.MAX_VALUE, Integer.MIN_VALUE, 19));
  }

  @Test
  void nestedGatesShortCircuitUnexecutedInvalidSamples() {
    var f = fixture(2, "x");
    var bad = new Noise();
    bad.uuid = id(10);
    bad.id = "sqrt";
    bad.algorithm.key = "CONSTANT";
    var item = ProcessingRegistry.create("EXPRESSION", "sqrt");
    item.uuid = id(11);
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("sqrt(x)"));
    bad.processing.add(item);
    bad.outputBinding = Binding.earlier(item.uuid);
    f.project.noise2D.add(bad);
    var g = f.layer.defaults.fieldGates;
    g.kind = "AND";
    var reject = new Gate();
    reject.kind = "COMPARE";
    reject.threshold = 100;
    var skipped = new Gate();
    skipped.kind = "COMPARE";
    skipped.noiseGeneratorUuid = bad.uuid;
    g.children.addAll(List.of(reject, skipped));
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      var d = w.trace(new BlockPos(-1, 0, 0)).steps().getFirst().details();
      assertFalse(d.gatePassed());
      assertEquals(2, d.gates().size());
      assertNull(d.predicatePassed());
    }
    g.kind = "NOT";
    g.children.removeLast();
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      assertEquals(1, w.getVoxelTypeId(new BlockPos(-1, 0, 0)));
    }
  }

  @Test
  void chunkOrderRootsAsyncContextsAndStagesShareTheSameDecisions() throws Exception {
    var f = fixture(2, "x/8");
    var s = f.layer.defaults;
    s.probability = .73;
    s.strengthDriver.enabled = true;
    s.strengthDriver.inputMin = -1;
    s.strengthDriver.inputMax = 1;
    s.typeSource = "INTERVALS";
    s.typeIntervals.addAll(List.of(range(-100, 0, true, false, 5), range(0, 100, true, true, 8)));
    var compiled = new CompiledProject(f.project);
    int[] expected = new int[16 * 16 * 4];
    try (var e = new NoiseGeneratorEngine(3, 32, 128L << 20);
        var oracle = e.openWorld(compiled, options(2))) {
      int at = 0;
      for (int z = -8; z < 8; z++)
        for (int y = -2; y < 2; y++)
          for (int x = -8; x < 8; x++)
            expected[at++] = oracle.trace(new BlockPos(x, y, z)).finalType();
      for (int size : new int[] {2, 4, 8})
        try (var w = e.openWorld(compiled, options(size))) {
          var jobs = new ArrayList<CompletableFuture<VoxelChunk>>();
          for (int z = -1; z <= 0; z++)
            for (int x = -1; x <= 0; x++) jobs.add(w.generateChunkAsync(new ChunkPos(x, 0, z)));
          Collections.reverse(jobs);
          for (var job : jobs) job.get(5, TimeUnit.SECONDS);
          var chunks = new HashMap<ChunkPos, VoxelChunk>();
          at = 0;
          for (int z = -8; z < 8; z++)
            for (int y = -2; y < 2; y++)
              for (int x = -8; x < 8; x++) {
                var p = new BlockPos(x, y, z);
                var chunk = chunks.computeIfAbsent(ChunkPos.fromBlock(p, size), w::generateChunk);
                assertEquals(
                    expected[at++],
                    chunk.getVoxelTypeId(
                        Math.floorMod(x, size), Math.floorMod(y, size), Math.floorMod(z, size)));
              }
          var pair = w.generateStageChunk(new ChunkPos(-1, 0, 0), f.operation.uuid);
          assertTrue(pair.before().isEmpty());
          sameChunk(w.generateChunk(new ChunkPos(-1, 0, 0)), pair.after());
        }
    }
  }

  @Test
  void failedTransformsPublishNoChunkAndOldFormatTwoSettingsStillLoad(@TempDir Path directory)
      throws Exception {
    var f = fixture(2, "x");
    f.layer.defaults.scaleX = Double.MAX_VALUE;
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(f.project), options(2))) {
      var failure =
          assertThrows(EvaluationFailure.class, () -> w.generateChunk(new ChunkPos(1, 0, 0)));
      assertEquals(f.operation.uuid, failure.itemUuid);
      assertEquals(0, w.cacheStats().chunks());
      assertEquals(0, w.inFlightChunks());
    }
    f.layer.defaults.scaleX = 1;
    var json = ProjectIO.JSON.valueToTree(f.project);
    var defaults =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.path("worldGenerator").path("layers").get(0).path("defaults");
    for (String key :
        List.of(
            "axisX",
            "axisY",
            "axisZ",
            "samplingTransforms",
            "typeSource",
            "typeIntervals",
            "probabilityDriver",
            "strengthDriver")) defaults.remove(key);
    var gate = (com.fasterxml.jackson.databind.node.ObjectNode) defaults.path("fieldGates");
    gate.remove(List.of("includeMin", "includeMax", "probability", "seedOffset"));
    var file = directory.resolve("historical.json");
    Files.writeString(file, json.toString());
    var loaded = new ProjectLoader().load(file);
    assertTrue(loaded.success(), loaded.diagnostics().toString());
    assertEquals("X", loaded.project().worldGenerator.layers.getFirst().defaults.axisX);
    try (var e = new NoiseGeneratorEngine();
        var a = e.openWorld(new CompiledProject(f.project), options(4));
        var b = e.openWorld(new CompiledProject(loaded.project()), options(4))) {
      sameChunk(a.generateChunk(new ChunkPos(-1, 0, 0)), b.generateChunk(new ChunkPos(-1, 0, 0)));
    }
  }
}
