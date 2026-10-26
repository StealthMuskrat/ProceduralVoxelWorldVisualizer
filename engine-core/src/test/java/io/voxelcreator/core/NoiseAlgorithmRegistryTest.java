package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NoiseAlgorithmRegistryTest {
  private Algorithm algorithm(String key) {
    var a = new Algorithm();
    a.key = key;
    return a;
  }

  @Test
  void stableKeysDimensionsAndOwnedSignaturesAreComplete() {
    assertEquals(10, NoiseAlgorithmRegistry.ITEMS.size());
    assertEquals(NoiseAlgorithms.KEYS, NoiseAlgorithmRegistry.KEYS);
    for (var spec : NoiseAlgorithmRegistry.ITEMS.values()) {
      assertEquals(List.of(2, 3), spec.dimensions());
      for (int d : new int[] {2, 3})
        for (var p : NoiseAlgorithmRegistry.parameters(spec.key(), d)) p.validate(p.defaultValue());
    }
    assertThrows(UnsupportedOperationException.class, () -> NoiseAlgorithmRegistry.ITEMS.clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> NoiseAlgorithmRegistry.get("PERLIN").parameters().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> NoiseAlgorithmRegistry.parameter("fractal").choices().clear());
  }

  @Test
  void directCompileRejectsUnsupportedDimensionAndUnknownAlgorithm() {
    assertThrows(
        NoiseAlgorithmRegistry.Problem.class,
        () -> NoiseAlgorithms.compile(algorithm("PERLIN"), 4, 0));
    assertThrows(
        NoiseAlgorithmRegistry.Problem.class,
        () -> NoiseAlgorithms.compile(algorithm("MISSING"), 2, 0));
  }

  @Test
  void allUnconfiguredFamiliesRemainFiniteAndRepeatable() {
    for (String key : NoiseAlgorithms.KEYS)
      for (int dimension : new int[] {2, 3}) {
        var a = algorithm(key);
        var f = NoiseAlgorithms.compile(a, dimension, 17);
        for (double x : new double[] {-31.5, 0, 2.25, 32}) {
          double value = f.sample(x, -4, 7);
          assertTrue(Double.isFinite(value));
          assertEquals(
              Double.doubleToRawLongBits(value),
              Double.doubleToRawLongBits(
                  NoiseAlgorithms.compile(a, dimension, 17).sample(x, -4, 7)));
        }
      }
  }

  @Test
  void globalHistoricalFrequencyValidationAlsoAppliesToAnalyticSources() {
    for (String key : NoiseAlgorithms.KEYS) {
      var a = algorithm(key);
      for (double value : new double[] {0, -1, 1000001, Double.NaN, Double.POSITIVE_INFINITY}) {
        a.parameters.put("frequency", value);
        assertEquals(
            "frequency",
            assertThrows(
                    NoiseAlgorithmRegistry.Problem.class, () -> NoiseAlgorithms.compile(a, 2, 0))
                .parameter());
      }
    }
  }

  @Test
  void knownInactiveControlsPersistWithoutChangingWhiteHash() {
    var a = algorithm("WHITE_HASH");
    var before = new LinkedHashMap<>(a.parameters);
    double value = NoiseAlgorithms.compile(a, 2, 9).sample(2, 99, 7);
    a.parameters.put("fractal", 8.);
    a.parameters.put("octaves", 99.);
    a.parameters.put("gain", -123.);
    a.parameters.put("radius", -5.);
    assertEquals(value, NoiseAlgorithms.compile(a, 2, 9).sample(2, -999, 7));
    assertEquals(8, a.parameters.get("fractal"));
    assertEquals(.01, before.get("frequency"));
  }

  @Test
  void knownInactiveControlsStillRequireFiniteTypedValues() {
    var a = algorithm("WHITE_HASH");
    a.parameters.put("octaves", 1.5);
    assertEquals(
        "octaves",
        assertThrows(NoiseAlgorithmRegistry.Problem.class, () -> NoiseAlgorithms.compile(a, 2, 0))
            .parameter());
    a.parameters.remove("octaves");
    a.parameters.put("gain", Double.NaN);
    assertEquals(
        "gain",
        assertThrows(NoiseAlgorithmRegistry.Problem.class, () -> NoiseAlgorithms.compile(a, 2, 0))
            .parameter());
  }

  @Test
  void unknownAndNullParametersCarryTheExactKey() {
    var a = algorithm("PERLIN");
    a.parameters.put("invented", 1.);
    assertEquals(
        "invented",
        assertThrows(NoiseAlgorithmRegistry.Problem.class, () -> NoiseAlgorithms.compile(a, 2, 0))
            .parameter());
    a.parameters.remove("invented");
    a.parameters.put("gain", null);
    assertEquals(
        "gain",
        assertThrows(NoiseAlgorithmRegistry.Problem.class, () -> NoiseAlgorithms.compile(a, 2, 0))
            .parameter());
  }

  @Test
  void activeEnumAndOctaveBoundsRejectWithoutRounding() {
    for (var control : List.of("fractal", "octaves", "cellularDistance", "cellularReturn")) {
      var a = algorithm("CELLULAR");
      for (double value : new double[] {-1, 1.5, 100}) {
        a.parameters.put(control, value);
        assertEquals(
            control,
            assertThrows(
                    NoiseAlgorithmRegistry.Problem.class, () -> NoiseAlgorithms.compile(a, 3, 0))
                .parameter());
      }
    }
  }

  @Test
  void legacyCellularIndicesKeepBoundsEvenOnOtherUpstreamFamilies() {
    var a = algorithm("PERLIN");
    a.parameters.put("cellularReturn", 7.);
    assertEquals(
        "cellularReturn",
        assertThrows(NoiseAlgorithmRegistry.Problem.class, () -> NoiseAlgorithms.compile(a, 2, 0))
            .parameter());
    a.key = "CONSTANT";
    assertEquals(0, NoiseAlgorithms.compile(a, 2, 0).sample(2, 0, 3));
  }

  @Test
  void signedSupportedFloatControlsAreNotSilentlyClampedToPresetRanges() {
    var a = algorithm("PERLIN");
    a.parameters.put("fractal", 1.);
    a.parameters.put("gain", -.5);
    a.parameters.put("lacunarity", -2.);
    assertTrue(Double.isFinite(NoiseAlgorithms.compile(a, 3, 1).sample(2, 3, 4)));
    assertEquals(-.5, a.parameters.get("gain"));
    assertEquals(0, NoiseAlgorithmRegistry.parameter("gain").presetMinimum());
    assertTrue(NoiseAlgorithmRegistry.parameter("gain").minimum() < 0);
  }

  @Test
  void floatNarrowingOverflowRejectsAtItsSupportedParameter() {
    var a = algorithm("PERLIN");
    a.parameters.put("gain", Double.MAX_VALUE);
    assertEquals(
        "gain",
        assertThrows(NoiseAlgorithmRegistry.Problem.class, () -> NoiseAlgorithms.compile(a, 2, 0))
            .parameter());
    a.key = "CONSTANT";
    assertEquals(0, NoiseAlgorithms.compile(a, 2, 0).sample(0, 0, 0));
  }

  @Test
  void negativeActiveSdfSizesRejectAndUnusedYIsIgnoredOnlyIn2D() {
    var sphere = algorithm("SPHERE_SDF");
    sphere.parameters.put("radius", -1.);
    assertEquals(
        "radius",
        assertThrows(
                NoiseAlgorithmRegistry.Problem.class, () -> NoiseAlgorithms.compile(sphere, 2, 0))
            .parameter());
    var box = algorithm("BOX_SDF");
    box.parameters.put("extentY", -5.);
    assertEquals(-16, NoiseAlgorithms.compile(box, 2, 0).sample(0, 0, 0));
    assertEquals(
        "extentY",
        assertThrows(NoiseAlgorithmRegistry.Problem.class, () -> NoiseAlgorithms.compile(box, 3, 0))
            .parameter());
  }

  @Test
  void dimensionalSignaturesAndFractalVisibilityDoNotMutateDefinitions() {
    var a = algorithm("PERLIN");
    assertEquals(Set.of("frequency", "fractal"), visible(a, 2));
    a.parameters.put("fractal", 1.);
    assertEquals(Set.of("frequency", "fractal", "octaves", "lacunarity", "gain"), visible(a, 2));
    a.parameters.put("fractal", 3.);
    assertTrue(visible(a, 2).contains("pingPongStrength"));
    assertEquals(2, a.parameters.size());
    assertFalse(
        NoiseAlgorithmRegistry.parameters("BOX_SDF", 2).stream()
            .anyMatch(p -> p.key().equals("extentY")));
  }

  private Set<String> visible(Algorithm a, int dimension) {
    var result = new HashSet<String>();
    for (var p : NoiseAlgorithmRegistry.parameters(a.key, dimension))
      if (p.visible(a)) result.add(p.key());
    return result;
  }

  @Test
  void enumIndicesRetainOriginalFastNoiseLiteMeanings() {
    assertEquals(List.of("NONE", "FBM", "RIDGED", "PING_PONG"), NoiseAlgorithmRegistry.FRACTALS);
    assertEquals("EUCLIDEAN_SQUARED", NoiseAlgorithmRegistry.DISTANCES.get(1));
    assertEquals("DISTANCE", NoiseAlgorithmRegistry.RETURNS.get(1));
    assertEquals("DISTANCE_2_DIVIDE", NoiseAlgorithmRegistry.RETURNS.get(6));
  }

  @Test
  void compiledFieldsOwnTheirValuesWhenTheAuthoredMapChanges() {
    var a = algorithm("PERLIN");
    var f = NoiseAlgorithms.compile(a, 3, 123);
    double value = f.sample(2, 3, 4);
    a.parameters.put("frequency", 2.);
    a.parameters.put("fractal", 3.);
    assertEquals(value, f.sample(2, 3, 4));
    var c = algorithm("CONSTANT");
    c.parameters.put("value", -0.);
    var constant = NoiseAlgorithms.compile(c, 2, 0);
    c.parameters.put("value", 9.);
    assertEquals(
        Double.doubleToRawLongBits(-0.), Double.doubleToRawLongBits(constant.sample(0, 0, 0)));
  }

  @Test
  void compilerDiagnosticsPointAtTheExactSourceControl() {
    var project = new ProjectData();
    var n = new Noise();
    n.id = "field";
    n.algorithm.parameters.put("frequency", 0.);
    project.noise2D.add(n);
    var issue = NoiseCompiler.validate(project).getFirst();
    assertEquals(n.uuid, issue.sourceUuid());
    assertEquals("algorithm/parameters/frequency", issue.parameterKey());
    assertTrue(issue.jsonPointer().endsWith("/algorithm/parameters/frequency"));
    assertEquals(0, n.algorithm.parameters.get("frequency"));
  }

  @Test
  void independentPythonAnalyticAndHashVectorsMatchExactDoubleBits() throws Exception {
    var fixtures =
        ProjectIO.JSON.readTree(Path.of("../fixtures/algorithm-analytic-vectors.json").toFile());
    int count = 0;
    for (var v : fixtures.path("vectors")) {
      var a = algorithm(v.path("algorithm").asText());
      a.parameters.clear();
      v.path("parameters")
          .fields()
          .forEachRemaining(e -> a.parameters.put(e.getKey(), e.getValue().doubleValue()));
      double value =
          NoiseAlgorithms.compile(
                  a,
                  v.path("dimension").intValue(),
                  Long.parseUnsignedLong(v.path("seedHex").asText(), 16))
              .sample(
                  v.path("x").doubleValue(), v.path("y").doubleValue(), v.path("z").doubleValue());
      assertEquals(
          Long.parseUnsignedLong(v.path("valueBits").asText(), 16),
          Double.doubleToRawLongBits(value),
          v.toString());
      count++;
    }
    assertEquals(240, count);
  }

  @Test
  void independentFullWidthSeedVectorsIncludeNegativeLongBoundaries() throws Exception {
    var fixtures =
        ProjectIO.JSON.readTree(Path.of("../fixtures/algorithm-analytic-vectors.json").toFile());
    int count = 0;
    for (var v : fixtures.path("seedVectors")) {
      assertEquals(
          Long.parseUnsignedLong(v.path("valueHex").asText(), 16),
          CoordinateHash.seed(
              Long.parseUnsignedLong(v.path("runtimeHex").asText(), 16),
              UUID.fromString(v.path("uuid").asText()),
              Long.parseUnsignedLong(v.path("offsetHex").asText(), 16)));
      count++;
    }
    assertEquals(32, count);
  }
}
