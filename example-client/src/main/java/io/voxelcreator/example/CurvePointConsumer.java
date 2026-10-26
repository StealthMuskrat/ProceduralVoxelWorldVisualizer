package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Independent Bernstein replay; does not call ScalarCurve for expected values. */
public final class CurvePointConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var compiled = new CompiledProject(loaded.project());
    int count = 0;
    for (String name : List.of("aligned", "free", "insert")) {
      var n =
          loaded.project().noise2D.stream()
              .filter(v -> v.id.equals(name))
              .findFirst()
              .orElseThrow();
      var curve =
          ProjectIO.JSON.convertValue(n.processing.getFirst().parameters.get("curve"), Curve.class);
      var sampler = compiled.noise2D(name, 0);
      for (int i = -8; i <= 40; i++)
        for (int z : new int[] {-3, 0, 7}) {
          double x = i / 32.;
          double expected =
              name.equals("insert") ? Math.max(0, Math.min(1, x)) : bernstein(curve, x);
          double actual = sampler.sample(x, z);
          if (Math.abs(actual - expected) > 2e-12)
            throw new AssertionError(name + " at " + x + ": " + actual + " != " + expected);
          count++;
        }
    }
    System.out.println(
        "CURVE_POINT_ENGINE_ONLY_PASS " + count + " independent Bernstein/linear points");
  }

  private static double bernstein(Curve c, double x) {
    if (x <= c.min) return c.points.getFirst().y;
    if (x >= c.max) return c.points.getLast().y;
    for (int i = 0; i < c.points.size() - 1; i++) {
      var a = c.points.get(i);
      var b = c.points.get(i + 1);
      if (x <= b.x) {
        double dx = b.x - a.x, t = (x - a.x) / dx, u = 1 - t;
        return u * u * u * a.y
            + 3 * u * u * t * (a.y + dx * a.outTangent / 3)
            + 3 * u * t * t * (b.y - dx * b.inTangent / 3)
            + t * t * t * b.y;
      }
    }
    throw new AssertionError("Missing segment");
  }
}
