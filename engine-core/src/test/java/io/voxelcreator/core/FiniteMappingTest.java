package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Deferred finite input/output span recovery and exactly rounded exceptional affine ratios. */
class FiniteMappingTest {
  private Path root() {
    for (var p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
      if (Files.isDirectory(p.resolve("fixtures"))) return p;
    throw new AssertionError("Mapping vectors unavailable");
  }

  private JsonNode cases() throws Exception {
    return ProjectIO.JSON
        .readTree(root().resolve("fixtures/finite-mapping-vectors.json").toFile())
        .path("cases");
  }

  private double number(JsonNode row, String name) {
    return Double.parseDouble(row.path(name + "Hex").asText());
  }

  private void bits(double expected, double actual) {
    assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
  }

  private Map<String, Double> values(JsonNode row) {
    var values = new LinkedHashMap<String, Double>();
    for (String key : List.of("value", "inputMin", "inputMax")) values.put(key, number(row, key));
    if (row.path("operation").asText().equals("remap"))
      for (String key : List.of("outputMin", "outputMax")) values.put(key, number(row, key));
    return values;
  }

  private String formula(JsonNode row) {
    return switch (row.path("operation").asText()) {
      case "remap" -> "remap(value,inputMin,inputMax,outputMin,outputMax)";
      case "inverseLerp" -> "inverseLerp(inputMin,inputMax,value)";
      default -> "smoothstep(inputMin,inputMax,value)";
    };
  }

  private ProjectData project(JsonNode row, int dimension, boolean processing) {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "mapping";
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 0.);
    var item = ProcessingRegistry.create(processing ? "REMAP" : "EXPRESSION", "mapping", dimension);
    item.inputs.clear();
    if (processing) {
      item.inputs.put("a", Binding.constant(number(row, "value")));
      for (String key : List.of("inputMin", "inputMax", "outputMin", "outputMax"))
        item.parameters.put(key, ProjectIO.JSON.valueToTree(number(row, key)));
    } else {
      values(row).forEach((key, value) -> item.inputs.put(key, Binding.constant(value)));
      item.parameters.put("expression", ProjectIO.JSON.valueToTree(formula(row)));
    }
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    return p;
  }

  @Test
  void handSpecifiedCasesReachActualProcessingAndExpressionCompilationInBothDimensions()
      throws Exception {
    var cases = cases();
    assertEquals(24, cases.size());
    for (var row : cases)
      for (int dimension : new int[] {2, 3})
        for (boolean processing :
            row.path("operation").asText().equals("remap")
                ? List.of(false, true)
                : List.of(false)) {
          var p = project(row, dimension, processing);
          var before = ProjectIO.JSON.valueToTree(p);
          var n = (dimension == 2 ? p.noise2D : p.noise3D).getFirst();
          bits(number(row, "expected"), new NoiseCompiler(p, 17).compile(n.uuid).sample(-3, 2, 5));
          assertEquals(before, ProjectIO.JSON.valueToTree(p));
        }
  }

  @Test
  void overflowingSpansCannotSilentlyReturnAZeroNormalizedCoordinate() {
    double h = 0x1p1023;
    bits(.5, FiniteMapping.inverse(-h, h, 0));
    bits(.25, FiniteMapping.inverse(-h, h, -h / 2));
    bits(.75, FiniteMapping.inverse(-h, h, h / 2));
    bits(.5, FiniteMapping.inverse(h, -h, 0));
    bits(1, FiniteMapping.inverse(-h, h, h));
    bits(4, FiniteMapping.inverse(h, h / 2, -h));
  }

  @Test
  void aNonrepresentableIntermediateWeightCanStillMapToAFiniteOrConstantOutput() {
    double small = Double.MIN_VALUE;
    bits(1, FiniteMapping.remap(1, 0, small, 0, small));
    bits(.25, FiniteMapping.remap(1, 0, small, .25, .25));
    bits(-0x1p1023, FiniteMapping.remap(2, 0, 1, 0x1p1023, 0));
  }

  @Test
  void exactRatiosRoundHalfwaySubnormalsAndNormalCarriesToEvenWithSignedUnderflow() {
    double h = 0x1p1023, tiny = Double.MIN_VALUE;
    bits(0.0, FiniteMapping.remap(0, -h, h, 0, tiny));
    bits(-0.0, FiniteMapping.remap(0, -h, h, 0, -tiny));
    bits(tiny, FiniteMapping.remap(h / 2, -h, h, 0, tiny));
    bits(
        Double.MIN_NORMAL,
        FiniteMapping.remap(0, -h, h, Math.nextDown(Double.MIN_NORMAL), Double.MIN_NORMAL));
    bits(1, FiniteMapping.remap(0, -h, h, 1, Math.nextUp(1.)));
    bits(Double.MAX_VALUE, FiniteMapping.remap(0, -h, h, Double.MAX_VALUE, Double.MAX_VALUE));
    bits(
        Math.nextDown(Double.MAX_VALUE),
        FiniteMapping.remap(0, -h, h, Math.nextDown(Double.MAX_VALUE), Double.MAX_VALUE));
    bits(
        0x1.0000000000002p0,
        FiniteMapping.remap(0, -h, h, 0x1.0000000000001p0, 0x1.0000000000002p0));
  }

  @Test
  void ordinaryFiniteOperationsRetainTheirRawBitsAndExistingUnderflowOrder() {
    var random = new Random(71031);
    for (int i = 0; i < 512; i++) {
      double a = Math.scalb(random.nextDouble() - .5, random.nextInt(201) - 100);
      double b = Math.scalb(random.nextDouble() + 1, random.nextInt(201) - 100);
      double v = Math.scalb(random.nextDouble() - .5, random.nextInt(201) - 100);
      double c = random.nextDouble() * 4 - 2, d = random.nextDouble() * 4 - 2;
      bits((v - a) / (b - a), FiniteMapping.inverse(a, b, v));
      bits(c + (v - a) / (b - a) * (d - c), FiniteMapping.remap(v, a, b, c, d));
    }
    for (double v : new double[] {-0.0, 0.0, Double.MIN_VALUE, -Double.MIN_VALUE}) {
      bits((v - 0.) / (1. - 0.), FiniteMapping.inverse(0, 1, v));
      bits(-0. + (v - 0.) / (1. - 0.) * (1. - (-0.)), FiniteMapping.remap(v, 0, 1, -0., 1));
    }
  }

  @Test
  void foldedNamedAndIndexedExpressionPathsShareTheExceptionalMappingPolicy() throws Exception {
    for (var row : cases()) {
      var values = values(row);
      var types = new LinkedHashMap<String, Expression.Type>();
      values.keySet().forEach(key -> types.put(key, Expression.Type.Scalar));
      var runtime = Expression.compile(formula(row), types);
      bits(number(row, "expected"), runtime.scalar(values::get));
      bits(
          number(row, "expected"),
          runtime.scalarIndexed(index -> values.get(runtime.inputs().get(index))));
      var folded = Expression.compile(formula(row), types, values);
      bits(number(row, "expected"), folded.constantValue().orElseThrow());
      bits(number(row, "expected"), folded.scalar(null));
    }
  }

  @Test
  void smoothstepClampsTheRecoveredRatioBeforeItsOriginalPolynomial() {
    double h = 0x1p1023;
    var p =
        Expression.compile(
            "smoothstep(lo,hi,v)",
            Map.of(
                "lo",
                Expression.Type.Scalar,
                "hi",
                Expression.Type.Scalar,
                "v",
                Expression.Type.Scalar));
    bits(.5, p.scalar(key -> key.equals("lo") ? -h : key.equals("hi") ? h : 0));
    bits(.15625, p.scalar(key -> key.equals("lo") ? -h : key.equals("hi") ? h : -h / 2));
    bits(1, p.scalar(key -> key.equals("lo") ? h : key.equals("hi") ? h / 2 : -h));
  }

  @Test
  void zeroWidthNonfiniteInputsAndTrueFinalOverflowRetainTheirOriginalBoundaries()
      throws Exception {
    assertTrue(Double.isNaN(FiniteMapping.inverse(1, 1, 1)));
    assertTrue(Double.isInfinite(FiniteMapping.inverse(1, 1, 2)));
    assertTrue(Double.isNaN(FiniteMapping.remap(1, 1, 1, 0, 1)));
    assertTrue(
        Double.isNaN(FiniteMapping.inverse(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0)));
    assertTrue(Double.isNaN(FiniteMapping.remap(Double.NaN, 0, 1, 0, 1)));
    assertTrue(
        Double.isInfinite(FiniteMapping.remap(2, 0, 1, -Double.MAX_VALUE, Double.MAX_VALUE)));
    var expression =
        Expression.compile(
            "remap(v,0,1,lo,hi)",
            Map.of(
                "v",
                Expression.Type.Scalar,
                "lo",
                Expression.Type.Scalar,
                "hi",
                Expression.Type.Scalar));
    assertThrows(
        Expression.Problem.class,
        () ->
            expression.scalar(
                key ->
                    key.equals("v") ? 2 : key.equals("lo") ? -Double.MAX_VALUE : Double.MAX_VALUE));
    var row = (com.fasterxml.jackson.databind.node.ObjectNode) cases().get(8).deepCopy();
    row.put("inputMinHex", "0x0p0")
        .put("inputMaxHex", "0x1p0")
        .put("valueHex", "0x1p1")
        .put("outputMinHex", "-0x1.fffffffffffffp1023")
        .put("outputMaxHex", "0x1.fffffffffffffp1023");
    var bad = project(row, 2, true);
    var field = new NoiseCompiler(bad, 0).compile(bad.noise2D.getFirst().uuid);
    assertThrows(EvaluationFailure.class, () -> field.sample(0, 0, 0));
  }

  @Test
  void processingOutputClampStillUsesSortedBoundsAfterFiniteMapping() {
    var i = ProcessingRegistry.create("REMAP", "map", 2);
    i.parameters.put("inputMin", ProjectIO.JSON.valueToTree(-0x1p1023));
    i.parameters.put("inputMax", ProjectIO.JSON.valueToTree(0x1p1023));
    i.parameters.put("clamp", ProjectIO.JSON.valueToTree(true));
    bits(.5, ScalarRemapping.compile(i, 2).sample(0, 0, 0, 0));
    i.parameters.put("inputMin", ProjectIO.JSON.valueToTree(0.));
    i.parameters.put("inputMax", ProjectIO.JSON.valueToTree(1.));
    i.parameters.put("outputMin", ProjectIO.JSON.valueToTree(1.));
    i.parameters.put("outputMax", ProjectIO.JSON.valueToTree(0.));
    bits(0, ScalarRemapping.compile(i, 2).sample(2, 0, 0, 0));
    bits(1, ScalarRemapping.compile(i, 2).sample(-1, 0, 0, 0));
  }

  @Test
  void eagerExpressionArgumentsStillFailBeforeMappingCanDiscardTheirValues() {
    var program = Expression.compile("remap(1,0,1,0,1/0)", Map.of());
    assertThrows(Expression.Problem.class, () -> program.scalar(null));
  }

  @Test
  void immutableCompiledMappingsRemainConsistentAcrossConcurrentExtremeSamples() throws Exception {
    var row = cases().get(0);
    var p = project(row, 3, false);
    var n = p.noise3D.getFirst();
    var compiled = new NoiseCompiler(p, 0).compile(n.uuid);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Callable<Double>>();
      for (int i = 0; i < 128; i++) jobs.add(() -> compiled.sample(-3, 2, 5));
      for (var result : pool.invokeAll(jobs)) bits(.5, result.get());
    }
  }

  @Test
  void remapRegistryExplainsTheSameFiniteOperandPolicy() throws Exception {
    var description = ProcessingExtensions.DETAILS.get("REMAP").description();
    assertTrue(description.contains("exact binary rational"));
    assertTrue(description.contains("nearest-even"));
    var registry =
        ProjectIO.JSON.readTree(
            root().resolve("registries/noise-processing-registry.json").toFile());
    assertEquals(
        description,
        registry.path("extendedOperations").path("REMAP").path("description").asText());
  }
}
