package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Deferred finite affine results, ordinary bit compatibility and retained failure boundaries. */
class FiniteInterpolationTest {
  private Path root() {
    for (var p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
      if (Files.isDirectory(p.resolve("fixtures"))) return p;
    throw new AssertionError("Interpolation vectors unavailable");
  }

  private JsonNode cases() throws Exception {
    return ProjectIO.JSON
        .readTree(root().resolve("fixtures/finite-interpolation-vectors.json").toFile())
        .path("cases");
  }

  private void bits(double expected, double actual) {
    assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
  }

  private Item blend(String mode) {
    var i = ProcessingRegistry.create("BLEND", "blend", 2);
    set(i, "mode", mode);
    return i;
  }

  private void set(Item i, String key, Object value) {
    i.parameters.put(key, ProjectIO.JSON.valueToTree(value));
  }

  private ProjectData project(
      double a, double b, double weight, int dimension, boolean expression) {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "field";
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 0.0);
    var i =
        ProcessingRegistry.create(expression ? "EXPRESSION" : "BLEND", "interpolation", dimension);
    i.inputs.clear();
    i.inputs.put("a", Binding.constant(a));
    i.inputs.put("b", Binding.constant(b));
    i.inputs.put("weight", Binding.constant(weight));
    if (expression) set(i, "expression", "lerp(a,b,weight)");
    n.processing.add(i);
    n.outputBinding = Binding.earlier(i.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    return p;
  }

  @Test
  void handSpecifiedDyadicCasesPassThroughBlendAndExpressionInBothDimensions() throws Exception {
    var rows = cases();
    assertEquals(16, rows.size());
    for (var row : rows)
      for (int dimension : new int[] {2, 3})
        for (boolean expression : List.of(false, true)) {
          double a = Double.parseDouble(row.path("aHex").asText()),
              b = Double.parseDouble(row.path("bHex").asText());
          double w = Double.parseDouble(row.path("weightHex").asText()),
              expected = Double.parseDouble(row.path("expectedHex").asText());
          var p = project(a, b, w, dimension, expression);
          var before = ProjectIO.JSON.valueToTree(p);
          var n = (dimension == 2 ? p.noise2D : p.noise3D).getFirst();
          bits(expected, new NoiseCompiler(p, 17).compile(n.uuid).sample(-3, 2, 5));
          assertEquals(before, ProjectIO.JSON.valueToTree(p));
        }
  }

  @Test
  void oppositeMaximumFiniteEndpointsHaveRepresentableMidAndQuarterResults() {
    double m = Double.MAX_VALUE;
    bits(0, FiniteInterpolation.between(-m, m, .5));
    bits(-m / 2, FiniteInterpolation.between(-m, m, .25));
    bits(m / 2, FiniteInterpolation.between(m, -m, .25));
  }

  @Test
  void rawExtrapolationRecoversWhenOnlyTheWeightedDifferenceOverflows() {
    double h = 0x1p1023;
    bits(-h, FiniteInterpolation.between(h, 0, 2));
    bits(h, FiniteInterpolation.between(-h, 0, 2));
    bits(-0x1.8p1023, FiniteInterpolation.between(h, h / 2, 5));
    bits(0x1.8p1023, FiniteInterpolation.between(-h / 2, -h, -4));
  }

  @Test
  void ordinaryFiniteArithmeticKeepsRawBitsIncludingSignedZeroAndSubnormals() {
    var random = new Random(17531);
    for (int i = 0; i < 512; i++) {
      double a = Math.scalb(random.nextDouble() - .5, random.nextInt(601) - 300);
      double b = Math.scalb(random.nextDouble() - .5, random.nextInt(601) - 300);
      double w = random.nextDouble() * 16 - 8;
      bits(a + (b - a) * w, FiniteInterpolation.between(a, b, w));
    }
    for (double a : new double[] {-0.0, 0.0, Double.MIN_VALUE, -Double.MIN_VALUE})
      for (double b : new double[] {-0.0, 0.0, Double.MIN_VALUE, -Double.MIN_VALUE})
        for (double w : new double[] {-1, -0.0, 0, .5, 1, 2})
          bits(a + (b - a) * w, FiniteInterpolation.between(a, b, w));
  }

  @Test
  void foldedNamedAndIndexedExpressionPathsAgreeOnRecoveredResults() throws Exception {
    var types =
        Map.of(
            "a", Expression.Type.Scalar, "b", Expression.Type.Scalar, "w", Expression.Type.Scalar);
    for (var row : cases()) {
      var values =
          Map.of(
              "a",
              Double.parseDouble(row.path("aHex").asText()),
              "b",
              Double.parseDouble(row.path("bHex").asText()),
              "w",
              Double.parseDouble(row.path("weightHex").asText()));
      double expected = Double.parseDouble(row.path("expectedHex").asText());
      var runtime = Expression.compile("lerp(a,b,w)", types);
      bits(expected, runtime.scalar(values::get));
      bits(expected, runtime.scalarIndexed(index -> values.get(runtime.inputs().get(index))));
      var folded = Expression.compile("lerp(a,b,w)", types, values);
      bits(expected, folded.constantValue().orElseThrow());
      bits(expected, folded.scalar(null));
    }
  }

  @Test
  void clampPoliciesRemainExplicitAndAreAppliedAfterRepresentability() {
    var i = blend("LERP");
    set(i, "weightPolicy", "CLAMP");
    var plan = ScalarRemapping.compile(i, 2);
    bits(Double.MAX_VALUE, plan.combine(-Double.MAX_VALUE, Double.MAX_VALUE, 2));
    set(i, "outputPolicy", "CLAMP_01");
    plan = ScalarRemapping.compile(i, 2);
    bits(0, plan.combine(-Double.MAX_VALUE, Double.MAX_VALUE, .5));
    bits(1, plan.combine(-Double.MAX_VALUE, Double.MAX_VALUE, .75));
    set(i, "weightPolicy", "RAW");
    plan = ScalarRemapping.compile(i, 2);
    bits(0, plan.combine(0x1p1023, 0, 2));
  }

  @Test
  void otherModesUseTheSameInterpolationAfterTheirFiniteTargetIsComputed() {
    for (String mode : List.of("MIN", "MAX", "DIVIDE")) {
      var plan = ScalarRemapping.compile(blend(mode), 2);
      double a = mode.equals("MAX") ? -Double.MAX_VALUE : Double.MAX_VALUE;
      double b = mode.equals("DIVIDE") ? -1 : -a;
      bits(0, plan.combine(a, b, .5));
    }
  }

  @Test
  void nonfiniteCombinationTargetsStillRejectBeforeOutputClamping() {
    for (String mode : List.of("ADD", "MULTIPLY", "DIVIDE")) {
      var i = blend(mode);
      set(i, "outputPolicy", "CLAMP_01");
      var plan = ScalarRemapping.compile(i, 2);
      double a = mode.equals("DIVIDE") ? 1 : Double.MAX_VALUE;
      double b = mode.equals("DIVIDE") ? 0 : mode.equals("MULTIPLY") ? 2 : Double.MAX_VALUE;
      assertThrows(ArithmeticException.class, () -> plan.combine(a, b, .5));
    }
  }

  @Test
  void genuinelyOutOfRangeFinalResultsRetainExecutingItemAndExpressionFailures() {
    var i = blend("LERP");
    set(i, "outputPolicy", "CLAMP_01");
    var plan = ScalarRemapping.compile(i, 2);
    assertThrows(
        ArithmeticException.class, () -> plan.combine(-Double.MAX_VALUE, Double.MAX_VALUE, 2));
    var p = project(-Double.MAX_VALUE, Double.MAX_VALUE, 2, 2, false);
    var n = p.noise2D.getFirst();
    var field = new NoiseCompiler(p, 0).compile(n.uuid);
    assertThrows(EvaluationFailure.class, () -> field.sample(0, 0, 0));
    var program =
        Expression.compile(
            "lerp(a,b,w)",
            Map.of(
                "a",
                Expression.Type.Scalar,
                "b",
                Expression.Type.Scalar,
                "w",
                Expression.Type.Scalar));
    assertThrows(
        Expression.Problem.class,
        () ->
            program.scalar(
                name ->
                    name.equals("a")
                        ? -Double.MAX_VALUE
                        : name.equals("b") ? Double.MAX_VALUE : 2));
  }

  @Test
  void nonfiniteArgumentsNeverEnterExactDecimalConversion() {
    assertTrue(Double.isNaN(FiniteInterpolation.between(Double.NaN, 1, .5)));
    assertTrue(Double.isNaN(FiniteInterpolation.between(Double.POSITIVE_INFINITY, 1, .5)));
    assertTrue(Double.isInfinite(FiniteInterpolation.between(1, Double.POSITIVE_INFINITY, .5)));
    assertTrue(Double.isInfinite(FiniteInterpolation.between(0, 1, Double.POSITIVE_INFINITY)));
  }

  @Test
  void sharedCompiledFieldRemainsDeterministicAcrossConcurrentExtremeSamples() throws Exception {
    var p = project(-Double.MAX_VALUE, Double.MAX_VALUE, .5, 3, false);
    var field = new NoiseCompiler(p, 0).compile(p.noise3D.getFirst().uuid);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Double>>();
      for (int i = 0; i < 128; i++) {
        int coordinate = i - 64;
        tasks.add(() -> field.sample(coordinate, -coordinate, coordinate * 2));
      }
      for (var result : pool.invokeAll(tasks)) bits(0, result.get());
    }
  }

  @Test
  void registryDescribesTheSharedFallbackAndKeepsSignaturesAndPolicies() throws Exception {
    var description = ProcessingExtensions.DETAILS.get("BLEND").description();
    var registry =
        ProjectIO.JSON.readTree(
            root().resolve("registries/noise-processing-registry.json").toFile());
    assertEquals(
        description,
        registry.path("extendedOperations").path("BLEND").path("description").asText());
    assertTrue(description.contains("exact arithmetic"));
    var plan = ScalarRemapping.compile(blend("LERP"), 2);
    bits(7, plan.combine(7, Double.POSITIVE_INFINITY, 0));
  }
}
