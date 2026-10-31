package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;

/** Deferred engine-only project/preferences replay after isolated selector callbacks. */
public final class SelectorDecisionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var loaded = new ProjectLoader().load(out.resolve("unchanged-project.json"));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (!ProjectIO.JSON
        .readTree(out.resolve("protected-project.json").toFile())
        .equals(ProjectIO.JSON.valueToTree(p)))
      throw new AssertionError("Selector callbacks changed complete project");
    if (p.noise2D.size() != 96
        || !p.noise2D.getFirst().id.equals("alpha")
        || !p.noise2D.get(1).id.equals("beta"))
      throw new AssertionError("Selector callbacks changed catalog resources");
    if (!ProjectIO.JSON
        .readTree(out.resolve("preferences-before.json").toFile())
        .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Selector callbacks changed active preferences");
    var compiled = new CompiledProject(p);
    for (int x = -17; x <= 17; x++) {
      if (compiled.noise2D("alpha", 42).sample(x, -x) != .125
          || compiled.noise2D("beta", 42).sample(x, -x) != .25)
        throw new AssertionError("Selected sources changed signed-coordinate samples");
    }
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
            throw new AssertionError("Selector callbacks changed EMPTY World");
        }
    }
    System.out.println(
        "Selector decisions engine-only PASS; complete project/preferences, .125/.25 sources and"
            + " EMPTY World");
  }
}
