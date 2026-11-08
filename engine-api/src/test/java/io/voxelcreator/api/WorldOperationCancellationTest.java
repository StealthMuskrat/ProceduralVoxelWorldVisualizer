package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred public operation-boundary cancellation, armed by completed raw profiler events. */
class WorldOperationCancellationTest {
  private static final ChunkPos BAD = new ChunkPos(-1, 0, 0), GOOD = new ChunkPos(0, 0, 0);
  private record Fixture(ProjectData project, UUID first, UUID second, UUID stage, UUID badExpression, Profiler.Kind boundary) {}

  private Fixture fixture(int mode, boolean split) {
    var p = new ProjectData(); p.project.minY = -8; p.project.maxY = 7;
    int dimension = mode == 2 ? 3 : 2;
    var a = new VoxelType(); a.id = "first"; var b = new VoxelType(); b.id = "second"; p.voxelTypes.addAll(List.of(a, b));
    var good = new Noise(); good.id = "constant"; good.dimension = dimension; good.algorithm.key = "CONSTANT";
    good.algorithm.parameters.clear(); good.algorithm.parameters.put("value", .25);
    var bad = new Noise(); bad.id = "domain"; bad.dimension = dimension; bad.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "domain", dimension);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("sqrt(x)"));
    bad.processing.add(expression); bad.outputBinding = Binding.earlier(expression.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).addAll(List.of(good, bad));
    var firstLayer = new Layer(); firstLayer.id = "first-layer"; firstLayer.defaults.action = "SET"; firstLayer.defaults.voxelTypeUuid = a.uuid;
    if (mode == 1) firstLayer.defaults.samplingTransforms.add(new SamplingTransform());
    p.worldGenerator.layers.add(firstLayer);
    var first = new Operation(); first.id = "first"; first.interpretation = "ALL"; first.noiseGeneratorUuid = good.uuid;
    firstLayer.operations.add(first);
    var secondLayer = firstLayer;
    if (split) {
      secondLayer = new Layer(); secondLayer.id = "second-layer"; secondLayer.defaults.action = "SET"; secondLayer.defaults.voxelTypeUuid = b.uuid;
      if (mode == 1) secondLayer.defaults.samplingTransforms.add(new SamplingTransform());
      p.worldGenerator.layers.add(secondLayer);
    }
    var second = new Operation(); second.id = "second"; second.interpretation = "ALL"; second.noiseGeneratorUuid = bad.uuid;
    if (!split) second.overrides.put("voxelTypeUuid", ProjectIO.JSON.valueToTree(b.uuid));
    secondLayer.operations.add(second);
    return new Fixture(p, first.uuid, second.uuid, secondLayer.uuid, expression.uuid,
        mode == 0 ? Profiler.Kind.FIELD_PREFETCH : Profiler.Kind.OPERATION);
  }
  private WorldOptions options(int root) { return new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0); }
  private Profiler.Capture capture(NoiseGeneratorEngine engine) { return engine.profiler().start(new Profiler.Options(60000, 4096, 128, 1, true)); }
  private boolean firstFinished(Profiler.Capture c, Fixture f) {
    return c.snapshot().samples().stream().anyMatch(s -> s.kind() == f.boundary && f.first.equals(s.sourceUuid()) && s.outcome() == Profiler.Outcome.SUCCESS);
  }
  private void stoppedBeforeSecond(Profiler.Capture c, Fixture f, NoiseGeneratorEngine e, WorldContext w) {
    assertTrue(firstFinished(c, f));
    assertTrue(c.snapshot().samples().stream().noneMatch(s -> s.kind() == f.boundary && f.second.equals(s.sourceUuid())));
    assertEquals(0, w.inFlightChunks()); assertEquals(0, w.cacheStats().chunks()); assertEquals(0, e.metrics().stagingBytes());
  }
  private void exact(VoxelChunk c) {
    for (int z = 0; z < c.size(); z++) for (int y = 0; y < c.size(); y++) for (int x = 0; x < c.size(); x++) assertEquals(2, c.getVoxelTypeId(x, y, z));
  }
  private void cancelled(int mode, int root, boolean split, int route) throws Exception {
    var f = fixture(mode, split); String authored = ProjectIO.JSON.valueToTree(f.project).toString(); VoxelChunk retained;
    try (var e = new NoiseGeneratorEngine(1, 8, 16L << 20); var w = e.openWorld(new CompiledProject(f.project), options(root))) {
      retained = w.generateChunk(GOOD); exact(retained); var c = capture(e);
      var token = new CancellationToken(() -> firstFinished(c, f));
      if (route == 2) assertThrows(CancellationException.class, () -> w.generateStageChunk(BAD, f.stage, token));
      else {
        var future = route == 0 ? w.generateChunkAsync(BAD, token) : w.generateChunkAsync(BAD, token, GenerationPriority.INTERACTIVE);
        assertThrows(CancellationException.class, () -> future.get(5, TimeUnit.SECONDS));
      }
      stoppedBeforeSecond(c, f, e, w); assertEquals(1, e.metrics().completed()); exact(retained);
    }
    exact(retained); assertEquals(authored, ProjectIO.JSON.valueToTree(f.project).toString());
  }

  @Test
  void cachedColumnsStopBetweenPlacementsAcrossLayerBoundaries() throws Exception {
    for (int root : new int[] {2, 4, 8}) for (boolean split : new boolean[] {false, true}) for (int route = 0; route < 2; route++) cancelled(0, root, split, route);
  }
  @Test
  void perVoxel2dAnd3dStopBetweenPlacementsAcrossLayerBoundaries() throws Exception {
    for (int mode : new int[] {1, 2}) for (int root : new int[] {2, 4, 8}) for (boolean split : new boolean[] {false, true}) for (int route = 0; route < 2; route++) cancelled(mode, root, split, route);
  }
  @Test
  void layerStageUsesTheSamePrefetchAndOperationBoundaries() throws Exception {
    for (int mode = 0; mode < 3; mode++) for (int root : new int[] {2, 4, 8}) cancelled(mode, root, false, 2);
  }
  @Test
  void explicitCancellationDuringHeldStageBoundaryRetainsOwnershipAndFlag() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var f = fixture(mode, false); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var callbacks = new AtomicInteger();
      try (var e = new NoiseGeneratorEngine(1, 8, 16L << 20); var w = e.openWorld(new CompiledProject(f.project), options(4)); var queries = Executors.newSingleThreadExecutor()) {
        var retained = w.generateChunk(GOOD); var c = capture(e);
        var token = new CancellationToken(() -> {
          if (!firstFinished(c, f)) return false;
          entered.countDown();
          try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Boundary not released"); }
          catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
          return false;
        });
        token.onCancel(callbacks::incrementAndGet);
        var pending = queries.submit(() -> w.generateStageChunk(BAD, f.stage, token));
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); assertFalse(pending.isDone()); assertTrue(token.cancel()); }
        finally { release.countDown(); }
        assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS)).getCause());
        assertTrue(token.isCancelled()); assertFalse(token.cancel()); assertEquals(1, callbacks.get());
        stoppedBeforeSecond(c, f, e, w); exact(retained);
      }
    }
  }
  @Test
  void parentFaultsAndUncancelledFieldFaultsKeepTheirOriginalIdentities() {
    for (int mode = 0; mode < 3; mode++) {
      var f = fixture(mode, false); var fault = new IllegalStateException("parent-boundary");
      try (var e = new NoiseGeneratorEngine(1, 8, 16L << 20); var w = e.openWorld(new CompiledProject(f.project), options(4))) {
        var retained = w.generateChunk(GOOD); var c = capture(e);
        var token = new CancellationToken(() -> { if (firstFinished(c, f)) throw fault; return false; });
        assertSame(fault, assertThrows(IllegalStateException.class, () -> w.generateStageChunk(BAD, f.stage, token)));
        stoppedBeforeSecond(c, f, e, w);
        var fieldFault = assertThrows(EvaluationFailure.class, () -> w.generateChunk(BAD));
        assertEquals(f.badExpression, fieldFault.itemUuid);
        assertEquals(0, e.metrics().stagingBytes()); assertEquals(0, w.cacheStats().chunks()); exact(retained); exact(w.generateChunk(GOOD));
      }
    }
  }
}
