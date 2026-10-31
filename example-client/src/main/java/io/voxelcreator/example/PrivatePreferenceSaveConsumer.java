package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only project replay and ordinary active-preference JSON comparison. */
public final class PrivatePreferenceSaveConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var loaded = new ProjectLoader().load(out.resolve("unchanged-project.json"));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (p.noise2D.size() != 1
        || !p.noise2D.getFirst().id.equals("existing")
        || p.noise2D.getFirst().seedOffset != 0)
      throw new AssertionError("Private preference callback changed authored/private resource");
    var compiled = new CompiledProject(p);
    for (int x = -17; x <= 17; x++)
      if (compiled.noise2D("existing", 42).sample(x, -x) != .125)
        throw new AssertionError("Private preferences changed source samples");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
            throw new AssertionError("Private preferences changed EMPTY World");
        }
    }
    var baseline = ProjectIO.JSON.readTree(out.resolve("preferences-before.json").toFile());
    for (var name :
        List.of(
            "appearance-saved.json",
            "limits-saved.json",
            "trust-saved.json",
            "input-saved.json",
            "preferences.json"))
      if (!baseline.equals(ProjectIO.JSON.readTree(out.resolve(name).toFile())))
        throw new AssertionError("Ordinary save applied private preference text");
    if (baseline.path("appearance").path("globalScale").asDouble() != 1
        || baseline.path("appearance").path("windowScales").has("Resources")
        || baseline.path("performance").path("cacheMiB").intValue() == 17
        || baseline.path("performance").path("previewCellBudget").longValue() == Long.MAX_VALUE
        || !baseline.path("performance").path("updateEndpoint").asText().isEmpty()
        || !baseline
            .path("input")
            .path("shortcuts")
            .path("Profiler")
            .asText()
            .equals("Ctrl+Shift+P"))
      throw new AssertionError("Baseline does not distinguish active and pending preferences");
    System.out.println(
        "Private preference saves engine-only PASS; exact active-only JSON, unchanged .125 source"
            + " and EMPTY World");
  }
}
