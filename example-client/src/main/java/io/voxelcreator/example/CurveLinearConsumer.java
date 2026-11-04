package io.voxelcreator.example;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred public curve vectors across direct/batch sampling, persistence and signed World cells.
 */
public final class CurveLinearConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]), Path.of(args[1]));
  }

  static void run(Path vectors, Path out) throws Exception {
    Files.createDirectories(out);
    var reference = ProjectIO.JSON.readTree(vectors.toFile());
    require(
        reference.path("formatVersion").asInt() == 1
            && reference
                .path("arithmeticPolicy")
                .asText()
                .equals(CurveModes.LINEAR_ARITHMETIC_POLICY),
        "Curve linear reference policy mismatch");
    int projects = 0, samples = 0;
    for (var row : reference.path("cases"))
      for (int dimension : new int[] {2, 3}) {
        var p = project(row, dimension);
        var values = ProjectIO.JSON.valueToTree(p);
        samples += replay(new CompiledProject(p), dimension, row);
        var file = out.resolve(row.path("id").asText() + "-" + dimension + ".pvproj.json");
        new ProjectIO().save(file, p);
        byte[] bytes = Files.readAllBytes(file);
        var loaded = new ProjectLoader().load(file);
        require(loaded.success(), "Curve linear save/load rejected: " + loaded.diagnostics());
        samples += replay(new CompiledProject(loaded.project()), dimension, row);
        require(
            values.equals(ProjectIO.JSON.valueToTree(p))
                && values.equals(ProjectIO.JSON.valueToTree(loaded.project()))
                && Arrays.equals(bytes, Files.readAllBytes(file)),
            "Curve sampling/persistence changed authoring values or saved bytes");
        projects++;
      }
    int cells = world(reference.path("cases").get(0), out);
    require(
        reference.path("cases").size() == 17 && projects == 34 && samples == 900 && cells == 300,
        "Unexpected curve linear replay dimensions");
    Files.writeString(
        out.resolve("curve-linear-engine.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "arithmeticPolicy",
                CurveModes.LINEAR_ARITHMETIC_POLICY,
                "cases",
                17,
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
        "CURVE_LINEAR_ENGINE_ONLY_PASS 900 exact fields / 300 threshold World cells");
  }

  private static double number(JsonNode row, String key) {
    return Double.parseDouble(row.path(key + "Hex").asText());
  }

  private static ProjectData project(JsonNode row, int dimension) {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "curve";
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 0.);
    var item = ProcessingRegistry.create("CURVE", "response", dimension);
    var input = Binding.constant(0);
    input.sourceKind = "Ambient Value";
    input.ambientKey = "x";
    item.inputs.put("a", input);
    var curve = new Curve();
    curve.min = number(row, "min");
    curve.max = number(row, "max");
    curve.outsideDomain = row.path("outside").asText();
    var first = curve.points.getFirst();
    var last = curve.points.getLast();
    first.x = curve.min;
    first.y = number(row, "y0");
    last.x = curve.max;
    last.y = number(row, "y1");
    first.segmentInterpolation = row.path("interpolation").asText();
    if (first.segmentInterpolation.equals("BEZIER"))
      curve.points.forEach(point -> point.handleMode = "FREE");
    first.outTangent = number(row, "outTangent");
    last.inTangent = number(row, "inTangent");
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    return p;
  }

  private static int replay(CompiledProject project, int dimension, JsonNode row) {
    int samples = 0;
    // Resolve the public sampler once; each vector also exercises offset batch destinations.
    var sampler2 = dimension == 2 ? project.noise2D("curve", 17) : null;
    var sampler3 = dimension == 3 ? project.noise3D("curve", 17) : null;
    for (var sample : row.path("samples")) {
      double x = number(sample, "x"), expected = number(sample, "expected");
      var grid = new double[] {19, 19, 19};
      var points = new double[] {19, 19, 19};
      if (dimension == 2) {
        bits(expected, sampler2.sample(x, 7));
        sampler2.sampleGrid(x, 7, 1, 1, 0, 0, grid, 1, 1, new CancellationToken());
        sampler2.samplePoints(
            new double[] {x}, new double[] {7}, 0, 1, points, 1, new CancellationToken());
      } else {
        bits(expected, sampler3.sample(x, -3, 7));
        sampler3.sampleGrid(x, -3, 7, 1, 1, 1, 0, 0, 0, grid, 1, 1, 1, new CancellationToken());
        sampler3.samplePoints(
            new double[] {x},
            new double[] {-3},
            new double[] {7},
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
          "Curve batch overwrote destination padding");
      samples += 3;
    }
    return samples;
  }

  private static int world(JsonNode row, Path out) throws Exception {
    var p = project(row, 2);
    p.project.minY = -1;
    p.project.maxY = 1;
    var type = new VoxelType();
    type.id = "curved";
    p.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "curved";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    layer.defaults.threshold = 0;
    var operation = new Operation();
    operation.id = "curve";
    operation.interpretation = "SELECTION";
    operation.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
    var values = ProjectIO.JSON.valueToTree(p);
    var file = out.resolve("curve-threshold-world.pvproj.json");
    new ProjectIO().save(file, p);
    byte[] bytes = Files.readAllBytes(file);
    var loaded = new ProjectLoader().load(file);
    require(loaded.success(), "Curve threshold World save/load failed");
    int cells = 0;
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (var document : List.of(p, loaded.project())) {
        var compiled = new CompiledProject(document);
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int z = -1; z <= 1; z++)
              for (int y = -2; y <= 2; y++)
                for (int x = -2; x <= 2; x++) {
                  int expected = x >= 1 && y >= -1 && y <= 1 ? 1 : 0;
                  require(
                      world.getVoxelTypeId(new BlockPos(x, y, z)) == expected,
                      "Curve changed exact signed-coordinate threshold/Y selection");
                  cells++;
                }
          }
      }
    }
    require(
        values.equals(ProjectIO.JSON.valueToTree(p))
            && values.equals(ProjectIO.JSON.valueToTree(loaded.project()))
            && Arrays.equals(bytes, Files.readAllBytes(file)),
        "Curve World replay changed authoring values or saved bytes");
    return cells;
  }

  private static void bits(double expected, double actual) {
    require(
        Double.isFinite(actual)
            && Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual),
        "Curve differs from hand-specified expectation: "
            + Double.toHexString(expected)
            + " != "
            + Double.toHexString(actual));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
