package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PlacementDriverPhaseTest {
  final ProjectData p = new ProjectData();
  final Layer layer = new Layer();
  final Operation op = new Operation();

  PlacementDriverPhaseTest() {
    p.project.minY = -8;
    p.project.maxY = 8;
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var n = field("primary", 2, "x");
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    p.worldGenerator.layers.add(layer);
    op.id = "placement";
    op.noiseGeneratorUuid = n.uuid;
    op.interpretation = "DENSITY";
    layer.operations.add(op);
  }

  Noise field(String id, int d, String text) {
    var n = new Noise();
    n.id = id;
    n.dimension = d;
    n.algorithm.key = "CONSTANT";
    var i = ProcessingRegistry.create("EXPRESSION", id + "-expression", d);
    i.parameters.put("expression", ProjectIO.JSON.valueToTree(text));
    n.processing.add(i);
    n.outputBinding = Binding.earlier(i.uuid);
    (d == 2 ? p.noise2D : p.noise3D).add(n);
    return n;
  }

  NumericDriver row(String target, String combination, double lo, double hi, Noise field) {
    var d = new NumericDriver();
    d.enabled = true;
    d.destination = target;
    d.combination = combination;
    d.outputMin = lo;
    d.outputMax = hi;
    d.noiseGeneratorUuid = field == null ? null : field.uuid;
    layer.defaults.parameterDrivers.add(d);
    return d;
  }

  WorldContext world(NoiseGeneratorEngine e, int size) {
    return e.openWorld(
        new CompiledProject(p), new WorldOptions(null, size, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  GenerationTrace trace(int x, int y, int z) {
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, 4)) {
      return w.trace(new BlockPos(x, y, z));
    }
  }

  @Test
  void samplingRowsUseOneFixedBaselineInsteadOfFeedingBack() {
    var x = field("driver", 3, "x");
    var a = row("offsetX", "ADD", 0, 10, x);
    a.inputMin = 0;
    a.inputMax = 10;
    var b = row("offsetX", "ADD", 0, 10, x);
    b.inputMin = 0;
    b.inputMax = 10;
    var t = trace(2, 0, 0);
    assertEquals(6, t.steps().getFirst().field());
    var rows = t.steps().getFirst().details().parameters();
    assertEquals(2, rows.get(0).sample());
    assertEquals(2, rows.get(1).sample());
    assertEquals(2, rows.get(1).sampleCoordinates().x());
    assertEquals(6, t.steps().getFirst().details().sampleCoordinates().x());
  }

  @Test
  void phasesPartitionInterleavedRowsButKeepAuthoredIndices() {
    row("strength", "REPLACE", 1, 1, null);
    row("threshold", "REPLACE", 2, 2, null);
    var n = field("offset", 2, "0");
    row("offsetX", "REPLACE", 3, 3, n);
    var t = trace(0, 0, 0);
    var rows = t.steps().getFirst().details().parameters();
    assertEquals(
        List.of(2, 1, 0), rows.stream().map(GenerationTrace.ParameterEvaluation::row).toList());
    assertEquals(
        List.of("SAMPLING", "INTERPRETATION", "ACCEPTANCE"),
        rows.stream().map(GenerationTrace.ParameterEvaluation::phase).toList());
    assertEquals(1, t.finalType());
  }

  @Test
  void samplingScaleAndOffsetPrecedeOrderedTransforms() {
    var n = field("driver", 2, "0");
    row("scaleX", "REPLACE", 2, 2, n);
    row("offsetX", "REPLACE", 3, 3, n);
    var t = new SamplingTransform();
    t.x = 4;
    layer.defaults.samplingTransforms.add(t);
    assertEquals(11, trace(2, 0, 0).steps().getFirst().field());
  }

  @Test
  void threeDimensionalDriverPreventsIncorrectTwoDimensionalColumnReuse() {
    var n = field("y-driver", 3, "y");
    var d = row("offsetX", "REPLACE", -8, 8, n);
    d.inputMin = -8;
    d.inputMax = 8;
    layer.defaults.threshold = .5;
    try (var e = new NoiseGeneratorEngine()) {
      for (int size : new int[] {2, 4, 8})
        try (var w = world(e, size)) {
          var chunk = w.generateChunk(new ChunkPos(0, 0, 0));
          for (int y = 0; y < size; y++)
            assertEquals(y >= 1 ? 1 : 0, chunk.getVoxelTypeId(0, y, 0));
        }
    }
  }

  @Test
  void postDriversAndGatesSampleFinalDrivenCoordinates() {
    var n = field("offset", 2, "0");
    row("offsetX", "REPLACE", 3, 3, n);
    var post = field("post", 3, "x");
    var d = row("threshold", "REPLACE", 0, 8, post);
    d.inputMax = 8;
    layer.defaults.fieldGates.kind = "COMPARE";
    layer.defaults.fieldGates.noiseGeneratorUuid = post.uuid;
    layer.defaults.fieldGates.threshold = 3;
    var t = trace(0, 0, 0);
    assertEquals(1, t.finalType());
    assertEquals(3, t.steps().getFirst().details().parameters().get(1).sample());
    assertEquals(3, t.steps().getFirst().details().gates().getFirst().value());
  }

  @Test
  void signedAxesAndDimensionalProjectionRemainCanonical() {
    var n = field("offset", 2, "0");
    row("offsetX", "REPLACE", 2, 2, n);
    layer.defaults.axisX = "-Y";
    assertEquals(-1, trace(9, 3, 0).steps().getFirst().field());
  }

  @Test
  void drivenYCanReachActiveAxesThroughSamplingRotation() {
    var n = field("offset", 3, "0");
    row("offsetY", "REPLACE", 4, 4, n);
    var rotation = new SamplingTransform();
    rotation.kind = "ROTATE";
    rotation.z = 90;
    layer.defaults.samplingTransforms.add(rotation);
    assertEquals(-4, trace(0, 0, 0).steps().getFirst().field(), 1e-12);
  }

  @Test
  void probabilityAndStrengthComposeWithExistingMappedFactors() {
    op.interpretation = "ALL";
    row("strength", "REPLACE", .5, .5, null);
    row("probability", "REPLACE", .5, .5, null);
    var s = layer.defaults;
    for (var d : List.of(s.strengthDriver, s.probabilityDriver)) {
      d.enabled = true;
      d.outputMin = d.outputMax = .5;
    }
    var t = trace(0, 0, 0).steps().getFirst().details();
    assertEquals(.0625, t.probability());
    assertEquals(.5, t.drivers().get(0).constant());
    assertEquals(.25, t.drivers().get(0).effective());
  }

  @Test
  void everyUnitIntermediateRejectsBeforeLaterReplacement() {
    op.interpretation = "ALL";
    row("strength", "ADD", 1, 1, null);
    row("strength", "REPLACE", .5, .5, null);
    var failure = assertThrows(EvaluationFailure.class, () -> trace(0, 0, 0));
    assertTrue(failure.getMessage().contains("parameterDrivers[0]"));
    assertTrue(failure.getMessage().contains("[0,1]"));
  }

  @Test
  void signedAcceptanceMappingsAreAllowedWhenEffectiveValueIsValid() {
    op.interpretation = "ALL";
    row("strength", "ADD", -.5, -.5, null);
    assertEquals(.5, trace(0, 0, 0).steps().getFirst().details().probability());
  }

  @Test
  void acceptanceRowsDoNotExecuteAfterInterpretationRejection() {
    var invalid = field("bad", 3, "1/0");
    row("probability", "REPLACE", 0, 1, invalid);
    layer.defaults.threshold = 1;
    var t = trace(0, 0, 0);
    assertEquals(0, t.finalType());
    assertTrue(t.steps().getFirst().details().parameters().isEmpty());
  }

  @Test
  void acceptanceRowsDoNotExecuteAfterMissingTypeInterval() {
    op.interpretation = "ALL";
    layer.defaults.typeSource = "INTERVALS";
    var invalid = field("bad", 3, "1/0");
    row("strength", "REPLACE", 0, 1, invalid);
    assertEquals(0, trace(0, 0, 0).finalType());
  }

  @Test
  void outOfYAndDisabledRowsDoNotSampleFailingFields() {
    var invalid = field("bad", 3, "1/0");
    var d = row("offsetX", "REPLACE", 0, 1, invalid);
    layer.defaults.minY = 2;
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, 2)) {
      assertTrue(w.generateChunk(new ChunkPos(0, 0, 0)).isEmpty());
    }
    d.enabled = false;
    layer.defaults.minY = -8;
    assertDoesNotThrow(() -> trace(0, 0, 0));
  }

  @Test
  void overflowFailsWithoutPublishingPartialChunk() {
    var n = field("large", 2, "0");
    row("scaleX", "REPLACE", Double.MAX_VALUE, Double.MAX_VALUE, n);
    try (var e = new NoiseGeneratorEngine();
        var w = world(e, 4)) {
      assertThrows(EvaluationFailure.class, () -> w.generateChunk(new ChunkPos(0, 0, 0)));
      assertEquals(0, w.cacheStats().chunks());
    }
  }

  @Test
  void rootWorkerStageAndTraceAgreeWithIndependentSelection() throws Exception {
    var n = field("y", 3, "y");
    var d = row("offsetX", "REPLACE", -8, 8, n);
    d.inputMin = -8;
    d.inputMax = 8;
    layer.defaults.threshold = 1;
    try (var e = new NoiseGeneratorEngine(3, 32, 512L << 20);
        var a = world(e, 4);
        var b = world(e, 8)) {
      a.generateChunkAsync(new ChunkPos(0, 0, 0)).get(10, TimeUnit.SECONDS);
      for (int x = -2; x <= 2; x++)
        for (int y = -2; y <= 2; y++) {
          var pos = new BlockPos(x, y, 0);
          int expected = x + y >= 1 ? 1 : 0;
          assertEquals(expected, a.getVoxelTypeId(pos));
          assertEquals(expected, b.getVoxelTypeId(pos));
          assertEquals(expected, a.trace(pos).finalType());
        }
      var stage = a.generateStageChunk(new ChunkPos(0, 0, 0), op.uuid);
      assertTrue(stage.before().isEmpty());
      assertEquals(1, stage.after().getVoxelTypeId(0, 1, 0));
    }
  }

  @Test
  void compiledDriversAreOwnedAndResetToLayerIsLive() {
    var n = field("offset", 2, "0");
    row("offsetX", "REPLACE", 3, 3, n);
    var compiled = new CompiledProject(p);
    layer.defaults.parameterDrivers.getFirst().outputMin =
        layer.defaults.parameterDrivers.getFirst().outputMax = -3;
    try (var e = new NoiseGeneratorEngine();
        var w =
            e.openWorld(
                compiled, new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      assertEquals(3, w.trace(new BlockPos(0, 0, 0)).steps().getFirst().field());
    }
    op.overrides.put("parameterDrivers", ProjectIO.JSON.createArrayNode());
    assertEquals(0, trace(0, 0, 0).steps().getFirst().field());
    op.overrides.remove("parameterDrivers");
    assertEquals(-3, trace(0, 0, 0).steps().getFirst().field());
  }

  @Test
  void legacyParameterConstructorRemainsAvailable() {
    var row = new GenerationTrace.ParameterEvaluation(0, "threshold", "REPLACE", null, 1, 1, 0, 1);
    assertEquals("INTERPRETATION", row.phase());
    assertNull(row.sampleCoordinates());
  }
}
