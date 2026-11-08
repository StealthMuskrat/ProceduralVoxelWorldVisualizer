package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferred public cache-policy replay with an independent integer-residue voxel oracle. */
public final class CachePolicyConsumer {
  private static final ChunkPos A = new ChunkPos(-7, -1, 0), B = new ChunkPos(0, -1, 0), C = new ChunkPos(7, -1, 0);
  private record Observed(VoxelChunk chunk, long seed) {}
  private static final class Replay {
    final List<Observed> observed = new ArrayList<>();
    long fields, worlds, pairs, generations, evictions, rejected;
    VoxelChunk observe(VoxelChunk chunk, long seed) { observed.add(new Observed(chunk, seed)); return chunk; }
    VoxelChunk sync(WorldContext w, ChunkPos pos, long seed) { return observe(w.generateChunk(pos), seed); }
    VoxelChunk async(WorldContext w, ChunkPos pos, long seed, GenerationPriority priority) throws Exception {
      return observe(w.generateChunkAsync(pos, priority).get(5, TimeUnit.SECONDS), seed);
    }
  }
  private static ProjectData project() {
    var p = new ProjectData(); p.project.seed = 17; p.project.minY = -64; p.project.maxY = 63;
    var type = new VoxelType(); type.id = "solid"; p.voxelTypes.add(type);
    var n = new Noise(); n.id = "residue"; n.dimension = 3; n.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "residue", 3);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("((x+2*z+4*y+projectSeed)%7+7)%7"));
    n.processing.add(expression); n.outputBinding = Binding.earlier(expression.uuid); p.noise3D.add(n);
    var layer = new Layer(); layer.id = "layer"; layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid; layer.defaults.threshold = 3;
    var op = new Operation(); op.id = "density"; op.interpretation = "DENSITY"; op.noiseGeneratorUuid = n.uuid;
    layer.operations.add(op); p.worldGenerator.layers.add(layer); return p;
  }
  private static long residue(long x, long y, long z, long seed) {
    long value = x + 2 * z + 4 * y + seed;
    return value - 7 * Math.floorDiv(value, 7);
  }
  private static int expected(VoxelChunk c, long seed, int x, int y, int z) {
    long ox = (long) c.position().x() * c.size(), oy = (long) c.position().y() * c.size(), oz = (long) c.position().z() * c.size();
    return residue(ox + x, oy + y, oz + z, seed) >= 3 ? 1 : 0;
  }
  private static void illegalState(Runnable action) {
    try { action.run(); throw new AssertionError("Cache ownership guard did not reject"); }
    catch (IllegalStateException expected) { }
  }
  private static void phase(Replay r, WorldContext w, VoxelChunk warm, long seed, WorldOptions.CachePolicy policy, long entry) throws Exception {
    var secondA = r.async(w, A, seed, GenerationPriority.INTERACTIVE);
    var firstB = r.sync(w, B, seed); var thirdA = r.sync(w, A, seed);
    if (policy != WorldOptions.CachePolicy.NO_CACHE) require(warm == secondA && warm == thirdA, "Retained A was not reused");
    if (policy == WorldOptions.CachePolicy.USER_MANAGED) require(w.pinChunk(A), "Retained user entry did not pin");
    else illegalState(() -> w.pinChunk(A));
    var firstC = r.async(w, C, seed, GenerationPriority.BACKGROUND);
    require(128 + firstB.estimatedBytes() == entry && 128 + firstC.estimatedBytes() == entry, "Period-equivalent storage estimates differ");
    switch (policy) {
      case NO_CACHE -> require(w.cacheStats().chunks() == 0 && w.cacheStats().bytes() == 0, "NO_CACHE retained completed data");
      case MEMORY_CACHE -> require(w.cacheStats().chunks() == 3, "MEMORY_CACHE incorrectly used zero local cacheBytes");
      case BOUNDED_MEMORY_CACHE -> require(w.cacheStats().chunks() == 2 && w.cacheStats().automaticEvictions() == 1, "Bounded LRU did not evict B");
      case USER_MANAGED -> require(w.cacheStats().chunks() == 2 && w.cacheStats().retentionRejected() == 1
          && w.cacheStats().automaticEvictions() == 0 && !w.pinChunk(C), "User-managed retention implicitly evicted or pinned an unretained result");
    }
    if (policy == WorldOptions.CachePolicy.USER_MANAGED) {
      illegalState(() -> w.evictChunk(A)); w.clearCache();
      require(w.cacheStats().chunks() == 1 && w.cacheStats().pinnedChunks() == 1, "Clear discarded the pin");
      var secondC = r.sync(w, C, seed);
      require(w.unpinChunk(A), "Pin did not unpin"); w.evictChunk(A);
      require(secondC == r.async(w, C, seed, GenerationPriority.INTERACTIVE), "Retained C was not reused");
      w.clearCache(); require(!w.pinChunk(C), "Cleared snapshot remained pinnable");
      r.sync(w, B, seed);
    } else {
      var fourthA = r.sync(w, A, seed); var secondB = r.async(w, B, seed, GenerationPriority.NORMAL);
      if (policy != WorldOptions.CachePolicy.NO_CACHE) require(fourthA == warm, "A refresh did not preserve its LRU ownership");
      if (policy == WorldOptions.CachePolicy.MEMORY_CACHE) require(secondB == firstB, "Unpressured MEMORY_CACHE lost B");
      if (policy == WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE) require(secondB != firstB
          && w.cacheStats().automaticEvictions() == 2, "Evicted B was not rebuilt");
      w.evictChunk(A); var rebuilt = r.sync(w, A, seed);
      if (policy != WorldOptions.CachePolicy.NO_CACHE) require(rebuilt != warm, "Explicitly evicted A was reused");
    }
    var stats = w.cacheStats();
    long hits = switch (policy) { case NO_CACHE -> 0; case MEMORY_CACHE -> 4; default -> 3; };
    long misses = 8 - hits;
    require(stats.hits() == hits && stats.misses() == misses && stats.automaticEvictions() == (policy == WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE ? 2 : 0)
        && stats.retentionRejected() == (policy == WorldOptions.CachePolicy.USER_MANAGED ? 1 : 0), "Cache event accounting differs");
    r.evictions += stats.automaticEvictions(); r.rejected += stats.retentionRejected(); r.worlds++;
    w.clearCache(); require(w.cacheStats().bytes() == 0 && w.cacheStats().pinnedChunks() == 0 && w.inFlightChunks() == 0, "Explicit cleanup retained ownership");
  }
  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var p = project(); String authored = ProjectIO.JSON.writeValueAsString(p);
    var compiled = new CompiledProject(p); var r = new Replay();
    for (int root : new int[] {2, 4, 8}) try (var e = new NoiseGeneratorEngine(2, 16, 4L << 20)) {
      var entries = new HashMap<Long, Long>();
      for (long seed : new long[] {17, -17}) {
        try (var probe = e.openWorld(compiled, new WorldOptions(seed, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          var sample = r.sync(probe, A, seed); entries.put(seed, 128 + sample.estimatedBytes());
        }
        var field = compiled.noise3D("residue", seed);
        for (int x : new int[] {-9, 0, 11}) for (int y : new int[] {-3, 0, 5}) for (int z : new int[] {-7, 0, 4}) {
          require(Double.doubleToRawLongBits((double) residue(x, y, z, seed)) == Double.doubleToRawLongBits(field.sample(x, y, z)), "Independent field residue differs"); r.fields++;
        }
      }
      for (var policy : WorldOptions.CachePolicy.values()) {
        long positiveBytes = policy == WorldOptions.CachePolicy.MEMORY_CACHE || policy == WorldOptions.CachePolicy.NO_CACHE ? 0 : 2 * entries.get(17L);
        long negativeBytes = policy == WorldOptions.CachePolicy.MEMORY_CACHE || policy == WorldOptions.CachePolicy.NO_CACHE ? 0 : 2 * entries.get(-17L);
        try (var positive = e.openWorld(compiled, new WorldOptions(null, root, policy, positiveBytes));
            var negative = e.openWorld(compiled, new WorldOptions(-17L, root, policy, negativeBytes))) {
          var a = r.sync(positive, A, 17); var b = r.sync(negative, A, -17); long otherBytes = negative.cacheStats().bytes();
          phase(r, positive, a, 17, policy, entries.get(17L));
          require(negative.cacheStats().bytes() == otherBytes, "One context's cache controls changed another context");
          positive.close();
          phase(r, negative, b, -17, policy, entries.get(-17L));
          require(e.metrics().cacheBytes() == 0 && e.metrics().stagingBytes() == 0 && e.metrics().collectionBytes() == 0, "Cache pair leaked admission"); r.pairs++;
        }
      }
      require(e.metrics().completed() == 46 && e.metrics().queued() == 0 && e.metrics().cacheBytes() == 0, "Root replay generation/accounting differs");
      r.generations += e.metrics().completed();
    }
    long cells = 0, occupied = 0;
    for (var observed : r.observed) {
      var c = observed.chunk; int size = c.size(), volume = size * size * size; var visited = new BitSet(volume); long expectedOccupied = 0;
      for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
        int value = expected(c, observed.seed, x, y, z); require(value == c.getVoxelTypeId(x, y, z), "Retained cached voxel differs from residue oracle");
        cells++; if (value != 0) expectedOccupied++;
      }
      c.forEachOccupied((x, y, z, value) -> {
        require(x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size && value == expected(c, observed.seed, x, y, z) && value == 1, "Occupied voxel differs");
        int index = (z * size + y) * size + x; require(!visited.get(index), "Duplicate occupied cell"); visited.set(index);
      });
      require(visited.cardinality() == expectedOccupied && c.storageStatistics().occupiedVoxels() == expectedOccupied
          && c.isEmpty() == (expectedOccupied == 0), "Occupied/EMPTY metadata differs"); occupied += expectedOccupied;
    }
    require(r.worlds == 24 && r.pairs == 12 && r.fields == 162 && r.generations == 138 && r.evictions == 12 && r.rejected == 6
        && r.observed.size() == 198 && cells == 38544 && authored.equals(ProjectIO.JSON.writeValueAsString(p)), "Cache replay dimensions/source ownership differ");
    Files.writeString(out.resolve("cache-policy-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "policyWorlds", r.worlds, "seedPairs", r.pairs, "fields", r.fields, "generations", r.generations,
        "automaticEvictions", r.evictions, "retentionRejected", r.rejected, "chunkObservations", r.observed.size(),
        "cells", cells, "occupiedCells", occupied, "roots", List.of(2, 4, 8))));
    System.out.println("CACHE_POLICY_CONSUMER_PASS");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
