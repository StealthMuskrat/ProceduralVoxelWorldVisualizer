package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ExpressionPlanTest {
  private static final Map<String, Expression.Type> SCALARS =
      Map.of("x", Expression.Type.Scalar, "z", Expression.Type.Scalar);

  @Test
  void constantsAndDeadBranchesFoldOnlyAfterCompleteValidation() {
    var p = Expression.compile("true ? sin(pi/2)+pow(2,3)*4 : 1/0", Map.of());
    assertEquals(
        33,
        p.scalar(
            n -> {
              throw new AssertionError(n);
            }));
    assertEquals(33, p.constantValue().orElseThrow());
    assertTrue(p.inputs().isEmpty());
    assertTrue(p.statistics().foldedNodes() >= 3);
    assertTrue(p.statistics().prunedBranches() > 0);
    assertThrows(
        Expression.Problem.class, () -> Expression.compile("true ? 1 : unknown", Map.of()));
    assertThrows(Expression.Problem.class, () -> Expression.compile("false && 2", Map.of()));
    var partial = Expression.compile("x+(3*7)+sin(0)", SCALARS);
    assertEquals(22.5, partial.scalar(n -> 1.5));
    assertEquals(List.of("x"), partial.inputs());
    var bound =
        Expression.compile(
            "enabled ? x+offset : 1/0",
            Map.of(
                "enabled",
                Expression.Type.Boolean,
                "x",
                Expression.Type.Scalar,
                "offset",
                Expression.Type.Scalar),
            Map.of("enabled", 1.0, "offset", 4.0));
    assertEquals(List.of("x"), bound.inputs());
    assertEquals(7, bound.scalar(n -> 3));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Expression.compile(
                "enabled", Map.of("enabled", Expression.Type.Boolean), Map.of("enabled", 2.0)));
  }

  @Test
  void sharingReadsStableInputsOnceAndNeverLeaksAcrossSamples() {
    var p = Expression.compile("(x+z)*(x+z)+(x+z)", SCALARS);
    var counts = new HashMap<String, Integer>();
    assertEquals(
        30,
        p.scalar(
            name -> {
              counts.merge(name, 1, Integer::sum);
              return name.equals("x") ? 2 : 3;
            }));
    assertEquals(Map.of("x", 1, "z", 1), counts);
    assertTrue(p.statistics().sharedExpressions() > 0);
    assertEquals(6, p.scalar(name -> 1));
    double[] values = new double[p.inputs().size()];
    for (int i = 0; i < values.length; i++) values[i] = p.inputs().get(i).equals("x") ? -2 : 5;
    assertEquals(12, p.scalarIndexed(index -> values[index]));
  }

  @Test
  void lazySharedOccurrencesKeepTheExecutingSourceSpan() {
    String source = "condition ? sqrt(x) : sqrt(x)";
    var types = new HashMap<>(SCALARS);
    types.put("condition", Expression.Type.Boolean);
    var p = Expression.compile(source, types);
    for (boolean condition : new boolean[] {true, false}) {
      var error =
          assertThrows(
              Expression.Problem.class,
              () -> p.scalar(name -> name.equals("condition") ? (condition ? 1 : 0) : -1));
      int expected = condition ? source.indexOf("sqrt") : source.lastIndexOf("sqrt");
      assertEquals(expected, error.start);
      assertEquals(expected + 7, error.end);
      var nonfinite =
          assertThrows(
              Expression.Problem.class,
              () -> p.scalar(name -> name.equals("condition") ? (condition ? 1 : 0) : Double.NaN));
      assertEquals(condition ? source.indexOf('x') : source.lastIndexOf('x'), nonfinite.start);
    }
    var logic = Expression.compile("x==0 || 1/x>0", SCALARS);
    assertTrue(logic.bool(n -> 0));
    var left = Expression.compile("sqrt(x)+log(z)", SCALARS);
    var reads = new AtomicInteger();
    var error =
        assertThrows(
            Expression.Problem.class,
            () ->
                left.scalar(
                    name -> {
                      if (name.equals("z")) reads.incrementAndGet();
                      return -1;
                    }));
    assertEquals(0, error.start);
    assertEquals(0, reads.get());
  }

  @Test
  void invalidConstantsAndClampErrorsRemainRuntimeBoundaries() {
    var p = Expression.compile("0*(1/0)", Map.of());
    assertTrue(p.constantValue().isEmpty());
    var error = assertThrows(Expression.Problem.class, () -> p.scalar(null));
    assertEquals(3, error.start);
    assertEquals(6, error.end);
    String source = "flag ? 9 : clamp(1,2,0)";
    var q = Expression.compile(source, Map.of("flag", Expression.Type.Boolean));
    assertEquals(9, q.scalar(n -> 1));
    var range = assertThrows(Expression.Problem.class, () -> q.scalar(n -> 0));
    assertEquals(source.indexOf("clamp"), range.start);
    assertTrue(range.getMessage().contains("Inverted clamp"));
  }

  @Test
  void optimizerPreservesSignedZeroRoundingAndMandatoryInputs() {
    assertEquals(
        Double.doubleToRawLongBits(-0.0),
        Double.doubleToRawLongBits(Expression.compile("-0.0", Map.of()).scalar(null)));
    assertEquals(0, Expression.compile("(1e16+x)-1e16", SCALARS).scalar(n -> 1));
    assertThrows(
        Expression.Problem.class, () -> Expression.compile("x*0", SCALARS).scalar(n -> Double.NaN));
    assertThrows(
        Expression.Problem.class,
        () -> Expression.compile("x-x", SCALARS).scalar(n -> Double.POSITIVE_INFINITY));
    var rawBoolean = Expression.compile("(true && b)==true", Map.of("b", Expression.Type.Boolean));
    assertTrue(rawBoolean.bool(n -> 2));
  }

  @Test
  void flatExpressionsExecuteWithoutARecursiveJavaEvaluationStack() {
    var p = Expression.compile(String.join("+", Collections.nCopies(1000, "x")), SCALARS);
    double expected = .1;
    for (int i = 1; i < 1000; i++) expected += .1;
    assertEquals(
        Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(p.scalar(n -> .1)));
    assertThrows(
        Expression.Problem.class,
        () -> Expression.compile("(".repeat(70) + "x" + ")".repeat(70), SCALARS));
    assertThrows(
        Expression.Problem.class, () -> Expression.compile("x+".repeat(3000) + "x", SCALARS));
    assertThrows(Expression.Problem.class, () -> Expression.compile("1+", SCALARS));
    assertThrows(Expression.Problem.class, () -> Expression.compile("", SCALARS));
  }

  @Test
  void scalarAndBooleanBatchesPreservePaddingAndFailurePrefix() {
    var p = Expression.compile("sqrt(x)", SCALARS);
    double[] destination = new double[7];
    Arrays.fill(destination, -99);
    var error =
        assertThrows(
            Expression.BatchFailure.class,
            () -> p.scalars(4, (name, index) -> new double[] {0, 1, -1, 4}[index], destination, 1));
    assertEquals(2, error.sampleIndex);
    assertInstanceOf(Expression.Problem.class, error.getCause());
    assertArrayEquals(new double[] {-99, 0, 1, -99, -99, -99, -99}, destination);
    var logic = Expression.compile("x!=0 && 1/x>0", SCALARS);
    boolean[] mask = {true, true, true, true, true};
    logic.booleans(3, (name, index) -> index == 0 ? 0 : index == 1 ? 2 : -2, mask, 1);
    assertArrayEquals(new boolean[] {true, false, true, false, true}, mask);
    var calls = new AtomicInteger();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            p.scalars(
                10,
                (name, index) -> {
                  calls.incrementAndGet();
                  return 1;
                },
                destination,
                0));
    assertEquals(0, calls.get());
  }

  @Test
  void nestedHostEvaluationsAndNestedBatchesUseIndependentFrames() {
    var p = Expression.compile("x+x", SCALARS);
    assertEquals(8, p.scalar(name -> p.scalar(inner -> 2)));
    double[] output = new double[4];
    p.scalars(4, (name, index) -> p.scalar(inner -> index + 1), output, 0);
    assertArrayEquals(new double[] {4, 8, 12, 16}, output);
    assertEquals(6, p.scalarIndexed(i -> 3));
  }

  @Test
  void compiledPlansAreSafeAcrossThreadsAndFreshInputEpochs() throws Exception {
    var p = Expression.compile("(x+z)*(x+z)+x", SCALARS);
    try (var executor = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Future<?>>();
      for (int thread = 0; thread < 8; thread++) {
        int base = thread * 1000;
        jobs.add(
            executor.submit(
                () -> {
                  for (int i = 0; i < 100; i++) {
                    int x = base + i;
                    assertEquals(
                        (x - 3.0) * (x - 3.0) + x, p.scalar(name -> name.equals("x") ? x : -3));
                  }
                }));
      }
      for (var job : jobs) job.get(5, TimeUnit.SECONDS);
    }
  }
}
