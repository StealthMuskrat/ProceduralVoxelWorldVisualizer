package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Deferred public engine-JAR replay of ownership changes during a normally false parent poll. */
public final class AbandonmentRecheckConsumer {
  private static final ChunkPos TARGET = new ChunkPos(-1, 0, 0), GOOD = new ChunkPos(0, 0, 0);
  private enum Scenario { FUTURE, TOKEN, FAULT, CLOSE, PEER, REPLACEMENT }
  private record Fixture(ProjectData project, UUID first, UUID second, Profiler.Kind boundary) {}
  private static Fixture fixture(int mode) {
    var p = new ProjectData(); p.project.minY = -8; p.project.maxY = 7;
    var a = new VoxelType(); a.id = "first"; var b = new VoxelType(); b.id = "second"; p.voxelTypes.addAll(List.of(a, b));
    var noise = new Noise(); noise.id = "constant"; noise.dimension = mode == 2 ? 3 : 2; noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear(); noise.algorithm.parameters.put("value", .25);
    (mode == 2 ? p.noise3D : p.noise2D).add(noise);
    var layer = new Layer(); layer.id = "layer"; layer.defaults.action = "SET"; layer.defaults.voxelTypeUuid = a.uuid;
    if (mode == 1) layer.defaults.samplingTransforms.add(new SamplingTransform());
    var first = new Operation(); first.id = "first"; first.interpretation = "ALL"; first.noiseGeneratorUuid = noise.uuid;
    var second = new Operation(); second.id = "second"; second.interpretation = "ALL"; second.noiseGeneratorUuid = noise.uuid;
    second.overrides.put("voxelTypeUuid", ProjectIO.JSON.valueToTree(b.uuid));
    layer.operations.addAll(List.of(first, second)); p.worldGenerator.layers.add(layer);
    return new Fixture(p, first.uuid, second.uuid, mode == 0 ? Profiler.Kind.FIELD_PREFETCH : Profiler.Kind.OPERATION);
  }
  private static final class Gate implements BooleanSupplier, AutoCloseable {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicBoolean held = new AtomicBoolean(); final Profiler.Capture capture; final Fixture fixture; final long context;
    Gate(Profiler.Capture capture, Fixture fixture, long context) { this.capture = capture; this.fixture = fixture; this.context = context; }
    public boolean getAsBoolean() {
      boolean first = capture.snapshot().samples().stream().anyMatch(s -> s.contextId() == context && TARGET.equals(s.chunk())
          && s.kind() == fixture.boundary && fixture.first.equals(s.sourceUuid()) && s.outcome() == Profiler.Outcome.SUCCESS);
      if (first && held.compareAndSet(false, true)) {
        entered.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Parent poll not released"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
      }
      return false;
    }
    public void close() { release.countDown(); }
  }
  private static long count(Profiler.Capture c, long context, Profiler.Kind kind, UUID source, Profiler.Outcome outcome) {
    return c.snapshot().samples().stream().filter(s -> s.contextId() == context && TARGET.equals(s.chunk()) && s.kind() == kind
        && (source == null || source.equals(s.sourceUuid())) && (outcome == null || outcome == s.outcome())).count();
  }
  private static void check(int mode, int root, WorldOptions.CachePolicy policy, Scenario scenario, List<VoxelChunk> retained) throws Exception {
    var f = fixture(mode); String authored = ProjectIO.JSON.writeValueAsString(f.project);
    var options = new WorldOptions(null, root, policy, 1L << 20); var compiled = new CompiledProject(f.project);
    try (var e = new NoiseGeneratorEngine(1, 16, 8L << 20); var w = e.openWorld(compiled, options); var independent = e.openWorld(compiled, options)) {
      retained.add(w.generateChunk(GOOD)); var c = e.profiler().start(new Profiler.Options(60000, 4096, 128, 1));
      try (var gate = new Gate(c, f, w.contextId())) {
        var token = new CancellationToken(gate); var first = w.generateChunkAsync(TARGET, token);
        CompletableFuture<VoxelChunk> peer = null; var fault = new IllegalStateException("withdrawn");
        try {
          require(gate.entered.await(3, TimeUnit.SECONDS) && !first.isDone(), "Live parent boundary not held");
          if (scenario == Scenario.PEER) peer = w.generateChunkAsync(TARGET);
          switch (scenario) {
            case TOKEN -> require(token.cancel(), "Token was already cancelled");
            case FAULT -> require(first.completeExceptionally(fault), "Subscriber was already terminal");
            case CLOSE -> w.close();
            default -> require(first.cancel(false), "Subscriber cancellation failed");
          }
          if (scenario == Scenario.REPLACEMENT) peer = w.generateChunkAsync(TARGET, GenerationPriority.INTERACTIVE);
        } finally { gate.close(); }
        try { first.get(5, TimeUnit.SECONDS); throw new AssertionError("Withdrawn subscriber returned a chunk"); }
        catch (CancellationException expected) { require(scenario != Scenario.FAULT, "Explicit fault became cancellation"); }
        catch (ExecutionException failure) { require(scenario == Scenario.FAULT && failure.getCause() == fault, "Subscriber fault identity changed"); }
        var barrier = independent.generateChunkAsync(TARGET);
        if (peer != null) retained.add(peer.get(5, TimeUnit.SECONDS));
        retained.add(barrier.get(5, TimeUnit.SECONDS));
        boolean needed = scenario == Scenario.PEER || scenario == Scenario.REPLACEMENT;
        long full = mode == 0 ? 1 : (long) root * root * root;
        require(count(c, w.contextId(), f.boundary, f.first, null) == (needed ? full + (scenario == Scenario.REPLACEMENT ? 1 : 0) : 1)
            && count(c, w.contextId(), f.boundary, f.second, null) == (needed ? full : 0), "Retired owner evaluated more operations or discarded a peer");
        require(count(c, w.contextId(), Profiler.Kind.CHUNK_GENERATION, null, Profiler.Outcome.CANCELLED) == (scenario == Scenario.PEER ? 0 : 1)
            && count(c, w.contextId(), Profiler.Kind.CHUNK_GENERATION, null, Profiler.Outcome.SUCCESS) == (needed ? 1 : 0), "Generation ownership outcomes changed");
        long cached = scenario == Scenario.CLOSE || policy == WorldOptions.CachePolicy.NO_CACHE ? 0 : needed ? 2 : 1;
        require(e.metrics().completed() == (needed ? 3 : 2) && w.cacheStats().chunks() == cached
            && w.inFlightChunks() == 0 && independent.inFlightChunks() == 0 && e.metrics().queued() == 0
            && e.metrics().stagingBytes() == 0 && e.metrics().collectionBytes() == 0
            && e.metrics().cacheBytes() == w.cacheStats().bytes() + independent.cacheStats().bytes(), "Ownership/admission accounting leaked");
      }
    }
    require(authored.equals(ProjectIO.JSON.writeValueAsString(f.project)), "Lifecycle changes mutated caller JSON");
  }
  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var retained = new ArrayList<VoxelChunk>(); long cases = 0, retired = 0, closed = 0, peers = 0, replacements = 0;
    for (int mode = 0; mode < 3; mode++) for (int root : new int[] {2, 4})
      for (var policy : new WorldOptions.CachePolicy[] {WorldOptions.CachePolicy.NO_CACHE, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE})
        for (var scenario : Scenario.values()) {
          check(mode, root, policy, scenario, retained); cases++;
          switch (scenario) { case CLOSE -> closed++; case PEER -> peers++; case REPLACEMENT -> replacements++; default -> retired++; }
        }
    long cells = 0, occupied = 0;
    for (var chunk : retained) {
      int size = chunk.size(), volume = size * size * size; var visited = new BitSet(volume);
      for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
        require(chunk.getVoxelTypeId(x, y, z) == 2, "Retained complete voxel changed"); cells++;
      }
      chunk.forEachOccupied((x, y, z, type) -> {
        require(x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size && type == 2, "Occupied coordinate/type changed");
        int index = (z * size + y) * size + x; require(!visited.get(index), "Duplicate occupied cell"); visited.set(index);
      });
      require(visited.cardinality() == volume && chunk.storageStatistics().occupiedVoxels() == volume
          && Arrays.equals(new int[] {0, 2}, chunk.paletteRuntimeIds()), "Occupied/palette metadata changed");
      occupied += visited.cardinality();
    }
    require(cases == 72 && retired == 36 && closed == 12 && peers == 12 && replacements == 12
        && retained.size() == 168 && cells == 6048 && occupied == cells, "Lifecycle replay dimensions changed");
    Files.writeString(out.resolve("abandonment-recheck-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "cases", cases, "retiredLastSubscribers", retired, "closedContexts", closed, "healthyPeers", peers,
        "replacements", replacements, "retainedChunks", retained.size(), "retainedCells", cells,
        "occupiedCells", occupied, "roots", List.of(2, 4))));
    System.out.println("ABANDONMENT_RECHECK_CONSUMER_PASS");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
