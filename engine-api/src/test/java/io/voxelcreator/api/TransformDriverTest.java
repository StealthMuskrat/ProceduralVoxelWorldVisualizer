package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Deferred development contracts; analytical expectations do not call the sampling program. */
class TransformDriverTest {
  private final PlacementDriverPhaseTest f = new PlacementDriverPhaseTest();

  private SamplingTransform transform(String kind) {
    var t = new SamplingTransform();
    t.kind = kind;
    if (kind.equals("SCALE")) t.x = t.y = t.z = 1;
    f.layer.defaults.samplingTransforms.add(t);
    return t;
  }

  private NumericDriver row(String component, int index, String combination, double value) {
    var d = f.row(component, combination, value, value, f.p.noise2D.getFirst());
    d.transformIndex = index;
    return d;
  }

  private GenerationTrace.Step step(int x, int y, int z) {
    return f.trace(x, y, z).steps().getFirst();
  }

  @Test
  void repeatedTargetsComposeWhileDistinctRowsKeepIndependentConstants() {
    transform("TRANSLATE").x = 1;
    transform("TRANSLATE").x = 10;
    row("transformX", 0, "ADD", 2);
    row("transformX", 1, "MULTIPLY", 3);
    row("transformX", 0, "MULTIPLY", 3);
    var s = step(2, 0, 0);
    assertEquals(41, s.field()); // 2 + ((1+2)*3) + (10*3)
    var rows = s.details().parameters();
    assertEquals(
        List.of(0, 1, 0),
        rows.stream().map(GenerationTrace.ParameterEvaluation::transformIndex).toList());
    assertEquals(3, rows.get(2).previous());
    assertEquals(9, rows.get(2).effective());
  }

  @Test
  void scalingAndPivotDriversUseTheDrivenPivotInAuthoredTransformOrder() {
    transform("TRANSLATE");
    transform("SCALE");
    row("transformX", 0, "REPLACE", 3);
    row("transformX", 1, "REPLACE", 2);
    row("transformPivotX", 1, "REPLACE", 1);
    assertEquals(9, step(2, 0, 0).field()); // 1 + (2+3-1)*2
  }

  @Test
  void rotationComponentsUseDegreesAndTheDeclaredAxisOrder() {
    var t = transform("ROTATE");
    t.rotationOrder = "XYZ";
    row("transformX", 0, "REPLACE", 90);
    row("transformY", 0, "REPLACE", 90);
    row("transformZ", 0, "REPLACE", 90);
    var s = step(2, 3, 4);
    assertEquals(4, s.field(), 1e-12); // (2,3,4) -> (2,-4,3) -> (3,-4,-2) -> (4,3,-2)
    assertEquals(-2, s.details().sampleCoordinates().z(), 1e-12);
    t.rotationOrder = "ZYX";
    assertEquals(4, step(2, 3, 4).field(), 1e-12);
    assertEquals(2, step(2, 3, 4).details().sampleCoordinates().z(), 1e-12);
    assertEquals(0, s.details().sampleCoordinates().y()); // primary remains a 2D field
  }

  @Test
  void drivenRotationPivotsAndNegativeAnglesRetainRightHandedSemantics() {
    transform("ROTATE");
    row("transformPivotX", 0, "REPLACE", 1);
    row("transformPivotY", 0, "REPLACE", 2);
    row("transformZ", 0, "REPLACE", -90);
    assertEquals(3, step(3, 4, 0).field(), 1e-12); // pivot + Rz(-90)*(2,2,0)
  }

  @Test
  void everyTransformDriverReadsTheSameOriginalTransformedBaseline() {
    transform("TRANSLATE").x = 1;
    for (int i = 0; i < 2; i++) {
      var d = f.row("transformX", "ADD", 0, 16, f.p.noise2D.getFirst());
      d.transformIndex = 0;
      d.inputMax = 16;
    }
    var s = step(2, 0, 0);
    assertEquals(9, s.field()); // baseline x=3, final x=2+(1+3+3)
    for (var r : s.details().parameters()) {
      assertEquals(3, r.sample());
      assertEquals(3, r.sampleCoordinates().x());
      assertEquals("SAMPLING", r.phase());
    }
  }

  @Test
  void disabledTransformRowsSkipTheirDriverFieldsAndDoNotRenumberLaterRows() {
    transform("TRANSLATE").enabled = false;
    transform("TRANSLATE");
    var bad = f.field("unreached", 3, "1/0");
    var d = f.row("transformX", "REPLACE", 0, 1, bad);
    d.transformIndex = 0;
    row("transformX", 1, "REPLACE", 4);
    var s = step(2, 0, 0);
    assertEquals(6, s.field());
    assertEquals(1, s.details().parameters().size());
    assertEquals(1, s.details().parameters().getFirst().row());
    assertEquals(1, s.details().parameters().getFirst().transformIndex());
  }

  @Test
  void finalCoordinatesReachInterpretationAndGateFields() {
    transform("TRANSLATE");
    row("transformX", 0, "REPLACE", 3);
    var post = f.field("post", 3, "x");
    var d = f.row("threshold", "REPLACE", 0, 8, post);
    d.inputMax = 8;
    f.layer.defaults.fieldGates.kind = "COMPARE";
    f.layer.defaults.fieldGates.noiseGeneratorUuid = post.uuid;
    f.layer.defaults.fieldGates.threshold = 3;
    var s = step(0, 0, 0);
    assertEquals(3, s.details().parameters().get(1).sample());
    assertNull(s.details().parameters().get(1).transformIndex());
    assertEquals(3, s.details().gates().getFirst().value());
    assertEquals(1, f.trace(0, 0, 0).finalType());
  }

  @Test
  void threeDimensionalDriversPreserveColumnRootWorkerAndTraceAgreement() throws Exception {
    transform("TRANSLATE");
    var d = f.row("transformX", "REPLACE", -8, 8, f.field("y", 3, "y"));
    d.transformIndex = 0;
    d.inputMin = -8;
    d.inputMax = 8;
    f.layer.defaults.threshold = 1;
    try (var engine = new NoiseGeneratorEngine(3, 32, 512L << 20)) {
      for (int root : new int[] {2, 4, 8})
        try (var world = f.world(engine, root)) {
          var jobs = new ArrayList<CompletableFuture<VoxelChunk>>();
          for (int x = -1; x <= 1; x++) jobs.add(world.generateChunkAsync(new ChunkPos(x, 0, 0)));
          for (var job : jobs) job.get(10, TimeUnit.SECONDS);
          for (int x = -2; x <= 2; x++)
            for (int y = -2; y <= 2; y++) {
              var p = new BlockPos(x, y, 0);
              int expected = x + y >= 1 ? 1 : 0;
              assertEquals(expected, world.getVoxelTypeId(p));
              assertEquals(expected, world.trace(p).finalType());
            }
        }
    }
  }

  @Test
  void overflowingIntermediateFailsBeforeAnyLaterReplacementCanHideIt() {
    transform("TRANSLATE").x = Double.MAX_VALUE;
    row("transformX", 0, "ADD", Double.MAX_VALUE);
    row("transformX", 0, "REPLACE", 0);
    var failure = assertThrows(EvaluationFailure.class, () -> step(0, 0, 0));
    assertTrue(failure.getMessage().contains("parameterDrivers[0]"));
    try (var engine = new NoiseGeneratorEngine();
        var world = f.world(engine, 4)) {
      assertThrows(EvaluationFailure.class, () -> world.generateChunk(new ChunkPos(0, 0, 0)));
      assertEquals(0, world.cacheStats().chunks());
    }
  }

  @Test
  void lastAuthoredTransformUsesAnOwnedBoundedSlot() {
    for (int i = 0; i < 64; i++) transform("TRANSLATE").enabled = i == 63;
    row("transformX", 63, "REPLACE", 2);
    var s = step(3, 0, 0);
    assertEquals(5, s.field());
    assertEquals(63, s.details().parameters().getFirst().transformIndex());
  }

  @Test
  void inheritanceResetAndCompiledOwnershipRemainIndependent() {
    transform("TRANSLATE");
    row("transformX", 0, "REPLACE", 3);
    var compiled = new CompiledProject(f.p);
    f.layer.defaults.parameterDrivers.getFirst().outputMin =
        f.layer.defaults.parameterDrivers.getFirst().outputMax = -3;
    try (var engine = new NoiseGeneratorEngine();
        var world =
            engine.openWorld(
                compiled, new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      assertEquals(3, world.trace(new BlockPos(0, 0, 0)).steps().getFirst().field());
    }
    f.op.overrides.put("parameterDrivers", ProjectIO.JSON.createArrayNode());
    assertEquals(0, step(0, 0, 0).field());
    f.op.overrides.remove("parameterDrivers");
    assertEquals(-3, step(0, 0, 0).field());
  }

  @Test
  void tenArgumentTraceConstructorRetainsItsSourceContract() {
    var coordinates = new GenerationTrace.SampleCoordinates(1, 0, 2);
    var r =
        new GenerationTrace.ParameterEvaluation(
            0, "offsetX", "ADD", null, 1, 1, 0, 1, "SAMPLING", coordinates);
    assertEquals(coordinates, r.sampleCoordinates());
    assertNull(r.transformIndex());
  }
}
