package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deferred engine-JAR-only coordinate/root admission with independent arbitrary-precision math. */
public final class CoordinateBoundaryConsumer {
  private static final int[] ROOTS = {2, 4, 8, 16, 32, 64, 128, 256};
  private static final int[] INVALID = {Integer.MIN_VALUE, -256, -2, -1, 0, 1, 3, 6, 12, 127, 129, 255, 257, 512, 1024, Integer.MAX_VALUE};
  private record Axis(int chunk, int local) {}
  @FunctionalInterface private interface Action { void run() throws Exception; }

  private static Axis axis(int coordinate, int root) {
    var divisor = BigInteger.valueOf(root); var parts = BigInteger.valueOf(coordinate).divideAndRemainder(divisor);
    if (parts[1].signum() < 0) { parts[0] = parts[0].subtract(BigInteger.ONE); parts[1] = parts[1].add(divisor); }
    return new Axis(parts[0].intValueExact(), parts[1].intValueExact());
  }
  private static BigInteger count(RegionBounds region, int root) {
    var min = region.min(); var max = region.max();
    return BigInteger.valueOf((long) axis(max.x(), root).chunk - axis(min.x(), root).chunk + 1)
        .multiply(BigInteger.valueOf((long) axis(max.y(), root).chunk - axis(min.y(), root).chunk + 1))
        .multiply(BigInteger.valueOf((long) axis(max.z(), root).chunk - axis(min.z(), root).chunk + 1));
  }
  private static void rejected(Action action) throws Exception {
    try { action.run(); throw new AssertionError("Unsupported root/count was accepted"); }
    catch (IllegalArgumentException expected) { }
  }
  private static List<RegionBounds> regions(int root) {
    int end = 65536 * root - 1;
    return List.of(
        new RegionBounds(new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE), new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE)),
        new RegionBounds(new BlockPos(-root, -root, -root), new BlockPos(root - 1, root - 1, root - 1)),
        new RegionBounds(new BlockPos(-1, -1, -1), new BlockPos(0, 0, 0)),
        new RegionBounds(new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE, 0, 0)),
        new RegionBounds(new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE), new BlockPos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)),
        new RegionBounds(new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE, end, end)),
        new RegionBounds(new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE - root, end, end)));
  }
  private static ProjectData project() {
    var p = new ProjectData(); p.project.seed = 17; p.project.minY = Integer.MIN_VALUE; p.project.maxY = Integer.MAX_VALUE;
    for (int dimension : new int[] {2, 3}) {
      var noise = new Noise(); noise.id = "coordinate-" + dimension; noise.dimension = dimension; noise.algorithm.key = "CONSTANT";
      var expression = ProcessingRegistry.create("EXPRESSION", "arithmetic", dimension);
      expression.parameters.put("expression", ProjectIO.JSON.valueToTree(dimension == 2 ? "x+2*z+.25" : "x+2*z+4*y+.25"));
      noise.processing.add(expression); noise.outputBinding = Binding.earlier(expression.uuid);
      (dimension == 2 ? p.noise2D : p.noise3D).add(noise);
    }
    return p; // No world placements: every logical root is an immediate EMPTY result.
  }

  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var p = project(); String authored = ProjectIO.JSON.writeValueAsString(p);
    var compiled = new CompiledProject(p); var two = compiled.noise2D("coordinate-2", 17); var three = compiled.noise3D("coordinate-3", 17);
    long vectors = 0, axes = 0, fields = 0, lookups = 0, async = 0, cases = 0, counts = 0, overflows = 0, clipping = 0;
    long invalid = 0, nullPriority = 0, nulls = 0; var retained = new ArrayList<VoxelChunk>();
    var callbacks = new AtomicInteger(); var point = new BlockPos(-1, 0, 1); var pos = new ChunkPos(-1, 0, 1);
    var singleton = new RegionBounds(point, point);
    for (int root : INVALID) {
      rejected(() -> ChunkPos.fromBlock(point, root)); invalid++;
      rejected(() -> singleton.chunkCount(root)); invalid++;
      rejected(() -> VoxelChunk.uniform(pos, root, 0)); invalid++;
      rejected(() -> new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0)); invalid++;
      rejected(() -> VoxelChunk.generate(pos, root, (x, y, z) -> callbacks.incrementAndGet())); invalid++;
      var bytes = new ByteArrayInputStream(new byte[] {0});
      rejected(() -> VoxelChunk.readTree(pos, root, new DataInputStream(bytes), value -> { callbacks.incrementAndGet(); return value; })); invalid++;
      require(bytes.available() == 1 && callbacks.get() == 0, "Invalid root consumed input/callback work");
      rejected(() -> ChunkPos.fromBlock(null, root)); nullPriority++;
    }
    try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20)) {
      for (int root : ROOTS) {
        try { ChunkPos.fromBlock(null, root); throw new AssertionError("Null coordinate accepted"); }
        catch (NullPointerException expected) { nulls++; }
        var values = new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -root - 1, -root, -root + 1, -1, 0, 1,
            root - 1, root, root + 1, 255, 256, 257, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        try (var world = engine.openWorld(compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (int i = 0; i < values.length; i++) {
            var block = new BlockPos(values[i], values[(i + 5) % values.length], values[(i + 11) % values.length]);
            var x = axis(block.x(), root); var y = axis(block.y(), root); var z = axis(block.z(), root);
            var expected = new ChunkPos(x.chunk, y.chunk, z.chunk);
            require(ChunkPos.fromBlock(block, root).equals(expected), "Signed coordinate mapping differs"); vectors++;
            for (int coordinate : new int[] {block.x(), block.y(), block.z()}) {
              var a = axis(coordinate, root);
              require(a.local >= 0 && a.local < root && Math.floorMod(coordinate, root) == a.local
                  && (long) a.chunk * root + a.local == coordinate
                  && (long) a.chunk * root >= Integer.MIN_VALUE && (long) a.chunk * root + root - 1 <= Integer.MAX_VALUE,
                  "Local reconstruction/root extent differs"); axes++;
            }
            bits((double) ((long) block.x() + 2L * block.z()) + .25, two.sample(block.x(), block.z()));
            bits((double) ((long) block.x() + 2L * block.z() + 4L * block.y()) + .25, three.sample(block.x(), block.y(), block.z())); fields += 2;
            require(world.getVoxelTypeId(block) == 0, "Empty world point lookup changed"); lookups++;
            var chunk = world.generateChunkAsync(expected, i % 2 == 0 ? GenerationPriority.INTERACTIVE : GenerationPriority.BACKGROUND).get(5, TimeUnit.SECONDS);
            require(chunk.position().equals(expected) && chunk.size() == root && chunk.isEmpty()
                && chunk.getVoxelTypeId(x.local, y.local, z.local) == 0, "Async chunk mapping/size differs"); async++;
          }
          retained.add(world.generateChunk(pos));
          require(world.inFlightChunks() == 0 && engine.metrics().stagingBytes() == 0, "Coordinate query retained staging/subscriber work");
        }
        var regions = regions(root);
        for (var region : regions) {
          var expected = count(region, root); cases++;
          if (expected.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) { rejected(() -> region.chunkCount(root)); overflows++; }
          else { require(region.chunkCount(root) == expected.longValueExact(), "Inclusive region count differs"); counts++; }
        }
        var original = regions.get(1);
        var middle = original.clipY(-1, 0).orElseThrow();
        require(middle.equals(new RegionBounds(new BlockPos(-root, -1, -root), new BlockPos(root - 1, 0, root - 1)))
            && middle.chunkCount(root) == 8, "Interior clipping differs"); clipping++;
        require(original.clipY(-root, -root).orElseThrow().chunkCount(root) == 4, "Inclusive endpoint clipping differs"); clipping++;
        require(original.clipY(root, root + 1).isEmpty() && original.min().y() == -root && original.max().y() == root - 1,
            "Empty clipping changed source bounds"); clipping++;
      }
    }
    long corners = 0;
    for (var chunk : retained) {
      int last = chunk.size() - 1;
      for (int z : new int[] {0, last}) for (int y : new int[] {0, last}) for (int x : new int[] {0, last}) {
        require(chunk.getVoxelTypeId(x, y, z) == 0, "Retained extreme-local EMPTY corner changed"); corners++;
      }
      chunk.forEachOccupied((x, y, z, value) -> { throw new AssertionError("EMPTY root emitted occupied cells"); });
      require(chunk.storageStatistics().occupiedVoxels() == 0 && Arrays.equals(new int[] {0}, chunk.paletteRuntimeIds()), "EMPTY storage metadata differs");
    }
    require(vectors == 128 && axes == 384 && fields == 256 && lookups == 128 && async == 128
        && cases == 56 && counts == 47 && overflows == 9 && clipping == 24 && invalid == 96
        && nullPriority == 16 && nulls == 8 && retained.size() == 8 && corners == 64
        && authored.equals(ProjectIO.JSON.writeValueAsString(p)), "Replay dimensions/source ownership changed");
    Files.writeString(out.resolve("coordinate-boundary-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "coordinateVectors", vectors, "axisReconstructions", axes,
        "validation", Map.of("invalidRoots", invalid, "rootBeforeNull", nullPriority, "validRootNulls", nulls),
        "regions", Map.of("cases", cases, "validCounts", counts, "overflows", overflows, "clipping", clipping),
        "engine", Map.of("lookups", lookups, "asyncChunks", async, "fields", fields, "retainedChunks", retained.size(), "corners", corners),
        "roots", Arrays.stream(ROOTS).boxed().toList())));
    System.out.println("COORDINATE_BOUNDARY_CONSUMER_PASS");
  }
  private static void bits(double expected, double actual) {
    require(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual), "Coordinate field raw bits differ");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
