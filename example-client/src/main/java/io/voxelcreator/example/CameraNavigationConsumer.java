package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Engine-only replay of the committed project across native camera and Inspector navigation. */
public final class CameraNavigationConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    int samples = 0, cells = 0;
    for (String name : new String[] {"orbit-before", "orbit-project"}) {
      var loaded = new ProjectLoader().load(out.resolve(name + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var compiled = new CompiledProject(loaded.project());
      for (int x = -4; x <= 4; x++)
        for (int z = -4; z <= 4; z++) {
          if (compiled.noise3D("orbit-constant", 0).sample(x, -x, z) != 1)
            throw new AssertionError("Camera navigation changed the constant field");
          samples++;
        }
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int c : new int[] {-1, 0})
              for (int chunkY : new int[] {-1, 0}) {
                var chunk = world.generateChunk(new ChunkPos(c, chunkY, c));
                for (int z = 0; z < root; z++)
                  for (int y = 0; y < root; y++)
                    for (int x = 0; x < root; x++) {
                      if (chunk.getVoxelTypeId(x, y, z) != (chunkY < 0 ? 0 : 1))
                        throw new AssertionError("Camera changed exact world occupancy");
                      cells++;
                    }
              }
          }
      }
    }
    if (samples != 162 || cells != 576) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Camera navigation engine-only PASS; 162 scalar observations and 576 exact cells");
  }
}
