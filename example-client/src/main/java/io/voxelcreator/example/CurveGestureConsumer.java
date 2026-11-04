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
    if (args.length > 1) backgroundApply(Path.of(args[1]));
  }

  private static void backgroundApply(Path root) throws Exception {
    int observations = 0;
    for (String mode : new String[] {
        "apply", "cancel-apply", "escape-ready", "cancel-ready", "cancel", "retry", "edit", "stale", "close"
    }) {
      var beforePath = root.resolve(mode).resolve("before.json");
      var afterPath = root.resolve(mode).resolve("after.json");
      var before = new ProjectLoader().load(beforePath);
      var after = new ProjectLoader().load(afterPath);
      if (!before.success() || !after.success())
        throw new AssertionError(mode + ": rejected curve saved state");
      boolean staged = mode.equals("apply") || mode.equals("retry");
      var expected = io.voxelcreator.model.ProjectIO.JSON.valueToTree(before.project());
      if (staged) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("noise2D").get(0))
            .put("id", "private");
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("noise2D").get(0)
            .path("processing").get(0).path("parameters").path("curve").path("points").get(1))
            .put("y", 3.);
      }
      if (!expected.equals(io.voxelcreator.model.ProjectIO.JSON.valueToTree(after.project())))
        throw new AssertionError(mode + ": unrelated saved values changed");
      for (boolean finalState : new boolean[] {false, true}) {
        var loaded = finalState ? after : before;
        double factor = finalState && staged ? 3 : 1;
        var sampler = new CompiledProject(loaded.project()).noise2D(
            finalState && staged ? "private" : "curve", 0);
        for (double x : new double[] {-1, 0, .25, .5, .75, 1, 2})
          for (int z : new int[] {-3, 0, 7}) {
            double value = sampler.sample(x, z);
            double wanted = factor * Math.max(0, Math.min(1, x));
            if (value != wanted)
              throw new AssertionError(mode + ": " + x + ": " + value + " != " + wanted);
            observations++;
          }
      }
    }
    System.out.println("BACKGROUND_CURVE_APPLY_ENGINE_ONLY_PASS " + observations
        + " independent clamped linear observations across eighteen saved projects");
  }

}
