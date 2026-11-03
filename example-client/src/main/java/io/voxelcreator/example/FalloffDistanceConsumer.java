package io.voxelcreator.example;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred analytic falloff ratios through the public engine JAR and explicit save/load. */
public final class FalloffDistanceConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]), Path.of(args[1]));
  }

  static void run(Path vectors, Path out) throws Exception {
    Files.createDirectories(out);
    var reference = ProjectIO.JSON.readTree(vectors.toFile());
    require(reference.path("formatVersion").asInt() == 1, "Falloff reference version");
    int projects = 0, samples = 0;
    for (var row : reference.path("cases"))
      for (String outside : List.of("CLAMP", "EXTEND")) {
        var project = project(row, outside);
        String before = ProjectIO.JSON.writeValueAsString(project);
        int dimension = row.path("dimension").asInt();
        double scale = Double.parseDouble(row.path("scaleHex").asText());
        double x = row.path("x").asDouble() * scale;
        double y = row.path("y").asDouble() * scale;
        double z = row.path("z").asDouble() * scale;
        double ratio = Double.parseDouble(row.path("ratioHex").asText());
        if (outside.equals("CLAMP")) ratio = Math.min(1, ratio);
        double expected = Double.parseDouble(row.path("aHex").asText()) * (1 - ratio);
        samples += replay(new CompiledProject(project), dimension, expected, x, y, z);
        var path = out.resolve(row.path("id").asText() + "-" + outside + ".pvproj.json");
        new ProjectIO().save(path, project);
        var loaded = new ProjectLoader().load(path);
        require(loaded.success(), "Falloff save/load rejected: " + loaded.diagnostics());
        samples += replay(new CompiledProject(loaded.project()), dimension, expected, x, y, z);
        require(
            before.equals(ProjectIO.JSON.writeValueAsString(project))
                && before.equals(ProjectIO.JSON.writeValueAsString(loaded.project())),
            "Falloff compilation or save/load changed authoring data");
        projects++;
      }
    require(
        reference.path("cases").size() == 20 && projects == 40 && samples == 240,
        "Unexpected falloff replay dimensions");
    Files.writeString(
        out.resolve("falloff-distance-engine.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "cases",
                20,
                "savedProjects",
                projects,
                "samples",
                samples,
                "samplingPaths",
                List.of("DIRECT", "GRID", "POINTS"),
                "projectDataUnchanged",
                true)));
    System.out.println("FALLOFF_DISTANCE_ENGINE_ONLY_PASS");
  }

  private static ProjectData project(JsonNode row, String outside) {
    var project = new ProjectData();
    var noise = new Noise();
    noise.id = "falloff";
    noise.dimension = row.path("dimension").asInt();
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", 0.0);
    var item = ProcessingRegistry.create("FALLOFF", "distance", noise.dimension);
    double scale = Double.parseDouble(row.path("scaleHex").asText());
    for (String key : List.of("domain", "metric", "axis"))
      item.parameters.put(key, row.path(key).deepCopy());
    for (String key : List.of("centerX", "centerY", "centerZ", "extent"))
      item.parameters.put(key, ProjectIO.JSON.valueToTree(row.path(key).asDouble() * scale));
    item.parameters.put("outside", ProjectIO.JSON.valueToTree(outside));
    item.inputs.put("a", Binding.constant(Double.parseDouble(row.path("aHex").asText())));
    noise.processing.add(item);
    noise.outputBinding = Binding.earlier(item.uuid);
    (noise.dimension == 2 ? project.noise2D : project.noise3D).add(noise);
    return project;
  }

  private static int replay(
      CompiledProject project, int dimension, double expected, double x, double y, double z) {
    var grid = new double[] {19, 19, 19};
    var points = new double[] {19, 19, 19};
    if (dimension == 2) {
      var sampler = project.noise2D("falloff", 17);
      equal(expected, sampler.sample(x, z));
      sampler.sampleGrid(x, z, 1, 1, 0, 0, grid, 1, 1, new CancellationToken());
      sampler.samplePoints(
          new double[] {x}, new double[] {z}, 0, 1, points, 1, new CancellationToken());
    } else {
      var sampler = project.noise3D("falloff", 17);
      equal(expected, sampler.sample(x, y, z));
      sampler.sampleGrid(x, y, z, 1, 1, 1, 0, 0, 0, grid, 1, 1, 1, new CancellationToken());
      sampler.samplePoints(
          new double[] {x},
          new double[] {y},
          new double[] {z},
          0,
          1,
          points,
          1,
          new CancellationToken());
    }
    equal(expected, grid[1]);
    equal(expected, points[1]);
    require(
        grid[0] == 19 && grid[2] == 19 && points[0] == 19 && points[2] == 19,
        "Falloff batch changed padding");
    return 3;
  }

  private static void equal(double expected, double actual) {
    require(
        Double.isFinite(actual) && Math.abs(expected - actual) <= 8 * Math.ulp(expected),
        "Falloff lost its analytic finite expectation");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
