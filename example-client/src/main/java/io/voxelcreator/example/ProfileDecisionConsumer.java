package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred engine-only project replay and ordinary JSON inspection of explicit profiler exports.
 */
public final class ProfileDecisionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var loaded = new ProjectLoader().load(out.resolve("unchanged-project.json"));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (p.noise2D.size() != 1
        || !p.noise2D.getFirst().id.equals("existing")
        || p.noise2D.getFirst().seedOffset != 0)
      throw new AssertionError("Profiler changed authored/private source");
    var compiled = new CompiledProject(p);
    for (int x = -17; x <= 17; x++)
      if (compiled.noise2D("existing", 42).sample(x, -x) != .125)
        throw new AssertionError("Profiler changed source samples");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
            throw new AssertionError("Profiler changed EMPTY World");
        }
    }
    var savedProject = ProjectIO.JSON.readTree(out.resolve("protected-project.json").toFile());
    if (!savedProject.equals(ProjectIO.JSON.valueToTree(p)))
      throw new AssertionError("Explicit capture export changed protected project");
    if (!ProjectIO.JSON
        .readTree(out.resolve("preferences-before.json").toFile())
        .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Profiler changed preferences");
    for (var name : List.of("reviewed", "replacement")) {
      long expected = name.equals("reviewed") ? 123 : 999;
      var capture = ProjectIO.JSON.readTree(out.resolve(name + ".json").toFile());
      var samples = capture.path("samples");
      if (!capture.path("state").asText().equals("STOPPED")
          || samples.size() != 1
          || samples.get(0).path("value").asLong() != expected
          || !samples.get(0).path("label").asText().equals("sample-" + expected)
          || !samples.get(0).path("kind").asText().equals("QUEUE_DEPTH"))
        throw new AssertionError("Frozen capture ownership mismatch");
    }
    System.out.println(
        "Profiler decisions engine-only PASS; frozen samples, unchanged .125 project/preferences"
            + " and EMPTY World");
  }
}
