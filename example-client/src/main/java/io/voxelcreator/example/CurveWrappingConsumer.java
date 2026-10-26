package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.DoubleUnaryOperator;

public final class CurveWrappingConsumer {
  public static void main(String[] args) throws Exception {
    var rows = ProjectIO.JSON.readTree(Path.of(args[0]).toFile()).path("vectors");
    var cache = new HashMap<String, DoubleUnaryOperator>();
    int samples = 0, fallback = 0, savedSamples = 0;
    for (var row : rows)
      for (int dimension : new int[] {2, 3}) {
        double lo = row.path("min").asDouble(), hi = row.path("max").asDouble();
        String mode = row.path("mode").asText();
        String key = dimension + ":" + lo + ":" + hi + ":" + mode;
        var sampler = cache.computeIfAbsent(key, ignored -> sampler(lo, hi, mode, dimension));
        verify(row, sampler.applyAsDouble(row.path("input").asDouble()));
        samples++;
        if (!row.path("legacyValid").asBoolean()) fallback++;
      }
    if (args.length > 1) {
      var loaded = new ProjectLoader().load(Path.of(args[1]));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var compiled = new CompiledProject(loaded.project());
      for (var row : rows)
        if (row.path("min").asDouble() == 1 && row.path("max").asDouble() == 2) {
          var sampler = compiled.noise2D(row.path("mode").asText().toLowerCase(Locale.ROOT), 0);
          verify(row, sampler.sample(row.path("input").asDouble(), 7));
          savedSamples++;
        }
    }
    if (args.length > 2)
      Files.writeString(
          Path.of(args[2]).resolve("curve-wrapping-engine.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "formatVersion",
                  1,
                  "vectors",
                  rows.size(),
                  "samples",
                  samples,
                  "fallbackSamples",
                  fallback,
                  "savedSamples",
                  savedSamples)));
    System.out.println(
        "CURVE_WRAPPING_ENGINE_ONLY_PASS "
            + samples
            + " 2D/3D samples / "
            + savedSamples
            + " saved-project samples");
  }

  private static void verify(com.fasterxml.jackson.databind.JsonNode row, double actual) {
    double expected = Double.parseDouble(row.path("expectedHex").asText());
    if (!Double.isFinite(actual) || actual < 0 || actual > 1)
      throw new AssertionError(row + " produced " + actual);
    if (row.path("legacyValid").asBoolean()) {
      if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual))
        throw new AssertionError(row + " changed historical bits: " + actual);
    } else if (Math.abs(expected - actual) > 2e-13)
      throw new AssertionError(row + " disagrees with rational reference: " + actual);
  }

  private static DoubleUnaryOperator sampler(double lo, double hi, String mode, int dimension) {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "curve";
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", 0.0);
    var item = ProcessingRegistry.create("CURVE", "curve");
    var input = Binding.constant(0);
    input.sourceKind = "Ambient Value";
    input.ambientKey = "x";
    item.inputs.put("a", input);
    var c = new Curve();
    c.min = lo;
    c.max = hi;
    c.outsideDomain = mode;
    c.points.getFirst().x = lo;
    c.points.getFirst().y = 0;
    c.points.getLast().x = hi;
    c.points.getLast().y = 1;
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(c));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    var compiled = new CompiledProject(p);
    if (dimension == 2) {
      var s = compiled.noise2D("curve", 0);
      return x -> s.sample(x, 7);
    }
    var s = compiled.noise3D("curve", 0);
    return x -> s.sample(x, -3, 7);
  }
}
