package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.DoubleUnaryOperator;

/** Deferred engine-JAR affine curve expectations across dimensions and explicit save/load. */
public final class CurveArithmeticConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]), Path.of(args[1]));
  }

  static void run(Path vectorsPath, Path out) throws Exception {
    Files.createDirectories(out);
    var vectors = ProjectIO.JSON.readTree(vectorsPath.toFile());
    require(
        vectors.path("formatVersion").asInt() == 1
            && vectors.path("arithmeticPolicy").asText().equals(CurveModes.ARITHMETIC_POLICY),
        "Curve reference policy mismatch");
    int projects = 0, compiledSamples = 0, reloadedSamples = 0;
    for (var row : vectors.path("cases"))
      for (String mode : List.of("SMOOTH", "BEZIER"))
        for (int dimension : new int[] {2, 3}) {
          double scale = Double.parseDouble(row.path("scaleHex").asText());
          var project = fixture(scale, row.path("knots").asInt(), mode, dimension);
          String before = ProjectIO.JSON.writeValueAsString(project);
          var compiled = new CompiledProject(project);
          var sampler = sampler(compiled, dimension);
          for (var sample : row.path("samples")) {
            double input = Double.parseDouble(sample.path("inputHex").asText());
            double factor = Double.parseDouble(sample.path("factorHex").asText());
            verify(factor == 0 ? 0 : scale * factor, sampler.applyAsDouble(input));
            compiledSamples++;
          }
          var file =
              out.resolve(row.path("id").asText() + "-" + mode + "-" + dimension + ".pvproj.json");
          new ProjectIO().save(file, project);
          var loaded = new ProjectLoader().load(file);
          require(loaded.success(), "Guarded curve save/load failed: " + loaded.diagnostics());
          var reloaded = sampler(new CompiledProject(loaded.project()), dimension);
          for (var sample : row.path("samples")) {
            double input = Double.parseDouble(sample.path("inputHex").asText());
            double factor = Double.parseDouble(sample.path("factorHex").asText());
            verify(factor == 0 ? 0 : scale * factor, reloaded.applyAsDouble(input));
            reloadedSamples++;
          }
          require(
              before.equals(ProjectIO.JSON.writeValueAsString(project))
                  && before.equals(ProjectIO.JSON.writeValueAsString(loaded.project())),
              "Curve compilation or serialization changed authoring values");
          projects++;
        }
    require(
        projects == 28 && compiledSamples == 276 && reloadedSamples == 276,
        "Unexpected curve replay dimensions");
    Files.writeString(
        out.resolve("curve-arithmetic-engine.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "arithmeticPolicy",
                CurveModes.ARITHMETIC_POLICY,
                "cases",
                vectors.path("cases").size(),
                "projects",
                projects,
                "compiledSamples",
                compiledSamples,
                "reloadedSamples",
                reloadedSamples,
                "totalSamples",
                compiledSamples + reloadedSamples,
                "projectDataUnchanged",
                true)));
    System.out.println("CURVE_ARITHMETIC_ENGINE_ONLY_PASS");
  }

  private static DoubleUnaryOperator sampler(CompiledProject project, int dimension) {
    if (dimension == 2) {
      var sampler = project.noise2D("curve", 17);
      return x -> sampler.sample(x, 7);
    }
    var sampler = project.noise3D("curve", 17);
    return x -> sampler.sample(x, -3, 7);
  }

  private static ProjectData fixture(double scale, int knots, String mode, int dimension) {
    var project = new ProjectData();
    var noise = new Noise();
    noise.id = "curve";
    noise.dimension = dimension;
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", 0.0);
    var item = ProcessingRegistry.create("CURVE", "response");
    var input = Binding.constant(0);
    input.sourceKind = "Ambient Value";
    input.ambientKey = "x";
    item.inputs.put("a", input);
    var curve = new Curve();
    curve.max = knots - 1;
    curve.points.clear();
    for (int i = 0; i < knots; i++) {
      var point = new Point(i, i == 0 ? 0 : i * scale);
      point.segmentInterpolation = mode;
      curve.points.add(point);
    }
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
    noise.processing.add(item);
    noise.outputBinding = Binding.earlier(item.uuid);
    (dimension == 2 ? project.noise2D : project.noise3D).add(noise);
    return project;
  }

  private static void verify(double expected, double actual) {
    require(
        Double.isFinite(actual)
            && Double.doubleToRawLongBits(actual) == Double.doubleToRawLongBits(expected),
        "Affine curve lost its exact independent expectation");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
