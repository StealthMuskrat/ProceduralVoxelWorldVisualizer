package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Independent engine replay of the saved project while a desktop renderer is unavailable. */
public final class RendererRecoveryConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var source = ProjectIO.JSON.readTree(out.resolve("source.json").toFile());
    if (!source.equals(ProjectIO.JSON.readTree(out.resolve("after.json").toFile()))
        || !source.equals(
            ProjectIO.JSON.readTree(out.resolve("saved-while-unavailable.json").toFile())))
      throw new AssertionError("Renderer recovery changed authored data");
    var load = new ProjectLoader().load(out.resolve("saved-while-unavailable.json"));
    if (!load.success()) throw new AssertionError(load.diagnostics());
    var compiled = new CompiledProject(load.project());
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++)
        if (compiled.noise2D("constant-2", 0).sample(x, z) != .125
            || compiled.noise3D("constant-3", 0).sample(x, -x, z) != .25)
          throw new AssertionError("Renderer recovery changed field values");
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
                  if (!world.registry().semanticId(chunk.getVoxelTypeId(x, y, z)).equals("solid"))
                    throw new AssertionError("Exact world changed");
                  cells++;
                }
          }
        }
    }
    if (cells != 144) throw new AssertionError("Coverage changed");
    System.out.println(
        "Renderer recovery engine-only PASS; complete project equality, 162 constant scalar values"
            + " and 144 exact signed/root world cells");
  }
}
