package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;

/** Deferred engine-only replay after the production browser transaction workflow. */
public final class BrowserEditConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (p.noise2D.size() != 3 || p.folders.size() != 1 || !p.worldGenerator.layers.isEmpty())
      throw new AssertionError("Deleted duplicate or fixture structure");
    var folder = p.folders.getFirst();
    var compiled = new CompiledProject(p);
    String[] names = {"renamed", "b", "c"};
    double[] values = {.25, .5, .75};
    int count = 0, cells = 0;
    for (int i = 0; i < 3; i++) {
      var noise = p.noise2D.get(i);
      if (!noise.id.equals(names[i])
          || noise.seedOffset != (i == 0 ? 17 : 0)
          || !(i == 0
              ? folder.uuid.equals(noise.parentFolderUuid)
              : noise.parentFolderUuid == null))
        throw new AssertionError("Combined Apply, discarded seed or cut identity");
      var sampler = compiled.noise2D(noise.uuid, p.project.seed);
      for (int x = -4; x <= 4; x++)
        for (int z = -4; z <= 4; z++) {
          if (Double.doubleToRawLongBits(sampler.sample(x, z))
              != Double.doubleToRawLongBits(values[i]))
            throw new AssertionError("Browser transaction changed scalar values");
          count++;
        }
    }
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (int chunkX : new int[] {-1, 0}) {
            var chunk = world.generateChunk(new ChunkPos(chunkX, 0, 0));
            for (int z = 0; z < root; z++)
              for (int y = 0; y < root; y++)
                for (int x = 0; x < root; x++) {
                  if (chunk.getVoxelTypeId(x, y, z) != 0)
                    throw new AssertionError("Empty world changed");
                  cells++;
                }
          }
        }
    }
    if (count != 243 || cells != 144) throw new AssertionError("Observation count");
    System.out.println(
        "BROWSER_EDIT_ENGINE_ONLY_PASS 243 exact scalar values / 144 EMPTY cells / exact saved"
            + " structure");
  }
}
