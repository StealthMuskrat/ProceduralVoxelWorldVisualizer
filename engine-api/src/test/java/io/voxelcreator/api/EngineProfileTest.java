package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class EngineProfileTest {
  private static final Profiler.Options OPTIONS = new Profiler.Options(10000, 4096, 128, 1);

  private static ProjectData project(int dimension, String expression) {
    var p = new ProjectData();
    p.project.minY = -32;
    p.project.maxY = 31;
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var noise = new Noise();
    noise.id = "field";
    noise.dimension = dimension;
    noise.algorithm.key = "CONSTANT";
    var item = ProcessingRegistry.create("EXPRESSION", "function");
    item.parameters.put("expression", ProjectIO.JSON.valueToTree(expression));
    noise.processing.add(item);
    noise.outputBinding = Binding.earlier(item.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).add(noise);
    var layer = new Layer();
    layer.id = "terrain";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    var op = new Operation();
    op.id = "surface";
    op.interpretation = "SELECTION";
    op.noiseGeneratorUuid = noise.uuid;
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    assertEquals(List.of(), Validator.validate(p));
    return p;
  }

  private static WorldContext world(NoiseGeneratorEngine engine, ProjectData p, int size) {
    return engine.openWorld(
        new CompiledProject(p), new WorldOptions(null, size, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  private static List<Profiler.Sample> samples(Profiler.Capture capture, Profiler.Kind kind) {
    return capture.snapshot().samples().stream().filter(s -> s.kind() == kind).toList();
  }

  @Test
  void sourceContextThreadAndPrefetchMeasurementsDescribeActualGenerationAndStages() {
    var p = project(2, "x");
    var op = p.worldGenerator.layers.getFirst().operations.getFirst();
    try (var engine = new NoiseGeneratorEngine(2, 8, 16L << 20);
        var world = world(engine, p, 4)) {
      assertTrue(engine.profiler().current().isEmpty());
      var capture = engine.profiler().start(OPTIONS);
      var pos = new ChunkPos(-1, 0, 0);
      var chunk = world.generateChunk(pos);
      assertTrue(chunk.isEmpty());
      assertEquals(64, samples(capture, Profiler.Kind.OPERATION).size());
      assertEquals(1, samples(capture, Profiler.Kind.FIELD_PREFETCH).size());
      assertEquals(1, samples(capture, Profiler.Kind.CHUNK_GENERATION).size());
      assertTrue(samples(capture, Profiler.Kind.QUEUE_WAIT).isEmpty());
      for (var s : samples(capture, Profiler.Kind.OPERATION)) {
        assertEquals(op.uuid, s.sourceUuid());
        assertEquals(p.project.uuid, s.projectUuid());
        assertEquals(world.contextId(), s.contextId());
        assertEquals(pos, s.chunk());
        assertEquals(Profiler.Outcome.SUCCESS, s.outcome());
        assertEquals("terrain / surface", s.label());
        assertTrue(s.value() >= 0);
      }
      var pair = world.generateStageChunk(pos, op.uuid);
      assertTrue(pair.before().isEmpty());
      assertTrue(pair.after().isEmpty());
      assertEquals(op.uuid, samples(capture, Profiler.Kind.STAGE).getFirst().sourceUuid());
      assertEquals(128, samples(capture, Profiler.Kind.OPERATION).size());
      assertEquals(2, samples(capture, Profiler.Kind.FIELD_PREFETCH).size());
      assertEquals(0, engine.metrics().stagingBytes());
    }
  }

  @Test
  void asyncSharedRequestsHaveOneQueueWaitAndOneGeneration() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 8, 16L << 20);
        var world = world(engine, project(2, "x"), 2)) {
      var held = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      engine.workers.execute(
          () -> {
            held.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(held.await(3, TimeUnit.SECONDS));
      var capture = engine.profiler().start(OPTIONS);
      var first = world.generateChunkAsync(new ChunkPos(0, 0, 0));
      var shared = world.generateChunkAsync(new ChunkPos(0, 0, 0));
      assertEquals(1, engine.schedulerStats().queued());
      release.countDown();
      assertSame(first.get(5, TimeUnit.SECONDS), shared.get(5, TimeUnit.SECONDS));
      assertEquals(1, samples(capture, Profiler.Kind.QUEUE_WAIT).size());
      assertEquals(1, samples(capture, Profiler.Kind.CHUNK_GENERATION).size());
      assertTrue(
          samples(capture, Profiler.Kind.QUEUE_WAIT)
              .getFirst()
              .threadName()
              .startsWith("voxel-generation-"));
    }
  }

  @Test
  void failedOperationAndPrefetchRetainFailedTimingsWithoutPublishingOrLeaking() {
    for (int dimension : new int[] {2, 3}) {
      try (var engine = new NoiseGeneratorEngine(1, 8, 16L << 20);
          var world = world(engine, project(dimension, "1/(x-x)"), 2)) {
        var capture = engine.profiler().start(OPTIONS);
        assertThrows(RuntimeException.class, () -> world.generateChunk(new ChunkPos(0, 0, 0)));
        assertEquals(
            Profiler.Outcome.FAILED,
            samples(capture, Profiler.Kind.CHUNK_GENERATION).getFirst().outcome());
        var detail =
            samples(
                capture, dimension == 2 ? Profiler.Kind.FIELD_PREFETCH : Profiler.Kind.OPERATION);
        assertEquals(Profiler.Outcome.FAILED, detail.getFirst().outcome());
        assertEquals(0, engine.metrics().stagingBytes());
        assertEquals(0, engine.metrics().completed());
        assertEquals(0, world.cacheStats().chunks());
      }
    }
  }

  @Test
  void cancelledGenerationRetainsCancellationAndReleasesAdmission() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 8, 16L << 20);
        var world = world(engine, project(2, "x"), 4)) {
      var capture = engine.profiler().start(OPTIONS);
      var token = new CancellationToken(() -> engine.metrics().stagingBytes() > 0);
      var future = world.generateChunkAsync(new ChunkPos(0, 0, 0), token);
      assertThrows(CancellationException.class, future::join);
      // Completion callbacks can run before the worker's final accounting.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (engine.metrics().stagingBytes() != 0 && System.nanoTime() < deadline)
        Thread.onSpinWait();
      assertEquals(0, engine.metrics().stagingBytes());
      assertEquals(
          Profiler.Outcome.CANCELLED,
          samples(capture, Profiler.Kind.CHUNK_GENERATION).getFirst().outcome());
    }
  }

  @Test
  void deliveryTimingIncludesConsumerAcknowledgementAndFailure() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 8, 16L << 20);
        var world = world(engine, project(2, "x"), 2)) {
      var capture = engine.profiler().start(OPTIONS);
      var entered = new CountDownLatch(1);
      var ack = new CompletableFuture<Void>();
      var request =
          RegionRequest.of(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1)));
      var task =
          world.streamRegion(
              request,
              chunk -> {
                entered.countDown();
                return ack;
              });
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertTrue(samples(capture, Profiler.Kind.REGION_DELIVERY).isEmpty());
      ack.complete(null);
      task.completion().get(5, TimeUnit.SECONDS);
      assertEquals(
          Profiler.Outcome.SUCCESS,
          samples(capture, Profiler.Kind.REGION_DELIVERY).getFirst().outcome());
      world
          .streamRegion(
              request,
              chunk ->
                  CompletableFuture.failedFuture(new IllegalStateException("consumer failure")))
          .completion()
          .get(5, TimeUnit.SECONDS);
      assertEquals(
          Profiler.Outcome.FAILED,
          samples(capture, Profiler.Kind.REGION_DELIVERY).getLast().outcome());
      var waiting = new CountDownLatch(1);
      var cancelled =
          world.streamRegion(
              request,
              chunk -> {
                waiting.countDown();
                return new CompletableFuture<>();
              });
      assertTrue(waiting.await(3, TimeUnit.SECONDS));
      cancelled.cancel();
      cancelled.completion().get(5, TimeUnit.SECONDS);
      assertEquals(
          Profiler.Outcome.CANCELLED,
          samples(capture, Profiler.Kind.REGION_DELIVERY).getLast().outcome());
    }
  }

  @Test
  void captureAndStrideNeverChangeWorldResultsAcrossWorkersRootsAndContextLifetimes()
      throws Exception {
    var p = project(3, "sin(x)+cos(z)-y/4");
    try (var engine = new NoiseGeneratorEngine(1, 8, 16L << 20);
        var plain = world(engine, p, 8)) {
      var expected = plain.generateChunk(new ChunkPos(0, 0, 0));
      for (int stride : new int[] {1, 7, 65536}) {
        try (var instrumentedEngine = new NoiseGeneratorEngine(3, 8, 16L << 20);
            var small = world(instrumentedEngine, p, 4)) {
          var capture =
              instrumentedEngine.profiler().start(new Profiler.Options(10000, 4096, 128, stride));
          var futures = new ArrayList<CompletableFuture<VoxelChunk>>();
          for (int y = 0; y < 2; y++)
            for (int z = 0; z < 2; z++)
              for (int x = 0; x < 2; x++)
                futures.add(small.generateChunkAsync(new ChunkPos(x, y, z)));
          for (var future : futures) {
            var chunk = future.get(5, TimeUnit.SECONDS);
            var pos = chunk.position();
            for (int y = 0; y < 4; y++)
              for (int z = 0; z < 4; z++)
                for (int x = 0; x < 4; x++)
                  assertEquals(
                      expected.getVoxelTypeId(pos.x() * 4 + x, pos.y() * 4 + y, pos.z() * 4 + z),
                      chunk.getVoxelTypeId(x, y, z));
          }
          small.close();
          assertFalse(capture.snapshot().samples().isEmpty());
        }
      }
    }
  }
}
