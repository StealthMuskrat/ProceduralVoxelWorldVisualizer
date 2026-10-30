package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred engine-only replay; application metadata is read as JSON without editor dependencies.
 */
public final class SettingsDecisionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    for (String name : List.of("before", "after", "undone", "redone")) {
      boolean changed = Set.of("after", "redone").contains(name);
      var loaded = new ProjectLoader().load(out.resolve("settings-" + name + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var p = loaded.project();
      if (p.project.seed != (changed ? Long.MIN_VALUE : 0)
          || p.project.minY != (changed ? -2 : -64)
          || p.project.maxY != (changed ? 3 : 319)
          || p.noise2D.size() != 1
          || !p.noise2D.getFirst().id.equals("existing")
          || p.noise2D.getFirst().seedOffset != 0)
        throw new AssertionError("Project settings/private resource isolation");
      var compiled = new CompiledProject(p);
      for (int x = -17; x <= 17; x++)
        if (compiled.noise2D("existing", 42).sample(x, -x) != .125)
          throw new AssertionError("Existing source changed with settings");
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
              throw new AssertionError("Unexpected World publication");
          }
      }
    }
    var profile = ProjectIO.JSON.readTree(out.resolve("profile-after.json").toFile());
    if (profile.path("formatVersion").intValue() != 1
        || !profile.path("creationDefaults").path("noise2D.algorithm").asText().equals("CONSTANT")
        || profile.path("creationDefaults").path("noise2D.algorithm.CONSTANT.value").asDouble()
            != .625) throw new AssertionError("Application creation profile");
    var future = new ProjectLoader().load(out.resolve("future-profile-item.json"));
    if (!future.success()) throw new AssertionError(future.diagnostics());
    var sampler = new CompiledProject(future.project()).noise2D("future", 42);
    for (int x = -17; x <= 17; x++)
      if (sampler.sample(x, -x) != .625)
        throw new AssertionError("Future concrete creation default");
    System.out.println(
        "Settings decisions engine-only PASS; exact signed metadata transaction, unchanged existing"
            + " source and concrete future default");
  }
}
