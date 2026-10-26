package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class FieldOperationsTest {
  private final ProjectData project = new ProjectData();

  private Noise noise(int dimension) {
    var n = new Noise();
    n.id = "field" + dimension;
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    (dimension == 2 ? project.noise2D : project.noise3D).add(n);
    return n;
  }

  private Item add(Noise n, String key) {
    var item = ProcessingRegistry.create(key, "item" + n.processing.size(), n.dimension);
    n.processing.add(item);
    if (item.resultType.equals("Scalar")) n.outputBinding = Binding.earlier(item.uuid);
    return item;
  }

  private Item expression(Noise n, String expression) {
    var i = add(n, "EXPRESSION");
    set(i, "expression", expression);
    return i;
  }

  private Item sampled(Noise n, String kind, Item prefix) {
    var i = add(n, kind);
    i.inputs.put("field", Binding.earlierField(prefix.uuid));
    return i;
  }

  private void set(Item i, String key, Object value) {
    i.parameters.put(key, ProjectIO.JSON.valueToTree(value));
  }

  private NoiseAlgorithms.Field field(Noise n) {
    return new NoiseCompiler(project, 17).compile(n.uuid);
  }

  private void output(Noise n, Item item) {
    n.outputBinding = Binding.earlier(item.uuid);
  }

  private Item scalarComponent(Noise n, Item vector, int component) {
    var item = add(n, "COMPONENT");
    var b = Binding.earlier(vector.uuid);
    b.type = vector.resultType;
    item.inputs.put("a", b);
    set(item, "component", component);
    return item;
  }

  private Binding coordinates(int dimension, double... values) {
    var b = Binding.constant(0);
    b.type = "Coordinates" + dimension + "D";
    b.value = ProjectIO.JSON.valueToTree(values);
    return b;
  }

  @Test
  void gradient2DHasIndependentQuadraticComponentsAndSignedCoordinates() {
    var n = noise(2);
    var f = expression(n, "x*x+3*z*z");
    var g = sampled(n, "GRADIENT", f);
    set(g, "spacing", .125);
    var c = scalarComponent(n, g, 0);
    assertEquals(-14, field(n).sample(-7, 0, 11));
    set(c, "component", 1);
    assertEquals(66, field(n).sample(-7, 0, 11));
  }

  @Test
  void gradient3DProducesXyzAndExplicitLength() {
    var n = noise(3);
    var f = expression(n, "x*x+2*y*y+3*z*z");
    var g = sampled(n, "GRADIENT", f);
    var c = scalarComponent(n, g, 0);
    for (int k = 0; k < 3; k++) {
      set(c, "component", k);
      assertEquals(new double[] {4, 20, 42}[k], field(n).sample(2, 5, 7));
    }
    var length = add(n, "LENGTH");
    var b = Binding.earlier(g.uuid);
    b.type = "Vector3";
    length.inputs.put("a", b);
    assertEquals(StrictMath.sqrt(2180), field(n).sample(2, 5, 7), 1e-12);
  }

  @Test
  void gradientCannotBecomeScalarOutputOrSilentlyChangeDimension() {
    var n = noise(2);
    var g = sampled(n, "GRADIENT", expression(n, "x"));
    output(n, g);
    assertFalse(NoiseCompiler.validate(project).isEmpty());
    n.outputBinding = Binding.source();
    g.resultType = "Vector3";
    assertTrue(
        NoiseCompiler.validate(project).stream()
            .anyMatch(d -> d.message().contains("Gradient result")));
  }

  @Test
  void laplacianMatchesQuadraticAcrossNegativeAndChunkBoundaryCoordinates() {
    for (int d : new int[] {2, 3}) {
      var n = noise(d);
      sampled(n, "LAPLACIAN", expression(n, d == 2 ? "x*x+3*z*z" : "x*x+2*y*y+3*z*z"));
      var f = field(n);
      for (int x : new int[] {-65, -32, -1, 0, 31, 32, 65})
        assertEquals(d == 2 ? 8 : 12, f.sample(x, -9, 31));
    }
  }

  @Test
  void laplacianAnnihilatesAffineFieldsAndSpacingCancels() {
    var n = noise(3);
    var lap = sampled(n, "LAPLACIAN", expression(n, "2*x-3*y+7*z+9"));
    for (double h : new double[] {.125, .5, 1, 4}) {
      set(lap, "spacing", h);
      assertEquals(0, field(n).sample(-32, 7, 31));
    }
  }

  @Test
  void sampledCoordinatesAndPrefixTransformsApplyExactlyOnce() {
    var n = noise(2);
    var t = new Item();
    t.id = "translate";
    t.kind = "TRANSLATE";
    t.resultType = "Coordinates2D";
    set(t, "x", 10);
    n.coordinateTransforms.add(t);
    var g = sampled(n, "GRADIENT", expression(n, "x*x+3*z*z"));
    g.inputs.put("coordinates", coordinates(2, 4, 5));
    scalarComponent(n, g, 0);
    assertEquals(28, field(n).sample(100, 0, 200));
  }

  @Test
  void calculusCanSampleAReferencedFieldWithAnExplicitSlice() {
    var target = noise(3);
    expression(target, "x*x+2*y*y+3*z*z");
    var caller = noise(2);
    var lap = add(caller, "LAPLACIAN");
    var b = Binding.noiseField(target.uuid);
    b.slice = 7;
    lap.inputs.put("field", b);
    assertEquals(8, field(caller).sample(2, 0, 3));
  }

  @Test
  void minimumAndMaximumUseActualOddNeighborhoodAndBlend() {
    var n = noise(2);
    var f = expression(n, "x*x+3*z*z");
    var min = sampled(n, "MIN_FILTER", f);
    assertEquals(13, field(n).sample(2, 0, 3));
    var max = sampled(n, "MAX_FILTER", f);
    assertEquals(57, field(n).sample(2, 0, 3));
    set(max, "strength", .25);
    assertEquals(37.5, field(n).sample(2, 0, 3));
    set(min, "strength", .5);
    output(n, min);
    assertEquals(22, field(n).sample(2, 0, 3));
  }

  @Test
  void medianUsesMiddleSignedValueWithoutAveraging() {
    var n = noise(2);
    var m = sampled(n, "MEDIAN_FILTER", expression(n, "x*x+3*z*z"));
    // Sorted 3x3 values around (2,3):13,16,21,28,31,36,49,52,57.
    assertEquals(31, field(n).sample(2, 0, 3));
    set(m, "radius", 0);
    assertEquals(31, field(n).sample(2, 0, 3));
  }

  @Test
  void medianAndExtrema3DRespectActiveAxes() {
    var n = noise(3);
    var f = expression(n, "x+10*y+100*z");
    var median = sampled(n, "MEDIAN_FILTER", f);
    assertEquals(321, field(n).sample(1, 2, 3));
    sampled(n, "MIN_FILTER", f);
    assertEquals(210, field(n).sample(1, 2, 3));
    sampled(n, "MAX_FILTER", f);
    assertEquals(432, field(n).sample(1, 2, 3));
  }

  @Test
  void zeroStrengthAndRadiusAreLazyAboutDangerousNeighbors() {
    var n = noise(2);
    var m = sampled(n, "MEDIAN_FILTER", expression(n, "x == 0 && z == 0 ? 7 : 1/0"));
    set(m, "strength", 0);
    assertEquals(7, field(n).sample(0, 0, 0));
    set(m, "strength", 1);
    set(m, "radius", 0);
    assertEquals(7, field(n).sample(0, 0, 0));
    set(m, "radius", 1);
    assertThrows(EvaluationFailure.class, () -> field(n).sample(0, 0, 0));
  }

  @Test
  void constantExtremesAtFullStrengthDoNotOverflowAnUnusedBlendDifference() {
    var n = noise(2);
    var max = sampled(n, "MAX_FILTER", expression(n, "x == 0 ? -1e308 : 1e308"));
    assertEquals(1e308, field(n).sample(0, 0, 0));
    set(max, "strength", .5);
    assertThrows(EvaluationFailure.class, () -> field(n).sample(0, 0, 0));
  }

  @Test
  void invalidControlsCarryTheExactItemAndParameter() {
    var n = noise(2);
    var m = sampled(n, "MEDIAN_FILTER", expression(n, "x"));
    for (var v : List.of(-1, 1.5, 5)) {
      set(m, "radius", v);
      var ds = NoiseCompiler.validate(project);
      assertEquals(m.uuid, ds.getFirst().itemUuid());
      assertEquals("radius", ds.getFirst().parameterKey());
    }
    m.parameters.clear();
    set(m, "spacing", 0);
    assertEquals("spacing", NoiseCompiler.validate(project).getFirst().parameterKey());
  }

  @Test
  void derivativeDenominatorsRejectOverflowAndUnderflowBeforeSampling() {
    var n = noise(2);
    var lap = sampled(n, "LAPLACIAN", expression(n, "x"));
    for (double h : new double[] {Double.MIN_VALUE, Double.MAX_VALUE}) {
      set(lap, "spacing", h);
      assertThrows(CompilationFailure.class, () -> field(n));
    }
    lap.parameters.clear();
    lap.kind = "GRADIENT";
    lap.resultType = "Vector2";
    n.outputBinding = Binding.source();
    set(lap, "spacing", Double.MAX_VALUE);
    assertThrows(CompilationFailure.class, () -> field(n));
  }

  @Test
  void calculusRejectsUnknownNumericInputsAndMismatchedCoordinates() {
    var n = noise(2);
    var lap = sampled(n, "LAPLACIAN", expression(n, "x"));
    lap.inputs.put("coordinates", coordinates(3, 1, 2, 3));
    assertThrows(CompilationFailure.class, () -> field(n));
    lap.inputs.remove("coordinates");
    lap.inputs.put("ignored", Binding.constant(1));
    assertThrows(CompilationFailure.class, () -> field(n));
  }

  @Test
  void nestedNeighborhoodAdmissionIsBoundedBeforeExecution() {
    var n = noise(3);
    var f = expression(n, "x");
    for (int i = 0; i < 3; i++) {
      f = sampled(n, "MEDIAN_FILTER", f);
      set(f, "radius", 4);
    }
    assertTrue(
        NoiseCompiler.validate(project).stream()
            .anyMatch(d -> d.message().contains("COMPLEXITY_LIMIT")));
  }

  @Test
  void planeDefinesAxisOffsetAndNormalWithoutClamping() {
    var n = noise(2);
    var plane = add(n, "PLANE");
    set(plane, "axis", "z");
    set(plane, "offset", 3);
    assertEquals(-8, field(n).sample(99, 0, -5));
    set(plane, "direction", "NEGATIVE");
    assertEquals(8, field(n).sample(99, 0, -5));
    set(plane, "axis", "y");
    assertEquals("axis", NoiseCompiler.validate(project).getFirst().parameterKey());
  }

  @Test
  void capsuleUsesClosedSegmentAndEndpointSpheresIn2D() {
    var n = noise(2);
    var capsule = add(n, "CAPSULE");
    assertEquals(-4, field(n).sample(0, 0, 0));
    assertEquals(1, field(n).sample(19, 0, 4));
    assertEquals(1, field(n).sample(-19, 0, 4));
    set(capsule, "startY", 999);
    assertEquals(-4, field(n).sample(0, 77, 0));
  }

  @Test
  void coincidentCapsuleEndpointsAreASphere() {
    var n = noise(3);
    var capsule = add(n, "CAPSULE");
    set(capsule, "startX", 1);
    set(capsule, "endX", 1);
    set(capsule, "radius", 2);
    assertEquals(3, field(n).sample(1, 3, 4));
  }

  @Test
  void cylinderHasExactCapCornerAndInteriorDistance() {
    var n = noise(3);
    var c = add(n, "CYLINDER");
    set(c, "radius", 3);
    set(c, "halfHeight", 2);
    assertEquals(-2, field(n).sample(0, 0, 0));
    assertEquals(0, field(n).sample(3, 0, 0));
    assertEquals(0, field(n).sample(0, 2, 0));
    assertEquals(5, field(n).sample(6, 6, 0));
  }

  @Test
  void cylinderAxisAndCenterAreExplicit() {
    var n = noise(3);
    var c = add(n, "CYLINDER");
    set(c, "axis", "x");
    set(c, "centerX", 10);
    set(c, "centerY", 20);
    set(c, "centerZ", 30);
    set(c, "radius", 3);
    set(c, "halfHeight", 2);
    assertEquals(5, field(n).sample(16, 26, 30));
  }

  @Test
  void torusHasIndependentTubeAndHoleVectors() {
    var n = noise(3);
    var t = add(n, "TORUS");
    assertEquals(12, field(n).sample(0, 0, 0));
    assertEquals(-4, field(n).sample(16, 0, 0));
    assertEquals(0, field(n).sample(20, 0, 0));
    assertEquals(1, field(n).sample(19, 4, 0));
    set(t, "axis", "z");
    assertEquals(-4, field(n).sample(0, 16, 0));
  }

  @Test
  void roundedBoxPreservesOuterBoundsAndHasEuclideanCorners() {
    var n = noise(2);
    var b = add(n, "ROUNDED_BOX");
    set(b, "extentX", 5);
    set(b, "extentZ", 4);
    set(b, "roundRadius", 1);
    assertEquals(-4, field(n).sample(0, 0, 0));
    assertEquals(0, field(n).sample(5, 0, 0));
    assertEquals(4, field(n).sample(7, 0, 7));
  }

  @Test
  void zeroRoundingIsTheSharpBoxAnd3DUsesY() {
    var n = noise(3);
    var b = add(n, "ROUNDED_BOX");
    set(b, "roundRadius", 0);
    set(b, "extentX", 2);
    set(b, "extentY", 3);
    set(b, "extentZ", 4);
    assertEquals(5, field(n).sample(5, 7, 4));
    assertEquals(-2, field(n).sample(0, 0, 0));
  }

  @Test
  void shapePositionIsTypedAndUsesItsExplicitCoordinates() {
    var n = noise(2);
    var b = add(n, "CAPSULE");
    b.inputs.put("position", coordinates(2, 19, 4));
    assertEquals(1, field(n).sample(100, 0, 200));
    b.inputs.put("position", coordinates(3, 19, 0, 4));
    assertThrows(CompilationFailure.class, () -> field(n));
  }

  @Test
  void shapeUsesTransformedSpaceByDefaultAndDoesNotFoldAsAConstant() {
    var n = noise(2);
    var t = new Item();
    t.id = "move";
    t.kind = "TRANSLATE";
    t.resultType = "Coordinates2D";
    set(t, "x", 10);
    n.coordinateTransforms.add(t);
    add(n, "PLANE");
    var c = new NoiseCompiler(project, 0);
    assertEquals(11, c.compile(n.uuid).sample(1, 0, 0));
    assertEquals(12, c.compile(n.uuid).sample(2, 0, 0));
    assertEquals(0, c.statistics(n.uuid).constantItems());
  }

  @Test
  void invalidShapeRangesAndDimensionsRejectAtCompilation() {
    var n = noise(3);
    var t = add(n, "TORUS");
    set(t, "minorRadius", 17);
    assertEquals("minorRadius", NoiseCompiler.validate(project).getFirst().parameterKey());
    n.processing.clear();
    var b = add(n, "ROUNDED_BOX");
    set(b, "roundRadius", 17);
    assertEquals("roundRadius", NoiseCompiler.validate(project).getFirst().parameterKey());
    n.processing.clear();
    var c = add(n, "CYLINDER");
    set(c, "radius", -1);
    assertEquals("radius", NoiseCompiler.validate(project).getFirst().parameterKey());
    assertThrows(
        IllegalArgumentException.class, () -> ProcessingRegistry.create("CYLINDER", "bad", 2));
    var two = noise(2);
    var bad = ProcessingRegistry.create("TORUS", "bad");
    two.processing.add(bad);
    assertThrows(CompilationFailure.class, () -> field(two));
  }

  @Test
  void smoothIntersectionAndSubtractionHaveExactOutsideAndTieExpectations() {
    var n = noise(2);
    var i = add(n, "SMOOTH_INTERSECTION");
    i.inputs.put("a", Binding.constant(2));
    i.inputs.put("b", Binding.constant(2));
    set(i, "smoothness", 4);
    assertEquals(3, field(n).sample(0, 0, 0));
    i.inputs.put("b", Binding.constant(-10));
    assertEquals(2, field(n).sample(0, 0, 0));
    i.kind = "SMOOTH_SUBTRACTION";
    i.inputs.put("b", Binding.constant(-2));
    assertEquals(3, field(n).sample(0, 0, 0));
  }

  @Test
  void smoothCombinationsAreSymmetricOrUseTheSignedSecondOperand() {
    var n = noise(2);
    var i = add(n, "SMOOTH_INTERSECTION");
    set(i, "smoothness", 2);
    for (double a : new double[] {-4, -.5, 0, 1, 7})
      for (double b : new double[] {-3, 0, .25, 4}) {
        i.inputs.put("a", Binding.constant(a));
        i.inputs.put("b", Binding.constant(b));
        double ab = field(n).sample(0, 0, 0);
        i.inputs.put("a", Binding.constant(b));
        i.inputs.put("b", Binding.constant(a));
        assertEquals(ab, field(n).sample(0, 0, 0));
        assertTrue(ab >= StrictMath.max(a, b));
        assertTrue(ab <= StrictMath.max(a, b) + .5);
      }
  }

  @Test
  void disabledExperimentalControlsPersistWithoutExecuting() {
    var n = noise(2);
    var i = add(n, "LAPLACIAN");
    i.enabled = false;
    set(i, "spacing", -1);
    n.outputBinding = Binding.source();
    assertEquals(0, field(n).sample(0, 0, 0));
    assertEquals(-1, i.parameters.get("spacing").intValue());
  }

  @Test
  void compiledMedianAndGradientSnapshotsAreIndependentAcrossWorkersAndMutation() throws Exception {
    var n = noise(3);
    var prefix = expression(n, "x+10*y+100*z");
    sampled(n, "MEDIAN_FILTER", prefix);
    var f = field(n);
    set(prefix, "expression", "0");
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Future<?>>();
      for (int w = 0; w < 4; w++) {
        int start = w;
        tasks.add(
            pool.submit(
                () -> {
                  for (int x = -65 + start; x <= 65; x += 4)
                    assertEquals(x + 20 + 300, f.sample(x, 2, 3));
                }));
      }
      for (var task : tasks) task.get();
    }
  }

  @Test
  void registryMetadataIsImmutableAndFactorySignaturesAreExplicit() {
    assertEquals(17, ProcessingExtensions.DETAILS.size());
    assertThrows(UnsupportedOperationException.class, () -> ProcessingExtensions.DETAILS.clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> ProcessingExtensions.DETAILS.get("GRADIENT").controls().clear());
    assertEquals("Vector2", ProcessingRegistry.create("GRADIENT", "g", 2).resultType);
    assertEquals("Vector3", ProcessingRegistry.create("GRADIENT", "g").resultType);
    assertTrue(ProcessingRegistry.create("GRADIENT", "g", 2).inputs.containsKey("field"));
  }
}
