package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Independent engine-only oracle for explicit numeric authoring and unchanged empty World. */
public final class PropertyNumericConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    int samples = 0, cells = 0;
    for (String file : new String[] {"before", "applied", "undone", "redone"}) {
      var loaded = new ProjectLoader().load(out.resolve(file + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var compiled = new CompiledProject(loaded.project());
      double expected = file.equals("applied") || file.equals("redone") ? .625 : .125;
      for (int x = -4; x <= 4; x++)
        for (int z = -4; z <= 4; z++) {
          if (compiled.noise2D("numeric-2", 0).sample(x, z) != expected
              || compiled.noise3D("numeric-3", 0).sample(x, -x, z) != .125)
            throw new AssertionError(
                "Numeric authoring changed independent source semantics: " + file);
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
                      throw new AssertionError(
                          "Private numeric controls populated an unauthored World");
                    cells++;
                  }
            }
          }
      }
    }
    if (samples != 648 || cells != 576) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Property numeric engine-only PASS; 648 exact scalar samples and 576 signed/root EMPTY"
            + " cells");
  }
}
