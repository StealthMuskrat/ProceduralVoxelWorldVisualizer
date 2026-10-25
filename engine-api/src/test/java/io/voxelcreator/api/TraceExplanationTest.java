package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TraceExplanationTest {
  private final ProjectData p = new ProjectData();
  private Noise noise;
  private Layer layer;
  private Operation op;

  private void fixture() {
    p.project.minY = -8;
    p.project.maxY = 8;
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    noise = new Noise();
    noise.id = "field";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.put("value", .75);
    p.noise2D.add(noise);
    layer = new Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    layer.defaults.threshold = .5;
    op = new Operation();
    op.id = "operation";
    op.noiseGeneratorUuid = noise.uuid;
    op.interpretation = "SELECTION";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
  }

  private GenerationTrace trace(int y) {
    try (var engine = new NoiseGeneratorEngine(2, 64, 128L << 20);
        var world = engine.openWorld(new CompiledProject(p))) {
      return world.trace(new BlockPos(2, y, 3));
    }
  }

  @Test
  void exactComparisonAndLiveProvenanceMatchTheSelectedWorld() {
    fixture();
    var step = trace(0).steps().getFirst();
    assertEquals(1, step.result());
    var c = step.details().comparisons().getLast();
    assertEquals(.75, c.left());
    assertEquals(.5, c.right());
    assertEquals(">=", c.operator());
    assertTrue(c.passed());
    var origin = step.details().settingTargets().get("threshold");
    assertEquals(layer.uuid, origin.resourceUuid());
    assertFalse(origin.override());
    assertEquals(.75, step.details().primaryField().value());
  }

  @Test
  void explicitZeroOverrideRetainsTheOperationOrigin() {
    fixture();
    op.overrides.put("threshold", ProjectIO.JSON.valueToTree(0));
    layer.defaults.threshold = 99;
    var step = trace(0).steps().getFirst();
    assertEquals(1, step.result());
    assertEquals(op.uuid, step.details().settingTargets().get("threshold").resourceUuid());
    assertTrue(step.details().settingTargets().get("threshold").override());
    assertEquals(0, step.details().comparisons().getLast().right());
  }

  @Test
  void interpretedRejectionShowsActualValuesAndUnreachedGateAndPredicate() {
    fixture();
    op.overrides.put("threshold", ProjectIO.JSON.valueToTree(1));
    var d = trace(0).steps().getFirst().details();
    assertFalse(d.comparisons().getLast().passed());
    assertNull(d.gatePassed());
    assertNull(d.predicatePassed());
    assertTrue(d.gates().isEmpty());
  }

  @Test
  void effectiveYBoundsExplainRejectionWithoutSampling() {
    fixture();
    layer.defaults.minY = 2;
    var step = trace(0).steps().getFirst();
    assertTrue(Double.isNaN(step.field()));
    assertNull(step.details().primaryField());
    assertEquals(2, step.details().comparisons().size());
    assertFalse(step.details().comparisons().getFirst().passed());
    assertTrue(trace(9).steps().isEmpty());
  }

  @Test
  void mappedThresholdComparisonUsesTheResolvedDestination() {
    fixture();
    var d = new NumericDriver();
    d.enabled = true;
    d.destination = "threshold";
    d.combination = "REPLACE";
    d.outputMin = 1;
    d.outputMax = 1;
    layer.defaults.parameterDrivers.add(d);
    var details = trace(0).steps().getFirst().details();
    assertEquals(1, details.comparisons().getLast().right());
    assertFalse(details.comparisons().getLast().passed());
    assertEquals(1, details.parameters().size());
  }

  @Test
  void heightComparisonsUseRoundedMappedHeight() {
    fixture();
    op.interpretation = "HEIGHT_BELOW";
    layer.defaults.baseHeight = 1;
    layer.defaults.heightScale = 2;
    layer.defaults.rounding = "FLOOR";
    var c = trace(3).steps().getFirst().details().comparisons().getLast();
    assertEquals("baseHeight", c.property());
    assertEquals(3, c.left());
    assertEquals(2, c.right());
    assertEquals("<=", c.operator());
    assertFalse(c.passed());
  }

  @Test
  void explanationDoesNotPopulateAWorldCacheOrChangeActualVoxelValues() {
    fixture();
    p.project.seed = 17;
    layer.defaults.seedOffset = 37;
    noise.algorithm.key = "WHITE_HASH";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("frequency", 1.);
    double expected =
        new NoiseCompiler(p, CoordinateHash.mix(17 ^ 37)).compile(noise.uuid).sample(2, 0, 3);
    try (var engine = new NoiseGeneratorEngine(2, 64, 128L << 20);
        var world = engine.openWorld(new CompiledProject(p))) {
      var trace = world.trace(new BlockPos(2, 0, 3));
      assertEquals(expected, trace.steps().getFirst().field());
      assertEquals(trace.finalType(), world.getVoxelTypeId(new BlockPos(2, 0, 3)));
      assertEquals(trace.finalType(), world.sampleStage(new BlockPos(2, 0, 3), Integer.MAX_VALUE));
    }
  }

  @Test
  void primaryRetentionBudgetIsSharedAndDoesNotTruncateComputation() {
    fixture();
    var e = ProcessingRegistry.create("EXPRESSION", "coordinate", 2);
    e.parameters.put("expression", ProjectIO.JSON.valueToTree("x*x+z*z"));
    noise.processing.add(e);
    var a = ProcessingRegistry.create("MULTIPLY", "double", 2);
    a.inputs.put("a", Binding.earlier(e.uuid));
    a.inputs.put("b", Binding.constant(2));
    noise.processing.add(a);
    var filter = ProcessingRegistry.create("MEDIAN_FILTER", "median", 2);
    filter.inputs.put("field", Binding.earlierField(a.uuid));
    filter.parameters.put("radius", ProjectIO.JSON.valueToTree(4));
    noise.processing.add(filter);
    noise.outputBinding = Binding.earlier(filter.uuid);
    // Several identical canonical operations exercise one world-wide observation allowance.
    for (int i = 0; i < 8; i++) {
      var other = ResourceTransactions.duplicate(op, Operation.class);
      other.id = "operation" + i;
      layer.operations.add(other);
    }
    var trace = trace(0);
    int count =
        trace.steps().stream().mapToInt(s -> s.details().primaryField().events().size()).sum();
    assertEquals(1024, count);
    assertTrue(trace.steps().getLast().details().primaryField().omitted() > 0);
    assertEquals(1, trace.finalType());
    assertEquals(trace.steps().getFirst().field(), trace.steps().getLast().field());
  }

  @Test
  void outerCollectionsAndHistoricalConstructorsAreDefensivelyOwned() {
    var sources = new HashMap<String, String>();
    sources.put("threshold", "Layer default");
    var step =
        new GenerationTrace.Step(
            null, null, null, "l", "o", 0, 0, 0, "NO_CHANGE", "reason", sources);
    var list = new ArrayList<>(List.of(step));
    var trace = new GenerationTrace(new BlockPos(0, 0, 0), 0, list);
    sources.clear();
    list.clear();
    assertEquals(1, trace.steps().size());
    assertEquals("Layer default", step.settingsSources().get("threshold"));
    var d = new GenerationTrace.Details(Map.of(), List.of(), null, null, 1, .2, false);
    assertTrue(d.settingTargets().isEmpty());
    assertTrue(d.comparisons().isEmpty());
    assertNull(d.primaryField());
  }

  @Test
  void allNewProvenanceCollectionsRejectMutation() {
    fixture();
    var d = trace(0).steps().getFirst().details();
    assertThrows(UnsupportedOperationException.class, () -> d.settingTargets().clear());
    assertThrows(UnsupportedOperationException.class, () -> d.comparisons().clear());
    assertThrows(UnsupportedOperationException.class, () -> d.primaryField().events().clear());
  }
}
