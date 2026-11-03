package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Deferred engine-JAR-only concurrent progress replay, invoked by RegionCompletionConsumer. */
public final class RegionProgressConsumer {
  private RegionProgressConsumer() {}

  static void replay(CompiledProject compiled, Path out) throws Exception {
    var cases = new ArrayList<Map<String, Object>>();
    var retained = new ArrayList<VoxelChunk>();
    RegionSummary mixed = null;
    for (int root : new int[] {2, 4})
      for (var order : RegionRequest.DeliveryOrder.values())
        for (String mode : List.of("SUCCESS", "MIXED", "CANCEL"))
          try (var engine = new NoiseGeneratorEngine(2, 64, 16L << 20);
              var world =
                  engine.openWorld(
                      compiled,
                      new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0));
              var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            // 257 X columns (-128..128) by two Y and two Z chunks, independent of root size.
            var bounds =
                new RegionBounds(
                    new BlockPos(-128 * root, -root, -root),
                    new BlockPos(129 * root - 1, root - 1, root - 1));
            var request = new RegionRequest(bounds, RegionRequest.FailurePolicy.CONTINUE, order, 8);
            var gate = new CompletableFuture<Void>();
            var entered = new CountDownLatch(1);
            var first = new AtomicReference<VoxelChunk>();
            var task =
                world.streamRegion(
                    request,
                    chunk -> {
                      first.compareAndSet(null, chunk);
                      entered.countDown();
                      return gate.thenCompose(
                          ignored ->
                              mode.equals("MIXED") && Math.floorMod(chunk.position().x(), 2) != 0
                                  ? CompletableFuture.<Void>failedFuture(
                                      new IllegalArgumentException("delivery fixture"))
                                  : CompletableFuture.<Void>completedFuture(null));
                    });
            try {
              require(entered.await(3, TimeUnit.SECONDS), "Initial delivery did not enter");
              var held = task.progress();
              require(
                  held.equals(new TaskProgress("RUNNING", 1028, 0, 0, 0)),
                  "Held acknowledgement consumed progress");
              var ready = new CountDownLatch(2);
              var left = readers.submit(() -> observe(task, ready));
              var right = readers.submit(() -> observe(task, ready));
              require(ready.await(3, TimeUnit.SECONDS), "Progress readers did not enter");
              if (mode.equals("CANCEL")) require(task.cancel(), "Owner cancellation refused");
              else gate.complete(null);
              var leftReport = left.get(12, TimeUnit.SECONDS);
              var rightReport = right.get(12, TimeUnit.SECONDS);
              var summary = task.completion().get(3, TimeUnit.SECONDS);
              var expected =
                  mode.equals("CANCEL")
                      ? new TaskProgress("CANCELLED", 0, 0, 0, 1028)
                      : mode.equals("MIXED")
                          ? new TaskProgress("COMPLETED_WITH_ERRORS", 0, 516, 512, 0)
                          : new TaskProgress("COMPLETED", 0, 1028, 0, 0);
              require(
                  summary.progress().equals(expected) && task.progress().equals(expected),
                  "Terminal progress differs from independent chunk counts");
              require(
                  held.equals(new TaskProgress("RUNNING", 1028, 0, 0, 0)),
                  "Retained progress changed");
              require(
                  !mode.equals("CANCEL") || !gate.isDone(),
                  "Engine completed the host acknowledgement");
              require(
                  summary.failures().size() == (mode.equals("MIXED") ? 512 : 0),
                  "Delivery failure details differ");
              if (mode.equals("MIXED")) mixed = summary;
              retained.add(first.get());
              cases.add(
                  Map.of(
                      "root",
                      root,
                      "order",
                      order.name(),
                      "mode",
                      mode,
                      "held",
                      held,
                      "terminal",
                      expected,
                      "left",
                      leftReport,
                      "right",
                      rightReport));
            } finally {
              gate.complete(null);
              task.cancel();
            }
          }
    long cells = 0;
    for (var chunk : retained)
      for (int z = 0; z < chunk.size(); z++)
        for (int y = 0; y < chunk.size(); y++)
          for (int x = 0; x < chunk.size(); x++) {
            require(
                chunk.getVoxelTypeId(x, y, z) == 0,
                "Closed runtime changed a retained empty snapshot");
            cells++;
          }
    require(cells == 432, "Unexpected retained-cell matrix");
    var source = new ArrayList<>(List.of(mixed.failures().getFirst()));
    var detached =
        new RegionSummary(mixed.requested(), mixed.effective(), mixed.progress(), source);
    int hash = detached.hashCode();
    source.clear();
    require(
        detached.failures().size() == 1 && detached.hashCode() == hash,
        "Caller mutation changed summary ownership");
    try {
      detached.failures().clear();
      throw new AssertionError("Published failure list is mutable");
    } catch (UnsupportedOperationException expected) {
    }
    Files.writeString(
        out.resolve("region-completion-progress.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "cases",
                cases,
                "largeCounts",
                largeCounts(compiled),
                "retainedCells",
                cells,
                "summaryDetached",
                true,
                "hostAcknowledgementsPreserved",
                true)));
  }

  private record Observations(long reads, List<TaskProgress> retained) {}

  private static List<Map<String, Object>> largeCounts(CompiledProject compiled) throws Exception {
    var result = new ArrayList<Map<String, Object>>();
    for (int root : new int[] {2, 4}) {
      long columns = (1L << 32) / root - 1;
      long expected = java.math.BigInteger.valueOf(columns).pow(2).shiftLeft(1).longValueExact();
      try (var engine = new NoiseGeneratorEngine(2, 32, 8L << 20);
          var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
        var bounds =
            new RegionBounds(
                new BlockPos(Integer.MIN_VALUE + root, -root, Integer.MIN_VALUE + root),
                new BlockPos(Integer.MAX_VALUE, root - 1, Integer.MAX_VALUE));
        var ack = new CompletableFuture<Void>();
        var entered = new CountDownLatch(1);
        var task =
            world.streamRegion(
                RegionRequest.of(bounds),
                chunk -> {
                  entered.countDown();
                  return ack;
                });
        try {
          require(entered.await(3, TimeUnit.SECONDS), "Large region did not enter lazy delivery");
          var held = task.progress();
          require(
              held.equals(new TaskProgress("RUNNING", expected, 0, 0, 0)),
              "Large pending count lost precision");
          task.cancel();
          var terminal = task.completion().get(3, TimeUnit.SECONDS).progress();
          require(
              terminal.equals(new TaskProgress("CANCELLED", 0, 0, 0, expected)),
              "Large cancellation count lost precision");
          require(
              held.pending() == expected && !ack.isDone(),
              "Large region changed retained/host ownership");
          result.add(
              Map.of(
                  "root",
                  root,
                  "columns",
                  columns,
                  "expected",
                  expected,
                  "held",
                  held,
                  "terminal",
                  terminal));
        } finally {
          ack.complete(null);
          task.cancel();
        }
      }
    }
    return List.copyOf(result);
  }

  private static Observations observe(RegionTask task, CountDownLatch ready) throws Exception {
    var completion = task.completion();
    var previous = task.progress();
    partition(previous);
    var retained = new ArrayList<TaskProgress>();
    retained.add(previous);
    ready.countDown();
    long reads = 1, until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      var current = task.progress();
      partition(current);
      require(
          current.completed() >= previous.completed()
              && current.failed() >= previous.failed()
              && current.cancelled() >= previous.cancelled()
              && current.pending() <= previous.pending(),
          "Progress moved backward");
      if (!current.equals(previous) && retained.size() < 16) retained.add(current);
      previous = current;
      if (++reads % 256 == 0) java.util.concurrent.locks.LockSupport.parkNanos(100_000);
      else Thread.onSpinWait();
      require(System.nanoTime() <= until, "Progress did not reach terminal ownership");
    } while (!completion.isDone());
    require(
        completion.get(3, TimeUnit.SECONDS).progress().equals(task.progress()),
        "Summary and terminal progress differ");
    for (var snapshot : retained) partition(snapshot);
    return new Observations(reads, List.copyOf(retained));
  }

  private static void partition(TaskProgress progress) {
    require(
        progress.pending() >= 0
            && progress.completed() >= 0
            && progress.failed() >= 0
            && progress.cancelled() >= 0,
        "Negative region progress");
    require(
        Math.addExact(
                Math.addExact(progress.pending(), progress.completed()),
                Math.addExact(progress.failed(), progress.cancelled()))
            == 1028,
        "Torn progress partition");
    require(
        List.of("PENDING", "RUNNING").contains(progress.state()) || progress.pending() == 0,
        "Terminal progress retains pending work");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
