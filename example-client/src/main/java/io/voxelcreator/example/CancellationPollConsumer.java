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

/** Deferred engine-JAR-only replay of explicit cancellation during a normally false parent poll. */
public final class CancellationPollConsumer {
  @FunctionalInterface private interface Query { void run(CancellationToken token); }

  private static void check(ExecutorService queries, boolean held, boolean throwsCancellation, Query query) throws Exception {
    var owner = new AtomicReference<CancellationToken>(); var armed = new AtomicBoolean();
    var reads = new AtomicInteger(); var callbacks = new AtomicInteger();
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var token = new CancellationToken(() -> {
      if (!armed.get()) return false;
      reads.incrementAndGet();
      if (held) {
        entered.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Parent poll was not released"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
      } else owner.get().cancel();
      return false;
    });
    owner.set(token); token.onCancel(callbacks::incrementAndGet); armed.set(true);
    Runnable action = () -> {
      boolean rejected = false;
      try { query.run(token); } catch (CancellationException expected) { rejected = true; }
      require(rejected == throwsCancellation, "Parent false return lost explicit cancellation");
    };
    if (held) {
      var pending = queries.submit(action);
      try {
        require(entered.await(3, TimeUnit.SECONDS), "Query did not enter parent poll");
        require(token.cancel() && callbacks.get() == 1, "Explicit cancellation did not notify once");
      } finally { release.countDown(); }
      pending.get(5, TimeUnit.SECONDS);
    } else action.run();
    require(token.isCancelled() && !token.cancel() && reads.get() == 1 && callbacks.get() == 1,
        "Poll repeated parent access/callbacks or lost terminal state");
  }

  private static ProjectData fixture() {
    var p = new ProjectData(); p.project.seed = 17; p.project.minY = -4; p.project.maxY = 3;
    var type = new VoxelType(); type.id = "solid"; p.voxelTypes.add(type);
    for (int dimension : new int[] {2, 3}) {
      var noise = new Noise(); noise.id = "field-" + dimension; noise.dimension = dimension; noise.algorithm.key = "CONSTANT";
      var expression = ProcessingRegistry.create("EXPRESSION", "coordinate", dimension);
      expression.parameters.put("expression", ProjectIO.JSON.valueToTree(dimension == 2 ? "x+2*z+.25" : "x+2*z+4*y+.25"));
      noise.processing.add(expression); noise.outputBinding = Binding.earlier(expression.uuid);
      (dimension == 2 ? p.noise2D : p.noise3D).add(noise);
    }
    var layer = new Layer(); layer.id = "layer"; layer.defaults.action = "SET"; layer.defaults.voxelTypeUuid = type.uuid;
    var op = new Operation(); op.id = "selection"; op.interpretation = "SELECTION"; op.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
    layer.operations.add(op); p.worldGenerator.layers.add(layer); return p;
  }
  private static long fields(NoiseSampler2D two, NoiseSampler3D three) {
    long count = 0;
    for (int z : new int[] {-2, 0, 3}) for (int x : new int[] {-3, 0, 5}) {
      bits(x + 2 * z + .25, two.sample(x, z)); count++;
      for (int y : new int[] {-1, 2}) { bits(x + 2 * z + 4 * y + .25, three.sample(x, y, z)); count++; }
    }
    return count;
  }
  private static CompletableFuture<VoxelChunk> request(WorldContext world, ChunkPos pos, CancellationToken token, int route) {
    return route == 0 ? world.generateChunkAsync(pos, token)
        : world.generateChunkAsync(pos, token, route == 1 ? GenerationPriority.INTERACTIVE : GenerationPriority.BACKGROUND);
  }
  private static void grid(NoiseSampler2D two, NoiseSampler3D three, int dimension, int axis, double[] destination, CancellationToken token) {
    int width = axis == 0 ? 0 : 1, height = dimension == 3 && axis == 1 ? 0 : 1;
    int depth = axis == dimension - 1 ? 0 : 1;
    if (dimension == 2) two.sampleGrid(2, 3, width, depth, 1, 1, destination, 1, 1, token);
    else three.sampleGrid(2, 1, 3, width, height, depth, 1, 1, 1, destination, 1, 1, 1, token);
  }
  private static double[] destination() { return new double[] {-0., Double.longBitsToDouble(0x7ff8000000000042L), 17.}; }
  private static void unchanged(double[] values) {
    var expected = destination(); for (int i = 0; i < values.length; i++) bits(expected[i], values[i]);
  }

  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var p = fixture(); String original = ProjectIO.JSON.writeValueAsString(p);
    var compiled = new CompiledProject(p); var two = compiled.noise2D("field-2", 17); var three = compiled.noise3D("field-3", 17);
    var retained = new ArrayList<VoxelChunk>(); long observations = fields(two, three), cached = 0, fresh = 0, direct = 0, batches = 0;
    long untouched = 0, controls = 0, batchValues = 0, padding = 0;
    try (var queries = Executors.newVirtualThreadPerTaskExecutor()) {
      for (boolean held : new boolean[] {false, true}) {
        check(queries, held, false, token -> require(token.isCancelled(), "Same poll returned false")); direct++;
        check(queries, held, true, CancellationToken::throwIfCancelled); direct++;
      }
      for (int root : new int[] {2, 4}) for (var policy : List.of(WorldOptions.CachePolicy.NO_CACHE,
          WorldOptions.CachePolicy.MEMORY_CACHE, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, WorldOptions.CachePolicy.USER_MANAGED)) {
        try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
            var world = engine.openWorld(compiled, new WorldOptions(null, root, policy, 1L << 20))) {
          var pos = new ChunkPos(-1, 0, 0); boolean cache = policy != WorldOptions.CachePolicy.NO_CACHE;
          VoxelChunk old = cache ? world.generateChunk(pos) : null;
          for (int route = 0; route < 3; route++) for (boolean held : new boolean[] {false, true}) {
            int selected = route; long hits = world.cacheStats().hits();
            check(queries, held, true, token -> {
              var future = request(world, pos, token, selected);
              require(future.isCancelled(), "Cancelled request exposed an admitted/cached future"); future.join();
            });
            require(world.cacheStats().hits() == hits && engine.metrics().completed() == (cache ? 1 : 0)
                && world.inFlightChunks() == 0 && engine.metrics().queued() == 0 && engine.metrics().stagingBytes() == 0,
                "Rejected request changed cache/work admission");
            if (cache) { require(world.generateChunk(pos) == old, "Cancellation removed healthy cached peer"); cached++; }
            else fresh++;
          }
          retained.add(cache ? old : world.generateChunk(pos));
        }
      }
      var called = new AtomicInteger(); NoiseSampler2D countedTwo = (x, z) -> { called.incrementAndGet(); return two.sample(x, z); };
      NoiseSampler3D countedThree = (x, y, z) -> { called.incrementAndGet(); return three.sample(x, y, z); };
      for (int dimension : new int[] {2, 3}) for (int axis = -1; axis < dimension; axis++) {
        int empty = axis;
        for (boolean held : new boolean[] {false, true}) {
          var values = destination();
          check(queries, held, true, token -> grid(countedTwo, countedThree, dimension, empty, values, token));
          require(called.get() == 0, "Cancelled batch evaluated sampler"); unchanged(values); untouched += values.length; batches++;
        }
      }
      for (int dimension : new int[] {2, 3}) for (int axis = -1; axis < dimension; axis++) {
        var values = destination(); grid(two, three, dimension, axis, values, new CancellationToken());
        if (axis == -1) {
          bits(dimension == 2 ? 8.25 : 12.25, values[1]); bits(-0., values[0]); bits(17., values[2]); batchValues++; padding += 2;
        } else { unchanged(values); padding += 3; }
        controls++;
      }
    }
    // A parent-only true observation remains passive and can subsequently return false.
    var signal = new AtomicBoolean(); var callbacks = new AtomicInteger(); var passive = new CancellationToken(signal::get);
    passive.onCancel(callbacks::incrementAndGet); signal.set(true); require(passive.isCancelled() && callbacks.get() == 0, "Parent observation drained listeners");
    signal.set(false); require(!passive.isCancelled(), "Parent observation was latched"); passive.cancel(); require(callbacks.get() == 1, "Explicit callback lost");
    long faults = 0;
    for (Throwable fault : List.of(new IllegalStateException("parent"), new AssertionError("parent"))) {
      var owner = new AtomicReference<CancellationToken>();
      var token = new CancellationToken(() -> {
        owner.get().cancel(); if (fault instanceof RuntimeException runtime) throw runtime; throw (Error) fault;
      });
      owner.set(token); Throwable observed = null;
      try { token.isCancelled(); } catch (Throwable failure) { observed = failure; }
      require(observed == fault && token.isCancelled() && fault.getSuppressed().length == 0, "Parent failure identity changed"); faults++;
    }
    observations += fields(two, three); long cells = 0;
    for (var chunk : retained) for (int z = 0; z < chunk.size(); z++) for (int y = 0; y < chunk.size(); y++)
      for (int x = 0; x < chunk.size(); x++) {
        int wx = chunk.position().x() * chunk.size() + x, wy = chunk.position().y() * chunk.size() + y,
            wz = chunk.position().z() * chunk.size() + z;
        require(chunk.getVoxelTypeId(x, y, z) == (wy >= -4 && wy <= 3 && wx + 2 * wz + .25 >= 0 ? 1 : 0), "Cancellation changed retained exact chunk"); cells++;
      }
    require(direct == 4 && cached == 36 && fresh == 12 && batches == 14 && untouched == 42 && controls == 7
        && batchValues == 2 && padding == 19 && faults == 2 && observations == 54 && retained.size() == 8 && cells == 288,
        "Replay dimensions changed");
    require(original.equals(ProjectIO.JSON.writeValueAsString(p)), "Cancellation polling changed authoring data");
    Files.writeString(out.resolve("cancellation-poll-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "direct", direct, "cached", cached, "fresh", fresh, "grids", Map.of("cancelled", batches, "untouchedCells", untouched,
            "controls", controls, "values", batchValues, "padding", padding), "parentFaults", faults,
        "fieldObservations", observations, "retainedChunks", retained.size(), "retainedCells", cells, "roots", List.of(2, 4))));
    System.out.println("CANCELLATION_POLL_CONSUMER_PASS");
  }
  private static void bits(double expected, double actual) {
    require(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual), "Raw field/destination bits differ");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
