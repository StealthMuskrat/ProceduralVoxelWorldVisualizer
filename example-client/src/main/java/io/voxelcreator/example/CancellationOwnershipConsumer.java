package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Deferred engine-JAR-only callback claims, queued cancellation and shared parent-fault replay. */
public final class CancellationOwnershipConsumer {
  private static final class Gate implements BooleanSupplier, AutoCloseable {
    final Thread submitter = Thread.currentThread();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

    public boolean getAsBoolean() {
      if (Thread.currentThread() != submitter) {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException interrupted) {
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
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var closedToken = new CancellationToken();
    var first = new AtomicReference<CancellationToken.Registration>();
    var second = new AtomicReference<CancellationToken.Registration>();
    var claimed = new AtomicInteger();
    first.set(
        closedToken.onCancel(
            () -> {
              claimed.incrementAndGet();
              second.get().close();
            }));
    second.set(
        closedToken.onCancel(
            () -> {
              claimed.incrementAndGet();
              first.get().close();
            }));
    require(
        closedToken.cancel() && claimed.get() == 1 && !closedToken.cancel(),
        "Closed captured callback still ran or replayed");

    var project = fixture();
    String original = ProjectIO.JSON.writeValueAsString(project);
    var compiled = new CompiledProject(project);
    var retained = new ArrayList<VoxelChunk>();
    long fields = 0;
    var sampler = compiled.noise2D("coordinate", compiled.defaultSeed());
    for (int z = -1; z <= 1; z++)
      for (int x = -3; x <= 3; x++) {
        require(
            Double.doubleToRawLongBits(sampler.sample(x, z))
                == Double.doubleToRawLongBits(x + .125),
            "Cancellation fixture field mismatch");
        fields++;
      }
    int listenerErrors = 0, parentFaults = 0;
    try (var engine = new NoiseGeneratorEngine(1, 32, 16L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          try (var gate = new Gate()) {
            var held =
                world.generateChunkAsync(new ChunkPos(-1, 0, 0), new CancellationToken(gate));
            ready(gate.entered);
            var token = new CancellationToken();
            var cancelled = world.generateChunkAsync(new ChunkPos(0, 0, 0), token);
            var peer = world.generateChunkAsync(new ChunkPos(0, 0, 0));
            var errors =
                List.of(
                    new AssertionError("listener-one"),
                    new AssertionError("listener-two"),
                    new AssertionError("listener-three"));
            var calls = new AtomicInteger();
            for (var error : errors)
              token.onCancel(
                  () -> {
                    calls.incrementAndGet();
                    throw error;
                  });
            Error observed;
            try {
              token.cancel();
              throw new AssertionError("Listener errors disappeared");
            } catch (Error failure) {
              observed = failure;
            }
            var seen = new HashSet<Throwable>(Arrays.asList(observed.getSuppressed()));
            seen.add(observed);
            require(
                seen.equals(new HashSet<>(errors)) && calls.get() == 3,
                "Listener error aborted cancellation fanout");
            require(
                token.isCancelled() && !token.cancel() && cancelled.isCancelled() && !peer.isDone(),
                "Queued cancellation did not retire just its subscriber");
            listenerErrors += calls.get();
            gate.close();
            retained.add(held.get(3, TimeUnit.SECONDS));
            retained.add(peer.get(3, TimeUnit.SECONDS));
          }

          long baseline = engine.metrics().completed();
          try (var gate = new Gate()) {
            var held =
                world.generateChunkAsync(new ChunkPos(1, -1, -1), new CancellationToken(gate));
            ready(gate.entered);
            var failure = new AssertionError("terminal parent");
            var token =
                new CancellationToken(
                    () -> {
                      if (engine.metrics().completed() >= baseline + 2) throw failure;
                      return false;
                    });
            var bad = world.generateChunkAsync(new ChunkPos(-1, -1, -1), token);
            var peer = world.generateChunkAsync(new ChunkPos(-1, -1, -1));
            gate.close();
            retained.add(held.get(3, TimeUnit.SECONDS));
            failedWith(bad, failure);
            retained.add(peer.get(3, TimeUnit.SECONDS));
            require(token.cancel(), "Faulted subscriber registration was not retired");
            parentFaults++;
          }

          var registrationFailure = new AssertionError("registration parent");
          var token =
              new CancellationToken(
                  () -> {
                    if (world.inFlightChunks() > 0) throw registrationFailure;
                    return false;
                  });
          var bad = world.generateChunkAsync(new ChunkPos(0, -1, 0), token);
          failedWith(bad, registrationFailure);
          require(world.inFlightChunks() == 0, "Registration fault stranded shared ownership");
          retained.add(world.generateChunkAsync(new ChunkPos(0, -1, 0)).get(3, TimeUnit.SECONDS));
          parentFaults++;
          require(
              world.sharedRequests() == 2
                  && world.inFlightChunks() == 0
                  && engine.metrics().stagingBytes() == 0,
              "Shared peer or staging admission did not retire");
        }
    }
    long cells = 0;
    for (var chunk : retained) {
      int size = chunk.size();
      for (int z = 0; z < size; z++)
        for (int y = 0; y < size; y++)
          for (int x = 0; x < size; x++) {
            int wx = chunk.position().x() * size + x;
            int wy = chunk.position().y() * size + y;
            int expected = wy >= -2 && wy <= 2 && wx >= 1 ? 1 : 0;
            require(
                chunk.getVoxelTypeId(x, y, z) == expected,
                "Cancellation or parent fault changed a retained exact snapshot");
            cells++;
          }
    }
    require(
        fields == 21
            && listenerErrors == 6
            && parentFaults == 4
            && retained.size() == 10
            && cells == 360,
        "Unexpected replay dimensions");
    require(
        original.equals(ProjectIO.JSON.writeValueAsString(project)),
        "Cancellation replay mutated authoring data");
    new ProjectIO().save(out.resolve("cancellation-ownership.pvproj.json"), project);
    Files.writeString(
        out.resolve("cancellation-ownership.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "fields",
                fields,
                "listenerErrors",
                listenerErrors,
                "parentFaults",
                parentFaults,
                "retainedChunks",
                retained.size(),
                "retainedCells",
                cells,
                "roots",
                List.of(2, 4))));
    ChunkSubscriberOwnershipConsumer.run(out.resolve("chunk-subscribers"));
    CancellationPollConsumer.run(out.resolve("parent-polls"));
    WorldOperationCancellationConsumer.run(out.resolve("world-operations"));
    System.out.println("CANCELLATION_OWNERSHIP_CONSUMER_PASS");
  }

  private static void ready(CountDownLatch phase) throws InterruptedException {
    require(phase.await(3, TimeUnit.SECONDS), "Generation gate was not reached");
  }

  private static void failedWith(CompletableFuture<VoxelChunk> future, Error expected)
      throws Exception {
    try {
      future.get(3, TimeUnit.SECONDS);
      throw new AssertionError("Parent failure exposed a chunk");
    } catch (ExecutionException failure) {
      require(failure.getCause() == expected, "Parent failure changed cause");
    }
  }

  private static ProjectData fixture() {
    var p = new ProjectData();
    p.project.seed = 17;
    p.project.minY = -2;
    p.project.maxY = 2;
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var noise = new Noise();
    noise.id = "coordinate";
    noise.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "coordinate-expression", 2);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x + 0.125"));
    noise.processing.add(expression);
    noise.outputBinding = Binding.earlier(expression.uuid);
    p.noise2D.add(noise);
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.threshold = .25;
    layer.defaults.voxelTypeUuid = type.uuid;
    var operation = new Operation();
    operation.id = "selection";
    operation.noiseGeneratorUuid = noise.uuid;
    operation.interpretation = "SELECTION";
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
    return p;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
