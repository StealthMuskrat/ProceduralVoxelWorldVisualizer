package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;

public final class ExpressionRecoveryConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var sampler = new CompiledProject(loaded.project()).noise2D("formula", 0);
    int count = 0;
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++) {
        if (sampler.sample(x, z) != x + 4)
          throw new AssertionError("Repaired formula at " + x + "," + z);
        count++;
      }
    System.out.println("EXPRESSION_RECOVERY_ENGINE_ONLY_PASS " + count + " independent points");
  }
}
