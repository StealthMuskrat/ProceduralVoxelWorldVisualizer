package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class WarpPlanTest {
  final ProjectData project = new ProjectData();

  Noise noise(String id, int d, String expression) {
    var n = new Noise();
    n.id = id;
    n.dimension = d;
    n.algorithm.key = "CONSTANT";
    (d == 2 ? project.noise2D : project.noise3D).add(n);
    var i = ProcessingRegistry.create("EXPRESSION", id + "-expression", d);
    set(i, "expression", expression);
    n.processing.add(i);
    n.outputBinding = Binding.earlier(i.uuid);
    return n;
  }

  Item warp(Noise n, boolean pre) {
    var i = ProcessingRegistry.create("WARP_SAMPLE", "warp", n.dimension);
    if (pre) {
      i.kind = "DOMAIN_WARP";
      i.resultType = "Coordinates" + n.dimension + "D";
      i.inputs.clear();
      n.coordinateTransforms.add(i);
    } else {
      i.inputs.put("field", Binding.earlierField(n.processing.getFirst().uuid));
      n.processing.add(i);
      n.outputBinding = Binding.earlier(i.uuid);
    }
    return i;
  }

  static void set(Item i, String key, Object value) {
    i.parameters.put(key, ProjectIO.JSON.valueToTree(value));
  }

  NoiseAlgorithms.Field field(Noise n) {
    return new NoiseCompiler(project, 17).compile(n.uuid);
  }

  void components(Item i, int d) {
    var x = noise("dx" + project.noise2D.size() + project.noise3D.size(), d, "z+.5*y");
    var y = noise("dy" + project.noise2D.size() + project.noise3D.size(), d, "x-z");
    var z = noise("dz" + project.noise2D.size() + project.noise3D.size(), d, "x-.25*y");
    i.inputs.put("displacementX", Binding.noiseField(x.uuid));
    if (d == 3) i.inputs.put("displacementY", Binding.noiseField(y.uuid));
    i.inputs.put("displacementZ", Binding.noiseField(z.uuid));
  }

  @Test
  void independentPythonVectorsExerciseBothActualCompilerStages() throws Exception {
    var vectors =
        ProjectIO.JSON.readTree(Path.of("../fixtures/warp-vectors.json").toFile()).path("vectors");
    assertEquals(432, vectors.size());
    for (var vector : vectors)
      for (boolean pre : new boolean[] {false, true}) {
        var fixture = new WarpPlanTest();
        int d = vector.path("dimension").intValue();
        var n = fixture.noise("oracle", d, d == 2 ? "x+100*z" : "x+10*y+100*z");
        var i = fixture.warp(n, pre);
        fixture.components(i, d);
        for (var entry :
            Map.of(
                    "warpMode",
                    "mode",
                    "warpOrder",
                    "order",
                    "warpOctaves",
                    "octaves",
                    "frequency",
                    "frequency",
                    "amplitude",
                    "amplitude")
                .entrySet()) i.parameters.put(entry.getKey(), vector.path(entry.getValue()));
        var input = vector.path("input");
        assertEquals(
            vector.path("result").doubleValue(),
            fixture
                .field(n)
                .sample(
                    input.get(0).doubleValue(),
                    input.get(1).doubleValue(),
                    input.get(2).doubleValue()),
            1e-9,
            vector.toString() + " pre=" + pre);
      }
  }

  @Test
  void sourceAlgorithmsUseTheCanonicalImplementationAndLegacyOffsets() {
    for (int d : new int[] {2, 3})
      for (var key : NoiseAlgorithms.KEYS) {
        var i = new Item();
        i.kind = "WARP_SAMPLE";
        set(i, "warpAlgorithm", key);
        set(i, "frequency", .125);
        var plan = WarpPlan.compile(i, d, 91);
        var a = WarpRegistry.algorithm(i, d);
        var source =
            NoiseAlgorithms.compile(a, d, CoordinateHash.mix(91 + Double.doubleToLongBits(0)));
        var out = new double[3];
        plan.apply(2, 3, 4, 8, null, null, out);
        assertEquals(2 + 8 * source.sample(2, d == 2 ? 0 : 3, 4), out[0]);
        assertEquals(4 + 8 * source.sample(2 - 11.2, d == 2 ? 0 : 3 + 43.8, 4 - 19.4), out[2]);
        if (d == 3) assertEquals(3 + 8 * source.sample(2 + 31.1, 3 - 17.3, 4 + 7.7), out[1]);
      }
  }

  @Test
  void legacyCoordinateNumericDisplacementsRetainStageValueSemantics() {
    var n = noise("legacy", 2, "x+100*z");
    var i = warp(n, true);
    i.inputs.put("warpX", Binding.constant(1));
    i.inputs.put("warpZ", Binding.constant(2));
    set(i, "amplitude", 3);
    assertEquals(905, field(n).sample(2, 0, 3));
  }

  @Test
  void postSourceNumericDisplacementsAreExplicitStageValues() {
    var n = noise("values", 2, "x+100*z");
    var i = warp(n, false);
    i.inputs.put("warpX", Binding.constant(1));
    i.inputs.put("warpZ", Binding.constant(2));
    set(i, "amplitude", 3);
    assertEquals(905, field(n).sample(2, 0, 3));
  }

  @Test
  void aSelectedEarlierPrefixIsResampledAtActualDisplacedLocations() {
    var n = noise("prefix", 2, "x*x+10*z");
    var i = warp(n, false);
    i.inputs.put("displacementX", Binding.earlierField(n.processing.getFirst().uuid));
    i.inputs.put("warpZ", Binding.constant(0));
    set(i, "amplitude", 1);
    set(i, "frequency", 1);
    assertEquals(1326, field(n).sample(2, 0, 3));
  }

  @Test
  void referencedDisplacementProjectsAndSlicesAcrossDimensions() {
    var n = noise("projected", 2, "x+100*z");
    var other = noise("three", 3, "x+10*y+100*z");
    var i = warp(n, false);
    var b = Binding.noiseField(other.uuid);
    b.slice = 7;
    i.inputs.put("displacementX", b);
    i.inputs.put("warpZ", Binding.constant(0));
    set(i, "amplitude", 1);
    set(i, "frequency", 1);
    assertEquals(674, field(n).sample(2, 0, 3));
  }

  @Test
  void coordinateWarpCannotSelectItsOwnSourceOrAPrefix() {
    var n = noise("stage", 2, "x");
    var i = warp(n, true);
    i.inputs.put("displacementX", Binding.sourceField());
    var ds = NoiseCompiler.validate(project);
    assertTrue(ds.stream().anyMatch(d -> d.code().equals("INVALID_STAGE")));
    assertThrows(CompilationFailure.class, () -> field(n));
  }

  @Test
  void selfLaterAndDisabledDisplacementPrefixesReject() {
    var n = noise("order", 2, "x");
    var i = warp(n, false);
    i.inputs.put("displacementX", Binding.earlierField(i.uuid));
    assertThrows(CompilationFailure.class, () -> field(n));
    i.inputs.put("displacementX", Binding.earlierField(n.processing.getFirst().uuid));
    n.processing.getFirst().enabled = false;
    assertThrows(CompilationFailure.class, () -> field(n));
  }

  @Test
  void resourceCyclesThroughDisplacementFieldsReject() {
    var n = noise("cycle", 2, "x");
    var i = warp(n, true);
    i.inputs.put("displacementX", Binding.noiseField(n.uuid));
    assertFalse(NoiseCompiler.validate(project).isEmpty());
    assertThrows(CompilationFailure.class, () -> field(n));
  }

  @Test
  void numericAndSampledDisplacementsCannotConflict() {
    var n = noise("conflict", 2, "x");
    var i = warp(n, false);
    i.inputs.put("warpX", Binding.constant(1));
    i.inputs.put("displacementX", Binding.sourceField());
    assertEquals("inputs/displacementX", NoiseCompiler.validate(project).getFirst().parameterKey());
  }

  @Test
  void twoDimensionalSampledYIsRejectedPrecisely() {
    var n = noise("no-y", 2, "x");
    var i = warp(n, false);
    i.inputs.put("displacementY", Binding.sourceField());
    assertEquals("inputs/displacementY", NoiseCompiler.validate(project).getFirst().parameterKey());
  }

  @Test
  void invalidWarpChoicesAndRangesCarryExactKeys() {
    var n = noise("invalid", 2, "x");
    var i = warp(n, false);
    for (var key : List.of("warpMode", "warpOrder", "warpAlgorithm", "octaveSeeds")) {
      i.parameters.clear();
      set(i, key, "BAD");
      assertEquals(key, NoiseCompiler.validate(project).getFirst().parameterKey());
    }
    for (var e : Map.of("frequency", 0, "warpOctaves", 1.5, "warpLacunarity", 0).entrySet()) {
      i.parameters.clear();
      set(i, e.getKey(), e.getValue());
      assertEquals(e.getKey(), NoiseCompiler.validate(project).getFirst().parameterKey());
    }
  }

  @Test
  void sourceControlsRetainPreciseKeysAndSupportedSignatures() {
    var n = noise("source", 2, "x");
    var i = warp(n, false);
    set(i, "source.fractal", 9);
    assertEquals("source.fractal", NoiseCompiler.validate(project).getFirst().parameterKey());
    set(i, "warpAlgorithm", "WHITE_HASH");
    assertDoesNotThrow(() -> field(n));
    set(i, "source.frequency", 2);
    assertEquals("source.frequency", NoiseCompiler.validate(project).getFirst().parameterKey());
  }

  @Test
  void zeroAmplitudeSkipsInvalidExecutingDisplacementButNotValidation() {
    var n = noise("zero", 2, "x+100*z");
    var other = noise("danger", 2, "1/0");
    var i = warp(n, false);
    i.inputs.put("displacementX", Binding.noiseField(other.uuid));
    set(i, "amplitude", 0);
    assertEquals(302, field(n).sample(2, 0, 3));
    set(i, "amplitude", 1);
    assertThrows(EvaluationFailure.class, () -> field(n).sample(2, 0, 3));
  }

  @Test
  void zeroGainSkipsUnusedLaterDisplacementQueries() {
    var i = new Item();
    set(i, "warpOctaves", 3);
    set(i, "warpGain", 0);
    var plan = WarpPlan.compile(i, 2, 0);
    int[] calls = {0};
    var displacement =
        new WarpPlan.Displacement<Void>() {
          public boolean present(int axis) {
            return true;
          }

          public double sample(Void context, int axis, double x, double y, double z) {
            calls[0]++;
            return 1;
          }
        };
    var out = new double[3];
    plan.apply(2, 0, 3, 1, null, displacement, out);
    assertEquals(2, calls[0]);
    assertArrayEquals(new double[] {3, 0, 4}, out);
  }

  @Test
  void malformedOctaveScalesAndGainRejectBeforeExecution() {
    var i = new Item();
    set(i, "warpOctaves", 3);
    set(i, "warpLacunarity", Double.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> WarpPlan.compile(i, 2, 0));
    set(i, "warpLacunarity", 2);
    set(i, "warpGain", Double.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> WarpPlan.compile(i, 2, 0));
  }

  @Test
  void nonfiniteCoordinatesNeverReachTheSelectedField() {
    var n = noise("overflow", 2, "x");
    var i = warp(n, false);
    i.inputs.put("warpX", Binding.constant(1e308));
    i.inputs.put("warpZ", Binding.constant(0));
    set(i, "amplitude", 1e308);
    assertThrows(EvaluationFailure.class, () -> field(n).sample(0, 0, 0));
  }

  @Test
  void independentAndProgressiveOctavesHaveDifferentRealDomains() {
    var n = noise("modes", 2, "x+100*z");
    var i = warp(n, false);
    components(i, 2);
    set(i, "frequency", 1);
    set(i, "amplitude", 1);
    set(i, "warpOctaves", 2);
    assertEquals(708, field(n).sample(2, 0, 3));
    set(i, "warpMode", "PROGRESSIVE");
    assertEquals(1010, field(n).sample(2, 0, 3));
  }

  @Test
  void sequentialAxisOrderIsNoncommutative() {
    var n = noise("axis", 2, "x+100*z");
    var i = warp(n, false);
    components(i, 2);
    set(i, "frequency", 1);
    set(i, "amplitude", 1);
    set(i, "warpMode", "SEQUENTIAL");
    assertEquals(805, field(n).sample(2, 0, 3));
    set(i, "warpOrder", "ZX");
    assertEquals(507, field(n).sample(2, 0, 3));
  }

  @Test
  void enabledUnusedWarpStillValidatesAndDisabledWarpRetainsDraft() {
    var n = noise("disabled", 2, "x");
    var i = warp(n, false);
    set(i, "warpOctaves", 17);
    n.outputBinding = Binding.source();
    assertThrows(CompilationFailure.class, () -> field(n));
    i.enabled = false;
    assertDoesNotThrow(() -> field(n));
    assertEquals(17, i.parameters.get("warpOctaves").intValue());
  }

  @Test
  void nestedWarpWorkAdmissionRejectsExplosivePrefixes() {
    var n = noise("work", 3, "x");
    for (int k = 0; k < 5; k++) {
      var previous = n.processing.getLast();
      var i = ProcessingRegistry.create("WARP_SAMPLE", "warp" + k, 3);
      i.inputs.put("field", Binding.earlierField(previous.uuid));
      i.inputs.put("displacementX", Binding.earlierField(previous.uuid));
      i.inputs.put("displacementY", Binding.earlierField(previous.uuid));
      i.inputs.put("displacementZ", Binding.earlierField(previous.uuid));
      set(i, "warpOctaves", 16);
      n.processing.add(i);
      n.outputBinding = Binding.earlier(i.uuid);
    }
    assertTrue(
        NoiseCompiler.validate(project).stream()
            .anyMatch(d -> d.message().contains("COMPLEXITY_LIMIT")));
  }

  @Test
  void compiledWarpOwnsParametersAndSamplesConcurrently() throws Exception {
    var n = noise("owned", 2, "x+100*z");
    var i = warp(n, false);
    components(i, 2);
    set(i, "frequency", 1);
    set(i, "amplitude", 1);
    var f = field(n);
    set(i, "amplitude", 0);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Future<?>>();
      for (int w = 0; w < 4; w++)
        tasks.add(
            pool.submit(
                () -> {
                  for (int x = -32; x <= 32; x++)
                    assertEquals((x + 3) + 100 * (3 + x), f.sample(x, 0, 3));
                }));
      for (var task : tasks) task.get();
    }
  }

  @Test
  void registryAndChoiceListsAreImmutableAndInvalidDraftsRemainDrawable() {
    assertSame(WarpRegistry.controls(2), WarpRegistry.controls(2));
    assertThrows(UnsupportedOperationException.class, () -> WarpRegistry.controls(2).clear());
    var i = new Item();
    set(i, "warpAlgorithm", "INVALID");
    assertDoesNotThrow(() -> WarpRegistry.visibleControls(i, 2));
  }

  @Test
  void algorithmOctaveSeedIncrementHasAnIndependentCanonicalExpectation() {
    var i = new Item();
    set(i, "warpAlgorithm", "PERLIN");
    set(i, "frequency", .125);
    set(i, "warpOctaves", 3);
    set(i, "warpGain", .25);
    set(i, "octaveSeeds", "INCREMENT");
    set(i, "seedOffset", -0.0);
    var plan = WarpPlan.compile(i, 3, 91);
    var actual = new double[3];
    plan.apply(2, 3, 4, 1, null, null, actual);
    double[] expected = {2, 3, 4};
    long base = CoordinateHash.mix(91 + Double.doubleToLongBits(-0.0));
    double[][] offsets = {{0, 0, 0}, {31.1, -17.3, 7.7}, {-11.2, 43.8, -19.4}};
    for (int o = 0; o < 3; o++) {
      var field =
          NoiseAlgorithms.compile(
              WarpRegistry.algorithm(i, 3), 3, o == 0 ? base : CoordinateHash.mix(base + o));
      double scale = StrictMath.pow(2, o), gain = StrictMath.pow(.25, o);
      for (int axis = 0; axis < 3; axis++)
        expected[axis] +=
            gain
                * field.sample(
                    (2 + offsets[axis][0]) * scale,
                    (3 + offsets[axis][1]) * scale,
                    (4 + offsets[axis][2]) * scale);
    }
    assertArrayEquals(expected, actual);
  }

  @Test
  void numericStageValuesDoNotExecuteUnusedFrequencyScaling() {
    for (boolean pre : new boolean[] {false, true}) {
      var n = noise("unscaled" + pre, 2, "x+100*z");
      var i = warp(n, pre);
      i.inputs.put("warpX", Binding.constant(1));
      i.inputs.put("warpZ", Binding.constant(0));
      set(i, "frequency", 1e6);
      set(i, "amplitude", 1);
      assertEquals(1e308, field(n).sample(1e308, 0, 0));
    }
  }

  @Test
  void legacyPostWarpRetainsTheUnchangedAmbientYForItsSelectedTarget() {
    var n = noise("passive-y", 2, "x+10*y+100*z");
    var i = warp(n, false);
    i.inputs.put("warpX", Binding.constant(0));
    i.inputs.put("warpZ", Binding.constant(0));
    assertEquals(372, field(n).sample(2, 7, 3));
  }

  @Test
  void nestedNumericGeneratorReferencesCountEveryOctaveInvocation() {
    var base = noise("base-work", 3, "x");
    var filter = ProcessingRegistry.create("MEDIAN_FILTER", "large-neighborhood", 3);
    filter.inputs.put("field", Binding.earlierField(base.processing.getFirst().uuid));
    set(filter, "radius", 4);
    base.processing.add(filter);
    base.outputBinding = Binding.earlier(filter.uuid);
    var previous = base;
    for (int k = 0; k < 3; k++) {
      var n = noise("numeric-work-" + k, 3, "x");
      var i = warp(n, false);
      set(i, "warpOctaves", 16);
      for (var key : List.of("warpX", "warpY", "warpZ")) {
        var binding = Binding.noiseField(previous.uuid);
        binding.sourceKind = "Noise Generator Reference";
        i.inputs.put(key, binding);
      }
      previous = n;
    }
    assertTrue(
        NoiseCompiler.validate(project).stream()
            .anyMatch(d -> d.message().contains("COMPLEXITY_LIMIT")));
  }
}
