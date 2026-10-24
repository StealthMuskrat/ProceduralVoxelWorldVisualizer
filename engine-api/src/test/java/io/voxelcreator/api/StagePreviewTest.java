package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

public class StagePreviewTest {
  private record Fixture(
      ProjectData project,
      Layer layer,
      Operation fill,
      Operation clear,
      Operation replace,
      VoxelType a,
      VoxelType b) {}

  private static Fixture fixture() {
    var p = new ProjectData();
    var a = new VoxelType();
    a.id = "a";
    var b = new VoxelType();
    b.id = "b";
    p.voxelTypes.addAll(List.of(a, b));
    var constant = new Noise();
    constant.id = "constant";
    constant.algorithm.key = "CONSTANT";
    constant.algorithm.parameters.put("value", 1.);
    p.noise2D.add(constant);
    var x = new Noise();
    x.id = "x";
    var expression = ProcessingRegistry.create("EXPRESSION", "x");
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x"));
    x.processing.add(expression);
    x.outputBinding = Binding.earlier(expression.uuid);
    p.noise2D.add(x);
    var layer = new Layer();
    layer.id = "pipeline";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = a.uuid;
    p.worldGenerator.layers.add(layer);
    var fill = new Operation();
    fill.id = "fill";
    fill.interpretation = "SELECTION";
    fill.noiseGeneratorUuid = constant.uuid;
    var clear = new Operation();
    clear.id = "clear";
    clear.interpretation = "SELECTION";
    clear.noiseGeneratorUuid = x.uuid;
    clear.overrides.put("action", ProjectIO.JSON.valueToTree("CLEAR"));
    var replace = new Operation();
    replace.id = "replace";
    replace.interpretation = "SELECTION";
    replace.noiseGeneratorUuid = constant.uuid;
    replace.overrides.put("voxelTypeUuid", ProjectIO.JSON.valueToTree(b.uuid));
    var predicate = new ProjectData.Predicate();
    predicate.kind = "EMPTY";
    replace.overrides.put("voxelStatePredicate", ProjectIO.JSON.valueToTree(predicate));
    layer.operations.addAll(List.of(fill, clear, replace));
    return new Fixture(p, layer, fill, clear, replace, a, b);
  }

  @Test
  void beforeAfterAndFinalFollowExactCommittedPipeline() {
    var f = fixture();
    try (var engine = new NoiseGeneratorEngine(2, 8, 64L << 20);
        var world =
            engine.openWorld(
                new CompiledProject(f.project),
                new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var clear = world.generateStageChunk(new ChunkPos(0, 0, 0), f.clear.uuid);
      assertEquals(1, clear.before().getVoxelTypeId(0, 0, 0));
      assertEquals(0, clear.after().getVoxelTypeId(0, 0, 0));
      assertTrue(clear.changed(0, 0, 0));
      assertEquals(2, world.getVoxelTypeId(new BlockPos(0, 0, 0)));
      var negative = world.generateStageChunk(new ChunkPos(-1, 0, 0), f.clear.uuid);
      assertFalse(negative.changed(1, 0, 0));
      assertEquals(1, negative.after().getVoxelTypeId(1, 0, 0));
      var layer = world.generateStageChunk(new ChunkPos(0, 0, 0), f.layer.uuid);
      assertEquals(0, layer.before().getVoxelTypeId(0, 0, 0));
      assertEquals(2, layer.after().getVoxelTypeId(0, 0, 0));
    }
  }

  @Test
  void disabledAndEmptyStagesHaveEqualBoundaries() {
    var f = fixture();
    var disabled = new Layer();
    disabled.id = "disabled";
    disabled.enabled = false;
    var op = new Operation();
    op.id = "disabled-op";
    op.noiseGeneratorUuid = f.fill.noiseGeneratorUuid;
    disabled.operations.add(op);
    f.project.worldGenerator.layers.add(disabled);
    var empty = new Layer();
    empty.id = "empty";
    f.project.worldGenerator.layers.add(empty);
    try (var engine = new NoiseGeneratorEngine(1, 4, 32L << 20);
        var world =
            engine.openWorld(
                new CompiledProject(f.project),
                new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var stage =
          world.stages().stream()
              .filter(s -> s.uuid().equals(disabled.uuid))
              .findFirst()
              .orElseThrow();
      assertFalse(stage.enabled());
      assertEquals(3, stage.before());
      assertEquals(stage.before(), stage.after());
      var chunks = world.generateStageChunk(new ChunkPos(0, 0, 0), empty.uuid);
      assertFalse(chunks.changed(0, 0, 0));
      assertEquals(2, chunks.after().getVoxelTypeId(0, 0, 0));
    }
  }

  @Test
  void stageSnapshotsSurviveClosureAndCancellationNeverPublishesPartialPairs() {
    var f = fixture();
    StageChunk saved;
    try (var engine = new NoiseGeneratorEngine(1, 4, 32L << 20);
        var world =
            engine.openWorld(
                new CompiledProject(f.project),
                new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var cancelled = new CancellationToken();
      cancelled.cancel();
      assertThrows(
          CancellationException.class,
          () -> world.generateStageChunk(new ChunkPos(0, 0, 0), f.clear.uuid, cancelled));
      assertThrows(
          IllegalArgumentException.class,
          () -> world.generateStageChunk(new ChunkPos(0, 0, 0), UUID.randomUUID()));
      saved = world.generateStageChunk(new ChunkPos(0, 0, 0), f.clear.uuid);
      assertEquals(0, engine.metrics().stagingBytes());
    }
    assertEquals(1, saved.before().getVoxelTypeId(0, 0, 0));
    assertEquals(0, saved.after().getVoxelTypeId(0, 0, 0));
  }

  @Test
  void traceContainsEffectiveValuesAndOnlyExecutedGateResults() {
    var f = fixture();
    var gate = new Gate();
    gate.kind = "AND";
    var reject = new Gate();
    reject.kind = "INTERVAL";
    reject.threshold = 2;
    reject.max = 3;
    gate.children.add(reject);
    gate.children.add(new Gate());
    f.clear.overrides.put("fieldGates", ProjectIO.JSON.valueToTree(gate));
    try (var engine = new NoiseGeneratorEngine(1, 4, 32L << 20);
        var world = engine.openWorld(new CompiledProject(f.project))) {
      var trace = world.trace(new BlockPos(0, 0, 0));
      assertEquals(1, trace.finalType());
      var details = trace.steps().get(1).details();
      assertFalse(details.gatePassed());
      assertNull(details.predicatePassed());
      assertFalse(details.selected());
      assertEquals(2, details.gates().size());
      assertTrue(details.effectiveSettings().get("voxelTypeUuid").contains(f.a.uuid.toString()));
      assertEquals(1, details.probability());
      assertTrue(details.random() >= 0 && details.random() < 1);
      assertEquals("NO_CHANGE", trace.steps().get(1).action());
    }
  }
}
