package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Engine-only analytic replay of chosen initial sources, independent of editor metadata. */
public final class NoiseCreationChoiceConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var load = new ProjectLoader().load(out.resolve("applied.json"));
    if (!load.success()) throw new AssertionError(load.diagnostics());
    var compiled = new CompiledProject(load.project());
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++) {
        if (compiled.noise2D("default-2", 0).sample(x, z) != .125
            || compiled.noise2D("gradient-2", 0).sample(x, z) != z
            || compiled.noise3D("constant-3", 0).sample(x, -x, z) != -.25
            || compiled.noise2D("frozen-2", 0).sample(x, z) != -.75)
          throw new AssertionError("Chosen source/defaults changed");
      }
    int cells = 0;
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
                    throw new AssertionError("Unused created sources changed the empty World");
                  cells++;
                }
          }
        }
    }
    if (cells != 144) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Initial source creation engine-only PASS; 324 exact scalar values and 144 signed/root"
            + " EMPTY cells");
  }
}
