package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.Diagnostic;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred core description ownership, diagnostic locations and expression-frame recovery. */
class CoreFailureDetailsTest {
  private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final double[] INPUT = {-2, -1, 0, 2}, SCALAR = {-3, -1, 1, 5};
  private static final boolean[] BOOLEAN = {true, true, false, false};
  private record Location(UUID item, String parameter, String suffix) {}

  private static RuntimeException cause(String mode, AtomicInteger reads) {
    return new RuntimeException() {
      public String getMessage() {
        int read = reads.incrementAndGet(); return switch (mode) {
          case "UNREADABLE_RUNTIME" -> throw new IllegalStateException("reader");
          case "UNREADABLE_ERROR" -> throw new AssertionError("reader");
          case "NULL" -> null; case "BLANK" -> " \t"; case "LONG" -> "雪".repeat(3000);
          case "ASTRAL" -> "😀".repeat(2000); case "LIMIT" -> "😀".repeat(1024); default -> "message-" + read;
        };
      }
      public String toString() { throw new AssertionError("Host toString called"); }
      public Throwable getCause() { throw new AssertionError("Host cause accessor called"); }
    };
  }
  private static Expression.Program program(boolean bool) {
    return Expression.compile(bool ? "x!=0 && x<2" : "x+x+1", Map.of("x", Expression.Type.Scalar));
  }
  private static double[] numbers() { var result = new double[7]; Arrays.fill(result, 99); return result; }
  private static boolean[] mask() { var result = new boolean[7]; Arrays.fill(result, true); return result; }
  private static void invoke(boolean bool, Expression.Program p, Expression.BatchVariables variables, double[] values, boolean[] mask) {
    if (bool) p.booleans(4, variables, mask, 1); else p.scalars(4, variables, values, 1);
  }
  private static void prefix(boolean bool, int count, double[] actual, boolean[] mask) {
    var expected = numbers(); var expectedMask = mask();
    for (int i = 0; i < count; i++) if (bool) expectedMask[i + 1] = BOOLEAN[i]; else expected[i + 1] = SCALAR[i];
    assertArrayEquals(expected, actual); assertArrayEquals(expectedMask, mask);
  }

  @Test
  void compilationExceptionAndDiagnosticShareOneFrozenMessageAndKeepEveryLocationField() {
    for (var location : List.of(new Location(null, null, ""), new Location(ITEM, "expression", "/control/" + ITEM + "/parameters/expression"),
        new Location(ITEM, "inputs/a/b~c", "/control/" + ITEM + "/inputs/a~1b~0c"),
        new Location(null, "algorithm/parameters/frequency", "/algorithm/parameters/frequency"))) {
      var reads = new AtomicInteger(); var cause = cause("MUTABLE", reads);
      var failure = new CompilationFailure(RESOURCE, location.item, location.parameter, cause); var diagnostic = failure.diagnostic();
      assertSame(cause, failure.getCause()); assertEquals("message-1", failure.getMessage()); assertEquals(failure.getMessage(), diagnostic.message());
      assertEquals("INVALID_NOISE", diagnostic.code()); assertEquals(Diagnostic.Severity.ERROR, diagnostic.severity());
      assertEquals(RESOURCE, diagnostic.sourceUuid()); assertEquals(location.item, diagnostic.itemUuid()); assertEquals(location.parameter, diagnostic.parameterKey());
      assertEquals("/noise/" + RESOURCE + location.suffix, diagnostic.jsonPointer()); assertEquals(1, reads.get()); assertEquals(0, cause.getSuppressed().length);
    }
  }

  @Test
  void bothConstructorsContainUnreadableNullAndBlankHostMessagesWithoutUnwrappingTheCause() {
    for (String mode : List.of("UNREADABLE_RUNTIME", "UNREADABLE_ERROR", "NULL", "BLANK")) {
      var reads = new AtomicInteger(); var cause = cause(mode, reads); String expected = cause.getClass().getName();
      var compilation = new CompilationFailure(RESOURCE, ITEM, "expression", cause); var batch = new Expression.BatchFailure(3, cause);
      assertSame(cause, compilation.getCause()); assertSame(cause, batch.getCause()); assertEquals(3, batch.sampleIndex);
      assertEquals(expected, compilation.getMessage()); assertEquals(expected, compilation.diagnostic().message());
      assertEquals("Expression batch sample 3: " + expected, batch.getMessage()); assertEquals(2, reads.get()); assertEquals(0, cause.getSuppressed().length);
    }
  }

  @Test
  void scalarAndBooleanHostMessageFaultsKeepOriginalCauseSampleIndexAndOnlySuccessfulPrefix() {
    for (boolean bool : new boolean[] {false, true}) for (int failedAt = 0; failedAt < 4; failedAt++)
      for (String mode : List.of("UNREADABLE_RUNTIME", "UNREADABLE_ERROR")) {
        int index = failedAt; var reads = new AtomicInteger(); var calls = new AtomicInteger(); var cause = cause(mode, reads);
        var values = numbers(); var mask = mask(); var p = program(bool);
        var failure = assertThrows(Expression.BatchFailure.class, () -> invoke(bool, p, (name, sample) -> {
          assertEquals("x", name); calls.incrementAndGet(); if (sample == index) throw cause; return INPUT[sample];
        }, values, mask));
        assertEquals(index, failure.sampleIndex); assertSame(cause, failure.getCause());
        assertEquals("Expression batch sample " + index + ": " + cause.getClass().getName(), failure.getMessage());
        assertEquals(index + 1, calls.get()); assertEquals(1, reads.get()); prefix(bool, index, values, mask);
      }
  }

  @Test
  void reentrantMessageEvaluationUsesIndependentFramesAndHealthySuccessorsKeepTheirBuffers() {
    for (boolean bool : new boolean[] {false, true}) {
      var p = program(bool); var reads = new AtomicInteger(); var values = numbers(); var mask = mask();
      var cause = new RuntimeException() {
        public String getMessage() {
          reads.incrementAndGet(); var nested = numbers(); var nestedMask = mask();
          invoke(bool, p, (name, index) -> INPUT[index], nested, nestedMask); prefix(bool, 4, nested, nestedMask);
          if (bool) assertTrue(p.bool(name -> -1)); else assertEquals(-1, p.scalar(name -> -1)); return "nested";
        }
      };
      var failure = assertThrows(Expression.BatchFailure.class, () -> invoke(bool, p, (name, sample) -> {
        if (sample == 2) throw cause; return INPUT[sample];
      }, values, mask));
      assertEquals("Expression batch sample 2: nested", failure.getMessage()); assertEquals(1, reads.get()); prefix(bool, 2, values, mask);
      var healthy = numbers(); var healthyMask = mask(); invoke(bool, p, (name, index) -> INPUT[index], healthy, healthyMask);
      prefix(bool, 4, healthy, healthyMask); prefix(bool, 2, values, mask); assertEquals(1, reads.get());
    }
  }

  @Test
  void boundsPreserveExactLimitsSurrogatePairsAndOriginalEnvelopesWhileNullCausesReject() {
    for (String mode : List.of("LONG", "ASTRAL", "LIMIT")) {
      var reads = new AtomicInteger(); var cause = cause(mode, reads);
      String expected = mode.equals("LONG") ? "雪".repeat(2047) + "…" : mode.equals("ASTRAL") ? "😀".repeat(1023) + "…" : "😀".repeat(1024);
      var compilation = new CompilationFailure(RESOURCE, ITEM, "expression", cause); var batch = new Expression.BatchFailure(1, cause);
      assertEquals(expected, compilation.getMessage()); assertEquals(expected, compilation.diagnostic().message());
      assertEquals("Expression batch sample 1: " + expected, batch.getMessage()); assertEquals(2, reads.get());
      assertSame(cause, compilation.getCause()); assertSame(cause, batch.getCause()); assertEquals(0, cause.getSuppressed().length);
    }
    var envelope = new CompletionException("host envelope", new IllegalStateException()) { public Throwable getCause() { throw new AssertionError("cause reader"); } };
    assertSame(envelope, new CompilationFailure(RESOURCE, ITEM, "expression", envelope).getCause());
    assertSame(envelope, new Expression.BatchFailure(0, envelope).getCause());
    assertThrows(NullPointerException.class, () -> new CompilationFailure(RESOURCE, ITEM, "expression", null));
    assertThrows(NullPointerException.class, () -> new Expression.BatchFailure(0, null));
  }

  @Test
  void expressionBatchKeepsWrappedCancellationAndRawErrorSemanticsAndReleasesItsFrame() {
    for (boolean bool : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true}) {
      var p = program(bool); var reads = new AtomicInteger(); var values = numbers(); var mask = mask();
      Throwable cause = error ? new AssertionError() { public String getMessage() { reads.incrementAndGet(); throw new AssertionError("reader"); } }
          : new CancellationException() { public String getMessage() { reads.incrementAndGet(); throw new AssertionError("reader"); } };
      Expression.BatchVariables variables = (name, sample) -> { if (sample == 2) { if (error) throw (Error) cause; throw (CancellationException) cause; } return INPUT[sample]; };
      if (error) assertSame(cause, assertThrows(AssertionError.class, () -> invoke(bool, p, variables, values, mask)));
      else assertSame(cause, assertThrows(Expression.BatchFailure.class, () -> invoke(bool, p, variables, values, mask)).getCause());
      assertEquals(error ? 0 : 1, reads.get()); prefix(bool, 2, values, mask);
      var healthy = numbers(); var healthyMask = mask(); invoke(bool, p, (name, index) -> INPUT[index], healthy, healthyMask); prefix(bool, 4, healthy, healthyMask);
    }
  }

  @Test
  void failedBatchCannotLeakInputsIntoLaterLazyBranchesOrBypassPreflight() {
    for (boolean bool : new boolean[] {false, true}) {
      var p = Expression.compile(bool ? "flag && x>0" : "flag ? x+x : 7", Map.of("flag", Expression.Type.Boolean, "x", Expression.Type.Scalar));
      var reads = new AtomicInteger(); var cause = cause("UNREADABLE_ERROR", reads); var values = numbers(); var mask = mask();
      var failure = assertThrows(Expression.BatchFailure.class, () -> invoke(bool, p, (name, index) -> {
        if (name.equals("flag")) return 1; if (index == 1) throw cause; return 3;
      }, values, mask));
      assertEquals(1, failure.sampleIndex); assertSame(cause, failure.getCause());
      var healthy = numbers(); var healthyMask = mask(); var calls = new AtomicInteger();
      invoke(bool, p, (name, index) -> { calls.incrementAndGet(); assertEquals("flag", name); return 0; }, healthy, healthyMask);
      for (int i = 1; i <= 4; i++) if (bool) assertFalse(healthyMask[i]); else assertEquals(7, healthy[i]);
      assertEquals(4, calls.get()); assertEquals(1, reads.get()); calls.set(0);
      Expression.BatchVariables invalid = (name, index) -> { calls.incrementAndGet(); throw cause; };
      if (bool) assertThrows(IllegalArgumentException.class, () -> p.booleans(8, invalid, healthyMask, 1));
      else assertThrows(IllegalArgumentException.class, () -> p.scalars(8, invalid, healthy, 1));
      assertEquals(0, calls.get()); assertEquals(1, reads.get());
    }
  }
}
