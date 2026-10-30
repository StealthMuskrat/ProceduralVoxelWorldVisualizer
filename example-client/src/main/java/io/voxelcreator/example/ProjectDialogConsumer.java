package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;

/** Deferred independent engine-only replay of the explicit project dialog save/open fixture. */
public final class ProjectDialogConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    check(out.resolve("saved.pvproj.json"), "field", .125);
    check(out.resolve("opened-project.json"), "incoming", .75);
    System.out.println(
        "Project dialog engine-only PASS; complete saved/opened projects, committed-only fields and"
            + " EMPTY worlds");
  }

  private static void check(Path path, String id, double expected) throws Exception {
    var loaded = new ProjectLoader().load(path);
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (p.noise2D.size() != 1
        || !p.noise2D.getFirst().id.equals(id)
        || p.noise2D.getFirst().seedOffset != 0
        || !p.worldGenerator.layers.isEmpty())
      throw new AssertionError("Private data leaked into the project file");
    var compiled = new CompiledProject(p);
    for (int x = -17; x <= 17; x++)
      if (compiled.noise2D(id, 42).sample(x, -x) != expected)
        throw new AssertionError("Saved scalar mismatch");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4, 8})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
            throw new AssertionError("Unexpected authored World resources");
        }
    }
  }
}
