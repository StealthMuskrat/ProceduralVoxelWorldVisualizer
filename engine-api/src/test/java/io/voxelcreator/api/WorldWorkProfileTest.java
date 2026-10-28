package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorldWorkProfileTest {
  static ProjectData fixture() throws Exception {
    return ProjectIO.JSON.readValue(
        Path.of("../fixtures/work-profile-project.json").toFile(), ProjectData.class);
  }

  private static boolean work(Profiler.Sample sample) {
    return sample.kind() == Profiler.Kind.OPERATION_WORK
        || sample.kind() == Profiler.Kind.LAYER_WORK;
  }

  private static WorldContext world(NoiseGeneratorEngine engine, ProjectData p, int side) {
    return engine.openWorld(
        new CompiledProject(p), new WorldOptions(null, side, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  private static Map<UUID, Long> totals(Profiler.Capture capture) {
    var result = new HashMap<UUID, Long>();
    for (var sample : capture.snapshot().samples())
      if (work(sample)) result.merge(sample.sourceUuid(), sample.value(), Long::sum);
    return result;
  }

  @Test
  void everyPlacementAndItsPrefetchContributeExactlyOncePerChunk() throws Exception {
    var p = fixture();
    var clock = new AtomicLong();
    try (var profiler = new Profiler(clock::getAndIncrement, Clock.systemUTC())) {
      var capture = profiler.start(new Profiler.Options(60000, 4096, 128, 65536, true));
      var scope = profiler.scope(3, p.project.uuid, new ChunkPos(0, 0, 0));
      var chunk =
          new CompiledProject(p)
              .plan(p.project.seed)
              .generate(new ChunkPos(0, 0, 0), 2, new CancellationToken(), scope);
      assertEquals(1, chunk.getVoxelTypeId(1, 1, 1));
      var values = totals(capture);
      for (var layer : p.worldGenerator.layers) {
        for (var op : layer.operations)
          assertEquals(
              9L,
              values.get(
                  op.uuid)); // 1 column prefetch + 8 visited cells; each fake interval is 1 ns.
        assertEquals(9L * layer.operations.size(), values.get(layer.uuid));
      }
      assertEquals(5, values.size());
      assertTrue(
          capture.snapshot().samples().stream()
              .noneMatch(s -> s.kind() == Profiler.Kind.OPERATION));
      assertEquals(
          3,
          capture.snapshot().samples().stream()
              .filter(s -> s.kind() == Profiler.Kind.FIELD_PREFETCH)
              .count());
    }
  }

  @Test
  void stageUsesOneLedgerForSharedPrefetchAndBothActuallyExecutedPrefixes() throws Exception {
    var p = fixture();
    var clock = new AtomicLong();
    try (var profiler = new Profiler(clock::getAndIncrement, Clock.systemUTC())) {
      var capture = profiler.start(new Profiler.Options(60000, 4096, 128, 65536, true));
      var scope = profiler.scope(3, p.project.uuid, new ChunkPos(0, 0, 0));
      var stage =
          new CompiledProject(p)
              .plan(0)
              .generateStage(
                  new ChunkPos(0, 0, 0),
                  2,
                  new CancellationToken(),
                  p.worldGenerator.layers.getLast().operations.getFirst().uuid,
                  scope);
      assertEquals(1, stage.before().getVoxelTypeId(1, 1, 1));
      assertEquals(1, stage.after().getVoxelTypeId(1, 1, 1));
      var values = totals(capture);
      var first = p.worldGenerator.layers.getFirst();
      var last = p.worldGenerator.layers.getLast();
      assertEquals(17L, values.get(first.operations.get(0).uuid));
      assertEquals(17L, values.get(first.operations.get(1).uuid));
      assertEquals(9L, values.get(last.operations.getFirst().uuid));
      assertEquals(34L, values.get(first.uuid));
      assertEquals(9L, values.get(last.uuid));
    }
  }

  @Test
  void stridesWorkersAndRootSizesDoNotChangeCanonicalOutputOrAttribution() throws Exception {
    var p = fixture();
    for (int stride : new int[] {1, 7, 65536})
      try (var engine = new NoiseGeneratorEngine(3, 16, 16L << 20);
          var w = world(engine, p, 4)) {
        var capture = engine.profiler().start(new Profiler.Options(60000, 4096, 128, stride, true));
        var a = w.generateChunkAsync(new ChunkPos(-1, 0, 0));
        var b = w.generateChunkAsync(new ChunkPos(0, 0, 0));
        for (var chunk : List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
          for (int z = 0; z < 4; z++)
            for (int y = 0; y < 4; y++)
              for (int x = 0; x < 4; x++) assertEquals(1, chunk.getVoxelTypeId(x, y, z));
        assertEquals(
            10, capture.snapshot().samples().stream().filter(WorldWorkProfileTest::work).count());
        var values = totals(capture);
        for (var layer : p.worldGenerator.layers)
          assertEquals(
              layer.operations.stream().mapToLong(op -> values.get(op.uuid)).sum(),
              values.get(layer.uuid));
        assertEquals(0, engine.metrics().stagingBytes());
      }
  }

  @Test
  void failureExportsAnExecutedPrefixAndReleasesItsStagingReservation() throws Exception {
    var p = fixture();
    p.noise2D
        .getFirst()
        .processing
        .getFirst()
        .parameters
        .put("expression", ProjectIO.JSON.valueToTree("1/(x-1)"));
    try (var engine = new NoiseGeneratorEngine(1, 8, 16L << 20);
        var w = world(engine, p, 4)) {
      var capture = engine.profiler().start(new Profiler.Options(60000, 4096, 128, 65536, true));
      assertThrows(EvaluationFailure.class, () -> w.generateChunk(new ChunkPos(0, 0, 0)));
      var samples =
          capture.snapshot().samples().stream().filter(WorldWorkProfileTest::work).toList();
      assertEquals(2, samples.size());
      assertTrue(samples.stream().allMatch(s -> s.outcome() == Profiler.Outcome.FAILED));
      assertEquals(0, engine.metrics().stagingBytes());
      assertEquals(0, w.cacheStats().chunks());
    }
  }

  @Test
  void cooperativeCancellationKeepsPartialCostWithoutReturningAChunk() throws Exception {
    var p = fixture();
    var clock = new AtomicLong();
    try (var profiler = new Profiler(clock::getAndIncrement, Clock.systemUTC())) {
      var capture = profiler.start(new Profiler.Options(60000, 4096, 128, 65536, true));
      var calls = new AtomicLong();
      var token = new CancellationToken(() -> calls.incrementAndGet() > 30);
      assertThrows(
          CancellationException.class,
          () ->
              new CompiledProject(p)
                  .plan(0)
                  .generate(
                      new ChunkPos(0, 0, 0), 4, token, profiler.scope(1, p.project.uuid, null)));
      var samples =
          capture.snapshot().samples().stream().filter(WorldWorkProfileTest::work).toList();
      assertEquals(5, samples.size());
      assertTrue(samples.stream().allMatch(s -> s.outcome() == Profiler.Outcome.CANCELLED));
    }
  }

  @Test
  void optionalLedgerIsAdmittedBeforeChunkAllocation() throws Exception {
    var p = fixture();
    try (var engine = new NoiseGeneratorEngine(1, 8, 1L << 20);
        var w = world(engine, p, 32)) {
      assertTrue(engine.reserveStaging(400000));
      try {
        assertDoesNotThrow(() -> w.generateChunk(new ChunkPos(0, 0, 0)));
        var capture = engine.profiler().start(new Profiler.Options(60000, 4096, 1024, 65536, true));
        assertThrows(
            RejectedExecutionException.class, () -> w.generateChunk(new ChunkPos(1, 0, 0)));
        assertTrue(capture.snapshot().samples().stream().noneMatch(WorldWorkProfileTest::work));
        assertEquals(400000, engine.metrics().stagingBytes());
      } finally {
        engine.stagingBytes.addAndGet(-400000);
      }
    }
  }

  @Test
  void stageReservesOnlyOneAdditionalBoundedLedger() throws Exception {
    var p = fixture();
    try (var engine = new NoiseGeneratorEngine(1, 8, 1L << 20);
        var w = world(engine, p, 16)) {
      UUID stage = p.worldGenerator.layers.getLast().uuid;
      long held = 800000;
      assertTrue(engine.reserveStaging(held));
      try {
        assertDoesNotThrow(() -> w.generateStageChunk(new ChunkPos(0, 0, 0), stage));
        engine.profiler().start(new Profiler.Options(60000, 4096, 1024, 65536, true));
        assertThrows(
            RejectedExecutionException.class,
            () -> w.generateStageChunk(new ChunkPos(0, 0, 0), stage));
        assertEquals(800000, engine.metrics().stagingBytes());
        engine.stagingBytes.addAndGet(-300000);
        held = 500000;
        assertDoesNotThrow(() -> w.generateStageChunk(new ChunkPos(0, 0, 0), stage));
        assertEquals(500000, engine.metrics().stagingBytes());
      } finally {
        engine.stagingBytes.addAndGet(-held);
      }
    }
  }
}
