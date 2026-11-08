package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferred complete-chunk region partition/clipping replay with an independent integer oracle. */
public final class RegionPartitionConsumer {
  private enum Route { STREAM, SYNC, ASYNC }
  private record Observed(VoxelChunk chunk, long seed, RegionBounds requested) {}
  private static final class Replay {
    final List<Observed> observed = new ArrayList<>();
    long healthy, empty, leases;
  }
  private static ProjectData project() {
    var p = new ProjectData(); p.project.seed = 17; p.project.minY = -1; p.project.maxY = 2;
    var type = new VoxelType(); type.id = "solid"; p.voxelTypes.add(type);
    var n = new Noise(); n.id = "residue"; n.dimension = 3; n.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "residue", 3);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("((x+2*z+4*y+projectSeed)%5+5)%5"));
    n.processing.add(expression); n.outputBinding = Binding.earlier(expression.uuid); p.noise3D.add(n);
    var layer = new Layer(); layer.id = "layer"; layer.defaults.action = "SET"; layer.defaults.voxelTypeUuid = type.uuid; layer.defaults.threshold = 2;
    var op = new Operation(); op.id = "density"; op.interpretation = "DENSITY"; op.noiseGeneratorUuid = n.uuid;
    layer.operations.add(op); p.worldGenerator.layers.add(layer); return p;
  }
  private static List<ChunkPos> positions(int root) {
    var result = new ArrayList<ChunkPos>();
    for (int x : new int[] {-2, -1, 0, 1}) for (int y : root == 2 ? new int[] {-1, 0, 1} : new int[] {-1, 0})
      for (int z : new int[] {-1, 0, 1}) result.add(new ChunkPos(x, y, z));
    return List.copyOf(result); // Independent boundary table, not an engine/chunkCount result.
  }
  private static int expected(long x, long y, long z, long seed) {
    if (y < -1 || y > 2) return 0;
    long value = x + 2 * z + 4 * y + seed;
    return value - 5 * Math.floorDiv(value, 5) >= 2 ? 1 : 0;
  }
  private static void query(Replay replay, NoiseGeneratorEngine e, WorldContext w, int root, long seed, RegionRequest request, Route route, boolean empty) throws Exception {
    var chunks = new ArrayList<VoxelChunk>(); RegionSummary summary;
    if (route == Route.STREAM) {
      var task = w.streamRegion(request, chunk -> { chunks.add(chunk); return CompletableFuture.completedFuture(null); });
      summary = task.completion().get(10, TimeUnit.SECONDS);
      require(summary.progress().equals(task.progress()) && !task.cancel(), "Terminal stream ownership/progress differs");
    } else {
      var result = route == Route.SYNC ? w.collectRegion(request) : w.collectRegionAsync(request).get(10, TimeUnit.SECONDS);
      try {
        summary = result.summary(); chunks.addAll(result.chunks());
        require(empty ? e.metrics().collectionBytes() == 0 : e.metrics().collectionBytes() > 0, "Collection lease was not retained");
        if (!empty) replay.leases++;
      } finally { result.close(); result.close(); }
      require(e.metrics().collectionBytes() == 0, "Collection lease did not release idempotently");
    }
    var wanted = empty ? List.<ChunkPos>of() : positions(root);
    var effective = empty ? Optional.<RegionBounds>empty() : Optional.of(new RegionBounds(
        new BlockPos(-root - 1, -1, -1), new BlockPos(root, Math.min(root, 2), root)));
    require(summary.requested().equals(request.bounds()) && summary.effective().equals(effective)
        && summary.progress().equals(new TaskProgress("COMPLETED", 0, wanted.size(), 0, 0)) && summary.failures().isEmpty(), "Requested/effective bounds or terminal partition differs");
    var actual = chunks.stream().map(VoxelChunk::position).toList();
    require(actual.size() == wanted.size() && new HashSet<>(actual).equals(new HashSet<>(wanted)), "Complete chunk coverage has gaps or duplicates");
    if (request.order() == RegionRequest.DeliveryOrder.COORDINATE) require(actual.equals(wanted), "Coordinate delivery order differs");
    for (var chunk : chunks) {
      require(chunk.size() == root, "Delivered chunk size differs"); replay.observed.add(new Observed(chunk, seed, request.bounds()));
    }
    require(w.inFlightChunks() == 0 && w.cacheStats().chunks() == 0 && e.metrics().queued() == 0
        && e.metrics().stagingBytes() == 0 && e.metrics().collectionBytes() == 0 && e.metrics().cacheBytes() == 0, "Completed region retained transient ownership");
    if (empty) replay.empty++; else replay.healthy++;
  }
  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var p = project(); String authored = ProjectIO.JSON.writeValueAsString(p);
    var compiled = new CompiledProject(p); var replay = new Replay(); long generations = 0;
    for (int root : new int[] {2, 4, 8}) try (var e = new NoiseGeneratorEngine(2, 8, 8L << 20)) {
      for (long seed : new long[] {17, -17}) try (var w = e.openWorld(compiled, new WorldOptions(seed == 17 ? null : Long.valueOf(seed), root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
        var bounds = new RegionBounds(new BlockPos(-root - 1, -root - 1, -1), new BlockPos(root, root, root));
        for (var order : RegionRequest.DeliveryOrder.values()) for (var policy : RegionRequest.FailurePolicy.values())
          for (int inFlight : new int[] {1, 3}) for (var route : Route.values())
            query(replay, e, w, root, seed, new RegionRequest(bounds, policy, order, inFlight, GenerationPriority.BACKGROUND), route, false);
        var outside = new RegionBounds(new BlockPos(-1, 10, -1), new BlockPos(1, 11, 1));
        for (var route : Route.values()) query(replay, e, w, root, seed, RegionRequest.of(outside), route, true);
      }
      require(e.metrics().completed() == 48L * positions(root).size() && e.metrics().cacheBytes() == 0
          && e.metrics().stagingBytes() == 0 && e.metrics().collectionBytes() == 0, "Root region generation/accounting differs");
      generations += e.metrics().completed();
    }
    long cells = 0, occupied = 0, edgeOccupied = 0, clippedCells = 0;
    for (var observed : replay.observed) {
      var c = observed.chunk; int size = c.size(), volume = size * size * size; var visited = new BitSet(volume); long wantedOccupied = 0;
      long ox = (long) c.position().x() * size, oy = (long) c.position().y() * size, oz = (long) c.position().z() * size;
      for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
        long wx = ox + x, wy = oy + y, wz = oz + z; int value = expected(wx, wy, wz, observed.seed);
        require(value == c.getVoxelTypeId(x, y, z), "Retained whole-chunk voxel differs from integer oracle"); cells++;
        if (wy < -1 || wy > 2) clippedCells++;
        if (value != 0) {
          wantedOccupied++;
          if (wx < observed.requested.min().x() || wx > observed.requested.max().x() || wz < observed.requested.min().z() || wz > observed.requested.max().z()) edgeOccupied++;
        }
      }
      c.forEachOccupied((x, y, z, value) -> {
        require(x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size && value == 1
            && value == expected(ox + x, oy + y, oz + z, observed.seed), "Occupied coordinate/type differs");
        int index = (z * size + y) * size + x; require(!visited.get(index), "Duplicate occupied voxel"); visited.set(index);
      });
      require(visited.cardinality() == wantedOccupied && c.storageStatistics().occupiedVoxels() == wantedOccupied
          && c.isEmpty() == (wantedOccupied == 0), "Occupied/EMPTY metadata differs"); occupied += wantedOccupied;
    }
    require(replay.healthy == 144 && replay.empty == 18 && replay.leases == 96 && replay.observed.size() == 4032
        && generations == 4032 && cells == 677376 && edgeOccupied > 0 && clippedCells == 483840
        && authored.equals(ProjectIO.JSON.writeValueAsString(p)), "Region replay dimensions/source ownership differ");
    Files.writeString(out.resolve("region-partition-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "healthyRegions", replay.healthy, "emptyRegions", replay.empty, "collectionLeases", replay.leases,
        "chunkObservations", replay.observed.size(), "generations", generations, "cells", cells,
        "occupiedCells", occupied, "occupiedOutsideRequestedXZ", edgeOccupied, "clippedYCells", clippedCells, "roots", List.of(2, 4, 8))));
    System.out.println("REGION_PARTITION_CONSUMER_PASS");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
