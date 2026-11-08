package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Deferred lifecycle changes during one held parent poll at a public operation boundary. */
class AbandonmentRecheckTest {
  private static final ChunkPos TARGET = new ChunkPos(-1, 0, 0), GOOD = new ChunkPos(0, 0, 0);
  private enum Scenario { FUTURE, TOKEN, FAULT, CLOSE, PEER, REPLACEMENT }
  private record Fixture(ProjectData project, UUID first, UUID second, Profiler.Kind boundary) {}
  private Fixture fixture(int mode) {
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
  private long count(Profiler.Capture c, long context, Profiler.Kind kind, UUID source, Profiler.Outcome outcome) {
    return c.snapshot().samples().stream().filter(s -> s.contextId() == context && TARGET.equals(s.chunk()) && s.kind() == kind
        && (source == null || source.equals(s.sourceUuid())) && (outcome == null || outcome == s.outcome())).count();
  }
  private void exact(VoxelChunk c) {
    for (int z = 0; z < c.size(); z++) for (int y = 0; y < c.size(); y++) for (int x = 0; x < c.size(); x++) assertEquals(2, c.getVoxelTypeId(x, y, z));
  }
  private void check(int mode, int root, WorldOptions.CachePolicy policy, Scenario scenario) throws Exception {
    var f = fixture(mode); String authored = ProjectIO.JSON.valueToTree(f.project).toString(); var retained = new ArrayList<VoxelChunk>();
    var options = new WorldOptions(null, root, policy, 1L << 20); var compiled = new CompiledProject(f.project);
    try (var e = new NoiseGeneratorEngine(1, 16, 8L << 20); var w = e.openWorld(compiled, options); var independent = e.openWorld(compiled, options)) {
      retained.add(w.generateChunk(GOOD)); var c = e.profiler().start(new Profiler.Options(60000, 4096, 128, 1));
      try (var gate = new Gate(c, f, w.contextId())) {
        var token = new CancellationToken(gate); var first = w.generateChunkAsync(TARGET, token);
        CompletableFuture<VoxelChunk> peer = null; var fault = new IllegalStateException("withdrawn");
        try {
          assertTrue(gate.entered.await(3, TimeUnit.SECONDS)); assertFalse(first.isDone());
          if (scenario == Scenario.PEER) peer = w.generateChunkAsync(TARGET);
          switch (scenario) {
            case TOKEN -> assertTrue(token.cancel());
            case FAULT -> assertTrue(first.completeExceptionally(fault));
            case CLOSE -> w.close();
            default -> assertTrue(first.cancel(false));
          }
          if (scenario == Scenario.REPLACEMENT) peer = w.generateChunkAsync(TARGET, GenerationPriority.INTERACTIVE);
        } finally { gate.close(); }
        if (scenario == Scenario.FAULT) assertSame(fault, assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS)).getCause());
        else assertThrows(CancellationException.class, () -> first.get(5, TimeUnit.SECONDS));
        var barrier = independent.generateChunkAsync(TARGET);
        if (peer != null) retained.add(peer.get(5, TimeUnit.SECONDS));
        retained.add(barrier.get(5, TimeUnit.SECONDS));
        boolean needed = scenario == Scenario.PEER || scenario == Scenario.REPLACEMENT;
        long full = mode == 0 ? 1 : (long) root * root * root;
        assertEquals(needed ? full + (scenario == Scenario.REPLACEMENT ? 1 : 0) : 1, count(c, w.contextId(), f.boundary, f.first, null));
        assertEquals(needed ? full : 0, count(c, w.contextId(), f.boundary, f.second, null));
        assertEquals(scenario == Scenario.PEER ? 0 : 1, count(c, w.contextId(), Profiler.Kind.CHUNK_GENERATION, null, Profiler.Outcome.CANCELLED));
        assertEquals(needed ? 1 : 0, count(c, w.contextId(), Profiler.Kind.CHUNK_GENERATION, null, Profiler.Outcome.SUCCESS));
        assertEquals(needed ? 3 : 2, e.metrics().completed());
        long cached = scenario == Scenario.CLOSE || policy == WorldOptions.CachePolicy.NO_CACHE ? 0 : needed ? 2 : 1;
        assertEquals(cached, w.cacheStats().chunks()); assertEquals(0, w.inFlightChunks()); assertEquals(0, independent.inFlightChunks());
        assertEquals(0, e.metrics().queued()); assertEquals(0, e.metrics().stagingBytes()); assertEquals(0, e.metrics().collectionBytes());
        assertEquals(w.cacheStats().bytes() + independent.cacheStats().bytes(), e.metrics().cacheBytes());
      }
    }
    retained.forEach(this::exact); assertEquals(authored, ProjectIO.JSON.valueToTree(f.project).toString());
  }
  private void cases(Scenario... scenarios) throws Exception {
    for (int mode = 0; mode < 3; mode++) for (int root : new int[] {2, 4})
      for (var policy : new WorldOptions.CachePolicy[] {WorldOptions.CachePolicy.NO_CACHE, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE})
        for (var scenario : scenarios) check(mode, root, policy, scenario);
  }
  @Test void lastSubscriberRetirementWinsOverItsNormallyFalseParentPoll() throws Exception { cases(Scenario.FUTURE, Scenario.TOKEN, Scenario.FAULT); }
  @Test void contextClosureWinsOverItsNormallyFalseParentPoll() throws Exception { cases(Scenario.CLOSE); }
  @Test void healthyAdmittedPeerKeepsTheOriginalGenerationAlive() throws Exception { cases(Scenario.PEER); }
  @Test void retiredOwnerCannotRemoveItsSamePositionReplacement() throws Exception { cases(Scenario.REPLACEMENT); }
}
