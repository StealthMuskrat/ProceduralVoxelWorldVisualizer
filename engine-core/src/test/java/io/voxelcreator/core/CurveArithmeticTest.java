package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred exact affine vectors and independent rational expectations for guarded arithmetic. */
class CurveArithmeticTest {
  private static final MathContext REFERENCE = new MathContext(100, RoundingMode.HALF_EVEN);

  private static Curve ramp(double scale, int knots, String interpolation) {
    var curve = new Curve();
    curve.max = knots - 1;
    curve.points.clear();
    for (int i = 0; i < knots; i++) {
      var point = new Point(i, i == 0 ? 0 : i * scale);
      point.segmentInterpolation = interpolation;
      curve.points.add(point);
    }
    return curve;
  }

  private static Path root() {
    for (var path = Path.of("").toAbsolutePath(); path != null; path = path.getParent())
      if (Files.isDirectory(path.resolve("fixtures"))) return path;
    throw new AssertionError("Curve vectors unavailable");
  }

  private static void close(double expected, double actual, int ulps) {
    assertTrue(Double.isFinite(actual));
    assertEquals(expected, actual, ulps * Math.ulp(expected));
  }

  @Test
  void independentAffineCorpusIsExactForBothCubicModesAtLargeSmallAndOrdinaryScales()
      throws Exception {
    var vectors =
        ProjectIO.JSON.readTree(root().resolve("fixtures/curve-arithmetic-vectors.json").toFile());
    assertEquals(CurveModes.ARITHMETIC_POLICY, vectors.path("arithmeticPolicy").asText());
    var registry = ProjectIO.JSON.readTree(root().resolve("registries/curve-modes.json").toFile());
    assertEquals(CurveModes.ARITHMETIC_POLICY, registry.path("arithmeticPolicy").asText());
    assertEquals(
        CurveModes.ARITHMETIC_DESCRIPTION, registry.path("arithmeticDescription").asText());
    int observations = 0;
    for (var row : vectors.path("cases"))
      for (String mode : List.of("SMOOTH", "BEZIER")) {
        double scale = Double.parseDouble(row.path("scaleHex").asText());
        var curve = new ScalarCurve(ramp(scale, row.path("knots").asInt(), mode));
        for (var sample : row.path("samples")) {
          double factor = Double.parseDouble(sample.path("factorHex").asText());
          double expected = factor == 0 ? 0 : scale * factor;
          assertEquals(
              Double.doubleToRawLongBits(expected),
              Double.doubleToRawLongBits(
                  curve.sample(Double.parseDouble(sample.path("inputHex").asText()))),
              row.path("id").asText());
          observations++;
        }
      }
    assertEquals(138, observations);
  }

  @Test
  void unequalFiniteSecantsMatchIndependentHighPrecisionHarmonicMeans() {
    for (int exponent : new int[] {-600, -530, 600, 1000})
      for (int sign : new int[] {-1, 1}) {
        double left = sign * Math.scalb(0x1.23456789abcdep0, exponent);
        double right = sign * Math.scalb(0x1.fedcba9876543p0, exponent);
        var curve = ramp(left, 3, "SMOOTH");
        curve.points.getLast().y = left + right;
        right = curve.points.getLast().y - left;
        var a = new BigDecimal(left);
        var b = new BigDecimal(right);
        double expected =
            a.multiply(b).multiply(BigDecimal.valueOf(2)).divide(a.add(b), REFERENCE).doubleValue();
        double actual = new ScalarCurve(curve).incomingSlope(1);
        close(expected, actual, 4);
        assertTrue(Math.abs(actual) <= Math.max(Math.abs(left), Math.abs(right)));
      }
  }

  @Test
  void unequalCubicMidpointsMatchIndependentRationalHermiteValues() {
    for (int exponent : new int[] {-600, 600, 1000})
      for (String mode : List.of("SMOOTH", "BEZIER")) {
        double scale = Math.scalb(1.0, exponent);
        var data = ramp(scale, 3, mode);
        data.points.getLast().y = 3 * scale;
        var curve = new ScalarCurve(data);
        var unit = new BigDecimal(scale);
        close(
            unit.multiply(BigDecimal.valueOf(11))
                .divide(BigDecimal.valueOf(24), REFERENCE)
                .doubleValue(),
            curve.sample(.5),
            8);
        close(
            unit.multiply(BigDecimal.valueOf(23))
                .divide(BigDecimal.valueOf(12), REFERENCE)
                .doubleValue(),
            curve.sample(1.5),
            8);
      }
  }

  @Test
  void largeFiniteCoefficientCancellationWorksForExplicitFreeHandles() {
    double unit = 0x1p1023;
    var data = ramp(0, 2, "BEZIER");
    data.points.getFirst().y = .5 * unit;
    data.points.getLast().y = 0;
    data.points.forEach(point -> point.handleMode = "FREE");
    data.points.getFirst().outTangent = -unit;
    data.points.getLast().inTangent = .75 * unit;
    var curve = new ScalarCurve(data);
    assertEquals(0x1p1018, curve.sample(.5));
    assertEquals(.5 * unit, curve.sample(0));
    assertEquals(0, curve.sample(1));
  }

  @Test
  void genuinelyNonrepresentableCoefficientsAndCubicSecantsStillReject() {
    var coefficients = ramp(Double.MAX_VALUE, 2, "BEZIER");
    coefficients.points.forEach(point -> point.handleMode = "FREE");
    assertThrows(IllegalArgumentException.class, () -> new ScalarCurve(coefficients));
    var secants = ramp(1, 2, "SMOOTH");
    secants.max = 0x1p-1070;
    secants.points.getLast().x = secants.max;
    assertThrows(IllegalArgumentException.class, () -> new ScalarCurve(secants));
  }

  @Test
  void turningAndFlatKnotsRetainZeroSlopeAtBothExtremeScales() {
    for (double scale : new double[] {0x1p600, -0x1p600, 0x1p-600, -0x1p-600}) {
      var turning = ramp(scale, 3, "SMOOTH");
      turning.points.getLast().y = 0;
      var peak = new ScalarCurve(turning);
      assertEquals(0, peak.incomingSlope(1));
      assertEquals(scale, peak.sample(1));
      assertTrue(peak.sample(.5) >= Math.min(0, scale) && peak.sample(.5) <= Math.max(0, scale));
      var flat = ramp(scale, 3, "SMOOTH");
      flat.points.get(1).y = 0;
      flat.points.getLast().y = scale;
      var compiled = new ScalarCurve(flat);
      assertEquals(0, compiled.incomingSlope(1));
      assertEquals(0, compiled.sample(.5));
    }
  }

  @Test
  void denseUnequalCurvesStayMonotoneAndInsideTheirEndpointRange() {
    for (int exponent : new int[] {-600, 600, 1000})
      for (int sign : new int[] {-1, 1})
        for (String mode : List.of("SMOOTH", "BEZIER")) {
          double scale = sign * Math.scalb(1.0, exponent);
          var data = ramp(scale, 3, mode);
          data.points.getLast().y = 3 * scale;
          var curve = new ScalarCurve(data);
          double previous = curve.sample(0);
          for (int i = 1; i <= 128; i++) {
            double value = curve.sample(i / 64.0);
            assertTrue(Double.isFinite(value));
            assertTrue(value >= Math.min(0, 3 * scale) && value <= Math.max(0, 3 * scale));
            assertTrue(sign * value >= sign * previous);
            previous = value;
          }
        }
  }

  @Test
  void outsidePoliciesAndExactKnotValuesRemainCanonical() {
    for (double scale : new double[] {0x1p600, 0x1p-600})
      for (String policy : CurveModes.OUTSIDE) {
        var data = ramp(scale, 3, "SMOOTH");
        data.outsideDomain = policy;
        var curve = new ScalarCurve(data);
        double left =
            switch (policy) {
              case "CLAMP" -> 0;
              case "EXTEND" -> -.25;
              case "REPEAT" -> 1.75;
              default -> .25;
            };
        double right =
            switch (policy) {
              case "CLAMP" -> 2;
              case "EXTEND" -> 2.25;
              case "REPEAT" -> .25;
              default -> 1.75;
            };
        assertEquals(left * scale, curve.sample(-.25));
        assertEquals(right * scale, curve.sample(2.25));
        assertEquals(0L, Double.doubleToRawLongBits(curve.sample(-0.0)));
        assertEquals(scale, curve.sample(1));
        assertEquals(2 * scale, curve.sample(2));
      }
  }

  @Test
  void guardedCompiledSlopesAndCoefficientsStayDetachedFromTheEditableCurve() {
    var data = ramp(0x1p-600, 3, "SMOOTH");
    var curve = new ScalarCurve(data);
    data.points.get(1).y = 100;
    data.points.getFirst().segmentInterpolation = "STEP";
    data.outsideDomain = "EXTEND";
    assertEquals(0x1p-601, curve.sample(.5));
    assertEquals(0x1p-600, curve.incomingSlope(1));
    assertEquals(0x1p-599, curve.sample(10));
  }

  @Test
  void noiseCompilerAcceptsGuardedCurvesWithoutChangingAuthoringDataInEitherDimension() {
    for (int dimension : new int[] {2, 3})
      for (double scale : new double[] {0x1p600, 0x1p-600}) {
        var project = new ProjectData();
        var noise = new Noise();
        noise.id = "guarded";
        noise.dimension = dimension;
        noise.algorithm.key = "CONSTANT";
        noise.algorithm.parameters.clear();
        noise.algorithm.parameters.put("value", 0.0);
        var item = ProcessingRegistry.create("CURVE", "curve");
        var input = Binding.constant(0);
        input.sourceKind = "Ambient Value";
        input.ambientKey = "x";
        item.inputs.put("a", input);
        item.parameters.put("curve", ProjectIO.JSON.valueToTree(ramp(scale, 3, "SMOOTH")));
        noise.processing.add(item);
        noise.outputBinding = Binding.earlier(item.uuid);
        (dimension == 2 ? project.noise2D : project.noise3D).add(noise);
        var before = ProjectIO.JSON.valueToTree(project);
        assertTrue(NoiseCompiler.validate(project).isEmpty());
        var compiled = new NoiseCompiler(project, 17).compile(noise.uuid);
        assertEquals(.5 * scale, compiled.sample(.5, -3, 7));
        assertEquals(1.5 * scale, compiled.sample(1.5, -3, 7));
        assertEquals(before, ProjectIO.JSON.valueToTree(project));
      }
  }
}
