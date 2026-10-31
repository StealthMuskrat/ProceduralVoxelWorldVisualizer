package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;

/** Deferred headless consumption of unchanged authoring and explicitly saved active preferences. */
public final class ImmediateSettingsDecisionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var loaded = new ProjectLoader().load(out.resolve("unchanged-project.json"));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (p.noise2D.size() != 1
        || !p.noise2D.getFirst().id.equals("existing")
        || p.noise2D.getFirst().seedOffset != 0)
      throw new AssertionError("Immediate preferences changed authored/private data");
    var compiled = new CompiledProject(p);
    for (int x = -17; x <= 17; x++)
      if (compiled.noise2D("existing", 42).sample(x, -x) != .125)
        throw new AssertionError("Immediate preferences changed source samples");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
            throw new AssertionError("Immediate preferences changed EMPTY World");
        }
    }
    var saved = ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile());
    var editor = saved.path("editor");
    var appearance = saved.path("appearance");
    var activeLimits = saved.path("performance");
    if (editor.path("autoRegenerate").asBoolean()
        || editor.path("opportunitiesPerSecond").intValue() != 20
        || saved.path("visualization").path("grid").asBoolean()
        || !saved.path("visualization").path("shadows").asBoolean()
        || activeLimits.path("cacheMiB").intValue() == 17
        || activeLimits.path("previewCellBudget").longValue() == Long.MAX_VALUE
        || appearance.path("globalScale").asDouble() != 1
        || appearance.path("windowScales").has("Resources")
        || appearance.path("windowScales").path("extension-window").asDouble() != .75)
      throw new AssertionError("Active preference save included hidden private text");
    System.out.println(
        "Immediate settings engine-only PASS; active-only preferences, unchanged .125 source and"
            + " EMPTY World");
  }
}
