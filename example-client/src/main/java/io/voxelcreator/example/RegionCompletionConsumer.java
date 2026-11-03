package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Independent engine-JAR-only ownership replay; never loads editor/native classes. */
public final class RegionCompletionConsumer {
  private static final RegionRequest ONE =
      RegionRequest.of(new RegionBounds(new BlockPos(-2, -2, -2), new BlockPos(-1, -1, -1)));

  private record Held(RegionTask task, CompletableFuture<Void> ack) {}

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var project = new ProjectData();
    String original = ProjectIO.JSON.writeValueAsString(project);
    var compiled = new CompiledProject(project);
    var retained = new ArrayList<VoxelChunk>();
    long observations = 0;
    try (var engine = new NoiseGeneratorEngine(2, 64, 16L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          var held = hold(world);
          var manual = held.task.completion();
          var cancelled = held.task.completion();
          var exceptional = held.task.completion();
          require(manual.complete(null), "Observer completion rejected unexpectedly");
          require(cancelled.cancel(true), "Observer cancel rejected unexpectedly");
          require(
              exceptional.completeExceptionally(new IllegalStateException("local observer")),
              "Observer exception rejected");
          var timed = held.task.completion().orTimeout(1, TimeUnit.NANOSECONDS);
          try {
            timed.get(3, TimeUnit.SECONDS);
            throw new AssertionError("Observer timeout absent");
          } catch (ExecutionException timeout) {
            require(timeout.getCause() instanceof TimeoutException, "Wrong observer timeout");
          }
          require(!held.task.completion().isDone(), "Observer mutation completed the stream");
          require(held.task.cancel(), "Observer mutation disabled owner cancellation");
          var summary = held.task.completion().get(3, TimeUnit.SECONDS);
          require(
              summary.progress().cancelled() == 1 && summary.progress().completed() == 0,
              "Cancellation summary lost exact ownership counts");
          require(!held.ack.isDone(), "Engine completed the host acknowledgement");
          held.ack.complete(null);
          require(
              summary.equals(held.task.completion().get(3, TimeUnit.SECONDS)),
              "Fresh observer lost the real result");
          capacity(world);
          try (var result = world.collectRegionAsync(ONE).get(3, TimeUnit.SECONDS)) {
            require(result.summary().progress().completed() == 1, "Async collection failed");
            require(
                engine.metrics().collectionBytes() > 0,
                "Delivered collection lost its reservation");
            retained.add(result.chunks().getFirst());
            result.close();
            result.close();
            require(
                engine.metrics().collectionBytes() == 0,
                "Collection lease was not idempotently released");
          }
          for (int z = -4; z <= 3; z++)
            for (int y = -4; y <= 3; y++)
              for (int x = -4; x <= 3; x++) {
                require(
                    world.getVoxelTypeId(new BlockPos(x, y, z)) == 0,
                    "Empty world changed after ownership operations");
                observations++;
              }
        }
    }
    long retainedObservations = 0;
    for (var chunk : retained)
      for (int z = 0; z < chunk.size(); z++)
        for (int y = 0; y < chunk.size(); y++)
          for (int x = 0; x < chunk.size(); x++) {
            require(chunk.getVoxelTypeId(x, y, z) == 0, "Snapshot changed after engine closure");
            retainedObservations++;
          }
    require(observations == 1024 && retainedObservations == 72, "Unexpected replay dimensions");
    RegionProgressConsumer.replay(compiled, out);
    require(
        original.equals(ProjectIO.JSON.writeValueAsString(project)),
        "Runtime ownership mutated authoring data");
    Files.writeString(out.resolve("region-completion-project.json"), original);
    Files.writeString(
        out.resolve("region-completion.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "roots",
                List.of(2, 4),
                "cells",
                observations,
                "retainedCells",
                retainedObservations,
                "observerMutations",
                8,
                "capacityCallbacks",
                2,
                "collections",
                2)));
    System.out.println("REGION_COMPLETION_CONSUMER_PASS");
  }

  private static Held hold(WorldContext world) throws Exception {
    var entered = new CountDownLatch(1);
    var ack = new CompletableFuture<Void>();
    var task =
        world.streamRegion(
            ONE,
            chunk -> {
              require(chunk.isEmpty(), "Unexpected generated occupancy");
              entered.countDown();
              return ack;
            });
    require(entered.await(3, TimeUnit.SECONDS), "Consumer was not invoked");
    return new Held(task, ack);
  }

  private static void capacity(WorldContext world) throws Exception {
    var held = new ArrayList<Held>();
    try {
      for (int i = 0; i < 16; i++) held.add(hold(world));
      try {
        world.streamRegion(ONE, c -> CompletableFuture.completedFuture(null));
        throw new AssertionError("The active-region cap was not enforced");
      } catch (RejectedExecutionException expected) {
      }
      var last = held.getLast();
      var successor = new CompletableFuture<RegionTask>();
      last.task
          .completion()
          .whenComplete(
              (summary, error) -> {
                try {
                  successor.complete(
                      world.streamRegion(ONE, c -> CompletableFuture.completedFuture(null)));
                } catch (Throwable rejected) {
                  successor.completeExceptionally(rejected);
                }
              });
      last.ack.complete(null);
      require(
          successor
                  .get(3, TimeUnit.SECONDS)
                  .completion()
                  .get(3, TimeUnit.SECONDS)
                  .progress()
                  .completed()
              == 1,
          "Completion callback could not reuse its released region slot");
    } finally {
      held.forEach(
          h -> {
            h.task.cancel();
            h.ack.complete(null);
          });
      for (var h : held) h.task.completion().get(3, TimeUnit.SECONDS);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
