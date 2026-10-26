package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ScalarRemappingTest {
  final ProjectData project = new ProjectData();
  final Noise noise = new Noise();

  ScalarRemappingTest() {
    noise.id = "remapping";
    noise.algorithm.key = "CONSTANT";
    project.noise2D.add(noise);
  }

  Item item(String kind) {
    var i = ProcessingRegistry.create(kind, "item" + noise.processing.size(), noise.dimension);
    noise.processing.add(i);
    noise.outputBinding = Binding.earlier(i.uuid);
    return i;
  }

  void set(Item i, String key, Object value) {
    i.parameters.put(key, ProjectIO.JSON.valueToTree(value));
  }

  NoiseAlgorithms.Field field() {
    return new NoiseCompiler(project, 0).compile(noise.uuid);
  }

  double value() {
    return field().sample(0, 0, 0);
  }

  Item expression(String text) {
    var i = item("EXPRESSION");
    set(i, "expression", text);
    return i;
  }

  @Test
  void independentPythonVectorsPassThroughTheActualCompiler() throws Exception {
    var path = Path.of("../fixtures/remapping-vectors.json");
    var vectors = ProjectIO.JSON.readTree(path.toFile()).path("vectors");
    assertEquals(548, vectors.size());
    for (var v : vectors) {
      var p = new ProjectData();
      var n = new Noise();
      n.id = "vector";
      n.dimension = v.path("dimension").intValue();
      n.algorithm.key = "CONSTANT";
      (n.dimension == 2 ? p.noise2D : p.noise3D).add(n);
      var i = ProcessingRegistry.create(v.path("kind").textValue(), "operation", n.dimension);
      v.path("parameters")
          .fields()
          .forEachRemaining(e -> i.parameters.put(e.getKey(), e.getValue()));
      i.inputs.put("a", Binding.constant(v.path("a").doubleValue()));
      if (i.kind.equals("BLEND")) i.inputs.put("b", Binding.constant(v.path("b").doubleValue()));
      n.processing.add(i);
      n.outputBinding = Binding.earlier(i.uuid);
      double actual =
          new NoiseCompiler(p, 0)
              .compile(n.uuid)
              .sample(
                  v.path("x").doubleValue(), v.path("y").doubleValue(), v.path("z").doubleValue());
      assertEquals(v.path("expected").doubleValue(), actual, 1e-10, v.toString());
    }
  }

  @Test
  void legacyFalloffDefaultRetainsRadialSignedMath() {
    var i = item("FALLOFF");
    i.inputs.put("a", Binding.constant(-4));
    set(i, "extent", 10);
    assertEquals(-2, field().sample(3, 100, 4));
  }

  @Test
  void falloffUsesTransformedCoordinatesOnce() {
    var t = new Item();
    t.id = "move";
    t.kind = "TRANSLATE";
    t.resultType = "Coordinates2D";
    set(t, "x", 3);
    noise.coordinateTransforms.add(t);
    var i = item("FALLOFF");
    i.inputs.put("a", Binding.constant(10));
    set(i, "extent", 10);
    assertEquals(5, field().sample(0, 0, 4));
  }

  @Test
  void falloffPositionIsExplicitAndTyped() {
    var i = item("FALLOFF");
    i.inputs.put("a", Binding.constant(10));
    set(i, "extent", 10);
    var b = Binding.constant(0);
    b.type = "Coordinates2D";
    b.value = ProjectIO.JSON.valueToTree(new double[] {3, 4});
    i.inputs.put("position", b);
    assertEquals(5, field().sample(999, 0, 999));
    b.type = "Vector2";
    assertThrows(CompilationFailure.class, this::field);
  }

  @Test
  void inputDomainIgnoresSamplePositionAndPreservesSignedOutput() {
    var i = item("FALLOFF");
    set(i, "domain", "INPUT");
    set(i, "centerX", -1);
    set(i, "extent", 4);
    i.inputs.put("a", Binding.constant(-3));
    assertEquals(-1.5, field().sample(1000, 0, -1000));
  }

  @Test
  void coordinateMetricChangesWithoutHiddenNormalization() {
    var i = item("FALLOFF");
    i.inputs.put("a", Binding.constant(8));
    set(i, "extent", 8);
    set(i, "metric", "CHEBYSHEV");
    assertEquals(4, field().sample(3, 0, 4));
    set(i, "metric", "MANHATTAN");
    assertEquals(1, field().sample(3, 0, 4));
    set(i, "metric", "AXIS");
    set(i, "axis", "z");
    assertEquals(4, field().sample(99, 0, 4));
  }

  @Test
  void threeDimensionalFalloffUsesY() {
    project.noise2D.clear();
    noise.dimension = 3;
    project.noise3D.add(noise);
    var i = item("FALLOFF");
    i.inputs.put("a", Binding.constant(8));
    set(i, "extent", 8);
    assertEquals(4, field().sample(0, 4, 0));
  }

  @Test
  void explicitExtensionCanCrossZeroWithoutClamping() {
    var i = item("FALLOFF");
    i.inputs.put("a", Binding.constant(4));
    set(i, "extent", 8);
    assertEquals(0, field().sample(16, 0, 0));
    set(i, "outside", "EXTEND");
    assertEquals(-4, field().sample(16, 0, 0));
  }

  @Test
  void weightedEndpointsSkipUnusedDivisionFailure() {
    var bad = expression("1/0");
    var i = item("BLEND");
    i.inputs.put("a", Binding.constant(7));
    i.inputs.put("b", Binding.earlier(bad.uuid));
    set(i, "weight", 0);
    assertEquals(7, value());
    set(i, "weight", .5);
    assertThrows(EvaluationFailure.class, this::value);
    set(i, "weight", 1);
    i.inputs.put("a", Binding.earlier(bad.uuid));
    i.inputs.put("b", Binding.constant(-3));
    assertEquals(-3, value());
  }

  @Test
  void clampedWeightSkipsUnusedBranchButRawWeightExtrapolates() {
    var i = item("BLEND");
    i.inputs.put("a", Binding.constant(-2));
    i.inputs.put("b", Binding.constant(4));
    set(i, "weight", 2);
    assertEquals(10, value());
    set(i, "weightPolicy", "CLAMP");
    assertEquals(4, value());
    set(i, "outputPolicy", "CLAMP_01");
    assertEquals(1, value());
  }

  @Test
  void weightBindingOverridesNumericValueAndSamplesReferencedGenerator() {
    var other = new Noise();
    other.id = "weight";
    other.algorithm.key = "CONSTANT";
    other.algorithm.parameters.put("value", .25);
    project.noise2D.add(other);
    var i = item("BLEND");
    i.inputs.put("a", Binding.constant(2));
    i.inputs.put("b", Binding.constant(10));
    var b = Binding.constant(0);
    b.sourceKind = "Noise Generator Reference";
    b.value = null;
    b.noiseGeneratorUuid = other.uuid;
    i.inputs.put("weight", b);
    set(i, "weight", 999);
    assertEquals(4, value());
  }

  @Test
  void invalidEnumAndTypedControlsHavePreciseDiagnostics() {
    var i = item("FALLOFF");
    for (var entry :
        Map.of("metric", "ROUND", "domain", "WORLD", "shape", "CUBIC", "outside", "WRAP")
            .entrySet()) {
      i.parameters.clear();
      set(i, entry.getKey(), entry.getValue());
      assertEquals(entry.getKey(), NoiseCompiler.validate(project).getFirst().parameterKey());
    }
  }

  @Test
  void invalidNumericRangesHavePreciseDiagnostics() {
    var i = item("TERRACE");
    set(i, "levels", 1.5);
    assertEquals("levels", NoiseCompiler.validate(project).getFirst().parameterKey());
    i.parameters.clear();
    set(i, "transition", 2);
    assertEquals("transition", NoiseCompiler.validate(project).getFirst().parameterKey());
    i.kind = "FALLOFF";
    i.parameters.clear();
    set(i, "extent", 0);
    assertEquals("extent", NoiseCompiler.validate(project).getFirst().parameterKey());
  }

  @Test
  void invalidClampAndRemapEndpointsRejectAtCompilation() {
    var i = item("CLAMP");
    set(i, "min", 2);
    set(i, "max", 1);
    assertEquals("max", NoiseCompiler.validate(project).getFirst().parameterKey());
    i.kind = "REMAP";
    i.parameters.clear();
    set(i, "inputMin", 1);
    assertEquals("inputMax", NoiseCompiler.validate(project).getFirst().parameterKey());
    set(i, "inputMin", 0);
    set(i, "clamp", 1);
    assertEquals("clamp", NoiseCompiler.validate(project).getFirst().parameterKey());
  }

  @Test
  void twoDimensionalAxisYRejectsAndHiddenControlsRemainOwned() {
    var i = item("FALLOFF");
    set(i, "axis", "y");
    assertEquals("axis", NoiseCompiler.validate(project).getFirst().parameterKey());
    set(i, "axis", "z");
    set(i, "centerY", 100);
    assertEquals(0, value());
    assertEquals(100, i.parameters.get("centerY").intValue());
  }

  @Test
  void divisionAndIntermediateOverflowFailAtExecutingItem() {
    var i = item("BLEND");
    set(i, "mode", "DIVIDE");
    set(i, "weight", 1);
    i.inputs.put("a", Binding.constant(1));
    i.inputs.put("b", Binding.constant(0));
    assertThrows(EvaluationFailure.class, this::value);
    set(i, "mode", "ADD");
    i.inputs.put("a", Binding.constant(1e308));
    i.inputs.put("b", Binding.constant(1e308));
    assertThrows(EvaluationFailure.class, this::value);
  }

  @Test
  void disabledInvalidControlsAreRetainedWithoutExecution() {
    var i = item("FALLOFF");
    i.enabled = false;
    set(i, "extent", -1);
    noise.outputBinding = Binding.source();
    assertEquals(0, value());
    assertEquals(-1, i.parameters.get("extent").intValue());
  }

  @Test
  void supportedInputNamesAndTypesRejectAccidentalBindings() {
    var i = item("BLEND");
    i.inputs.put("position", Binding.constant(1));
    assertThrows(CompilationFailure.class, this::field);
    i.inputs.remove("position");
    var b = Binding.constant(0);
    b.type = "Boolean";
    b.value = ProjectIO.JSON.valueToTree(false);
    i.inputs.put("weight", b);
    assertThrows(CompilationFailure.class, this::field);
  }

  @Test
  void compilationOwnsPlansAndParallelSamplesAreStable() throws Exception {
    var i = item("FALLOFF");
    i.inputs.put("a", Binding.constant(8));
    set(i, "extent", 8);
    var f = field();
    set(i, "extent", 1);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Future<?>>();
      for (int k = 0; k < 4; k++)
        tasks.add(
            pool.submit(
                () -> {
                  for (int x = -32; x <= 32; x++)
                    assertEquals(8 * (1 - Math.min(Math.abs(x) / 8., 1)), f.sample(x, 0, 0));
                }));
      for (var task : tasks) task.get();
    }
  }

  @Test
  void constantBlendFoldsButCoordinateFalloffDoesNot() {
    var i = item("BLEND");
    i.inputs.put("a", Binding.constant(2));
    i.inputs.put("b", Binding.constant(4));
    var compiler = new NoiseCompiler(project, 0);
    assertEquals(3, compiler.compile(noise.uuid).sample(0, 0, 0));
    assertEquals(1, compiler.statistics(noise.uuid).constantItems());
    var f = item("FALLOFF");
    f.inputs.put("a", Binding.earlier(i.uuid));
    compiler = new NoiseCompiler(project, 0);
    compiler.compile(noise.uuid);
    assertEquals(1, compiler.statistics(noise.uuid).constantItems());
  }

  @Test
  void terraceExactKnotsAndNegativeIntervalsHaveDocumentedTies() {
    var i = item("TERRACE");
    set(i, "levels", 4);
    for (double a : new double[] {-.25, 0, .25, 1, 2}) {
      i.inputs.put("a", Binding.constant(a));
      assertEquals(a, value());
    }
    i.inputs.put("a", Binding.constant(-.125));
    assertEquals(-.25, value());
    set(i, "transition", 1);
    assertEquals(-.125, value());
  }

  @Test
  void explicitOutputClampCannotMaskBlendOverflowAndFullWeightAvoidsUnusedDifference() {
    var i = item("BLEND");
    i.inputs.put("a", Binding.constant(1e308));
    i.inputs.put("b", Binding.constant(-1e308));
    set(i, "weight", .5);
    set(i, "outputPolicy", "CLAMP_01");
    assertThrows(EvaluationFailure.class, this::value);
    set(i, "weight", 1);
    assertEquals(0, value());
    set(i, "outputPolicy", "RAW");
    assertEquals(-1e308, value());
  }

  @Test
  void inputDomainDoesNotExecuteAnUnusedFailingPositionBinding() {
    var bad = expression("1/0");
    var position = item("COORDINATES2");
    position.inputs.put("x", Binding.earlier(bad.uuid));
    var i = item("FALLOFF");
    var coordinates = Binding.earlier(position.uuid);
    coordinates.type = "Coordinates2D";
    i.inputs.put("position", coordinates);
    i.inputs.put("a", Binding.constant(-3));
    set(i, "domain", "INPUT");
    set(i, "centerX", -1);
    set(i, "extent", 4);
    assertEquals(-1.5, field().sample(99, 0, 99));
    set(i, "domain", "COORDINATES");
    assertThrows(EvaluationFailure.class, this::value);
  }
}
