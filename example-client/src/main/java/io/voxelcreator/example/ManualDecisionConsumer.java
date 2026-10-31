package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;

/** Deferred engine-only replay of the complete project and preferences after manual navigation. */
public final class ManualDecisionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var load = new ProjectLoader().load(out.resolve("unchanged-project.json"));
    if (!load.success()) throw new AssertionError(load.diagnostics());
    var p = load.project();
    if (!ProjectIO.JSON
        .readTree(out.resolve("protected-project.json").toFile())
        .equals(ProjectIO.JSON.valueToTree(p)))
      throw new AssertionError("Manual decisions changed the complete project");
    if (p.noise2D.size() != 1
        || !p.noise2D.getFirst().id.equals("existing")
        || p.noise2D.getFirst().seedOffset != 0)
      throw new AssertionError("Manual navigation applied private resource text");
    if (!ProjectIO.JSON
        .readTree(out.resolve("preferences-before.json").toFile())
        .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Manual decisions changed active preferences");
    var compiled = new CompiledProject(p);
    for (int x = -17; x <= 17; x++)
      if (compiled.noise2D("existing", 42).sample(x, -x) != .125)
        throw new AssertionError("Manual decisions changed canonical source samples");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
            throw new AssertionError("Manual decisions changed EMPTY World");
        }
    }
    System.out.println(
        "Manual decisions engine-only PASS; unchanged complete project/preferences,"
            + " .125 samples and EMPTY World");
  }
}
