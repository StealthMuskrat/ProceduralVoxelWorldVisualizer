package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Portable engine-JAR-only warp oracle and saved native authoring replay. */
public final class WarpConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var compiled = new CompiledProject(loaded.project());
    equal(Double.parseDouble(args[1]), compiled.noise2D("warp-sample", 17).sample(2, 3));
    equal(55, compiled.noise2D("domain-warp", 17).sample(2, 3));
    equal(403, compiled.noise3D("warp-sample3", 17).sample(2, -1, 3));
    equal(403, compiled.noise3D("domain-warp3", 17).sample(2, -1, 3));
    int vectors = 0, cases = 0;
    for (var vector : ProjectIO.JSON.readTree(Path.of(args[2]).toFile()).path("vectors")) {
      vectors++;
      for (boolean pre : new boolean[] {false, true}) {
        var p = new ProjectData();
        int d = vector.path("dimension").intValue();
        var n = noise(p, "oracle", d, d == 2 ? "x+100*z" : "x+10*y+100*z");
        var dx = noise(p, "displacement-x", d, "z+.5*y");
        var dy = noise(p, "displacement-y", d, "x-z");
        var dz = noise(p, "displacement-z", d, "x-.25*y");
        var i = ProcessingRegistry.create("WARP_SAMPLE", "warp", d);
        if (pre) {
          i.kind = "DOMAIN_WARP";
          i.resultType = "Coordinates" + d + "D";
          i.inputs.clear();
          n.coordinateTransforms.add(i);
        } else {
          i.inputs.put("field", Binding.earlierField(n.processing.getFirst().uuid));
          n.processing.add(i);
          n.outputBinding = Binding.earlier(i.uuid);
        }
        i.inputs.put("displacementX", Binding.noiseField(dx.uuid));
        if (d == 3) i.inputs.put("displacementY", Binding.noiseField(dy.uuid));
        i.inputs.put("displacementZ", Binding.noiseField(dz.uuid));
        for (var entry :
            Map.of(
                    "warpMode",
                    "mode",
                    "warpOrder",
                    "order",
                    "warpOctaves",
                    "octaves",
                    "frequency",
                    "frequency",
                    "amplitude",
                    "amplitude")
                .entrySet()) i.parameters.put(entry.getKey(), vector.path(entry.getValue()));
        var xyz = vector.path("input");
        equal(
            vector.path("result").doubleValue(),
            new NoiseCompiler(p, 17)
                .compile(n.uuid)
                .sample(
                    xyz.get(0).doubleValue(), xyz.get(1).doubleValue(), xyz.get(2).doubleValue()));
        cases++;
      }
    }
    if (vectors != 432 || cases != 864) throw new AssertionError("Incomplete warp vectors");
    if (args.length > 3)
      Files.writeString(
          Path.of(args[3]).resolve("warp-engine-reference.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "formatVersion",
                  1,
                  "vectors",
                  vectors,
                  "stageCases",
                  cases,
                  "nativeValue",
                  Double.parseDouble(args[1]))));
    System.out.println(
        "WARP_ENGINE_ONLY_PASS 432 independent vectors / 864 coordinate and processing cases /"
            + " saved native project / 2D-3D sources");
  }

  private static Noise noise(ProjectData p, String id, int d, String text) {
    var n = new Noise();
    n.id = id;
    n.dimension = d;
    n.algorithm.key = "CONSTANT";
    (d == 2 ? p.noise2D : p.noise3D).add(n);
    var expression = ProcessingRegistry.create("EXPRESSION", id + "-expression", d);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree(text));
    n.processing.add(expression);
    n.outputBinding = Binding.earlier(expression.uuid);
    return n;
  }

  private static void equal(double expected, double actual) {
    if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1e-9)
      throw new AssertionError(expected + " != " + actual);
  }
}
