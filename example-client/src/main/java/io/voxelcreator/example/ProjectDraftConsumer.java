package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;

/** Deferred engine-JAR-only replay of explicit Apply, failed-save retry and standalone Save. */
public final class ProjectDraftConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    String[] files = {
      "transition-applied-project.json",
      "transition-retry-project.json",
      "transition-standalone-project.json"
    };
    long[] seeds = {Long.MIN_VALUE, 17, 0};
    int samples = 0, cells = 0;
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (int i = 0; i < files.length; i++) {
        var loaded = new ProjectLoader().load(out.resolve(files[i]));
        if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
        var p = loaded.project();
        if (p.project.seed != 41 || p.noise2D.size() != 1 || !p.worldGenerator.layers.isEmpty())
          throw new AssertionError("Saved transition structure");
        var noise = p.noise2D.getFirst();
        if (!noise.id.equals("owned") || noise.seedOffset != seeds[i])
          throw new AssertionError("Unapplied values escaped into saved project");
        var compiled = new CompiledProject(p);
        var sampler = compiled.noise2D(noise.uuid, p.project.seed);
        for (int x = -4; x <= 4; x++)
          for (int z = -4; z <= 4; z++) {
            if (Double.doubleToRawLongBits(sampler.sample(x, z)) != Double.doubleToRawLongBits(.25))
              throw new AssertionError("Transition changed constant samples");
            samples++;
          }
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
    }
    if (samples != 243 || cells != 432) throw new AssertionError("Observation count");
    System.out.println(
        "PROJECT_DRAFT_ENGINE_ONLY_PASS exact signed seeds / 243 scalar values / 432 EMPTY cells /"
            + " three explicit saves");
  }
}
