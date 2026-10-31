package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;

/** Deferred engine-only project replay plus ordinary JSON checks of separate saved preferences. */
public final class PreferenceDecisionConsumer {
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
    var saved = ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile());
    var limits = saved.path("performance");
    var appearance = saved.path("appearance");
    if (limits.path("cacheMiB").intValue() != 0
        || limits.path("previewCellBudget").longValue() != Long.MAX_VALUE
        || !limits.path("updateEndpoint").asText().equals("https://updates.example.test/meta.json")
        || appearance.path("globalScale").asDouble() != 1.25
        || appearance.path("windowScales").path("Resources").asDouble() != 1.5
        || appearance.path("windowScales").path("extension-window").asDouble() != .75)
      throw new AssertionError("Independent preference publication mismatch");
    System.out.println(
        "Preference decisions engine-only PASS; unchanged authored source/EMPTY world and exact"
            + " independent saved preference values");
  }
}
