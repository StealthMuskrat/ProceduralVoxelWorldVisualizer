package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

public class FieldSamplingTest {
  private static Noise noise(ProjectData p, String id, int dimension) {
    var n = new Noise();
    n.id = id;
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    return n;
  }

  private static Item expression(Noise n, String text) {
    var i = ProcessingRegistry.create("EXPRESSION", "expression" + n.processing.size());
    i.parameters.put("expression", ProjectIO.JSON.valueToTree(text));
    n.processing.add(i);
    n.outputBinding = Binding.earlier(i.uuid);
    return i;
  }

  private static Item sample(Noise n, String kind, Item prefix) {
    var i = ProcessingRegistry.create(kind, kind.toLowerCase() + n.processing.size());
    if (prefix != null) i.inputs.put("field", Binding.earlierField(prefix.uuid));
    n.processing.add(i);
    n.outputBinding = Binding.earlier(i.uuid);
    return i;
  }

  private static void param(Item i, String key, Object value) {
    i.parameters.put(key, ProjectIO.JSON.valueToTree(value));
  }

  private static NoiseAlgorithms.Field field(ProjectData p, Noise n) {
    var diagnostics = NoiseCompiler.validate(p);
    assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    return new NoiseCompiler(p, 17).compile(n.uuid);
  }

  @Test
  void prefixReappliesTransformsExactlyOnceAtRequestedLocation() {
    var p = new ProjectData();
    var n = noise(p, "prefix", 2);
    var t = new Item();
    t.id = "translate";
    t.kind = "TRANSLATE";
    t.resultType = "Coordinates2D";
    param(t, "x", 10);
    n.coordinateTransforms.add(t);
    var square = expression(n, "x*x");
    var offset = sample(n, "OFFSET_SAMPLE", square);
    param(offset, "x", 3);
    assertEquals(225, field(p, n).sample(2, 0, 9));
    var derivative = sample(n, "DERIVATIVE", square);
    param(derivative, "spacing", .25);
    assertEquals(24, field(p, n).sample(2, 0, 9));
    var supplied = Binding.constant(0);
    supplied.type = "Coordinates2D";
    supplied.value = ProjectIO.JSON.valueToTree(new double[] {4, 9});
    offset.inputs.put("coordinates", supplied);
    n.outputBinding = Binding.earlier(offset.uuid);
    assertEquals(289, field(p, n).sample(100, 0, 200));
  }

  @Test
  void filterUsesNeighboringFieldValuesWithNormalizedKernelAndStrength() {
    var p = new ProjectData();
    var n = noise(p, "filter", 2);
    var quadratic = expression(n, "x*x+z*z");
    var filter = sample(n, "SMOOTH_FILTER", quadratic);
    param(filter, "radius", 1);
    param(filter, "strength", .5);
    assertEquals(13 + 2. / 3, field(p, n).sample(2, 0, 3), 1e-12);
    param(filter, "kernel", "GAUSSIAN");
    param(filter, "strength", 1);
    double side = StrictMath.exp(-2);
    assertEquals(13 + 4 * side / (1 + 2 * side), field(p, n).sample(2, 0, 3), 1e-12);
    param(filter, "radius", 0);
    assertEquals(13, field(p, n).sample(2, 0, 3));
  }

  @Test
  void fractalAndWarpResampleTheSelectedDefinition() {
    var p = new ProjectData();
    var n = noise(p, "fractal", 2);
    var x = expression(n, "x");
    var fractal = sample(n, "FBM", x);
    param(fractal, "octaves", 3);
    param(fractal, "frequency", 1);
    param(fractal, "lacunarity", 2);
    param(fractal, "gain", .5);
    assertEquals(6, field(p, n).sample(2, 0, 3));
    param(fractal, "frequency", 3);
    assertEquals(18, field(p, n).sample(2, 0, 3));
    var warp = sample(n, "WARP_SAMPLE", x);
    param(warp, "amplitude", 0);
    assertEquals(2, field(p, n).sample(2, 0, 3));
  }

  @Test
  void dimensionalReferencesHaveExplicitProjectionAndSlice() {
    var p = new ProjectData();
    var target3 = noise(p, "target3", 3);
    expression(target3, "x+10*y+100*z");
    var caller2 = noise(p, "caller2", 2);
    var s2 = sample(caller2, "NOISE_REFERENCE", null);
    var binding = Binding.noiseField(target3.uuid);
    binding.slice = 7;
    s2.inputs.put("field", binding);
    for (var c : Map.of("XY", 732., "XZ", 372., "YZ", 327.).entrySet()) {
      binding.projection = c.getKey();
      assertEquals(c.getValue(), field(p, caller2).sample(2, 0, 3));
    }
    var target2 = noise(p, "target2", 2);
    expression(target2, "x+100*z");
    var caller3 = noise(p, "caller3", 3);
    var s3 = sample(caller3, "NOISE_REFERENCE", null);
    binding = Binding.noiseField(target2.uuid);
    s3.inputs.put("field", binding);
    for (var c : Map.of("XY", 302., "XZ", 702., "YZ", 703.).entrySet()) {
      binding.projection = c.getKey();
      assertEquals(c.getValue(), field(p, caller3).sample(2, 3, 7));
    }
  }

  @Test
  void prefixOrderTypesCyclesAndWorkAreValidatedBeforeSampling() {
    var p = new ProjectData();
    var n = noise(p, "invalid", 3);
    var first = sample(n, "OFFSET_SAMPLE", null);
    first.inputs.put("field", Binding.earlierField(first.uuid));
    assertFalse(NoiseCompiler.validate(p).isEmpty());
    first.inputs.put("field", Binding.sourceField());
    var vector = ProcessingRegistry.create("COMPOSE3", "vector");
    n.processing.add(0, vector);
    first.inputs.put("field", Binding.earlierField(vector.uuid));
    assertFalse(NoiseCompiler.validate(p).isEmpty());
    first.inputs.put("field", Binding.sourceField());
    var other = noise(p, "cycle", 3);
    var reference = sample(other, "NOISE_REFERENCE", null);
    reference.inputs.put("field", Binding.noiseField(n.uuid));
    first.inputs.put("field", Binding.noiseField(other.uuid));
    assertFalse(NoiseCompiler.validate(p).isEmpty());
    first.inputs.put("field", Binding.sourceField());
    Item prior = first;
    for (int i = 0; i < 3; i++) {
      prior = sample(n, "SMOOTH_FILTER", prior);
      param(prior, "radius", 4);
    }
    assertTrue(
        NoiseCompiler.validate(p).stream().anyMatch(d -> d.message().contains("COMPLEXITY_LIMIT")));
  }

  @Test
  void immutablePrefixScratchIsIndependentAcrossWorkers() throws Exception {
    var p = new ProjectData();
    var n = noise(p, "parallel", 2);
    var square = expression(n, "x*x+z*z");
    var derivative = sample(n, "DERIVATIVE", square);
    var f = field(p, n);
    param(square, "expression", "0");
    try (var executor = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<?>>();
      for (int w = 0; w < 4; w++) {
        final int worker = w;
        futures.add(
            executor.submit(
                () -> {
                  for (int x = -65 + worker; x <= 65; x += 4)
                    assertEquals(2. * x, f.sample(x, 0, 31));
                }));
      }
      for (var future : futures) future.get();
    }
  }

  @Test
  void sourceFieldAndLegacyReferenceRemainSelectable() {
    var p = new ProjectData();
    var source = noise(p, "constant", 2);
    source.algorithm.parameters.put("value", 3.);
    var n = noise(p, "legacy", 2);
    n.algorithm.parameters.put("value", 2.);
    var s = sample(n, "NOISE_REFERENCE", null);
    assertEquals(2, field(p, n).sample(0, 0, 0));
    s.inputs.remove("field");
    param(s, "noiseGeneratorUuid", source.uuid.toString());
    assertEquals(3, field(p, n).sample(0, 0, 0));
    n.outputBinding = Binding.sourceField();
    assertFalse(NoiseCompiler.validate(p).isEmpty());
  }
}
