package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;

/** Deferred engine-JAR isolation replay; command chrome has no procedural authority. */
public final class ViewportCommandConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var before = ProjectIO.JSON.readTree(out.resolve("viewport-project.json").toFile());
    if (!before.equals(ProjectIO.JSON.readTree(out.resolve("after.json").toFile())))
      throw new AssertionError("View commands changed project fields");
    var preferences = ProjectIO.JSON.readTree(out.resolve("preferences-before.json").toFile());
    if (!preferences.equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Explicit inverse preference toggles did not restore values");
    var toggled = preferences.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) toggled.path("editor"))
        .put("autoRegenerate", !preferences.path("editor").path("autoRegenerate").asBoolean());
    if (!toggled.equals(ProjectIO.JSON.readTree(out.resolve("auto-toggled.json").toFile())))
      throw new AssertionError("Auto preference changed unrelated values");
    for (var name : new String[] {"viewport-project.json", "after.json"}) {
      var load = new ProjectLoader().load(out.resolve(name));
      if (!load.success()) throw new AssertionError(load.diagnostics());
      var compiled = new CompiledProject(load.project());
      for (int x = -17; x <= 17; x++) {
        if (compiled.noise2D("two", 42).sample(x, -x) != .125
            || compiled.noise3D("three", 42).sample(x, 2 * x, -x) != .25)
          throw new AssertionError("View commands changed raw samples");
      }
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
              throw new AssertionError("View commands changed EMPTY World");
          }
      }
    }
    System.out.println(
        "Viewport commands engine-only PASS; complete project/preferences, atomic Auto payload,"
            + " unchanged .125/.25 fields and EMPTY World");
  }
}
