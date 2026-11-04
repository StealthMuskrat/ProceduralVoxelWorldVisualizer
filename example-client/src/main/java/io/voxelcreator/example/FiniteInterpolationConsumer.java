package io.voxelcreator.example;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred exact dyadic interpolation through the engine JAR, batches and explicit persistence. */
public final class FiniteInterpolationConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]), Path.of(args[1]));
  }

  static void run(Path vectors, Path out) throws Exception {
    Files.createDirectories(out);
    var reference = ProjectIO.JSON.readTree(vectors.toFile());
    require(reference.path("formatVersion").asInt() == 1, "Interpolation vector version");
    int projects = 0, samples = 0;
    for (var row : reference.path("cases"))
      for (int dimension : new int[] {2, 3})
        for (String kind : List.of("BLEND", "EXPRESSION")) {
          var project = project(row, dimension, kind);
          var before = ProjectIO.JSON.valueToTree(project);
          double expected = Double.parseDouble(row.path("expectedHex").asText());
          samples += replay(new CompiledProject(project), dimension, expected);
          var path =
              out.resolve(row.path("id").asText() + "-" + dimension + "-" + kind + ".pvproj.json");
          new ProjectIO().save(path, project);
          var loaded = new ProjectLoader().load(path);
          require(loaded.success(), "Interpolation save/load rejected: " + loaded.diagnostics());
          samples += replay(new CompiledProject(loaded.project()), dimension, expected);
          require(
              before.equals(ProjectIO.JSON.valueToTree(project))
                  && before.equals(ProjectIO.JSON.valueToTree(loaded.project())),
              "Interpolation sampling or persistence changed authoring data");
          projects++;
        }
    require(
        reference.path("cases").size() == 16 && projects == 64 && samples == 384,
        "Unexpected interpolation replay dimensions");
    Files.writeString(
        out.resolve("finite-interpolation-engine.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "cases",
                16,
                "projects",
                projects,
                "samples",
                samples,
                "samplingPaths",
                List.of("DIRECT", "GRID", "POINTS"),
                "rawBits",
                true,
                "projectDataUnchanged",
                true)));
    System.out.println("FINITE_INTERPOLATION_ENGINE_ONLY_PASS");
  }

  private static ProjectData project(JsonNode row, int dimension, String kind) {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "interpolation";
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 0.0);
    var item = ProcessingRegistry.create(kind, "interpolation", dimension);
    item.inputs.clear();
    item.inputs.put("a", Binding.constant(Double.parseDouble(row.path("aHex").asText())));
    item.inputs.put("b", Binding.constant(Double.parseDouble(row.path("bHex").asText())));
    item.inputs.put("weight", Binding.constant(Double.parseDouble(row.path("weightHex").asText())));
    if (kind.equals("EXPRESSION"))
      item.parameters.put("expression", ProjectIO.JSON.valueToTree("lerp(a,b,weight)"));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    return p;
  }

  private static int replay(CompiledProject project, int dimension, double expected) {
    var grid = new double[] {19, 19, 19};
    var points = new double[] {19, 19, 19};
    if (dimension == 2) {
      var sampler = project.noise2D("interpolation", 17);
      bits(expected, sampler.sample(-3, 5));
      sampler.sampleGrid(-3, 5, 1, 1, 0, 0, grid, 1, 1, new CancellationToken());
      sampler.samplePoints(
          new double[] {-3}, new double[] {5}, 0, 1, points, 1, new CancellationToken());
    } else {
      var sampler = project.noise3D("interpolation", 17);
      bits(expected, sampler.sample(-3, 2, 5));
      sampler.sampleGrid(-3, 2, 5, 1, 1, 1, 0, 0, 0, grid, 1, 1, 1, new CancellationToken());
      sampler.samplePoints(
          new double[] {-3},
          new double[] {2},
          new double[] {5},
          0,
          1,
          points,
          1,
          new CancellationToken());
    }
    bits(expected, grid[1]);
    bits(expected, points[1]);
    require(
        grid[0] == 19 && grid[2] == 19 && points[0] == 19 && points[2] == 19,
        "Interpolation batches changed destination padding");
    return 3;
  }

  private static void bits(double expected, double actual) {
    require(
        Double.isFinite(actual)
            && Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual),
        "Interpolation differs from exact dyadic expectation: "
            + Double.toHexString(expected)
            + " != "
            + Double.toHexString(actual));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
