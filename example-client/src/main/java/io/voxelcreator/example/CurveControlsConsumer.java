package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;

public final class CurveControlsConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var sampler = new CompiledProject(loaded.project()).noise2D("curve-controls", 0);
    int count = 0;
    for (int i = -56; i <= 64; i++)
      for (int z : new int[] {-3, 0, 7}) {
        double x = i / 8.,
            phase = (x + 1) - 6 * StrictMath.floor((x + 1) / 6),
            mapped = -1 + (phase <= 3 ? phase : 6 - phase);
        double expected = mapped <= .5 ? .5 * (mapped + 1) : .75 + (mapped - .5) / 6;
        double actual = sampler.sample(x, z);
        if (Math.abs(actual - expected) > 2e-12)
          throw new AssertionError(x + ": " + actual + " != " + expected);
        count++;
      }
    System.out.println(
        "CURVE_CONTROLS_ENGINE_ONLY_PASS "
            + count
            + " independent mirrored piecewise-linear samples");
  }
}
