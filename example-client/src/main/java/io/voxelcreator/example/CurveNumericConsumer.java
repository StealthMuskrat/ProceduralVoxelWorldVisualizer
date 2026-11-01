package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Independent piecewise-linear values from the saved numeric-curve publication states. */
public final class CurveNumericConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    int samples = 0, cells = 0;
    for (String file : new String[] {"before", "applied", "undone", "redone"}) {
      var loaded = new ProjectLoader().load(out.resolve(file + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var compiled = new CompiledProject(loaded.project());
      var field = compiled.noise2D("numeric-curve", 0);
      boolean changed = file.equals("applied") || file.equals("redone");
      for (int ix = -8; ix <= 40; ix++)
        for (int z : new int[] {-3, 0, 7}) {
          double x = ix / 32.;
          double expected =
              x <= 0 ? 0 : x >= 1 ? 1 : !changed ? x : x <= .5 ? 1.5 * x : .75 + .5 * (x - .5);
          double actual = field.sample(x, z);
          if (!Double.isFinite(actual) || Math.abs(actual - expected) > 2e-12)
            throw new AssertionError(file + " / " + x);
          samples++;
        }
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int c : new int[] {-1, 0}) {
              var chunk = world.generateChunk(new ChunkPos(c, c, c));
              for (int z = 0; z < root; z++)
                for (int y = 0; y < root; y++)
                  for (int x = 0; x < root; x++) {
                    if (chunk.getVoxelTypeId(x, y, z) != 0)
                      throw new AssertionError("Numeric curve populated unused World");
                    cells++;
                  }
            }
          }
      }
    }
    if (samples != 588 || cells != 576) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Curve numeric engine-only PASS; 588 analytical curve samples and 576 EMPTY cells");
  }
}
