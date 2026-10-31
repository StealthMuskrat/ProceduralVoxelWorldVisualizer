package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;

/** Deferred engine-only project replay plus ordinary JSON checks of separate saved preferences. */
public final class InteractivePreferenceDecisionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var loaded = new ProjectLoader().load(out.resolve("unchanged-project.json"));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (p.noise2D.size() != 1
        || !p.noise2D.getFirst().id.equals("existing")
        || p.noise2D.getFirst().seedOffset != 0)
      throw new AssertionError("Private Properties input leaked");
    var compiled = new CompiledProject(p);
    for (int x = -17; x <= 17; x++)
      if (compiled.noise2D("existing", 42).sample(x, -x) != .125)
        throw new AssertionError("Preferences changed authored field");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
            throw new AssertionError("Unexpected World publication");
        }
    }
    var saved = ProjectIO.JSON.readTree(out.resolve("user-config.json").toFile());
    var appearance = saved.path("appearance");
    var warning = appearance.path("statusColors").path("Warning");
    if (!saved.path("input").path("shortcuts").path("Profiler").asText().equals("Alt+F9")
        || !appearance.path("theme").asText().equals("Light")
        || appearance.path("colors").size() != 0
        || appearance.path("statusColors").size() != 1
        || warning.size() != 4
        || warning.get(0).asDouble() != .25
        || warning.get(1).asDouble() != .5
        || warning.get(2).asDouble() != .75
        || warning.get(3).asDouble() != 1
        || appearance.path("windowScales").path("extension-window").asDouble() != .75
        || saved.path("workspace").path("width").intValue() != 1234)
      throw new AssertionError("Independent Theme/Input save mismatch");
    System.out.println(
        "Interactive preference decisions engine-only PASS; unchanged source/EMPTY world and"
            + " independent theme/binding JSON publication");
  }
}
