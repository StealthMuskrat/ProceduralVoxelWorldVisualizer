package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Headless canonical field replay; volume encoding and rendering never enter the engine API. */
public final class RectangularVolumeConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    int samples = 0, cells = 0;
    for (String name : new String[] {"before", "after"}) {
      var loaded = new ProjectLoader().load(out.resolve(name + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var compiled = new CompiledProject(loaded.project());
      var field = compiled.noise3D("affine-volume", 0);
      for (int z = 0; z < 7; z++)
        for (int y = 0; y < 5; y++)
          for (int x = 0; x < 3; x++) {
            double wx = 8.75 + x + .5, wy = -5.5 + y + .5, wz = -11 + z + .5;
            if (field.sample(wx, wy, wz) != wx + 10 * wy + 100 * wz)
              throw new AssertionError("Volume extents altered canonical values");
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
                      throw new AssertionError("Volume populated an unauthored World");
                    cells++;
                  }
            }
          }
      }
    }
    if (samples != 210 || cells != 288) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Rectangular volume engine-only PASS; 210 analytic field samples and 288 EMPTY cells");
  }
}
