package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Deferred public engine-JAR observer isolation, removal and retained-snapshot replay. */
public final class DiagnosticListenerConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]));
  }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var project = new ProjectData();
    String before = ProjectIO.JSON.writeValueAsString(project);
    var compiled = new CompiledProject(project);
    var retained = new ArrayList<VoxelChunk>();
    int cases = 0, observerCalls = 0, healthyCalls = 0;
    for (int root : new int[] {2, 4})
      for (var order : RegionRequest.DeliveryOrder.values())
        for (var policy : RegionRequest.FailurePolicy.values())
          for (boolean error : new boolean[] {false, true}) {
            var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
            var reports = new CopyOnWriteArrayList<Diagnostic>();
            var calls = new AtomicInteger();
            var badCalls = new AtomicInteger();
            var original = new AssertionError("original-delivery");
            var observerError = new AssertionError("observer-error");
            var observerException = new IllegalStateException("observer-exception");
            Consumer<Diagnostic> bad =
                diagnostic -> {
                  badCalls.incrementAndGet();
                  if (error) throw observerError;
                  throw observerException;
                };
            Consumer<Diagnostic> healthy = reports::add;
            try (engine;
                var world =
                    engine.openWorld(
                        compiled,
                        new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
              engine.addDiagnosticListener(bad);
              engine.addDiagnosticListener(bad);
              engine.addDiagnosticListener(healthy);
              var request =
                  new RegionRequest(
                      new RegionBounds(
                          new BlockPos(0, 0, 0), new BlockPos(3 * root - 1, root - 1, root - 1)),
                      policy,
                      order,
                      1);
              var task =
                  world.streamRegion(
                      request,
                      chunk -> {
                        retained.add(chunk);
                        if (calls.incrementAndGet() == 1) throw original;
                        return CompletableFuture.completedFuture(null);
                      });
              var summary = task.completion().get(5, TimeUnit.SECONDS);
              boolean continued = policy == RegionRequest.FailurePolicy.CONTINUE;
              require(
                  summary
                      .progress()
                      .equals(
                          new TaskProgress(
                              continued ? "COMPLETED_WITH_ERRORS" : "CANCELLED",
                              0,
                              continued ? 2 : 0,
                              1,
                              continued ? 0 : 2)),
                  "Observer changed region policy");
              require(task.progress().equals(summary.progress()), "Terminal progress changed");
              require(calls.get() == (continued ? 3 : 1), "Consumer retried or stopped early");
              require(
                  summary.failures().size() == 1
                      && summary.failures().getFirst().phase().equals("DELIVERY")
                      && summary.failures().getFirst().message().equals("original-delivery")
                      && summary.failures().getFirst().chunk().equals(new ChunkPos(0, 0, 0)),
                  "Observer replaced original delivery failure");
              require(
                  badCalls.get() == 2
                      && reports.size() == 1
                      && reports.getFirst().code().equals("CHUNK_DELIVERY_FAILED")
                      && reports.getFirst().message().equals("original-delivery"),
                  "Observer fanout lost a registration or its diagnostic");
              require(
                  engine.removeDiagnosticListener(bad) && !engine.removeDiagnosticListener(bad),
                  "Identity removal did not revoke duplicates");
              // Trigger another real diagnostic after removal, retaining the host consumer's
              // original failure details and proving that the healthy observer remains registered.
              var again =
                  world.streamRegion(
                      new RegionRequest(
                          new RegionBounds(
                              new BlockPos(-root, 0, 0), new BlockPos(-1, root - 1, root - 1)),
                          policy,
                          order,
                          1),
                      chunk -> {
                        throw original;
                      });
              var second = again.completion().get(5, TimeUnit.SECONDS);
              require(
                  second.progress().failed() == 1 && second.failures().size() == 1,
                  "Successor stream failed to retire");
              require(
                  badCalls.get() == 2 && reports.size() == 2,
                  "Removed observers were invoked or healthy observer was lost");
              require(world.inFlightChunks() == 0, "Region left admitted chunks behind");
              retained.add(world.generateChunk(new ChunkPos(-2, 0, 0)));
              require(
                  original.getSuppressed().length == 0
                      && observerError.getSuppressed().length == 0
                      && observerException.getSuppressed().length == 0,
                  "Reporting mutated host failures");
              observerCalls += badCalls.get();
              healthyCalls += reports.size();
            }
            require(!engine.removeDiagnosticListener(healthy), "Engine closure retained observers");
            boolean rejected = false;
            try {
              engine.addDiagnosticListener(healthy);
            } catch (IllegalStateException expected) {
              rejected = true;
            }
            require(rejected, "Closed engine accepted observer ownership");
            cases++;
          }
    long cells = 0;
    for (var chunk : retained)
      for (int z = 0; z < chunk.size(); z++)
        for (int y = 0; y < chunk.size(); y++)
          for (int x = 0; x < chunk.size(); x++) {
            require(chunk.getVoxelTypeId(x, y, z) == 0, "Retained empty-world cell changed");
            cells++;
          }
    require(
        cases == 16
            && observerCalls == 32
            && healthyCalls == 32
            && retained.size() == 48
            && cells == 1728,
        "Unexpected observer replay dimensions");
    require(
        before.equals(ProjectIO.JSON.writeValueAsString(project)),
        "Observer replay changed authoring data");
    Files.writeString(
        out.resolve("diagnostic-listeners-engine.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "cases",
                cases,
                "faultyObserverCalls",
                observerCalls,
                "healthyObserverCalls",
                healthyCalls,
                "retainedChunks",
                retained.size(),
                "exactCells",
                cells,
                "projectDataUnchanged",
                true)));
    System.out.println("DIAGNOSTIC_LISTENERS_ENGINE_ONLY_PASS");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
