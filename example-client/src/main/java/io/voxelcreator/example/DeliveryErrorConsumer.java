package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferred independent engine-JAR replay of synchronous/stage Error delivery-policy parity. */
public final class DeliveryErrorConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]).toAbsolutePath());
  }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var project = fixture();
    String before = ProjectIO.JSON.writeValueAsString(project);
    var compiled = new CompiledProject(project);
    var retained = new ArrayList<VoxelChunk>();
    long fields = 0;
    var sampler = compiled.noise2D("constant", compiled.defaultSeed());
    for (int z = -1; z <= 1; z++)
      for (int x = -3; x <= 3; x++) {
        require(sampler.sample(x, z) == .75, "Delivery-error fixture scalar changed");
        fields++;
      }
    long cases = 0, completed = 0, failed = 0, cancelled = 0;
    try (var engine = new NoiseGeneratorEngine(2, 32, 16L << 20)) {
      for (int root : new int[] {2, 4})
        for (var order : RegionRequest.DeliveryOrder.values())
          for (var policy : RegionRequest.FailurePolicy.values())
            for (String mode : List.of("DIRECT", "FAILED_FUTURE", "CONVERSION", "OBSERVER"))
              try (var world =
                  engine.openWorld(
                      compiled,
                      new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
                var error = new AssertionError("delivery-error-" + mode);
                var host = new CompletableFuture<Void>();
                if (mode.equals("FAILED_FUTURE")) host.completeExceptionally(error);
                var delivered = new LinkedHashMap<ChunkPos, Integer>();
                var attempted = new ArrayList<VoxelChunk>();
                var bounds =
                    new RegionBounds(
                        new BlockPos(-root, -root, -root), new BlockPos(2 * root - 1, -1, -1));
                var task =
                    world.streamRegion(
                        new RegionRequest(bounds, policy, order, 2),
                        chunk -> {
                          delivered.merge(chunk.position(), 1, Integer::sum);
                          attempted.add(chunk);
                          if (attempted.size() == 1) {
                            if (mode.equals("DIRECT")) throw error;
                            return stage(host, mode, error);
                          }
                          return CompletableFuture.completedFuture(null);
                        });
                var summary = task.completion().get(3, TimeUnit.SECONDS);
                boolean keepGoing = policy == RegionRequest.FailurePolicy.CONTINUE;
                require(
                    summary
                        .progress()
                        .equals(
                            new TaskProgress(
                                keepGoing ? "COMPLETED_WITH_ERRORS" : "CANCELLED",
                                0,
                                keepGoing ? 2 : 0,
                                1,
                                keepGoing ? 0 : 2)),
                    "Synchronous/stage Error policy counts differ");
                require(
                    summary.requested().equals(bounds)
                        && summary
                            .effective()
                            .orElseThrow()
                            .equals(
                                new RegionBounds(
                                    new BlockPos(-root, -1, -root),
                                    new BlockPos(2 * root - 1, -1, -1))),
                    "Delivery failure changed requested/effective bounds");
                require(
                    attempted.size() == (keepGoing ? 3 : 1)
                        && delivered.values().stream().allMatch(count -> count == 1),
                    "A consumer was retried or healthy delivery was skipped");
                var detail = summary.failures().getFirst();
                require(
                    summary.failures().size() == 1
                        && detail.chunk().equals(attempted.getFirst().position())
                        && detail.phase().equals("DELIVERY")
                        && detail.message().equals(error.getMessage()),
                    "Delivery failure lost its chunk/phase/original message");
                require(
                    error.getSuppressed().length == 0 && !host.isCancelled(),
                    "Engine mutated a host-owned failure/stage");
                if (mode.equals("FAILED_FUTURE")) {
                  try {
                    host.join();
                    throw new AssertionError("Host failure disappeared");
                  } catch (CompletionException observed) {
                    require(observed.getCause() == error, "Host Error identity changed");
                  }
                } else
                  require(!host.isDone(), "Engine completed a host stage while recording a fault");
                require(
                    task.progress().equals(summary.progress()) && world.inFlightChunks() == 0,
                    "Delivery fault stranded region/chunk ownership");
                retained.addAll(attempted);
                completed += summary.progress().completed();
                failed += summary.progress().failed();
                cancelled += summary.progress().cancelled();
                cases++;
              }
    }
    long cells = 0, solid = 0;
    for (var chunk : retained) {
      int size = chunk.size();
      for (int z = 0; z < size; z++)
        for (int y = 0; y < size; y++)
          for (int x = 0; x < size; x++) {
            int wy = chunk.position().y() * size + y;
            int expected = wy >= -1 && wy <= 2 ? 1 : 0;
            require(
                chunk.getVoxelTypeId(x, y, z) == expected,
                "Failed delivery invalidated an exact retained chunk");
            cells++;
            if (expected == 1) solid++;
          }
    }
    require(
        cases == 32
            && completed == 32
            && failed == 32
            && cancelled == 32
            && retained.size() == 64
            && cells == 2304
            && solid == 640
            && fields == 21,
        "Unexpected delivery-error replay dimensions");
    require(
        before.equals(ProjectIO.JSON.writeValueAsString(project)),
        "Delivery errors changed authoring data");
    new ProjectIO().save(out.resolve("delivery-errors.pvproj.json"), project);
    Files.writeString(
        out.resolve("delivery-errors.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "cases",
                cases,
                "completed",
                completed,
                "failed",
                failed,
                "cancelled",
                cancelled,
                "retainedChunks",
                retained.size(),
                "retainedCells",
                cells,
                "solidCells",
                solid,
                "fields",
                fields,
                "roots",
                List.of(2, 4))));
    System.out.println("DELIVERY_ERROR_CONSUMER_PASS");
  }

  @SuppressWarnings("unchecked")
  private static CompletionStage<Void> stage(
      CompletableFuture<Void> host, String mode, Error error) {
    if (mode.equals("FAILED_FUTURE")) return host;
    return (CompletionStage<Void>)
        Proxy.newProxyInstance(
            DeliveryErrorConsumer.class.getClassLoader(),
            new Class<?>[] {CompletionStage.class},
            (proxy, method, args) -> {
              if (method.getName().equals("equals")) return proxy == args[0];
              if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
              if (method.getName().equals("toCompletableFuture")) {
                if (mode.equals("CONVERSION")) throw error;
                throw new UnsupportedOperationException("No host conversion");
              }
              if (method.getName().equals("whenComplete")) throw error;
              try {
                return method.invoke(host, args);
              } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
              }
            });
  }

  private static ProjectData fixture() {
    var project = new ProjectData();
    project.project.seed = 41;
    project.project.minY = -1;
    project.project.maxY = 2;
    var type = new VoxelType();
    type.id = "stone";
    project.voxelTypes.add(type);
    var noise = new Noise();
    noise.id = "constant";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", .75);
    project.noise2D.add(noise);
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.threshold = .5;
    layer.defaults.voxelTypeUuid = type.uuid;
    var operation = new Operation();
    operation.id = "selection";
    operation.noiseGeneratorUuid = noise.uuid;
    operation.interpretation = "SELECTION";
    layer.operations.add(operation);
    project.worldGenerator.layers.add(layer);
    return project;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
