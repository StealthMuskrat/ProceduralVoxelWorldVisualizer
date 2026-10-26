package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.EvaluationFailure;
import java.nio.file.*;

public final class CurvePreviewConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var n = loaded.project().noise2D.getFirst();
    var item = n.processing.getFirst();
    var sampler = new CompiledProject(loaded.project()).noise2D("preview-recovery", 0);
    int finite = 0, failures = 0;
    for (int i = -16; i <= 16; i++)
      for (int z : new int[] {-3, 0, 7}) {
        double x = i / 16., actual = sampler.sample(x, z);
        if (!Double.isFinite(actual) || Math.abs(actual / 1e308 - x) > 2e-14)
          throw new AssertionError("Unchanged raw extension at " + x);
        finite++;
      }
    for (int x : new int[] {-4, 4})
      for (int z : new int[] {-3, 0, 7}) {
        try {
          sampler.sample(x, z);
          throw new AssertionError("Overflow was hidden");
        } catch (EvaluationFailure expected) {
          if (!n.uuid.equals(expected.resourceUuid)
              || !item.uuid.equals(expected.itemUuid)
              || expected.x != x
              || expected.z != z) throw new AssertionError("Wrong numerical boundary", expected);
          failures++;
        }
      }
    System.out.println(
        "CURVE_PREVIEW_ENGINE_ONLY_PASS "
            + finite
            + " raw finite vectors / "
            + failures
            + " unchanged numerical boundaries");
  }
}
