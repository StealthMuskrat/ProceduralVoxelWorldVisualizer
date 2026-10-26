package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;

public final class CurveGestureConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var sampler = new CompiledProject(loaded.project()).noise2D("curve", 0);
    int count = 0;
    for (int ix = -8; ix <= 40; ix++)
      for (int z : new int[] {-3, 0, 7}) {
        double x = ix / 32.;
        double expected =
            x <= 0 ? 0 : x >= 1 ? 1 : x <= .25 ? 1.4 * x : x <= .75 ? x + .1 : .85 + .6 * (x - .75);
        double actual = sampler.sample(x, z);
        if (Math.abs(actual - expected) > 2e-12)
          throw new AssertionError(x + ": " + actual + " != " + expected);
        count++;
      }
    System.out.println(
        "CURVE_GESTURE_ENGINE_ONLY_PASS " + count + " independent piecewise-linear points");
  }
}
