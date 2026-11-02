package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Engine-only field and exact-World replay of the rectangular visualization fixture. */
public final class RectangularPreviewConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    int samples = 0, cells = 0;
    for (String name : new String[] {"before", "after"}) {
      var loaded = new ProjectLoader().load(out.resolve(name + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var compiled = new CompiledProject(loaded.project());
      var solid = compiled.noise3D("solid", 0);
      var plane = compiled.noise3D("plane", 0);
      for (int y = 0; y < 5; y++)
        for (int z = 0; z < 7; z++)
          for (int x = 0; x < 3; x++) {
            if (solid.sample(8 + x, -6 + y, -11 + z) != 1
                || plane.sample(8 + x, -6 + y, -11 + z) != x - 1.25)
              throw new AssertionError("Preview extents changed canonical fields");
            samples += 2;
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
                      throw new AssertionError("Fixed analysis populated an unauthored World");
                    cells++;
                  }
            }
          }
      }
    }
    if (samples != 420 || cells != 288) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Rectangular preview engine-only PASS; 420 analytic field values and 288 EMPTY cells");
  }
}
