package io.voxelcreator.example;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Algorithm;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Engine-only independent C++/Python reference comparison and canonical Java bit digests. */
public final class AlgorithmReferenceConsumer {
  public static void main(String[] args) throws Exception {
    if (args.length != 3 && args.length != 4)
      throw new IllegalArgumentException("Pass C++ TSV, analytic JSON, output directory");
    Path output = Path.of(args[2]);
    Files.createDirectories(output);
    var digests = new TreeMap<String, MessageDigest>();
    int count = 0, exact = 0;
    double maximumError = 0;
    var families = new TreeMap<String, Integer>();
    var lines = Files.readAllLines(Path.of(args[0]));
    require(lines.getFirst().startsWith("algorithm\tdimension\tseedHex"), "C++ header");
    for (String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) continue;
      var v = line.split("\t");
      require(v.length == 16, "C++ row shape");
      var a = new Algorithm();
      a.key = v[0];
      a.parameters.clear();
      String[] names = {
        "frequency",
        "fractal",
        "octaves",
        "lacunarity",
        "gain",
        "pingPongStrength",
        "cellularDistance",
        "cellularReturn",
        "jitter"
      };
      for (int i = 0; i < names.length; i++)
        a.parameters.put(names[i], Double.parseDouble(v[i + 6]));
      int dimension = Integer.parseInt(v[1]);
      long seed = Long.parseUnsignedLong(v[2], 16);
      double x = Double.parseDouble(v[3]),
          y = Double.parseDouble(v[4]),
          z = Double.parseDouble(v[5]);
      double expected = Double.longBitsToDouble(Long.parseUnsignedLong(v[15], 16));
      double value = NoiseAlgorithms.compile(a, dimension, seed).sample(x, y, z);
      double error = StrictMath.abs(value - expected);
      maximumError = StrictMath.max(maximumError, error);
      require(
          Double.isFinite(value) && error <= 2e-6 + 2e-6 * StrictMath.abs(expected),
          "C++ mismatch row "
              + count
              + " "
              + a.key
              + " "
              + dimension
              + " expected="
              + expected
              + " actual="
              + value
              + " error="
              + error);
      if (Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(expected)) exact++;
      update(digests, a.key + "/" + dimension, value);
      families.merge(a.key + "/" + dimension, 1, Integer::sum);
      count++;
    }
    var analytic = ProjectIO.JSON.readTree(Path.of(args[1]).toFile());
    int analyticCount = 0, seedCount = 0;
    for (var vector : analytic.path("vectors")) {
      var a = new Algorithm();
      a.key = vector.path("algorithm").asText();
      a.parameters.clear();
      vector
          .path("parameters")
          .fields()
          .forEachRemaining(e -> a.parameters.put(e.getKey(), e.getValue().doubleValue()));
      int dimension = vector.path("dimension").intValue();
      double value =
          NoiseAlgorithms.compile(
                  a, dimension, Long.parseUnsignedLong(vector.path("seedHex").asText(), 16))
              .sample(
                  vector.path("x").doubleValue(),
                  vector.path("y").doubleValue(),
                  vector.path("z").doubleValue());
      require(
          Double.doubleToRawLongBits(value)
              == Long.parseUnsignedLong(vector.path("valueBits").asText(), 16),
          "Analytic/hash bits " + a.key + " row " + analyticCount);
      update(digests, a.key + "/" + dimension, value);
      analyticCount++;
    }
    for (var vector : analytic.path("seedVectors")) {
      long value =
          CoordinateHash.seed(
              Long.parseUnsignedLong(vector.path("runtimeHex").asText(), 16),
              UUID.fromString(vector.path("uuid").asText()),
              Long.parseUnsignedLong(vector.path("offsetHex").asText(), 16));
      require(
          value == Long.parseUnsignedLong(vector.path("valueHex").asText(), 16),
          "Independent seed vector " + seedCount);
      seedCount++;
    }
    require(
        count == 3264 && analyticCount == 240 && seedCount == 32, "Incomplete independent corpus");
    var hashes = new TreeMap<String, String>();
    for (var entry : digests.entrySet())
      hashes.put(entry.getKey(), HexFormat.of().formatHex(entry.getValue().digest()));
    require(hashes.size() == 20, "Missing source/dimension bit digest");
    Files.writeString(
        output.resolve("algorithm-jvm-bits.json"),
        ProjectIO.JSON.writeValueAsString(hashes) + "\n");
    var report = new LinkedHashMap<String, Object>();
    report.put("formatVersion", 1);
    report.put("cppCases", count);
    report.put("exactCppBits", exact);
    report.put("maximumAbsoluteError", maximumError);
    report.put("absoluteTolerance", 2e-6);
    report.put("relativeTolerance", 2e-6);
    report.put("analyticCases", analyticCount);
    report.put("seedCases", seedCount);
    report.put("families", families);
    Files.writeString(
        output.resolve("algorithm-reference-report.json"),
        ProjectIO.JSON.writeValueAsString(report) + "\n");
    if (args.length == 4) {
      var load = new io.voxelcreator.api.ProjectLoader().load(Path.of(args[3]));
      require(load.success(), load.diagnostics().toString());
      var p = load.project();
      var n = p.noise2D.getFirst();
      require(
          n.algorithm.parameters.get("fractal") == 3
              && n.algorithm.parameters.get("pingPongStrength") == 3.5,
          "Saved native source settings");
      long seed = CoordinateHash.seed(p.project.seed, n.uuid, n.seedOffset);
      var direct = NoiseAlgorithms.compile(n.algorithm, 2, seed);
      var sampler = new io.voxelcreator.api.CompiledProject(p).noise2D(n.uuid, p.project.seed);
      for (double x : new double[] {-32, -.5, 0, 31.25})
        require(
            Double.doubleToRawLongBits(direct.sample(x, 0, 7.5))
                == Double.doubleToRawLongBits(sampler.sample(x, 7.5)),
            "Native/headless source mismatch");
    }
    System.out.println(
        "ALGORITHM_REFERENCE_ENGINE_ONLY_PASS "
            + count
            + " independent C++ cases / "
            + exact
            + " exact C++ bit matches / max error "
            + maximumError
            + " / "
            + analyticCount
            + " exact Python analytic-hash / "
            + seedCount
            + " exact 64-bit seed vectors / 20 JVM bit digests");
  }

  private static void update(Map<String, MessageDigest> digests, String key, double value)
      throws Exception {
    var digest = digests.get(key);
    if (digest == null) {
      digest = MessageDigest.getInstance("SHA-256");
      digests.put(key, digest);
    }
    digest.update(ByteBuffer.allocate(8).putLong(Double.doubleToRawLongBits(value)).array());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
