package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CacheAdmissionTest {
  private static final long BUDGET = 1L << 20;

  private static CompiledProject empty() {
    return new CompiledProject(new ProjectData());
  }

  private static CompiledProject mixed() {
    var p = new ProjectData();
    p.project.minY = 0;
    p.project.maxY = 15;
    var type = new ProjectData.VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var noise = new ProjectData.Noise();
    noise.id = "source";
    noise.dimension = 3;
    noise.algorithm.key = "WHITE_HASH";
    p.noise3D.add(noise);
    var layer = new ProjectData.Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    var op = new ProjectData.Operation();
    op.id = "place";
    op.noiseGeneratorUuid = noise.uuid;
    op.interpretation = "DENSITY";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    return new CompiledProject(p);
  }

  private static WorldOptions options(int root, WorldOptions.CachePolicy policy, long bytes) {
    return new WorldOptions(null, root, policy, bytes);
  }

  private static long hash(VoxelChunk chunk) {
    long result = 0xcbf29ce484222325L;
    for (int y = 0; y < chunk.size(); y++)
      for (int z = 0; z < chunk.size(); z++)
        for (int x = 0; x < chunk.size(); x++) {
          result ^= chunk.getVoxelTypeId(x, y, z);
          result *= 0x100000001b3L;
        }
    return result;
  }

  private static VoxelChunk fill(WorldContext w) {
    VoxelChunk first = w.generateChunk(new ChunkPos(0, 0, 0));
    int i = 1;
    while (w.cacheStats().bytes() < BUDGET * 3 / 4 && i < 1000)
      w.generateChunk(new ChunkPos(i++, 0, 0));
    assertTrue(w.cacheStats().bytes() >= BUDGET * 3 / 4);
    return first;
  }

  @Test
  void cachePoliciesHaveDistinctRetentionAndLocalLruBehavior() {
    long cost = 128 + VoxelChunk.uniform(new ChunkPos(0, 0, 0), 2, 0).estimatedBytes();
    try (var engine = new NoiseGeneratorEngine(1, 8, BUDGET);
        var memory =
            engine.openWorld(empty(), options(2, WorldOptions.CachePolicy.MEMORY_CACHE, 0));
        var bounded =
            engine.openWorld(
                empty(), options(2, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, cost * 2));
        var none = engine.openWorld(empty(), options(2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      for (int i = 0; i < 3; i++) memory.generateChunk(new ChunkPos(i, 0, 0));
      assertEquals(3, memory.cacheStats().chunks());
      var a = bounded.generateChunk(new ChunkPos(0, 0, 0));
      var b = bounded.generateChunk(new ChunkPos(1, 0, 0));
      assertSame(a, bounded.generateChunk(new ChunkPos(0, 0, 0)));
      bounded.generateChunk(new ChunkPos(2, 0, 0));
      assertSame(a, bounded.generateChunk(new ChunkPos(0, 0, 0)));
      assertNotSame(b, bounded.generateChunk(new ChunkPos(1, 0, 0)));
      assertEquals(2, bounded.cacheStats().chunks());
      assertTrue(bounded.cacheStats().automaticEvictions() >= 2);
      none.generateChunk(new ChunkPos(0, 0, 0));
      assertEquals(0, none.cacheStats().chunks());
      assertEquals(0, none.cacheStats().retentionRejected());
    }
  }

  @Test
  void userManagedRetentionNeverImplicitlyEvictsAndPinsProtectExplicitRemoval() {
    long cost = 128 + VoxelChunk.uniform(new ChunkPos(0, 0, 0), 2, 0).estimatedBytes();
    try (var engine = new NoiseGeneratorEngine(1, 8, BUDGET);
        var w =
            engine.openWorld(
                empty(), options(2, WorldOptions.CachePolicy.USER_MANAGED, cost * 2))) {
      var a = w.generateChunk(new ChunkPos(0, 0, 0));
      w.generateChunk(new ChunkPos(1, 0, 0));
      assertTrue(w.pinChunk(a.position()));
      var unretained = w.generateChunk(new ChunkPos(2, 0, 0));
      assertFalse(w.pinChunk(unretained.position()));
      assertEquals(2, w.cacheStats().chunks());
      assertEquals(1, w.cacheStats().retentionRejected());
      assertEquals(0, w.cacheStats().automaticEvictions());
      assertThrows(IllegalStateException.class, () -> w.evictChunk(a.position()));
      w.clearCache();
      assertEquals(1, w.cacheStats().chunks());
      assertSame(a, w.generateChunk(a.position()));
      assertTrue(w.unpinChunk(a.position()));
      w.evictChunk(a.position());
      assertEquals(0, w.cacheStats().bytes());
      assertEquals(0, engine.metrics().cacheBytes());
      assertTrue(a.isEmpty());
    }
  }

  @Test
  void globalGenerationAdmissionReclaimsAutomaticCachesAcrossWorlds() {
    var compiled = mixed();
    try (var engine = new NoiseGeneratorEngine(1, 8, BUDGET);
        var a =
            engine.openWorld(
                compiled, options(16, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, BUDGET));
        var b = engine.openWorld(compiled, options(32, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var snapshot = fill(a);
      long oldHash = hash(snapshot), oldBytes = a.cacheStats().bytes();
      var result = b.generateChunk(new ChunkPos(0, 0, 0));
      assertEquals(32, result.size());
      assertTrue(a.cacheStats().bytes() < oldBytes);
      assertTrue(a.cacheStats().automaticEvictions() > 0);
      assertEquals(0, engine.metrics().stagingBytes());
      assertTrue(engine.metrics().cacheBytes() <= BUDGET);
      assertEquals(oldHash, hash(snapshot));
      var rebuilt = a.generateChunk(snapshot.position());
      assertNotSame(snapshot, rebuilt);
      assertEquals(oldHash, hash(rebuilt));
    }
  }

  @Test
  void globalPressureDoesNotReclaimUserManagedEvenWhenUnpinned() {
    var compiled = mixed();
    try (var engine = new NoiseGeneratorEngine(1, 8, BUDGET);
        var user =
            engine.openWorld(compiled, options(16, WorldOptions.CachePolicy.USER_MANAGED, BUDGET));
        var other = engine.openWorld(compiled, options(32, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var snapshot = fill(user);
      long bytes = user.cacheStats().bytes(), snapshotHash = hash(snapshot);
      assertTrue(user.pinChunk(snapshot.position()));
      assertThrows(
          RejectedExecutionException.class, () -> other.generateChunk(new ChunkPos(0, 0, 0)));
      assertEquals(bytes, user.cacheStats().bytes());
      assertEquals(0, user.cacheStats().automaticEvictions());
      assertEquals(0, engine.metrics().stagingBytes());
      user.clearCache();
      assertEquals(1, user.cacheStats().chunks());
      assertEquals(32, other.generateChunk(new ChunkPos(0, 0, 0)).size());
      user.close();
      assertEquals(0, engine.metrics().cacheBytes());
      assertEquals(snapshotHash, hash(snapshot));
    }
  }

  @Test
  void impossibleCollectionDoesNotFlushCachesAndContextCloseBalancesAccounting() {
    try (var engine = new NoiseGeneratorEngine(1, 8, BUDGET);
        var w =
            engine.openWorld(
                empty(), options(2, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, BUDGET))) {
      w.generateChunk(new ChunkPos(0, 0, 0));
      long before = w.cacheStats().bytes();
      var huge =
          RegionRequest.of(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(999, 1, 999)));
      assertThrows(RejectedExecutionException.class, () -> w.collectRegion(huge));
      assertEquals(before, w.cacheStats().bytes());
      w.close();
      assertEquals(0, engine.metrics().cacheBytes());
      assertEquals(0, engine.metrics().collectionBytes());
    }
  }

  @Test
  void concurrentRetainsReclaimsAndCloseHaveNoCacheLockCycle() throws Exception {
    var compiled = mixed();
    try (var engine = new NoiseGeneratorEngine(2, 16, 2 * BUDGET);
        var workers = Executors.newFixedThreadPool(4)) {
      var contexts = new ArrayList<WorldContext>();
      for (int i = 0; i < 4; i++)
        contexts.add(
            engine.openWorld(
                compiled, options(16, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, 2 * BUDGET)));
      var futures = new ArrayList<Future<?>>();
      for (var w : contexts)
        futures.add(
            workers.submit(
                () -> {
                  for (int x = 0; x < 300; x++) {
                    try {
                      w.generateChunk(new ChunkPos(x, 0, 0));
                    } catch (RejectedExecutionException pressure) {
                      /* Bounded concurrent admission may reject. */
                    }
                  }
                }));
      for (var future : futures) future.get(20, TimeUnit.SECONDS);
      for (var w : contexts) w.close();
      assertEquals(0, engine.metrics().cacheBytes());
      assertEquals(0, engine.metrics().stagingBytes());
    }
  }
}
