package io.voxelcreator.example;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deferred engine-JAR replay of expression batch failures and structured compilation details. */
public final class ExpressionFailureConsumer {
  private static final double[] INPUT = {-3, -1, 0, 2}, SCALAR = {3, -1, 0, 8};
  private static final boolean[] BOOLEAN = {false, false, true, true};
  private record Retained(Object actual, Object expected) {}

  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }

  static void run(Path out) throws Exception {
    Files.createDirectories(out); var failed = new ArrayList<Retained>(); var healthy = new ArrayList<Retained>();
    long cases = 0, reads = 0, calls = 0, prefix = 0, direct = 0;
    for (boolean bool : new boolean[] {false, true}) {
      var p = Expression.compile(bool ? "x>0 || x==0" : "x*x+2*x", Map.of("x", Expression.Type.Scalar));
      for (int failedAt = 0; failedAt < 4; failedAt++) for (String mode : List.of("MUTABLE", "UNREADABLE_RUNTIME", "UNREADABLE_ERROR", "LONG")) {
        int index = failedAt; var described = new AtomicInteger(); var invoked = new AtomicInteger(); var result = buffer(bool);
        var cause = new RuntimeException() {
          public String getMessage() {
            int read = described.incrementAndGet();
            if (mode.equals("UNREADABLE_RUNTIME")) throw new IllegalStateException("message reader");
            if (mode.equals("UNREADABLE_ERROR")) throw new AssertionError("message reader");
            return mode.equals("LONG") ? "雪".repeat(3000) : "message-" + read;
          }
          public String toString() { throw new AssertionError("Host toString called"); }
        };
        try {
          invoke(bool, p, (name, sample) -> {
            require(name.equals("x"), "Unexpected expression input"); invoked.incrementAndGet(); if (sample == index) throw cause; return INPUT[sample];
          }, result);
          throw new AssertionError("Expression batch failure disappeared");
        } catch (Expression.BatchFailure failure) {
          require(failure.sampleIndex == index && failure.getCause() == cause, "Expression failure lost its cause/sample index");
          String detail = mode.startsWith("UNREADABLE") ? cause.getClass().getName() : mode.equals("LONG") ? "雪".repeat(2047) + "…" : "message-1";
          String expected = "Expression batch sample " + index + ": " + detail;
          require(expected.equals(failure.getMessage()) && expected.equals(failure.getMessage()), "Expression failure did not retain one frozen description");
          require(described.get() == 1 && invoked.get() == index + 1 && cause.getSuppressed().length == 0, "Expression failure retried an input or changed host ownership");
        }
        var expected = buffer(bool); fill(bool, expected, index); failed.add(new Retained(result, expected));
        prefix += index; calls += invoked.get(); reads += described.get(); cases++;
      }
      var result = buffer(bool); invoke(bool, p, (name, index) -> INPUT[index], result);
      var expected = buffer(bool); fill(bool, expected, 4); healthy.add(new Retained(result, expected));
      for (int i = 0; i < 4; i++) {
        double input = INPUT[i];
        if (bool) { require(p.bool(name -> input) == BOOLEAN[i] && p.boolIndexed(index -> input) == BOOLEAN[i], "Healthy Boolean frame retained a failed input"); }
        else { bits(SCALAR[i], p.scalar(name -> input)); bits(SCALAR[i], p.scalarIndexed(index -> input)); }
        direct += 2;
      }
    }
    long failedCells = verify(failed), healthyCells = verify(healthy), compilationCases = compilation();
    require(cases == 32 && calls == 80 && reads == 32 && prefix == 48 && failedCells == 256 && healthyCells == 16 && direct == 16 && compilationCases == 2,
        "Unexpected expression failure replay dimensions");
    Files.writeString(out.resolve("expression-failure-report.txt"),
        "batchCases=" + cases + "\ncompilationCases=" + compilationCases + "\ninputReads=" + calls + "\nmessageReads=" + reads
            + "\nsuccessfulPrefixValues=" + prefix + "\nuntouchedCells=" + (failedCells - prefix) + "\nretainedFailedDestinationCells=" + failedCells
            + "\nhealthyBatchValues=8\nhealthyDirectValues=" + direct + "\nretainedHealthyDestinationCells=" + healthyCells + "\n");
    System.out.println("EXPRESSION_FAILURE_CONSUMER_PASS");
  }

  private static long compilation() throws Exception {
    long cases = 0;
    for (int dimension : new int[] {2, 3}) {
      var project = new ProjectData(); var noise = new Noise(); noise.dimension = dimension; noise.id = "invalid-expression";
      if (dimension == 2) project.noise2D.add(noise); else project.noise3D.add(noise);
      var item = ProcessingRegistry.create("EXPRESSION", "formula"); item.parameters.put("expression", ProjectIO.JSON.valueToTree("unknown"));
      noise.processing.add(item); noise.outputBinding = Binding.earlier(item.uuid); String authored = ProjectIO.JSON.writeValueAsString(project);
      try { new NoiseCompiler(project, 0).compile(noise.uuid); throw new AssertionError("Unknown expression input compiled"); }
      catch (CompilationFailure failure) {
        require(failure.getCause() instanceof Expression.Problem, "Compilation lost its parser cause"); var problem = (Expression.Problem) failure.getCause();
        var diagnostic = failure.diagnostic(); require(problem.start == 0 && problem.end == 7, "Compilation changed the unknown-input span");
        require(failure.getMessage().equals(problem.getMessage()) && failure.getMessage().equals(diagnostic.message()), "Compilation exception/diagnostic text diverged");
        require(diagnostic.code().equals("INVALID_NOISE") && diagnostic.severity() == Diagnostic.Severity.ERROR
            && noise.uuid.equals(diagnostic.sourceUuid()) && item.uuid.equals(diagnostic.itemUuid()) && "expression".equals(diagnostic.parameterKey()), "Compilation diagnostic lost its location");
        require(diagnostic.jsonPointer().equals("/noise/" + noise.uuid + "/control/" + item.uuid + "/parameters/expression"), "Compilation pointer changed");
      }
      require(authored.equals(ProjectIO.JSON.writeValueAsString(project)), "Failed compilation changed authoring data"); cases++;
    }
    return cases;
  }
  private static Object buffer(boolean bool) {
    if (bool) { var values = new boolean[8]; Arrays.fill(values, true); return values; }
    var values = new double[8]; Arrays.fill(values, 99); return values;
  }
  private static void fill(boolean bool, Object destination, int count) {
    for (int i = 0; i < count; i++) if (bool) ((boolean[]) destination)[i + 2] = BOOLEAN[i]; else ((double[]) destination)[i + 2] = SCALAR[i];
  }
  private static void invoke(boolean bool, Expression.Program p, Expression.BatchVariables variables, Object destination) {
    if (bool) p.booleans(4, variables, (boolean[]) destination, 2); else p.scalars(4, variables, (double[]) destination, 2);
  }
  private static long verify(List<Retained> results) {
    long cells = 0;
    for (var result : results) if (result.actual instanceof double[] actual) {
      var expected = (double[]) result.expected; for (int i = 0; i < actual.length; i++) { bits(expected[i], actual[i]); cells++; }
    } else { var actual = (boolean[]) result.actual; var expected = (boolean[]) result.expected; for (int i = 0; i < actual.length; i++) { require(expected[i] == actual[i], "Expression Boolean prefix/padding changed"); cells++; } }
    return cells;
  }
  private static void bits(double expected, double actual) { require(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual), "Independent expression arithmetic changed"); }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
