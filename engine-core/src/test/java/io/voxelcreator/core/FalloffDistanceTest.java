package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred analytic distance ratios and ordinary-operation-order compatibility. */
class FalloffDistanceTest {
  private Item item(String metric, double extent) {
    var item = ProcessingRegistry.create("FALLOFF", "distance", 3);
    set(item, "metric", metric);
    set(item, "extent", extent);
    return item;
  }

  private void set(Item item, String key, Object value) {
    item.parameters.put(key, ProjectIO.JSON.valueToTree(value));
  }

  private void close(double expected, double actual) {
    assertTrue(Double.isFinite(actual));
    assertEquals(expected, actual, 8 * Math.ulp(expected));
  }

  private Path root() {
    for (var path = Path.of("").toAbsolutePath(); path != null; path = path.getParent())
      if (Files.isDirectory(path.resolve("fixtures"))) return path;
    throw new AssertionError("Falloff vectors unavailable");
  }

  private Item item(JsonNode row, String outside) {
    double scale = Double.parseDouble(row.path("scaleHex").asText());
    var item = item(row.path("metric").asText(), row.path("extent").asDouble() * scale);
    for (String key : List.of("axis", "domain")) set(item, key, row.path(key).asText());
    for (String key : List.of("centerX", "centerY", "centerZ"))
      set(item, key, row.path(key).asDouble() * scale);
    set(item, "outside", outside);
    return item;
  }

  @Test
  void analyticCorpusPreservesFiniteLinearResultsAcrossBothOutsidePolicies() throws Exception {
    var rows =
        ProjectIO.JSON
            .readTree(root().resolve("fixtures/falloff-distance-vectors.json").toFile())
            .path("cases");
    assertEquals(20, rows.size());
    for (var row : rows)
      for (String outside : List.of("CLAMP", "EXTEND")) {
        double scale = Double.parseDouble(row.path("scaleHex").asText());
        double input = Double.parseDouble(row.path("aHex").asText());
        double ratio = Double.parseDouble(row.path("ratioHex").asText());
        if (outside.equals("CLAMP")) ratio = Math.min(1, ratio);
        var plan = ScalarRemapping.compile(item(row, outside), row.path("dimension").asInt());
        close(
            input * (1 - ratio),
            plan.sample(
                input,
                row.path("x").asDouble() * scale,
                row.path("y").asDouble() * scale,
                row.path("z").asDouble() * scale));
      }
  }

  @Test
  void ordinaryFinitePathsRetainHistoricalBitsAcrossMetricsShapesAndPolicies() {
    for (int dimension : new int[] {2, 3})
      for (String metric : List.of("EUCLIDEAN", "MANHATTAN", "CHEBYSHEV", "AXIS"))
        for (String shape : List.of("LINEAR", "SMOOTHSTEP", "SMOOTHERSTEP", "COSINE"))
          for (String outside : List.of("CLAMP", "EXTEND")) {
            var item = item(metric, 5);
            set(item, "shape", shape);
            set(item, "outside", outside);
            set(item, "axis", "z");
            var plan = ScalarRemapping.compile(item, dimension);
            for (double x : new double[] {-12, -3, 0, .125, 3}) {
              double y = dimension == 2 ? 0 : 2, z = 4;
              double distance =
                  switch (metric) {
                    case "EUCLIDEAN" ->
                        StrictMath.sqrt(
                            StrictMath.pow(x, 2) + StrictMath.pow(y, 2) + StrictMath.pow(z, 2));
                    case "MANHATTAN" -> StrictMath.abs(x) + StrictMath.abs(y) + StrictMath.abs(z);
                    case "CHEBYSHEV" ->
                        StrictMath.max(
                            StrictMath.abs(x),
                            StrictMath.max(StrictMath.abs(y), StrictMath.abs(z)));
                    default -> StrictMath.abs(z);
                  };
              double t = distance / 5;
              if (outside.equals("CLAMP")) t = Expression.clamp(t, 0, 1);
              double shaped =
                  switch (shape) {
                    case "LINEAR" -> t;
                    case "SMOOTHSTEP" -> t * t * (3 - 2 * t);
                    case "SMOOTHERSTEP" -> t * t * t * (t * (6 * t - 15) + 10);
                    default -> (1 - StrictMath.cos(StrictMath.PI * t)) * .5;
                  };
              assertEquals(
                  Double.doubleToRawLongBits(-8 * (1 - shaped)),
                  Double.doubleToRawLongBits(plan.sample(-8, x, 2, z)));
            }
          }
  }

  @Test
  void tinyAndLargePythagoreanRatiosReachEveryShapeWithoutFlattening() {
    for (double scale : new double[] {0x1p-600, 0x1p600})
      for (String shape : List.of("LINEAR", "SMOOTHSTEP", "SMOOTHERSTEP", "COSINE")) {
        var item = item("EUCLIDEAN", 10 * scale);
        set(item, "shape", shape);
        close(4, ScalarRemapping.compile(item, 2).sample(8, 3 * scale, 0, 4 * scale));
        close(-4, ScalarRemapping.compile(item, 2).sample(-8, 3 * scale, 0, 4 * scale));
      }
  }

  @Test
  void twoDimensionsIgnoreYAndExplicitCentersUseTheirOwnCoordinateDifferences() {
    var item = item("EUCLIDEAN", 10 * 0x1p600);
    set(item, "centerX", 7 * 0x1p600);
    set(item, "centerY", -Double.MAX_VALUE);
    set(item, "centerZ", -4 * 0x1p600);
    close(4, ScalarRemapping.compile(item, 2).sample(8, 10 * 0x1p600, Double.MAX_VALUE, 0));
  }

  @Test
  void nearbyLargeCoordinatesKeepTheirSmallRelativeDifference() {
    double origin = 0x1p1000, difference = Math.ulp(origin);
    var item = item("AXIS", 2 * difference);
    set(item, "axis", "x");
    set(item, "centerX", origin);
    assertEquals(4, ScalarRemapping.compile(item, 2).sample(8, Math.nextUp(origin), 0, 0));
  }

  @Test
  void genuineNonfiniteExtendedResultStillFailsAtTheCompiledItemBoundary() {
    var project = new ProjectData();
    var noise = new Noise();
    noise.id = "distance";
    noise.algorithm.key = "CONSTANT";
    project.noise2D.add(noise);
    var item = item("EUCLIDEAN", Double.MIN_VALUE);
    set(item, "outside", "EXTEND");
    item.inputs.put("a", Binding.constant(8));
    noise.processing.add(item);
    noise.outputBinding = Binding.earlier(item.uuid);
    var field = new NoiseCompiler(project, 0).compile(noise.uuid);
    var failure = assertThrows(EvaluationFailure.class, () -> field.sample(Double.MAX_VALUE, 0, 0));
    assertTrue(failure.getMessage().contains("Nonfinite"));
    assertEquals(noise.uuid, failure.resourceUuid);
    assertEquals(item.uuid, failure.itemUuid);
    set(item, "outside", "CLAMP");
    assertEquals(
        0, new NoiseCompiler(project, 0).compile(noise.uuid).sample(Double.MAX_VALUE, 0, 0));
  }

  @Test
  void compiledPlanRetainsOwnedParametersAcrossLaterEdits() {
    var item = item("EUCLIDEAN", 10 * 0x1p-600);
    var before = ProjectIO.JSON.valueToTree(item);
    var plan = ScalarRemapping.compile(item, 2);
    assertEquals(before, ProjectIO.JSON.valueToTree(item));
    set(item, "extent", 1);
    set(item, "centerX", 100);
    set(item, "metric", "AXIS");
    close(4, plan.sample(8, 3 * 0x1p-600, 0, 4 * 0x1p-600));
  }

  @Test
  void transformedCoordinatesReachGuardedFalloffExactlyOnceInEitherDimension() {
    for (int dimension : new int[] {2, 3})
      for (double scale : new double[] {0x1p-600, 0x1p600}) {
        var project = new ProjectData();
        var noise = new Noise();
        noise.id = "translated";
        noise.dimension = dimension;
        noise.algorithm.key = "CONSTANT";
        (dimension == 2 ? project.noise2D : project.noise3D).add(noise);
        var transform = new Item();
        transform.id = "move";
        transform.kind = "TRANSLATE";
        transform.resultType = dimension == 2 ? "Coordinates2D" : "Coordinates3D";
        set(transform, "x", 3 * scale);
        set(transform, "z", 4 * scale);
        noise.coordinateTransforms.add(transform);
        var falloff = item("EUCLIDEAN", 10 * scale);
        falloff.inputs.put("a", Binding.constant(8));
        noise.processing.add(falloff);
        noise.outputBinding = Binding.earlier(falloff.uuid);
        var before = ProjectIO.JSON.valueToTree(project);
        var field = new NoiseCompiler(project, 0).compile(noise.uuid);
        close(4, field.sample(0, 0, 0));
        assertEquals(before, ProjectIO.JSON.valueToTree(project));
      }
  }

  @Test
  void explicitTypedPositionUsesGuardedDistancesIndependentlyOfTheSamplingLocation() {
    for (int dimension : new int[] {2, 3}) {
      double scale = 0x1p-600;
      var project = new ProjectData();
      var noise = new Noise();
      noise.id = "positioned";
      noise.dimension = dimension;
      noise.algorithm.key = "CONSTANT";
      (dimension == 2 ? project.noise2D : project.noise3D).add(noise);
      var falloff = item("EUCLIDEAN", (dimension == 2 ? 10 : 14) * scale);
      falloff.inputs.put("a", Binding.constant(-8));
      var position = Binding.constant(0);
      position.type = dimension == 2 ? "Coordinates2D" : "Coordinates3D";
      position.value =
          ProjectIO.JSON.valueToTree(
              dimension == 2
                  ? new double[] {3 * scale, 4 * scale}
                  : new double[] {2 * scale, 3 * scale, 6 * scale});
      falloff.inputs.put("position", position);
      noise.processing.add(falloff);
      noise.outputBinding = Binding.earlier(falloff.uuid);
      close(-4, new NoiseCompiler(project, 0).compile(noise.uuid).sample(100, -200, 300));
    }
  }

  @Test
  void registryAndGuardedZeroOrInvalidInputsKeepTheirDeclaredPolicies() throws Exception {
    var registry =
        ProjectIO.JSON.readTree(
            root().resolve("registries/noise-processing-registry.json").toFile());
    assertEquals(
        ProcessingExtensions.DETAILS.get("FALLOFF").description(),
        registry.path("extendedOperations").path("FALLOFF").path("description").asText());
    var plan = ScalarRemapping.compile(item("EUCLIDEAN", 1), 3);
    assertEquals(8, plan.sample(8, -0.0, 0, 0));
    assertTrue(Double.isNaN(plan.sample(8, Double.NaN, Double.POSITIVE_INFINITY, 0)));
  }
}
