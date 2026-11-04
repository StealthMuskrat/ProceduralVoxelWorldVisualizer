package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Deferred finite Linear endpoints and guarded extension, without relaxing cubic validation. */
class CurveLinearArithmeticTest {
  private Path root() {
    for (var p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
      if (Files.isDirectory(p.resolve("fixtures"))) return p;
    throw new AssertionError("Curve linear vectors unavailable");
  }

  private JsonNode vectors() throws Exception {
    return ProjectIO.JSON.readTree(root().resolve("fixtures/curve-linear-vectors.json").toFile());
  }

  private double number(JsonNode value, String key) {
    return Double.parseDouble(value.path(key + "Hex").asText());
  }

  private void bits(double expected, double actual) {
    assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
  }

  private Curve curve(double lo, double hi, double y0, double y1, String mode, String outside) {
    var c = new Curve();
    c.min = lo;
    c.max = hi;
    c.outsideDomain = outside;
    c.points.getFirst().x = lo;
    c.points.getFirst().y = y0;
    c.points.getFirst().segmentInterpolation = mode;
    c.points.getLast().x = hi;
    c.points.getLast().y = y1;
    if (mode.equals("BEZIER")) c.points.forEach(p -> p.handleMode = "FREE");
    return c;
  }

  private Curve curve(JsonNode row) {
    var c =
        curve(
            number(row, "min"),
            number(row, "max"),
            number(row, "y0"),
            number(row, "y1"),
            row.path("interpolation").asText(),
            row.path("outside").asText());
    c.points.getFirst().outTangent = number(row, "outTangent");
    c.points.getLast().inTangent = number(row, "inTangent");
    return c;
  }

  private ProjectData project(Curve c, int dimension) {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "curve";
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 0.);
    var item = ProcessingRegistry.create("CURVE", "response", dimension);
    var input = Binding.constant(0);
    input.sourceKind = "Ambient Value";
    input.ambientKey = "x";
    item.inputs.put("a", input);
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(c));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    return p;
  }

  @Test
  void handSpecifiedBinaryVectorsAgreeInDirectCurveAndBothCompiledDimensions() throws Exception {
    var vectors = vectors();
    assertEquals(17, vectors.path("cases").size());
    int samples = 0;
    for (var row : vectors.path("cases")) {
      var c = curve(row);
      var before = ProjectIO.JSON.valueToTree(c);
      var compiled = new ScalarCurve(c);
      for (var sample : row.path("samples")) {
        bits(number(sample, "expected"), compiled.sample(number(sample, "x")));
        samples++;
      }
      for (int dimension : new int[] {2, 3}) {
        var p = project(c, dimension);
        var values = ProjectIO.JSON.valueToTree(p);
        var n = (dimension == 2 ? p.noise2D : p.noise3D).getFirst();
        var field = new NoiseCompiler(p, 17).compile(n.uuid);
        for (var sample : row.path("samples"))
          bits(number(sample, "expected"), field.sample(number(sample, "x"), -3, 7));
        assertEquals(values, ProjectIO.JSON.valueToTree(p));
      }
      assertEquals(before, ProjectIO.JSON.valueToTree(c));
    }
    assertEquals(75, samples);
  }

  @Test
  void aLinearSegmentKeepsFiniteOppositeEndpointsWithoutAnInfiniteCoefficient() {
    double h = 0x1p1023;
    var c = new ScalarCurve(curve(0, 1, -h, h, "LINEAR", "CLAMP"));
    bits(-h, c.sample(0));
    bits(-h / 2, c.sample(.25));
    bits(0, c.sample(.5));
    bits(h / 2, c.sample(.75));
    bits(h, c.sample(1));
    bits(-h, c.sample(-1));
    bits(h, c.sample(2));
    var mixed = curve(0, 2, -h, 0, "LINEAR", "EXTEND");
    var knot = new Point(1, h);
    knot.segmentInterpolation = "STEP";
    mixed.points.add(1, knot);
    var compiled = new ScalarCurve(mixed);
    bits(0, compiled.sample(.5));
    bits(h, compiled.sample(1));
    bits(h, compiled.sample(1.5));
    bits(0, compiled.sample(2));
    bits(0, compiled.sample(3));
    bits(-0x1.8p1023, compiled.sample(-.25));
  }

  @Test
  void microscopicDomainExtensionDoesNotMultiplyByAnUnrepresentableDerivedSlope() {
    double tiny = Double.MIN_VALUE;
    var c = new ScalarCurve(curve(0, tiny, 0, 1, "LINEAR", "EXTEND"));
    bits(2, c.sample(2 * tiny));
    bits(-1, c.sample(-tiny));
    bits(1, c.sample(tiny));
  }

  @Test
  void zeroedNonflatLinearSlopeDoesNotFlattenRepresentableExtendedValues() {
    double h = 0x1p1023, tiny = Double.MIN_VALUE;
    var left = new ScalarCurve(curve(0, h, 0, tiny, "LINEAR", "EXTEND"));
    bits(-tiny, left.sample(-h));
    var right = new ScalarCurve(curve(-h, 0, 0, tiny, "LINEAR", "EXTEND"));
    bits(2 * tiny, right.sample(h));
    bits(2 * tiny, right.sample(h / 2));
    var halfway = new ScalarCurve(curve(0, h, tiny, 2 * tiny, "LINEAR", "EXTEND"));
    bits(0, halfway.sample(-h / 2));
    bits(2 * tiny, halfway.sample(0x1.8p1023));
  }

  @Test
  void stepAndExplicitZeroTangentsRetainTheirEndpointWhenOnlyTheOffsetOverflows() {
    for (String mode : List.of("STEP", "BEZIER", "LINEAR")) {
      var data = curve(-Double.MAX_VALUE, -0x1p1023, 0, -0., mode, "EXTEND");
      var c = new ScalarCurve(data);
      bits(-0., c.sample(Double.MAX_VALUE));
    }
  }

  @Test
  void finiteExplicitBezierTangentRecoversAProductThatCancelsWithItsEndpoint() {
    double h = 0x1p1023;
    var data = curve(0, 1, h, h / 2, "BEZIER", "EXTEND");
    data.points.getLast().inTangent = -h;
    var c = new ScalarCurve(data);
    bits(-0x1.8p1023, c.sample(3));
    bits(-h / 2, c.sample(2));
  }

  @Test
  void ordinaryFiniteSegmentsAndEndpointExtensionKeepTheirHistoricalBits() {
    var random = new Random(81037);
    for (String mode : CurveModes.INTERPOLATIONS) {
      var data = curve(0, 1, -.25, 1.75, mode, "EXTEND");
      if (mode.equals("BEZIER")) {
        data.points.getFirst().outTangent = 2;
        data.points.getLast().inTangent = 2;
      }
      var c = new ScalarCurve(data);
      for (int i = 0; i < 512; i++) {
        double v = random.nextDouble() * 8 - 4, expected;
        if (mode.equals("STEP")) expected = v < 1 ? -.25 : 1.75;
        else expected = v < 0 ? -.25 + (v - 0) * 2 : v > 1 ? 1.75 + (v - 1) * 2 : 2 * v - .25;
        bits(expected, c.sample(v));
      }
    }
  }

  @Test
  void cubicCoefficientsAndUnrepresentableCubicSecantsStillReject() {
    var free = curve(0, 1, 0, Double.MAX_VALUE, "BEZIER", "CLAMP");
    assertThrows(IllegalArgumentException.class, () -> new ScalarCurve(free));
    var smooth = curve(0, Double.MIN_VALUE, 0, 1, "SMOOTH", "CLAMP");
    assertThrows(IllegalArgumentException.class, () -> new ScalarCurve(smooth));
  }

  @Test
  void domainDisplayAndNonfiniteInputRulesRemainUnchanged() {
    var domain = curve(-Double.MAX_VALUE, Double.MAX_VALUE, 0, 1, "LINEAR", "CLAMP");
    assertThrows(IllegalArgumentException.class, () -> new ScalarCurve(domain));
    var display = curve(0, 1, 0, 1, "LINEAR", "CLAMP");
    display.displayMin = -Double.MAX_VALUE;
    display.displayMax = Double.MAX_VALUE;
    assertThrows(IllegalArgumentException.class, () -> new ScalarCurve(display));
    var c = new ScalarCurve(curve(0, 1, 0, 1, "LINEAR", "EXTEND"));
    assertThrows(IllegalArgumentException.class, () -> c.sample(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> c.sample(Double.POSITIVE_INFINITY));
  }

  @Test
  void trueExtensionOverflowStillFailsAtTheExecutingCurveItemBoundary() {
    var data = curve(0, 1, -0x1p1023, 0x1p1023, "LINEAR", "EXTEND");
    assertTrue(Double.isInfinite(new ScalarCurve(data).sample(1.5)));
    var p = project(data, 2);
    var field = new NoiseCompiler(p, 0).compile(p.noise2D.getFirst().uuid);
    bits(0x1.8p1023, field.sample(1.25, 0, 0));
    assertThrows(EvaluationFailure.class, () -> field.sample(1.5, 0, 0));
  }

  @Test
  void compiledEndpointAndExtensionSnapshotsStayOwnedAcrossMutationAndConcurrentSampling()
      throws Exception {
    var data = curve(0, 1, -0x1p1023, 0x1p1023, "LINEAR", "CLAMP");
    var c = new ScalarCurve(data);
    data.points.getFirst().y = 7;
    data.points.getLast().y = 9;
    data.min = -1;
    data.outsideDomain = "EXTEND";
    try (var pool = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Callable<Double>>();
      for (int i = 0; i < 128; i++) jobs.add(() -> c.sample(.25));
      for (var result : pool.invokeAll(jobs)) bits(-0x1p1022, result.get());
    }
    bits(-0x1p1023, c.sample(-1));
  }

  @Test
  void additiveRegistryPolicyMatchesTheHandCorpusAndRetainsTheCubicDescriptor() throws Exception {
    var registry = ProjectIO.JSON.readTree(root().resolve("registries/curve-modes.json").toFile());
    assertEquals(
        CurveModes.LINEAR_ARITHMETIC_POLICY, registry.path("linearArithmeticPolicy").asText());
    assertEquals(
        CurveModes.LINEAR_ARITHMETIC_DESCRIPTION,
        registry.path("linearArithmeticDescription").asText());
    assertEquals(CurveModes.LINEAR_ARITHMETIC_POLICY, vectors().path("arithmeticPolicy").asText());
    assertEquals(
        "guarded-harmonic-and-coefficients-v1", registry.path("arithmeticPolicy").asText());
    assertEquals(
        CurveModes.ARITHMETIC_DESCRIPTION, registry.path("arithmeticDescription").asText());
  }
}
