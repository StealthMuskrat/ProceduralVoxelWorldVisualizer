package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;

/** Deferred headless check: future creation defaults cannot change an existing authored source. */
public final class MetadataApplyConsumer {
  public static void main(String[] args) throws Exception {
    int count = 0;
    for (String mode : new String[] {
        "apply", "cancel", "escape-ready", "revert-ready", "edit", "active", "hidden", "close", "retry"
    }) {
      var load = new ProjectLoader().load(Path.of(args[0]).resolve(mode).resolve("created.json"));
      if (!load.success()) throw new AssertionError(load.diagnostics());
      var compiled = new CompiledProject(load.project());
      double future = mode.equals("apply") || mode.equals("retry") ? .75 : .25;
      for (int x : new int[] {-17, -2, -1, 0, 1, 2, 17})
        for (int z : new int[] {-3, 0, 7}) {
          if (compiled.noise2D("existing", 0).sample(x, z) != .125
              || compiled.noise2D("future", 0).sample(x, z) != future)
            throw new AssertionError(mode + ": authored/future values changed");
          count += 2;
        }
    }
    System.out.println("BACKGROUND_PROFILE_ENGINE_ONLY_PASS " + count + " independent constant observations");
  }
}
