package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.*;

public class MathContractTest {
  private double eval(String s) {
    return Expression.compile(s, Map.of())
        .scalar(
            n -> {
              throw new AssertionError();
            });
  }

  @Test
  void arithmeticPrecedenceAndPower() {
    assertEquals(512, eval("2^3^2"));
    assertEquals(-4, eval("-2^2"));
    assertEquals(7, eval("1 + 2*3"));
    assertEquals(1, eval("-5 % 3 + 3"));
  }

  @Test
  void shortCircuitAndTypes() {
    assertEquals(7, eval("true ? 7 : 1/0"));
    assertFalse(Expression.compile("false && sqrt(-1)>0", Map.of()).bool(n -> 0));
    assertTrue(Expression.compile("true || log(-1)>0", Map.of()).bool(n -> 0));
    assertThrows(Expression.Problem.class, () -> Expression.compile("1 && true", Map.of()));
    assertThrows(Expression.Problem.class, () -> Expression.compile("true ? 2 : false", Map.of()));
    assertThrows(Expression.Problem.class, () -> eval("1/0"));
    assertThrows(Expression.Problem.class, () -> eval("sqrt(-1)"));
    assertThrows(Expression.Problem.class, () -> Expression.compile("System.exit(0)", Map.of()));
  }

  @Test
  void functionInventory() {
    Map<String, Double> cases = new LinkedHashMap<>();
    cases.put("sin(0)", 0.);
    cases.put("cos(0)", 1.);
    cases.put("tan(0)", 0.);
    cases.put("asin(0)", 0.);
    cases.put("atan(0)", 0.);
    cases.put("atan2(0,1)", 0.);
    cases.put("sinh(0)", 0.);
    cases.put("cosh(0)", 1.);
    cases.put("tanh(0)", 0.);
    cases.put("abs(-3)", 3.);
    cases.put("min(2,3)", 2.);
    cases.put("max(2,3)", 3.);
    cases.put("floor(-1.2)", -2.);
    cases.put("ceil(-1.2)", -1.);
    cases.put("round(-1.5)", -1.);
    cases.put("fract(-1.25)", .75);
    cases.put("sqrt(9)", 3.);
    cases.put("cbrt(8)", 2.);
    cases.put("pow(2,3)", 8.);
    cases.put("exp(0)", 1.);
    cases.put("log(1)", 0.);
    cases.put("log10(100)", 2.);
    cases.put("sign(-2)", -1.);
    cases.put("clamp(3,0,1)", 1.);
    cases.put("lerp(2,4,0.5)", 3.);
    cases.put("inverseLerp(2,4,3)", .5);
    cases.put("step(2,2)", 1.);
    cases.put("smoothstep(0,1,0.5)", .5);
    cases.put("mod(-1,3)", 2.);
    cases.put("saturate(-2)", 0.);
    cases.put("remap(0.5,0,1,10,20)", 15.);
    cases.put("wrap(-1,0,3)", 2.);
    cases.put("pingPong(3,2)", 1.);
    for (var c : cases.entrySet()) assertEquals(c.getValue(), eval(c.getKey()), 1e-12, c.getKey());
    assertEquals(Expression.FUNCTIONS.size(), cases.size());
  }

  @Test
  void sourceSpansAndLimits() {
    var ex =
        assertThrows(Expression.Problem.class, () -> Expression.compile("1 + unknown", Map.of()));
    assertEquals(4, ex.start);
    assertThrows(
        Expression.Problem.class,
        () -> Expression.compile("(".repeat(70) + "1" + ")".repeat(70), Map.of()));
  }

  @Test
  void curvesKnotsAndPolicies() {
    var c = new Curve();
    var linear = new ScalarCurve(c);
    assertEquals(.5, linear.sample(.5));
    assertEquals(0, linear.sample(-1));
    assertEquals(1, linear.sample(2));
    c.outsideDomain = "EXTEND";
    assertEquals(-2, new ScalarCurve(c).sample(-2));
    c.outsideDomain = "REPEAT";
    assertEquals(.25, new ScalarCurve(c).sample(1.25));
    c.outsideDomain = "MIRROR";
    assertEquals(.75, new ScalarCurve(c).sample(1.25));
    c.outsideDomain = "CLAMP";
    c.points.getFirst().segmentInterpolation = "STEP";
    assertEquals(0, new ScalarCurve(c).sample(.999));
    assertEquals(1, new ScalarCurve(c).sample(1));
    c.points.getFirst().segmentInterpolation = "BEZIER";
    c.points.getFirst().outTangent = 1;
    c.points.getLast().inTangent = 1;
    assertEquals(.3, new ScalarCurve(c).sample(.3), 1e-14);
    c.points.add(1, new Point(.5, .9));
    c.points.forEach(p -> p.segmentInterpolation = "SMOOTH");
    var smooth = new ScalarCurve(c);
    double last = -1;
    for (int i = 0; i <= 100; i++) {
      double y = smooth.sample(i / 100.);
      assertTrue(y >= last - 1e-14 && y >= 0 && y <= 1);
      last = y;
    }
  }

  @Test
  void hashHasKnownIndependentSplitMixVectors() {
    assertEquals(0, CoordinateHash.mix(0));
    assertEquals(0x5692161d100b05e5L, CoordinateHash.mix(1));
    assertEquals(0xdbd238973a2b148aL, CoordinateHash.mix(2));
    assertTrue(CoordinateHash.probability(-1, -1, -1, -1) >= 0);
    assertTrue(CoordinateHash.probability(-1, -1, -1, -1) < 1);
  }

  @Test
  void noiseFamiliesRepeatAtNegativeAndLargeCoordinates() {
    for (String key : NoiseAlgorithms.KEYS) {
      var a = new Algorithm();
      a.key = key;
      var f = NoiseAlgorithms.compile(a, 3, 123);
      for (double x : new double[] {-999, -.5, 0, 25.25, 1e6}) {
        double v = f.sample(x, 17, -23);
        assertTrue(Double.isFinite(v), key);
        assertEquals(
            Double.doubleToLongBits(v), Double.doubleToLongBits(f.sample(x, 17, -23)), key);
      }
    }
  }

  @Test
  void processingOutputAndSharedDefinition() {
    var p = new io.voxelcreator.model.ProjectData();
    var n = new Noise();
    n.id = "field";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", 2.);
    p.noise2D.add(n);
    var item = ProcessingRegistry.create("MULTIPLY", "triple");
    item.inputs.put("b", Binding.constant(3));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    assertTrue(NoiseCompiler.validate(p).isEmpty());
    var f = new NoiseCompiler(p, 0).compile(n.uuid);
    assertEquals(6, f.sample(-1, 0, 50));
    n.algorithm.parameters.put("value", 99.);
    assertEquals(6, f.sample(-1, 0, 50), "Compiled snapshot must not observe edits");
    item.enabled = false;
    assertFalse(NoiseCompiler.validate(p).isEmpty());
  }
}
