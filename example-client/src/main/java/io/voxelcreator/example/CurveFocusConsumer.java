package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;

/** Deferred public engine-JAR replay of a curve repaired through the native editor. */
public final class CurveFocusConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var sampler = new CompiledProject(loaded.project()).noise2D("curve-focus", 0);
    int count = 0;
    for (int i = -16; i <= 32; i++) {
      double x = i / 16.0;
      double expected = x <= 0 ? 0 : x >= 1 ? 1 : x <= .5 ? 1.5 * x : .5 * x + .5;
      for (int z : new int[] {-37, 0, 41}) {
        if (sampler.sample(x, z) != expected)
          throw new AssertionError("Curve result at " + x + "," + z);
        count++;
      }
    }
    System.out.println(
        "CURVE_FOCUS_ENGINE_ONLY_PASS " + count + " exact piecewise-linear observations");
  }
}
