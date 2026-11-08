package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.EvaluationFailure;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Deferred engine-JAR oracle for excluded field domains and inclusive prefetch boundaries. */
public final class ColumnPrefetchConsumer {
  private record Fixture(ProjectData project, Layer bounded, Operation skipped) {}
  private record Retained(VoxelChunk chunk, int minY, int maxY) {}
  private static UUID id(int value) { return new UUID(0, value); }

  private static Fixture fixture(int[] domain, int mode) {
    var p = new ProjectData(); p.project.uuid = id(1); p.worldGenerator.uuid = id(2);
    p.project.minY = domain[0]; p.project.maxY = domain[1]; p.project.seed = 17;
    var type = new VoxelType(); type.uuid = id(10); type.id = "solid"; p.voxelTypes.add(type);
    var good = new Noise(); good.uuid = id(100); good.id = "constant"; good.algorithm.key = "CONSTANT";
    good.algorithm.parameters.put("value", .25); p.noise2D.add(good);
    var bad = new Noise(); bad.uuid = id(101); bad.id = "experimental"; bad.algorithm.key = "CONSTANT";
    var item = ProcessingRegistry.create("EXPRESSION", "domain", 2); item.uuid = id(110);
    item.parameters.put("expression", ProjectIO.JSON.valueToTree(mode == 2 ? "source" : "sqrt(x)"));
    bad.processing.add(item); bad.outputBinding = Binding.earlier(item.uuid); p.noise2D.add(bad);
    var fill = new Layer(); fill.uuid = id(200); fill.id = "fill";
    fill.defaults.action = "SET"; fill.defaults.voxelTypeUuid = type.uuid; p.worldGenerator.layers.add(fill);
    var first = new Operation(); first.uuid = id(300); first.id = "fill"; first.interpretation = "ALL";
    first.noiseGeneratorUuid = good.uuid; fill.operations.add(first);
    var bounded = new Layer(); bounded.uuid = id(201); bounded.id = "bounded";
    bounded.defaults.action = "CLEAR"; bounded.defaults.minY = domain[2]; bounded.defaults.maxY = domain[3];
    if (mode == 1) bounded.defaults.samplingTransforms.add(new SamplingTransform());
    if (mode == 2) bounded.defaults.scaleX = Double.MAX_VALUE;
    var skipped = new Operation(); skipped.uuid = id(301); skipped.id = "experimental";
    skipped.interpretation = "ALL"; skipped.noiseGeneratorUuid = bad.uuid; bounded.operations.add(skipped);
    p.worldGenerator.layers.add(bounded); return new Fixture(p, bounded, skipped);
  }

  private static List<int[]> domains(int root) {
    return List.of(new int[] {-16, 15, 8, 9, 0}, new int[] {-16, 15, -9, -8, 0}, new int[] {0, 0, 1, 1, 0},
        new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE + 7, Integer.MIN_VALUE + 4, Integer.MIN_VALUE + 5, Math.floorDiv(Integer.MIN_VALUE, root)},
        new int[] {Integer.MAX_VALUE - 7, Integer.MAX_VALUE, Integer.MAX_VALUE - 5, Integer.MAX_VALUE - 4, Math.floorDiv(Integer.MAX_VALUE, root)});
  }
  private static int expected(long y, int min, int max) { return y >= min && y <= max ? 1 : 0; }
  private static long cells(Retained retained) {
    var chunk = retained.chunk; long checked = 0;
    for (int z = 0; z < chunk.size(); z++) for (int y = 0; y < chunk.size(); y++)
      for (int x = 0; x < chunk.size(); x++) {
        long worldY = (long) chunk.position().y() * chunk.size() + y;
        require(chunk.getVoxelTypeId(x, y, z) == expected(worldY, retained.minY, retained.maxY), "Excluded placement changed exact voxel"); checked++;
      }
    return checked;
  }
  private static WorldOptions options(int root, int workers) {
    return new WorldOptions(null, root, workers == 1 ? WorldOptions.CachePolicy.NO_CACHE
        : WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, 1L << 20);
  }

  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var finals = new ArrayList<Retained>(); var stages = new ArrayList<Retained>();
    long worlds = 0, traces = 0, regions = 0, failures = 0, captures = 0;
    for (int root : new int[] {2, 4}) for (int workers : new int[] {1, 3})
      for (int mode : new int[] {0, 1, 2}) for (var domain : domains(root)) {
        var f = fixture(domain, mode); String original = ProjectIO.JSON.writeValueAsString(f.project);
        var compiled = new CompiledProject(f.project); var pos = new ChunkPos(-1, domain[4], 0);
        int bottom = pos.y() * root, top = bottom + (root - 1);
        try (var engine = new NoiseGeneratorEngine(workers, 16, 16L << 20);
            var world = engine.openWorld(compiled, options(root, workers))) {
          var capture = engine.profiler().start(new Profiler.Options(60000, 4096, 128, 65536, true));
          var chunk = world.generateChunkAsync(pos).get(10, TimeUnit.SECONDS);
          finals.add(new Retained(chunk, domain[0], domain[1]));
          if (workers == 3) require(world.generateChunk(pos) == chunk, "Bounded cache lost completed chunk");
          var pair = world.generateStageChunk(pos, f.skipped.uuid);
          stages.add(new Retained(pair.before(), domain[0], domain[1])); stages.add(new Retained(pair.after(), domain[0], domain[1]));
          for (int y : new int[] {bottom, top}) {
            var point = new BlockPos(-root, y, 0); var trace = world.trace(point);
            require(trace.finalType() == expected(y, domain[0], domain[1])
                && world.getVoxelTypeId(point) == trace.finalType(), "Trace/direct/chunk exclusion differs");
            if (!trace.steps().isEmpty()) {
              var skipped = trace.steps().getLast();
              require(skipped.operationUuid().equals(id(301)) && Double.isNaN(skipped.field())
                  && skipped.details().primaryField() == null, "Trace evaluated excluded field");
            }
            traces++;
          }
          var bounds = new RegionBounds(new BlockPos(-root, bottom, 0), new BlockPos(-1, top, root - 1));
          var request = new RegionRequest(bounds, workers == 1 ? RegionRequest.FailurePolicy.CONTINUE : RegionRequest.FailurePolicy.FAIL_FAST,
              workers == 1 ? RegionRequest.DeliveryOrder.COMPLETION : RegionRequest.DeliveryOrder.COORDINATE, 1);
          try (var result = world.collectRegionAsync(request).get(10, TimeUnit.SECONDS)) {
            require(result.summary().failures().isEmpty() && result.chunks().size() == 1, "Excluded field failed region delivery");
            var progress = result.summary().progress();
            require(progress.pending() == 0 && progress.completed() == 1 && progress.failed() == 0 && progress.cancelled() == 0,
                "Excluded region lost terminal chunk counts");
            var effective = new RegionBounds(new BlockPos(-root, Math.max(bottom, domain[0]), 0),
                new BlockPos(-1, Math.min(top, domain[1]), root - 1));
            require(result.summary().requested().equals(bounds) && result.summary().effective().equals(Optional.of(effective)),
                "Region clipping metadata differs");
            finals.add(new Retained(result.chunks().getFirst(), domain[0], domain[1])); regions++;
          }
          require(engine.metrics().collectionBytes() == 0 && engine.metrics().stagingBytes() == 0, "Request reservation leaked");
          engine.profiler().stop();
          var prefetch = capture.snapshot().samples().stream().filter(s -> s.kind() == Profiler.Kind.FIELD_PREFETCH).toList();
          require(!prefetch.isEmpty() && prefetch.stream().allMatch(s -> id(300).equals(s.sourceUuid())), "Excluded field produced prefetch measurements");
          captures++; worlds++;
        }
        require(original.equals(ProjectIO.JSON.writeValueAsString(f.project)), "Generation changed caller project");
      }
    for (int root : new int[] {2, 4}) for (int workers : new int[] {1, 3})
      for (int mode : new int[] {0, 2}) for (int endpoint : new int[] {0, root - 1}) {
        var f = fixture(new int[] {-16, 15, 8, 9, 0}, mode);
        f.skipped.overrides.put("minY", ProjectIO.JSON.valueToTree(endpoint));
        f.skipped.overrides.put("maxY", ProjectIO.JSON.valueToTree(endpoint));
        try (var engine = new NoiseGeneratorEngine(workers, 8, 8L << 20);
            var world = engine.openWorld(new CompiledProject(f.project), options(root, workers))) {
          for (boolean stage : new boolean[] {false, true}) {
            try {
              if (stage) world.generateStageChunk(new ChunkPos(-1, 0, 0), f.skipped.uuid);
              else world.generateChunk(new ChunkPos(-1, 0, 0));
              throw new AssertionError("Inclusive eligible field failure was suppressed");
            } catch (EvaluationFailure failure) {
              require(failure.resourceUuid.equals(id(101)) && failure.itemUuid.equals(id(mode == 2 ? 301 : 110)),
                  "Eligible failure lost source identity"); failures++;
            }
          }
          require(world.cacheStats().chunks() == 0 && engine.metrics().stagingBytes() == 0, "Failed request retained partial results");
        }
      }
    long finalCells = 0, stageCells = 0;
    for (var retained : finals) finalCells += cells(retained);
    for (var retained : stages) stageCells += cells(retained);
    require(worlds == 60 && traces == 120 && regions == 60 && captures == 60 && failures == 32
        && finals.size() == 120 && finalCells == 4320 && stages.size() == 120 && stageCells == 4320, "Replay dimensions changed");
    Files.writeString(out.resolve("column-prefetch-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "excludedWorlds", worlds, "traces", traces, "regions", regions, "captures", captures, "eligibleFailures", failures,
        "retainedFinalChunks", finals.size(), "retainedFinalCells", finalCells, "retainedStageSnapshots", stages.size(),
        "retainedStageCells", stageCells, "roots", List.of(2, 4))));
    System.out.println("COLUMN_PREFETCH_CONSUMER_PASS");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
