package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferred engine-JAR consumer for world contribution capture and exact voxel invariance. */
public final class WorldWorkConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    var out = Path.of(args[1]);
    Files.createDirectories(out);
    int checked = 0;
    Profiler.Snapshot snapshot;
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      var capture = engine.profiler().start(new Profiler.Options(60000, 4096, 128, 65536, true));
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                new CompiledProject(p),
                new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          var tasks = new ArrayList<CompletableFuture<VoxelChunk>>();
          for (int x : new int[] {-1, 0})
            tasks.add(world.generateChunkAsync(new ChunkPos(x, 0, 0)));
          for (var task : tasks) {
            var chunk = task.get(10, TimeUnit.SECONDS);
            for (int z = 0; z < root; z++)
              for (int y = 0; y < root; y++)
                for (int x = 0; x < root; x++) {
                  if (chunk.getVoxelTypeId(x, y, z) != 1)
                    throw new AssertionError("Canonical cell changed");
                  checked++;
                }
          }
          var pair =
              world.generateStageChunk(
                  new ChunkPos(0, 0, 0),
                  p.worldGenerator.layers.getLast().operations.getFirst().uuid);
          for (int z = 0; z < root; z++)
            for (int y = 0; y < root; y++)
              for (int x = 0; x < root; x++) {
                if (pair.before().getVoxelTypeId(x, y, z) != 1
                    || pair.after().getVoxelTypeId(x, y, z) != 1)
                  throw new AssertionError("Stage cell changed");
                checked += 2;
              }
        }
      engine.profiler().stop();
      snapshot = capture.snapshot();
      if (snapshot.samples().stream().anyMatch(s -> s.kind() == Profiler.Kind.OPERATION))
        throw new AssertionError("Stride unexpectedly sampled a visited operation");
      if (snapshot.samples().stream()
              .filter(
                  s ->
                      s.kind() == Profiler.Kind.OPERATION_WORK
                          || s.kind() == Profiler.Kind.LAYER_WORK)
              .count()
          != 30) throw new AssertionError("Missing chunk/stage contribution sources");
      if (engine.metrics().stagingBytes() != 0)
        throw new AssertionError("Work ledger reservation leaked");
    }
    ProfileIO.writeJSON(out.resolve("world-work-capture.json"), snapshot);
    ProfileIO.writeCSV(out.resolve("world-work-capture.csv"), snapshot);
    new ProjectIO().save(out.resolve("world-work-project.json"), p);
    Files.writeString(
        out.resolve("world-work-engine.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "checkedCells",
                checked,
                "workSamples",
                30,
                "stagingReleased",
                true)));
    System.out.println(
        "WORLD_WORK_ENGINE_ONLY_PASS bounded per-source attribution / both stage prefixes /"
            + " canonical voxels / JSON-CSV exports");
  }
}
