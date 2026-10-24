package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

public class EngineContractTest {
  @TempDir Path temp;

  static ProjectData fixture() {
    var p = new ProjectData();
    p.project.minY = -2;
    p.project.maxY = 3;
    var stone = new VoxelType();
    stone.id = "stone";
    p.voxelTypes.add(stone);
    var dirt = new VoxelType();
    dirt.id = "dirt";
    p.voxelTypes.add(dirt);
    var n = new Noise();
    n.id = "height";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", 0.);
    p.noise2D.add(n);
    var layer = new Layer();
    layer.id = "terrain";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = stone.uuid;
    layer.defaults.heightScale = 1;
    var op = new Operation();
    op.id = "fill";
    op.noiseGeneratorUuid = n.uuid;
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    var carve = new Layer();
    carve.id = "carve";
    carve.defaults.action = "CLEAR";
    carve.defaults.minY = 0;
    carve.defaults.maxY = 0;
    carve.defaults.voxelStatePredicate.kind = "OCCUPIED";
    var clear = new Operation();
    clear.id = "clear";
    clear.noiseGeneratorUuid = n.uuid;
    carve.operations.add(clear);
    p.worldGenerator.layers.add(carve);
    var top = new Layer();
    top.id = "top";
    top.defaults.action = "SET";
    top.defaults.voxelTypeUuid = dirt.uuid;
    top.defaults.minY = 0;
    top.defaults.maxY = 0;
    top.defaults.voxelStatePredicate.kind = "EMPTY";
    var fill = new Operation();
    fill.id = "top";
    fill.noiseGeneratorUuid = n.uuid;
    top.operations.add(fill);
    p.worldGenerator.layers.add(top);
    return p;
  }

  @Test
  void layeredActionsTraceAndAllRootSizes() {
    var p = fixture();
    var compiled = new CompiledProject(p);
    try (var engine = new NoiseGeneratorEngine(2, 16, 512L << 20)) {
      for (int size : new int[] {2, 4, 8, 16, 32, 64, 128, 256})
        try (var w =
            engine.openWorld(
                compiled, new WorldOptions(null, size, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (int y = -3; y <= 4; y++) {
            var trace = w.trace(new BlockPos(-1, y, -1));
            assertEquals(
                y < -2 || y > 0 ? 0 : y == 0 ? 2 : 1,
                trace.finalType(),
                "size=" + size + " y=" + y);
            if (y == 0) {
              assertEquals(3, trace.steps().size());
              assertEquals(1, trace.steps().get(1).previous());
              assertEquals(0, trace.steps().get(1).result());
              assertEquals("SET", trace.steps().get(2).action());
            }
          }
          if (size <= 32) {
            var c = w.generateChunk(new ChunkPos(-1, 0, -1));
            assertEquals(2, c.getVoxelTypeId(size - 1, 0, size - 1));
            assertEquals(0, c.getVoxelTypeId(size - 1, 1, size - 1));
          }
        }
    }
  }

  @Test
  void explicitZeroOverrideAndLiveInheritance() {
    var p = fixture();
    var layer = p.worldGenerator.layers.getFirst();
    var op = layer.operations.getFirst();
    op.overrides.put("strength", ProjectIO.JSON.valueToTree(0));
    assertEquals(0, SettingsResolver.resolve(layer, op).strength);
    layer.defaults.strength = .6;
    assertEquals(0, SettingsResolver.resolve(layer, op).strength);
    op.overrides.remove("strength");
    assertEquals(.6, SettingsResolver.resolve(layer, op).strength);
    assertEquals(.01, p.noise2D.getFirst().algorithm.parameters.get("frequency"));
  }

  @Test
  void snapshotsSeedsCachesAndAsyncStayIsolated() throws Exception {
    var p = fixture();
    var n = p.noise2D.getFirst();
    n.algorithm.key = "WHITE_HASH";
    p.worldGenerator.layers.getFirst().defaults.probability = .5;
    var c = new CompiledProject(p);
    try (var e = new NoiseGeneratorEngine(3, 32, 256L << 20);
        var a =
            e.openWorld(
                c,
                new WorldOptions(1L, 8, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, 1 << 20));
        var b = e.openWorld(c, new WorldOptions(2L, 8, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var pos = new ChunkPos(-1, -1, 0);
      var one = a.generateChunk(pos);
      var two = a.generateChunkAsync(pos).get(5, TimeUnit.SECONDS);
      for (int y = 0; y < 8; y++)
        for (int z = 0; z < 8; z++)
          for (int x = 0; x < 8; x++)
            assertEquals(one.getVoxelTypeId(x, y, z), two.getVoxelTypeId(x, y, z));
      assertTrue(a.cacheStats().hits() > 0);
      assertEquals(0, b.cacheStats().chunks());
      a.clearCache();
      assertEquals(0, a.cacheStats().chunks());
      assertEquals(one.getVoxelTypeId(7, 7, 0), one.getVoxelTypeId(7, 7, 0));
      assertNotEquals(c.noise2D(n.uuid, 1).sample(4, 5), c.noise2D(n.uuid, 2).sample(4, 5));
      a.close();
      assertNotNull(b.generateChunk(pos));
    }
  }

  @Test
  void streamingBackpressureAndCancellation() throws Exception {
    try (var e = new NoiseGeneratorEngine(2, 32, 128L << 20);
        var w =
            e.openWorld(
                new CompiledProject(new ProjectData()),
                new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var bounds = new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(31, 1, 1));
      var gate = new CompletableFuture<Void>();
      var deliveries = new AtomicInteger();
      var task =
          w.streamRegion(
              new RegionRequest(
                  bounds,
                  RegionRequest.FailurePolicy.CONTINUE,
                  RegionRequest.DeliveryOrder.COMPLETION,
                  3),
              c -> {
                deliveries.incrementAndGet();
                return gate;
              });
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (deliveries.get() == 0 && System.nanoTime() < until) Thread.sleep(5);
      assertEquals(1, deliveries.get());
      assertFalse(task.completion().isDone());
      assertTrue(
          e.metrics().completed() <= 3, "In-flight capacity must include callback acknowledgement");
      assertTrue(task.cancel());
      var sum = task.completion().get(3, TimeUnit.SECONDS);
      assertTrue(sum.progress().cancelled() > 0);
      assertEquals(0, sum.progress().completed());
    }
  }

  @Test
  void consumerFailurePoliciesAndYClipping() throws Exception {
    try (var e = new NoiseGeneratorEngine();
        var w =
            e.openWorld(
                new CompiledProject(new ProjectData()),
                new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var request =
          RegionRequest.of(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(3, 1, 1)));
      var task =
          w.streamRegion(
              request,
              c -> CompletableFuture.failedFuture(new IllegalArgumentException("consumer boom")));
      var sum = task.completion().get(5, TimeUnit.SECONDS);
      assertEquals(2, sum.progress().failed());
      assertEquals("DELIVERY", sum.failures().getFirst().phase());
      var outside =
          w.streamRegion(
                  RegionRequest.of(
                      new RegionBounds(new BlockPos(0, 500, 0), new BlockPos(1, 510, 1))),
                  c -> {
                    fail("No deliveries outside Y");
                    return null;
                  })
              .completion()
              .get();
      assertTrue(outside.effective().isEmpty());
    }
  }

  @Test
  void transactionalLoadUndoAndAtomicSave() throws Exception {
    var session = new DocumentSession();
    session.setValidator(NoiseCompiler::validate);
    Path save = temp.resolve("p.json");
    assertFalse(session.dirty());
    assertTrue(
        session
            .edit(
                p -> {
                  var n = new Noise();
                  n.id = "test";
                  p.noise2D.add(n);
                })
            .isEmpty());
    assertTrue(session.dirty());
    session.save(save);
    byte[] good = Files.readAllBytes(save);
    assertFalse(session.dirty());
    session.edit(p -> p.project.seed = 42);
    assertTrue(session.dirty());
    session.undo();
    assertFalse(session.dirty());
    session.redo();
    assertTrue(session.dirty());
    Path bad = temp.resolve("bad.json");
    Files.writeString(bad, "{\"formatVersion\":999}");
    assertFalse(session.open(bad).isEmpty());
    assertEquals(42, session.data().project.seed);
    assertTrue(session.dirty());
    assertArrayEquals(good, Files.readAllBytes(save));
    var result = session.edit(p -> p.project.minY = 9999);
    assertFalse(result.isEmpty());
    assertEquals(-64, session.data().project.minY);
  }

  @Test
  void rejectsCyclesBadBindingsAndAllowsDisabledDraft() {
    var p = fixture();
    var n = p.noise2D.getFirst();
    var i = new Item();
    i.id = "draft";
    i.kind = "EXPRESSION";
    i.enabled = false;
    i.parameters.put("expression", ProjectIO.JSON.valueToTree("incomplete +"));
    n.processing.add(i);
    assertTrue(NoiseCompiler.validate(p).isEmpty());
    i.enabled = true;
    assertFalse(NoiseCompiler.validate(p).isEmpty());
    i.enabled = false;
    var ref = new Binding();
    ref.sourceKind = "Noise Generator Reference";
    ref.noiseGeneratorUuid = n.uuid;
    i.inputs.put("self", ref);
    assertTrue(NoiseCompiler.validate(p).stream().anyMatch(d -> d.code().equals("NOISE_CYCLE")));
  }

  @Test
  void previousStatePredicateMatrixAndSeparateNestedFieldGates() {
    for (String kind :
        List.of("ANY", "EMPTY", "OCCUPIED", "TYPE_IS", "TYPE_IS_NOT", "IN_SET", "NOT_IN_SET")) {
      var p = fixture();
      while (p.worldGenerator.layers.size() > 1) p.worldGenerator.layers.removeLast();
      var set = new Membership();
      set.id = "stone-set";
      set.voxelTypeUuids.add(p.voxelTypes.getFirst().uuid);
      p.voxelSets.add(set);
      var layer = new Layer();
      layer.id = "predicate";
      layer.defaults.action = "SET";
      layer.defaults.voxelTypeUuid = p.voxelTypes.get(1).uuid;
      layer.defaults.minY = -1;
      layer.defaults.maxY = 1;
      layer.defaults.voxelStatePredicate.kind = kind;
      if (kind.startsWith("TYPE_"))
        layer.defaults.voxelStatePredicate.reference = p.voxelTypes.getFirst().uuid;
      if (kind.endsWith("SET")) layer.defaults.voxelStatePredicate.reference = set.uuid;
      var op = new Operation();
      op.id = "test";
      op.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
      op.interpretation = "SELECTION";
      layer.operations.add(op);
      p.worldGenerator.layers.add(layer);
      try (var e = new NoiseGeneratorEngine();
          var w = e.openWorld(new CompiledProject(p))) {
        boolean occupiedMatch = Set.of("ANY", "OCCUPIED", "TYPE_IS", "IN_SET").contains(kind),
            emptyMatch = Set.of("ANY", "EMPTY").contains(kind);
        assertEquals(occupiedMatch ? 2 : 1, w.trace(new BlockPos(0, -1, 0)).finalType(), kind);
        assertEquals(emptyMatch ? 2 : 0, w.trace(new BlockPos(0, 1, 0)).finalType(), kind);
      }
    }
    var p = fixture();
    var layer = p.worldGenerator.layers.getFirst();
    layer.defaults.voxelStatePredicate.kind = "AND";
    var empty = new ProjectData.Predicate();
    empty.kind = "EMPTY";
    var any = new ProjectData.Predicate();
    any.kind = "ANY";
    layer.defaults.voxelStatePredicate.children.addAll(List.of(empty, any));
    layer.defaults.fieldGates.kind = "OR";
    var reject = new Gate();
    reject.kind = "COMPARE";
    reject.threshold = 1;
    var accept = new Gate();
    accept.kind = "COMPARE";
    accept.threshold = -1;
    layer.defaults.fieldGates.children.addAll(List.of(reject, accept));
    assertTrue(NoiseCompiler.validate(p).isEmpty());
    try (var e = new NoiseGeneratorEngine();
        var w = e.openWorld(new CompiledProject(p))) {
      assertEquals(1, w.trace(new BlockPos(0, -1, 0)).finalType());
    }
  }

  @Test
  void generationFailureContinueAndFailFastAreNotCoordinatorFailures() throws Exception {
    var p = fixture();
    var n = p.noise2D.getFirst();
    var expr = ProcessingRegistry.create("EXPRESSION", "failure");
    expr.parameters.put("expression", ProjectIO.JSON.valueToTree("x == 0 ? 1/0 : 0"));
    n.processing.add(expr);
    n.outputBinding = Binding.earlier(expr.uuid);
    try (var e = new NoiseGeneratorEngine(2, 16, 128L << 20);
        var w =
            e.openWorld(
                new CompiledProject(p),
                new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var r = new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(3, 1, 1));
      var summary =
          w.streamRegion(RegionRequest.of(r), c -> CompletableFuture.completedFuture(null))
              .completion()
              .get(5, TimeUnit.SECONDS);
      assertEquals(1, summary.progress().failed());
      assertEquals(1, summary.progress().completed());
      assertEquals("GENERATION", summary.failures().getFirst().phase());
      var failfast =
          w.streamRegion(
                  new RegionRequest(
                      r,
                      RegionRequest.FailurePolicy.FAIL_FAST,
                      RegionRequest.DeliveryOrder.COORDINATE,
                      1),
                  c -> CompletableFuture.completedFuture(null))
              .completion()
              .get(5, TimeUnit.SECONDS);
      assertEquals(1, failfast.progress().failed());
      assertEquals(1, failfast.progress().cancelled());
    }
  }

  @Test
  void actualChunksAgreeAcrossEveryRootSize() {
    var p = fixture();
    try (var e = new NoiseGeneratorEngine(1, 16, 512L << 20)) {
      for (int size : new int[] {2, 4, 8, 16, 32, 64, 128, 256})
        try (var w =
            e.openWorld(
                new CompiledProject(p),
                new WorldOptions(null, size, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          var c = w.generateChunk(new ChunkPos(-1, 0, -1));
          assertEquals(2, c.getVoxelTypeId(size - 1, 0, size - 1), "Root " + size);
          assertEquals(0, c.getVoxelTypeId(0, 1, 0));
          assertTrue(
              c.estimatedBytes() <= 32L * size * size + 1024,
              "A plane should require O(size squared) storage");
        }
    }
  }

  @Test
  void strictProjectShapeRejectsMissingIdsCoercionsAndUnknownFields() throws Exception {
    var p = fixture();
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) ProjectIO.JSON.valueToTree(p);
    var n = (com.fasterxml.jackson.databind.node.ObjectNode) node.path("noise2D").get(0);
    n.remove("uuid");
    assertFalse(new ProjectIO().parse(node.toString()).success());
    node = ProjectIO.JSON.valueToTree(p);
    ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("project")).put("seed", "42");
    assertFalse(new ProjectIO().parse(node.toString()).success());
    node = ProjectIO.JSON.valueToTree(p);
    node.put("unexpected", true);
    assertFalse(new ProjectIO().parse(node.toString()).success());
    node = ProjectIO.JSON.valueToTree(p);
    ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("project"))
        .put("uuid", "1-1-1-1-1");
    assertFalse(new ProjectIO().parse(node.toString()).success());
  }

  @Test
  void cancelledConsumerAcknowledgementIsADeliveryFailure() throws Exception {
    try (var engine = new NoiseGeneratorEngine();
        var world =
            engine.openWorld(
                new CompiledProject(new ProjectData()),
                new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var request =
          RegionRequest.of(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(3, 1, 1)));
      var summary =
          world
              .streamRegion(
                  request,
                  c -> {
                    var ack = new CompletableFuture<Void>();
                    ack.cancel(false);
                    return ack;
                  })
              .completion()
              .get(5, TimeUnit.SECONDS);
      assertEquals(2, summary.progress().failed());
      assertEquals(0, summary.progress().cancelled());
      assertEquals("DELIVERY", summary.failures().getFirst().phase());
    }
  }

  @Test
  void nonfiniteOperationArithmeticFailsBeforePublishingAChunk() {
    var p = fixture();
    p.noise2D.getFirst().algorithm.parameters.put("value", Double.MAX_VALUE);
    p.worldGenerator.layers.getFirst().defaults.heightScale = 2;
    try (var engine = new NoiseGeneratorEngine();
        var world =
            engine.openWorld(
                new CompiledProject(p),
                new WorldOptions(
                    null, 2, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, 1 << 20))) {
      assertThrows(EvaluationFailure.class, () -> world.generateChunk(new ChunkPos(0, 0, 0)));
      assertEquals(0, world.cacheStats().chunks());
      assertEquals(0, engine.metrics().stagingBytes());
    }
  }
}
