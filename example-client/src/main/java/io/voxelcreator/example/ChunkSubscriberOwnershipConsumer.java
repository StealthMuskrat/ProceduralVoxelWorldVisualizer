package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Deferred public engine-JAR replay of full admission during a blocked completion callback. */
public final class ChunkSubscriberOwnershipConsumer {
  private static final class Gate implements BooleanSupplier, AutoCloseable {
    final Thread submitter = Thread.currentThread();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

    public boolean getAsBoolean() {
      if (Thread.currentThread() != submitter) {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          return true;
        }
      }
      return false;
    }

    public void close() {
      release.countDown();
    }
  }

  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]).toAbsolutePath());
  }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var project = new ProjectData();
    String original = ProjectIO.JSON.writeValueAsString(project);
    var compiled = new CompiledProject(project);
    var snapshots = new ArrayList<VoxelChunk>();
    for (int root : new int[] {2, 4}) {
      try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
          var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0));
          var gate = new Gate();
          var publishers = Executors.newVirtualThreadPerTaskExecutor()) {
        var position = new ChunkPos(-1, -1, 0);
        var requests = new ArrayList<CompletableFuture<VoxelChunk>>();
        var first = world.generateChunkAsync(position, new CancellationToken(gate));
        requests.add(first);
        ready(gate.entered);
        for (int i = 1; i < 8192; i++) requests.add(world.generateChunkAsync(position));
        try {
          world.generateChunkAsync(position);
          throw new AssertionError("Subscriber limit was not enforced");
        } catch (RejectedExecutionException expected) {
          // The next request must become admissible before the first callback returns.
        }
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var successor = new AtomicReference<CompletableFuture<VoxelChunk>>();
        var callbackFailure = new AtomicReference<Throwable>();
        first.whenComplete(
            (value, failure) -> {
              try {
                successor.set(world.generateChunkAsync(position));
              } catch (Throwable rejected) {
                callbackFailure.set(rejected);
              } finally {
                entered.countDown();
              }
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
        var supplied = VoxelChunk.uniform(position, root, 42);
        var publication = publishers.submit(() -> first.complete(supplied));
        try {
          ready(entered);
          require(
              callbackFailure.get() == null && successor.get() != null,
              "Blocking callback retained subscriber admission");
          require(
              first.isDone() && first.join() == supplied && !publication.isDone(),
              "Publication/callback phase was not held");
          require(
              !requests.get(1).isDone() && world.inFlightChunks() == 1,
              "Manual completion retired a healthy shared peer");
          successor.get().cancel(false);
          var replacement = world.generateChunkAsync(position);
          replacement.cancel(false);
          for (int i = 1; i < requests.size(); i++) requests.get(i).cancel(false);
          require(
              world.inFlightChunks() == 0
                  && engine.metrics().queued() == 0
                  && !publication.isDone(),
              "Retirement awaited a host completion callback");
        } finally {
          release.countDown();
          for (var request : requests) request.cancel(false);
          if (successor.get() != null) successor.get().cancel(false);
          gate.close();
        }
        require(publication.get(3, TimeUnit.SECONDS), "Manual result did not win publication");
        snapshots.add(supplied);
        snapshots.add(world.generateChunkAsync(position).get(3, TimeUnit.SECONDS));
      }
    }
    long cells = 0;
    for (int i = 0; i < snapshots.size(); i++) {
      var chunk = snapshots.get(i);
      int expected = i % 2 == 0 ? 42 : 0;
      for (int z = 0; z < chunk.size(); z++)
        for (int y = 0; y < chunk.size(); y++)
          for (int x = 0; x < chunk.size(); x++) {
            require(
                chunk.getVoxelTypeId(x, y, z) == expected,
                "Manual completion contaminated generated/retained snapshots");
            cells++;
          }
    }
    require(cells == 144 && snapshots.size() == 4, "Unexpected snapshot replay dimensions");
    var asyncFailures = asyncFailures(compiled);
    require(
        original.equals(ProjectIO.JSON.writeValueAsString(project)),
        "Subscriber replay mutated authoring data");
    Files.writeString(
        out.resolve("chunk-subscriber-retirement.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "roots",
                List.of(2, 4),
                "subscriberLimit",
                8192,
                "blockedCallbackCases",
                2,
                "retainedChunks",
                snapshots.size(),
                "retainedCells",
                cells,
                "asyncFailures",
                asyncFailures)));
    System.out.println("CHUNK_SUBSCRIBER_OWNERSHIP_CONSUMER_PASS");
  }

  /** Independent supplier-fault replay through public chunk futures, with no API internals. */
  private static Map<String, Object> asyncFailures(CompiledProject compiled) throws Exception {
    var retained = new ArrayList<VoxelChunk>(); long cases = 0, explicitReads = 0;
    for (int root : new int[] {2, 4}) for (String kind : List.of("RUNTIME", "ERROR", "CANCELLATION", "COMPLETION"))
      for (boolean shared : new boolean[] {false, true}) for (boolean unreadable : new boolean[] {false, true})
        try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
            var world = engine.openWorld(compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0));
            var gate = new Gate()) {
          var reads = new AtomicInteger(); var calls = new AtomicInteger();
          Supplier<String> message = () -> { int read = reads.incrementAndGet(); if (unreadable) throw new AssertionError("message reader"); return "message-" + read; };
          Throwable failure = switch (kind) {
            case "ERROR" -> new AssertionError() { public String getMessage() { return message.get(); } };
            case "CANCELLATION" -> new CancellationException() { public String getMessage() { return message.get(); } };
            case "COMPLETION" -> new CompletionException("host envelope", new IllegalStateException("cause")) { public String getMessage() { return message.get(); } };
            default -> new IllegalStateException() { public String getMessage() { return message.get(); } };
          };
          var position = new ChunkPos(-1, 0, 1); var token = new CancellationToken(gate);
          var future = world.generateChunkAsync(position, token); ready(gate.entered);
          var peer = shared ? world.generateChunkAsync(position) : null;
          var replacement = new AtomicReference<CompletableFuture<VoxelChunk>>();
          var observed = future.handle((value, fault) -> {
            calls.incrementAndGet(); require(value == null, "Fault published a supplier value");
            require(world.inFlightChunks() == (shared ? 1 : 0), "Callback preceded subscriber retirement");
            if (shared) require(!peer.isDone(), "Supplier fault completed a healthy peer");
            else replacement.set(world.generateChunkAsync(position));
            return fault;
          });
          future.completeAsync(() -> {
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw (Error) failure;
          }, Runnable::run);
          var outcome = observed.get(3, TimeUnit.SECONDS);
          require(outcome instanceof CompletionException, "Asynchronous fault lost its completion envelope");
          require(kind.equals("COMPLETION") ? outcome == failure : outcome.getCause() == failure, "Asynchronous completion replaced the original fault");
          require(reads.get() == 0 && calls.get() == 1 && !future.isCancelled(), "Engine read host text or changed async callback/cancellation semantics");
          var healthy = shared ? peer : replacement.get(); token.cancel();
          require(healthy != null && !healthy.isDone() && world.inFlightChunks() == 1, "Retired token cancelled a healthy request");
          gate.close(); retained.add(healthy.get(3, TimeUnit.SECONDS));
          require(world.inFlightChunks() == 0 && failure.getSuppressed().length == 0 && reads.get() == 0, "Supplier-fault cleanup mutated host state");
          try {
            String detail = failure.getMessage(); require(!unreadable && detail.equals("message-1"), "Host's first explicit message read was consumed");
          } catch (AssertionError inaccessible) {
            if (!unreadable || !"message reader".equals(inaccessible.getMessage())) throw inaccessible;
          }
          require(reads.get() == 1, "Host text was not read exactly once by the host"); explicitReads += reads.get(); cases++;
        }
    long cells = 0;
    for (var chunk : retained) for (int z = 0; z < chunk.size(); z++) for (int y = 0; y < chunk.size(); y++) for (int x = 0; x < chunk.size(); x++) {
      require(chunk.getVoxelTypeId(x, y, z) == 0, "Failed supplier contaminated a retained generated snapshot"); cells++;
    }
    require(cases == 32 && explicitReads == 32 && retained.size() == 32 && cells == 1152, "Unexpected asynchronous supplier replay dimensions");
    return Map.of("cases", cases, "engineTextReads", 0, "explicitHostReads", explicitReads, "retainedChunks", retained.size(), "retainedCells", cells, "roots", List.of(2, 4));
  }

  private static void ready(CountDownLatch phase) throws InterruptedException {
    require(phase.await(3, TimeUnit.SECONDS), "Deferred ownership phase was not reached");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
