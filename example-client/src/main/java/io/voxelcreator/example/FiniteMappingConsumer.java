package io.voxelcreator.example;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred exact affine vectors through public direct/grid/point and World persistence paths. */
public final class FiniteMappingConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]), Path.of(args[1]));
  }

  static void run(Path vectors, Path out) throws Exception {
    Files.createDirectories(out);
    var reference = ProjectIO.JSON.readTree(vectors.toFile());
    require(reference.path("formatVersion").asInt() == 1, "Mapping vector version");
    int projects = 0, samples = 0;
    for (var row : reference.path("cases"))
      for (int dimension : new int[] {2, 3})
        for (boolean processing :
            row.path("operation").asText().equals("remap")
                ? List.of(false, true)
                : List.of(false)) {
          var p = project(row, dimension, processing);
          var values = ProjectIO.JSON.valueToTree(p);
          double expected = number(row, "expected");
          samples += replay(new CompiledProject(p), dimension, expected);
          var file =
              out.resolve(
                  row.path("id").asText()
                      + "-"
                      + dimension
                      + "-"
                      + (processing ? "REMAP" : "EXPRESSION")
                      + ".pvproj.json");
          new ProjectIO().save(file, p);
          byte[] bytes = Files.readAllBytes(file);
          var loaded = new ProjectLoader().load(file);
          require(loaded.success(), "Mapping save/load rejected: " + loaded.diagnostics());
          samples += replay(new CompiledProject(loaded.project()), dimension, expected);
          require(
              values.equals(ProjectIO.JSON.valueToTree(p))
                  && values.equals(ProjectIO.JSON.valueToTree(loaded.project()))
                  && Arrays.equals(bytes, Files.readAllBytes(file)),
              "Mapping sampling/persistence changed authoring values");
          projects++;
        }
    require(
        reference.path("cases").size() == 24 && projects == 72 && samples == 432,
        "Unexpected mapping replay dimensions");
    int cells = world(reference.path("cases").get(8), out);
    require(cells == 420, "Unexpected mapping World replay dimensions");
    Files.writeString(
        out.resolve("finite-mapping-engine.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "cases",
                24,
                "projects",
                projects,
                "samples",
                samples,
                "worldCells",
                cells,
                "samplingPaths",
                List.of("DIRECT", "GRID", "POINTS"),
                "rawBits",
                true,
                "projectDataUnchanged",
                true)));
    System.out.println(
        "FINITE_MAPPING_ENGINE_ONLY_PASS 432 exact fields / 420 threshold World cells");
  }

  private static double number(JsonNode row, String key) {
    return Double.parseDouble(row.path(key + "Hex").asText());
  }

  private static ProjectData project(JsonNode row, int dimension, boolean processing) {
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
      var keys = new ArrayList<>(List.of("inputMin", "inputMax", "value"));
      if (row.path("operation").asText().equals("remap"))
        keys.addAll(List.of("outputMin", "outputMax"));
      for (String key : keys) item.inputs.put(key, Binding.constant(number(row, key)));
      String formula =
          switch (row.path("operation").asText()) {
            case "remap" -> "remap(value,inputMin,inputMax,outputMin,outputMax)";
            case "inverseLerp" -> "inverseLerp(inputMin,inputMax,value)";
            default -> "smoothstep(inputMin,inputMax,value)";
          };
      item.parameters.put("expression", ProjectIO.JSON.valueToTree(formula));
    }
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    return p;
  }

  private static int replay(CompiledProject project, int dimension, double expected) {
    var grid = new double[] {19, 19, 19};
    var points = new double[] {19, 19, 19};
    if (dimension == 2) {
      var sampler = project.noise2D("mapping", 17);
      bits(expected, sampler.sample(-3, 5));
      sampler.sampleGrid(-3, 5, 1, 1, 0, 0, grid, 1, 1, new CancellationToken());
      sampler.samplePoints(
          new double[] {-3}, new double[] {5}, 0, 1, points, 1, new CancellationToken());
    } else {
      var sampler = project.noise3D("mapping", 17);
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
        "Mapping batch overwrote padding");
    return 3;
  }

  private static int world(JsonNode row, Path out) throws Exception {
    var p = project(row, 2, true);
    p.project.minY = -1;
    p.project.maxY = 1;
    var type = new VoxelType();
    type.id = "mapped";
    p.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "mapped";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    layer.defaults.threshold = .25;
    var operation = new Operation();
    operation.id = "mapping";
    operation.interpretation = "SELECTION";
    operation.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
    var values = ProjectIO.JSON.valueToTree(p);
    var file = out.resolve("mapping-threshold-world.pvproj.json");
    new ProjectIO().save(file, p);
    byte[] bytes = Files.readAllBytes(file);
    var loaded = new ProjectLoader().load(file);
    require(loaded.success(), "Mapped World save/load failed");
    int cells = 0;
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (var project : List.of(p, loaded.project())) {
        var compiled = new CompiledProject(project);
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int z = -1; z <= 1; z++)
              for (int y = -2; y <= 2; y++)
                for (int x = -3; x <= 3; x++) {
                  int expected = y >= -1 && y <= 1 ? 1 : 0;
                  require(
                      world.getVoxelTypeId(new BlockPos(x, y, z)) == expected,
                      "Recovered mapping changed exact threshold/Y selection");
                  cells++;
                }
          }
      }
    }
    require(
        values.equals(ProjectIO.JSON.valueToTree(p))
            && values.equals(ProjectIO.JSON.valueToTree(loaded.project()))
            && Arrays.equals(bytes, Files.readAllBytes(file)),
        "Mapped World replay changed model/file values");
    return cells;
  }

  private static void bits(double expected, double actual) {
    require(
        Double.isFinite(actual)
            && Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual),
        "Mapping differs from hand-specified exact expectation: "
            + Double.toHexString(expected)
            + " != "
            + Double.toHexString(actual));
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
