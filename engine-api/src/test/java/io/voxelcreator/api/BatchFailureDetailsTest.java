package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred public sampler failure details, coordinates and successful-prefix ownership. */
class BatchFailureDetailsTest {
  private enum Route { GRID2, GRID3, POINTS2, POINTS3 }
  private record Fixture(double[] xs, double[] ys, double[] zs, double[] values, int[] addresses) {}

  private static Fixture fixture(Route route) {
    boolean three = route == Route.GRID3 || route == Route.POINTS3;
    return new Fixture(new double[] {-2, -1, -2, -1},
        three ? new double[] {-3, -3, -2, -2} : new double[] {0, 0, 0, 0},
        three ? new double[] {4, 4, 4, 4} : new double[] {4, 4, 5, 5},
        three ? new double[] {368, 369, 378, 379} : new double[] {8, 10, 11, 13},
        route == Route.GRID2 ? new int[] {1, 2, 5, 6} : route == Route.GRID3 ? new int[] {1, 2, 6, 7} : new int[] {2, 3, 4, 5});
  }

  private static double[] padded(double[] values) { return new double[] {-99, values[0], values[1], values[2], values[3], -99}; }
  private static double[] destination() { var result = new double[10]; Arrays.fill(result, -99); return result; }
  private static void invoke(Route route, NoiseSampler2D two, NoiseSampler3D three, double[] result, CancellationToken token) {
    var f = fixture(route);
    switch (route) {
      case GRID2 -> two.sampleGrid(-2, 4, 2, 2, 1, 1, result, 1, 4, token);
      case GRID3 -> three.sampleGrid(-2, -3, 4, 2, 2, 1, 1, 1, 1, result, 1, 3, 5, token);
      case POINTS2 -> two.samplePoints(padded(f.xs), padded(f.zs), 1, 4, result, 2, token);
      case POINTS3 -> three.samplePoints(padded(f.xs), padded(f.ys), padded(f.zs), 1, 4, result, 2, token);
    }
  }
  private static void prefix(Route route, int count, double[] result) {
    var f = fixture(route); var expected = destination();
    for (int i = 0; i < count; i++) expected[f.addresses[i]] = f.values[i];
    assertArrayEquals(expected, result);
  }
  private static String location(Route route, int index) {
    var f = fixture(route); return "Sample " + index + " at " + f.xs[index] + "," + f.ys[index] + "," + f.zs[index] + ": ";
  }
  private static BatchSamplingException fail(Route route, int index, RuntimeException cause, double[] result, AtomicInteger calls) {
    var f = fixture(route);
    NoiseSampler2D two = (x, z) -> {
      int at = calls.getAndIncrement(); assertEquals(f.xs[at], x); assertEquals(f.zs[at], z);
      if (at == index) throw cause; return 2 * x + 3 * z;
    };
    NoiseSampler3D three = (x, y, z) -> {
      int at = calls.getAndIncrement(); assertEquals(f.xs[at], x); assertEquals(f.ys[at], y); assertEquals(f.zs[at], z);
      if (at == index) throw cause; return x + 10 * y + 100 * z;
    };
    var failure = assertThrows(BatchSamplingException.class, () -> invoke(route, two, three, result, new CancellationToken()));
    assertSame(cause, failure.getCause()); assertEquals(index, failure.sampleIndex);
    assertEquals(f.xs[index], failure.x); assertEquals(f.ys[index], failure.y); assertEquals(f.zs[index], failure.z);
    assertEquals(index + 1, calls.get()); prefix(route, index, result); assertEquals(0, cause.getSuppressed().length);
    return failure;
  }

  @Test
  void allFourRoutesKeepCauseCoordinatesAndPrefixWhenMessageAccessThrowsRuntimeOrError() {
    for (var route : Route.values()) for (int index = 0; index < 4; index++) for (boolean error : new boolean[] {false, true}) {
      var reads = new AtomicInteger(); var cause = new RuntimeException() {
        public String getMessage() { reads.incrementAndGet(); if (error) throw new AssertionError("reader"); throw new IllegalStateException("reader"); }
        public String toString() { throw new AssertionError("Host toString called"); }
      };
      var failure = fail(route, index, cause, destination(), new AtomicInteger());
      assertEquals(location(route, index) + cause.getClass().getName(), failure.getMessage()); assertEquals(1, reads.get());
    }
  }

  @Test
  void mutableDescriptionIsFrozenOnceAndAHealthySuccessorBatchKeepsItsOwnBuffer() {
    for (var route : Route.values()) {
      var reads = new AtomicInteger(); var cause = new RuntimeException() { public String getMessage() { return "message-" + reads.incrementAndGet(); } };
      var result = destination(); var failure = fail(route, 2, cause, result, new AtomicInteger());
      for (int i = 0; i < 3; i++) assertEquals(location(route, 2) + "message-1", failure.getMessage());
      assertEquals(1, reads.get()); var healthy = destination();
      invoke(route, (x, z) -> 2 * x + 3 * z, (x, y, z) -> x + 10 * y + 100 * z, healthy, new CancellationToken());
      prefix(route, 4, healthy); prefix(route, 2, result); assertEquals(1, reads.get());
    }
  }

  @Test
  void nullBlankAndLongDescriptionsStayReadableAndDoNotSplitSurrogatePairs() {
    for (var route : Route.values()) for (String mode : List.of("NULL", "BLANK", "LONG", "ASTRAL", "LIMIT")) {
      var reads = new AtomicInteger();
      var cause = new RuntimeException() {
        public String getMessage() {
          reads.incrementAndGet(); return switch (mode) {
            case "NULL" -> null; case "BLANK" -> " \t"; case "LONG" -> "雪".repeat(3000);
            case "ASTRAL" -> "😀".repeat(2000); default -> "😀".repeat(1024);
          };
        }
        public String toString() { throw new AssertionError("Host toString called"); }
      };
      String detail = switch (mode) {
        case "NULL", "BLANK" -> cause.getClass().getName(); case "LONG" -> "雪".repeat(2047) + "…";
        case "ASTRAL" -> "😀".repeat(1023) + "…"; default -> "😀".repeat(1024);
      };
      var failure = fail(route, 1, cause, destination(), new AtomicInteger());
      assertEquals(location(route, 1) + detail, failure.getMessage()); assertTrue(detail.length() <= 2048); assertEquals(1, reads.get());
    }
  }

  @Test
  void samplerCancellationAndErrorsKeepIdentityWithoutBatchWrappingOrTextAccess() {
    for (var route : Route.values()) for (boolean error : new boolean[] {false, true}) {
      var reads = new AtomicInteger(); var calls = new AtomicInteger(); var result = destination();
      Throwable cause = error ? new AssertionError() { public String getMessage() { reads.incrementAndGet(); throw new AssertionError("reader"); } }
          : new CancellationException() { public String getMessage() { reads.incrementAndGet(); throw new AssertionError("reader"); } };
      NoiseSampler2D two = (x, z) -> { if (calls.getAndIncrement() == 2) { if (error) throw (Error) cause; throw (CancellationException) cause; } return 2 * x + 3 * z; };
      NoiseSampler3D three = (x, y, z) -> { if (calls.getAndIncrement() == 2) { if (error) throw (Error) cause; throw (CancellationException) cause; } return x + 10 * y + 100 * z; };
      var failure = error ? assertThrows(AssertionError.class, () -> invoke(route, two, three, result, new CancellationToken()))
          : assertThrows(CancellationException.class, () -> invoke(route, two, three, result, new CancellationToken()));
      assertSame(cause, failure); assertEquals(0, reads.get()); assertEquals(3, calls.get()); prefix(route, 2, result);
    }
  }

  @Test
  void invalidCapacityAndPrecancelledTokensDoNotInvokeSamplerOrReadItsFault() {
    for (var route : Route.values()) {
      var calls = new AtomicInteger(); var reads = new AtomicInteger();
      var cause = new RuntimeException() { public String getMessage() { reads.incrementAndGet(); throw new AssertionError("reader"); } };
      NoiseSampler2D two = (x, z) -> { calls.incrementAndGet(); throw cause; };
      NoiseSampler3D three = (x, y, z) -> { calls.incrementAndGet(); throw cause; };
      assertThrows(IllegalArgumentException.class, () -> invoke(route, two, three, new double[0], new CancellationToken()));
      var token = new CancellationToken(); token.cancel(); var result = destination();
      assertThrows(CancellationException.class, () -> invoke(route, two, three, result, token));
      assertEquals(0, calls.get()); assertEquals(0, reads.get()); prefix(route, 0, result);
    }
  }

  @Test
  void publicConstructorKeepsCoordinateBitsAndOrdinaryTextAndRejectsNullCause() {
    var cause = new IllegalStateException("original");
    var failure = new BatchSamplingException(Long.MAX_VALUE, -0.0, Double.MAX_VALUE, -2.5, cause);
    assertSame(cause, failure.getCause()); assertEquals(Long.MAX_VALUE, failure.sampleIndex);
    assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(failure.x));
    assertEquals(Double.MAX_VALUE, failure.y); assertEquals(-2.5, failure.z);
    assertEquals("Sample " + Long.MAX_VALUE + " at -0.0," + Double.MAX_VALUE + ",-2.5: original", failure.getMessage());
    var envelope = new CompletionException("host envelope", cause) {
      public Throwable getCause() { throw new AssertionError("Cause accessor called"); }
    };
    var wrapped = new BatchSamplingException(0, 1, 2, 3, envelope);
    assertSame(envelope, wrapped.getCause()); assertEquals("Sample 0 at 1.0,2.0,3.0: host envelope", wrapped.getMessage());
    assertThrows(NullPointerException.class, () -> new BatchSamplingException(0, 0, 0, 0, null));
  }
}
