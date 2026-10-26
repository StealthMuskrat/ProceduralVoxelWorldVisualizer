package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Engine-JAR-only replay of independently calculated scalar manipulation vectors. */
public final class ScalarRemappingConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    double expected = Double.parseDouble(args[1]);
    equal(expected, new CompiledProject(loaded.project()).noise2D("remapping", 0).sample(2, 3));
    int count = 0;
    for (var v : ProjectIO.JSON.readTree(Path.of(args[2]).toFile()).path("vectors")) {
      var p = new ProjectData();
      var n = new Noise();
      n.id = "oracle";
      n.dimension = v.path("dimension").intValue();
      n.algorithm.key = "CONSTANT";
      (n.dimension == 2 ? p.noise2D : p.noise3D).add(n);
      var i = ProcessingRegistry.create(v.path("kind").textValue(), "operation", n.dimension);
      v.path("parameters")
          .fields()
          .forEachRemaining(e -> i.parameters.put(e.getKey(), e.getValue()));
      i.inputs.put("a", Binding.constant(v.path("a").doubleValue()));
      if (i.kind.equals("BLEND")) i.inputs.put("b", Binding.constant(v.path("b").doubleValue()));
      n.processing.add(i);
      n.outputBinding = Binding.earlier(i.uuid);
      double actual =
          new NoiseCompiler(p, 0)
              .compile(n.uuid)
              .sample(
                  v.path("x").doubleValue(), v.path("y").doubleValue(), v.path("z").doubleValue());
      equal(v.path("expected").doubleValue(), actual);
      count++;
    }
    if (count != 548) throw new AssertionError("Incomplete vector corpus " + count);
    if (args.length > 3)
      Files.writeString(
          Path.of(args[3]).resolve("remapping-engine-reference.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of("formatVersion", 1, "vectors", count, "fixtureValue", expected)));
    System.out.println(
        "SCALAR_REMAPPING_ENGINE_ONLY_PASS "
            + count
            + " independent vectors / saved project / reference generator / canonical field");
  }

  private static void equal(double expected, double actual) {
    if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1e-10)
      throw new AssertionError(expected + " != " + actual);
  }
}
