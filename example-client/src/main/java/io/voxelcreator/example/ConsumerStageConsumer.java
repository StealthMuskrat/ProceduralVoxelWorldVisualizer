package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deferred engine-JAR-only replay with real CompletionStages that cannot convert to futures. */
public final class ConsumerStageConsumer {
  private static final RegionBounds REGION =
      new RegionBounds(new BlockPos(-4, -2, -2), new BlockPos(3, 3, 1));
  private static final RegionBounds THREE =
      new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(5, 1, 1));

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var project = fixture();
    String before = ProjectIO.JSON.writeValueAsString(project);
    var compiled = new CompiledProject(project);
    var retained = new ArrayList<VoxelChunk>();
    long fields = 0;
    var sampler = compiled.noise2D("coordinate", compiled.defaultSeed());
    for (int z = -1; z <= 1; z++)
      for (int x = -3; x <= 3; x++) {
        require(
            Double.doubleToRawLongBits(sampler.sample(x, z))
                == Double.doubleToRawLongBits(x + .125),
            "Acknowledgement fixture field mismatch");
        fields++;
      }
    long deliveredChunks = 0;
    try (var engine = new NoiseGeneratorEngine(2, 32, 16L << 20)) {
      for (int root : new int[] {2, 4})
        for (var order : RegionRequest.DeliveryOrder.values())
          try (var world = world(engine, compiled, root)) {
            var host = new CompletableFuture<Void>();
            var observed = new CountDownLatch(1);
            var calls = new AtomicInteger();
            long baselineCompleted = engine.metrics().completed();
            var task =
                world.streamRegion(
                    new RegionRequest(REGION, RegionRequest.FailurePolicy.CONTINUE, order, 2),
                    chunk -> {
                      retained.add(chunk);
                      if (calls.incrementAndGet() == 1) return opaque(host, observed);
                      return opaque(CompletableFuture.completedFuture(null), new CountDownLatch(0));
                    });
            try {
              ready(observed);
              require(
                  calls.get() == 1 && !host.isDone() && !task.completion().isDone(),
                  "Opaque stage lost real backpressure");
              require(
                  engine.metrics().completed() - baselineCompleted <= 2,
                  "Acknowledgement released in-flight capacity early");
              host.complete(null);
              var summary = task.completion().get(3, TimeUnit.SECONDS);
              long expectedChunks = root == 2 ? 24 : 8;
              require(
                  summary.progress().completed() == expectedChunks
                      && summary.progress().failed() == 0
                      && summary.progress().cancelled() == 0
                      && calls.get() == expectedChunks,
                  "Opaque success counts changed");
              deliveredChunks += summary.progress().completed();
            } finally {
              host.complete(null);
              task.cancel();
            }
          }

      for (var policy : RegionRequest.FailurePolicy.values())
        try (var world = world(engine, compiled, 2)) {
          var host = new CompletableFuture<Void>();
          var observed = new CountDownLatch(1);
          var calls = new AtomicInteger();
          var task =
              world.streamRegion(
                  new RegionRequest(THREE, policy, RegionRequest.DeliveryOrder.COORDINATE, 1),
                  chunk ->
                      calls.incrementAndGet() == 1
                          ? opaque(host, observed)
                          : CompletableFuture.completedFuture(null));
          try {
            ready(observed);
            host.completeExceptionally(new IllegalArgumentException("ack-boom"));
            var summary = task.completion().get(3, TimeUnit.SECONDS);
            boolean keepGoing = policy == RegionRequest.FailurePolicy.CONTINUE;
            require(
                summary.progress().failed() == 1
                    && summary.progress().completed() == (keepGoing ? 2 : 0)
                    && summary.progress().cancelled() == (keepGoing ? 0 : 2)
                    && calls.get() == (keepGoing ? 3 : 1),
                "Acknowledgement failure policy or once-only delivery changed");
            require(
                summary.failures().getFirst().phase().equals("DELIVERY")
                    && summary.failures().getFirst().message().equals("ack-boom"),
                "Acknowledgement failure lost its phase/cause");
            require(
                host.isCompletedExceptionally() && !host.isCancelled(),
                "Engine changed the host failure");
          } finally {
            task.cancel();
            host.complete(null);
          }
        }

      for (boolean closeContext : List.of(false, true))
        try (var world = world(engine, compiled, 2);
            var peer = world(engine, compiled, 2)) {
          var host = new CompletableFuture<Void>();
          var observed = new CountDownLatch(1);
          var calls = new AtomicInteger();
          var task =
              world.streamRegion(
                  new RegionRequest(
                      THREE,
                      RegionRequest.FailurePolicy.CONTINUE,
                      RegionRequest.DeliveryOrder.COORDINATE,
                      1),
                  chunk -> {
                    calls.incrementAndGet();
                    return opaque(host, observed);
                  });
          try {
            ready(observed);
            if (closeContext) world.close();
            else require(task.cancel(), "Owner cancellation rejected");
            var summary = task.completion().get(3, TimeUnit.SECONDS);
            require(
                summary.progress().cancelled() == 3
                    && summary.progress().failed() == 0
                    && summary.progress().completed() == 0
                    && calls.get() == 1,
                "Cancelled opaque delivery changed counts or retried");
            require(!host.isDone(), "Engine completed or cancelled a host-owned stage");
            retained.add(peer.generateChunkAsync(new ChunkPos(1, 0, 0)).get(3, TimeUnit.SECONDS));
          } finally {
            host.complete(null);
            task.cancel();
          }
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
            int expected = wy >= -1 && wy <= 2 && wx >= 1 ? 1 : 0;
            require(
                chunk.getVoxelTypeId(x, y, z) == expected,
                "Snapshot changed after acknowledgement/cancellation/context closure");
            cells++;
          }
    }
    require(
        fields == 21 && deliveredChunks == 64 && retained.size() == 66 && cells == 1424,
        "Unexpected replay dimensions");
    require(
        before.equals(ProjectIO.JSON.writeValueAsString(project)),
        "Streaming mutated authoring data");
    new ProjectIO().save(out.resolve("consumer-stages.pvproj.json"), project);
    Files.writeString(
        out.resolve("consumer-stages.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "fields",
                fields,
                "deliveredChunks",
                deliveredChunks,
                "retainedChunks",
                retained.size(),
                "retainedCells",
                cells,
                "failurePolicies",
                2,
                "cancellationModes",
                2,
                "roots",
                List.of(2, 4))));
    DeliveryErrorConsumer.run(out.resolve("delivery-errors"));
    DiagnosticListenerConsumer.run(out.resolve("diagnostic-listeners"));
    System.out.println("CONSUMER_STAGE_CONSUMER_PASS");
  }

  private static WorldContext world(
      NoiseGeneratorEngine engine, CompiledProject project, int root) {
    return engine.openWorld(
        project, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  private static void ready(CountDownLatch observed) throws InterruptedException {
    require(observed.await(3, TimeUnit.SECONDS), "Host stage was not observed");
  }

  @SuppressWarnings("unchecked")
  private static CompletionStage<Void> opaque(
      CompletableFuture<Void> host, CountDownLatch observed) {
    return (CompletionStage<Void>)
        Proxy.newProxyInstance(
            ConsumerStageConsumer.class.getClassLoader(),
            new Class<?>[] {CompletionStage.class},
            (proxy, method, args) -> {
              if (method.getName().equals("equals")) return proxy == args[0];
              if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
              if (method.getName().equals("toCompletableFuture"))
                throw new UnsupportedOperationException("No host future conversion");
              try {
                Object result = method.invoke(host, args);
                if (method.getName().equals("handle")) observed.countDown();
                return result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private static ProjectData fixture() {
    var p = new ProjectData();
    p.project.seed = 17;
    p.project.minY = -1;
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
