package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;

public final class CurveNavigationConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var sampler = new CompiledProject(loaded.project()).noise2D("navigation", 0);
    int count = 0;
    for (int i = -32; i <= 32; i++)
      for (int z : new int[] {-3, 0, 7}) {
        double x = i / 8., expected = Math.max(-2, Math.min(2, x)), actual = sampler.sample(x, z);
        if (Math.abs(expected - actual) > 2e-12)
          throw new AssertionError(x + ": " + actual + " != " + expected);
        count++;
      }
    System.out.println(
        "CURVE_NAVIGATION_ENGINE_ONLY_PASS " + count + " unchanged independent points");
  }
}
