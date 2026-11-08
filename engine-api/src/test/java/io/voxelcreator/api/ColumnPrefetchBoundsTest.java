package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Deferred semantic equivalence of Y rejection and column prefetch, including stage prefixes. */
class ColumnPrefetchBoundsTest {
  private record Fixture(ProjectData project, Layer bounded, Operation fill, Operation skipped, Noise bad, Item expression) {}

  private Fixture fixture(int projectMin, int projectMax, int placementMin, int placementMax, boolean overflow) {
    var p = new ProjectData(); p.project.minY = projectMin; p.project.maxY = projectMax;
    var type = new VoxelType(); type.id = "solid"; p.voxelTypes.add(type);
    var good = new Noise(); good.id = "constant"; good.algorithm.key = "CONSTANT";
    good.algorithm.parameters.put("value", .25); p.noise2D.add(good);
    var bad = new Noise(); bad.id = "experimental"; bad.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "domain", 2);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree(overflow ? "source" : "sqrt(x)"));
    bad.processing.add(expression); bad.outputBinding = Binding.earlier(expression.uuid); p.noise2D.add(bad);
    var initial = new Layer(); initial.id = "fill"; initial.defaults.action = "SET";
    initial.defaults.voxelTypeUuid = type.uuid; p.worldGenerator.layers.add(initial);
    var fill = new Operation(); fill.id = "fill"; fill.interpretation = "ALL";
    fill.noiseGeneratorUuid = good.uuid; initial.operations.add(fill);
    var bounded = new Layer(); bounded.id = "bounded"; bounded.defaults.action = "CLEAR";
    bounded.defaults.minY = placementMin; bounded.defaults.maxY = placementMax;
    if (overflow) bounded.defaults.scaleX = Double.MAX_VALUE;
    var skipped = new Operation(); skipped.id = "experimental"; skipped.interpretation = "ALL";
    skipped.noiseGeneratorUuid = bad.uuid; bounded.operations.add(skipped); p.worldGenerator.layers.add(bounded);
    return new Fixture(p, bounded, fill, skipped, bad, expression);
  }

  private WorldOptions options(int root) { return new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0); }

  private void exact(VoxelChunk chunk, int min, int max) {
    for (int z = 0; z < chunk.size(); z++) for (int y = 0; y < chunk.size(); y++)
      for (int x = 0; x < chunk.size(); x++) {
        long worldY = (long) chunk.position().y() * chunk.size() + y;
        assertEquals(worldY >= min && worldY <= max ? 1 : 0, chunk.getVoxelTypeId(x, y, z));
      }
  }

  @Test
  void excludedColumnsPreserveEarlierWritesAcrossProjectClippingAndExtremeY() {
    for (int root : new int[] {2, 4}) for (boolean overflow : new boolean[] {false, true})
      for (int[] domain : new int[][] {
          {-16, 15, 8, 9, 0}, {-16, 15, -9, -8, 0}, {0, 0, 1, 1, 0},
          {Integer.MIN_VALUE, Integer.MIN_VALUE + 7, Integer.MIN_VALUE + 4, Integer.MIN_VALUE + 5, Math.floorDiv(Integer.MIN_VALUE, root)},
          {Integer.MAX_VALUE - 7, Integer.MAX_VALUE, Integer.MAX_VALUE - 5, Integer.MAX_VALUE - 4, Math.floorDiv(Integer.MAX_VALUE, root)}}) {
        var f = fixture(domain[0], domain[1], domain[2], domain[3], overflow);
        assertTrue(NoiseCompiler.validate(f.project).isEmpty());
        String authored = ProjectIO.JSON.valueToTree(f.project).toString();
        var pos = new ChunkPos(-1, domain[4], 0);
        try (var engine = new NoiseGeneratorEngine(2, 8, 8L << 20);
            var world = engine.openWorld(new CompiledProject(f.project), options(root))) {
          exact(world.generateChunk(pos), domain[0], domain[1]);
          var pair = world.generateStageChunk(pos, f.skipped.uuid);
          exact(pair.before(), domain[0], domain[1]); exact(pair.after(), domain[0], domain[1]);
          for (int y = 0; y < root; y++) {
            int worldY = pos.y() * root + y;
            var trace = world.trace(new BlockPos(-root, worldY, 0));
            assertEquals(worldY >= domain[0] && worldY <= domain[1] ? 1 : 0, trace.finalType());
            if (!trace.steps().isEmpty()) {
              assertTrue(Double.isNaN(trace.steps().getLast().field()));
              assertNull(trace.steps().getLast().details().primaryField());
            }
          }
          assertEquals(0, engine.metrics().stagingBytes());
        }
        assertEquals(authored, ProjectIO.JSON.valueToTree(f.project).toString());
      }
  }

  @Test
  void resolvedPlacementOverridesExcludeFieldsAndResetRestoresTheirFailureBoundary() {
    var f = fixture(-16, 15, 0, 3, false);
    f.skipped.overrides.put("minY", ProjectIO.JSON.valueToTree(8));
    f.skipped.overrides.put("maxY", ProjectIO.JSON.valueToTree(9));
    var compiled = new CompiledProject(f.project);
    f.skipped.overrides.clear();
    try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
        var old = engine.openWorld(compiled, options(4));
        var reset = engine.openWorld(new CompiledProject(f.project), options(4))) {
      var retained = old.generateChunk(new ChunkPos(-1, 0, 0)); exact(retained, -16, 15);
      assertThrows(EvaluationFailure.class, () -> reset.generateChunk(new ChunkPos(-1, 0, 0)));
      exact(retained, -16, 15); exact(old.generateChunk(new ChunkPos(-1, 0, 0)), -16, 15);
      assertEquals(0, engine.metrics().stagingBytes());
    }
  }

  @Test
  void inclusiveBottomAndTopContactStillEvaluateAndRejectIncompleteResults() {
    for (int root : new int[] {2, 4}) for (int endpoint : new int[] {0, root - 1})
      for (boolean overflow : new boolean[] {false, true}) {
        var f = fixture(-16, 15, endpoint, endpoint, overflow);
        try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
            var world = engine.openWorld(new CompiledProject(f.project), options(root))) {
          var failure = assertThrows(EvaluationFailure.class, () -> world.generateChunk(new ChunkPos(-1, 0, 0)));
          assertEquals(f.bad.uuid, failure.resourceUuid);
          assertEquals(overflow ? f.skipped.uuid : f.expression.uuid, failure.itemUuid);
          assertThrows(EvaluationFailure.class, () -> world.generateStageChunk(new ChunkPos(-1, 0, 0), f.skipped.uuid));
          assertEquals(0, world.cacheStats().chunks()); assertEquals(0, engine.metrics().stagingBytes());
        }
      }
  }

  @Test
  void eligibleAndSkippedPrefetchAttributionFollowsActualEvaluation() {
    var f = fixture(-16, 15, 8, 9, false);
    try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
        var world = engine.openWorld(new CompiledProject(f.project), options(4))) {
      var capture = engine.profiler().start(new Profiler.Options(60000, 4096, 128, 65536, true));
      world.generateChunk(new ChunkPos(-1, 0, 0)); world.generateStageChunk(new ChunkPos(-1, 0, 0), f.skipped.uuid);
      var samples = capture.snapshot().samples().stream().filter(s -> s.kind() == Profiler.Kind.FIELD_PREFETCH).toList();
      assertFalse(samples.isEmpty()); assertTrue(samples.stream().allMatch(s -> f.fill.uuid.equals(s.sourceUuid())));
      assertTrue(capture.snapshot().samples().stream().anyMatch(s -> s.kind() == Profiler.Kind.OPERATION_WORK && f.skipped.uuid.equals(s.sourceUuid())));
    }
  }

  @Test
  void identityTransformFallbackAndBothRegionPoliciesMatchExcludedColumnResults() throws Exception {
    for (boolean perVoxel : new boolean[] {false, true}) for (var policy : RegionRequest.FailurePolicy.values()) {
      var f = fixture(0, 0, 1, 1, false);
      if (perVoxel) f.bounded.defaults.samplingTransforms.add(new SamplingTransform());
      try (var engine = new NoiseGeneratorEngine(2, 8, 8L << 20);
          var world = engine.openWorld(new CompiledProject(f.project), options(4))) {
        var bounds = new RegionBounds(new BlockPos(-4, 0, 0), new BlockPos(-1, 3, 3));
        var request = new RegionRequest(bounds, policy, RegionRequest.DeliveryOrder.COORDINATE, 1);
        try (var result = world.collectRegionAsync(request).get(10, TimeUnit.SECONDS)) {
          assertTrue(result.summary().failures().isEmpty()); assertEquals(1, result.chunks().size());
          assertEquals(new TaskProgress("COMPLETED", 0, 1, 0, 0), result.summary().progress());
          assertEquals(Optional.of(new RegionBounds(new BlockPos(-4, 0, 0), new BlockPos(-1, 0, 3))), result.summary().effective());
          exact(result.chunks().getFirst(), 0, 0);
        }
        assertEquals(0, engine.metrics().collectionBytes()); assertEquals(0, engine.metrics().stagingBytes());
      }
    }
  }
}
