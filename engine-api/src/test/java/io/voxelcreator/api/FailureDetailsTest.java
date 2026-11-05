package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred pure description contracts; failure objects remain host-owned. */
class FailureDetailsTest {
  @Test
  void ordinaryTextIsExactAndEachDescriptionCallsMessageOnlyOnce() {
    var calls = new AtomicInteger();
    var failure = new IllegalStateException() { public String getMessage() { return "message-" + calls.incrementAndGet(); } };
    assertEquals("message-1", FailureDetails.describe(failure)); assertEquals(1, calls.get()); assertEquals(0, failure.getSuppressed().length);
  }

  @Test
  void unreadableNullAndBlankDetailsUseClassNamesWithoutCallingHostToString() {
    for (int mode = 0; mode < 3; mode++) {
      final int kind = mode;
      var failure = new RuntimeException() {
        public String getMessage() { if (kind == 0) throw new AssertionError("reader"); return kind == 1 ? null : " \t"; }
        public String toString() { throw new AssertionError("Host toString called"); }
      };
      assertEquals(failure.getClass().getName(), FailureDetails.describe(failure)); assertEquals(0, failure.getSuppressed().length);
    }
    assertEquals("IllegalStateException", FailureDetails.describe(new IllegalStateException()));
  }

  @Test
  void oversizedTextHasOneEllipsisAndNeverSplitsASurrogatePair() {
    for (int prefix : new int[] {0, 1}) {
      String text = "x".repeat(prefix) + "😀".repeat(2000);
      var failure = new IllegalArgumentException(text); String bounded = FailureDetails.describe(failure);
      assertTrue(bounded.length() <= FailureDetails.LIMIT); assertTrue(bounded.endsWith("…"));
      assertFalse(Character.isHighSurrogate(bounded.charAt(bounded.length() - 2)));
      assertEquals(text, failure.getMessage()); assertEquals(0, failure.getSuppressed().length);
    }
    String exact = "雪".repeat(FailureDetails.LIMIT); assertEquals(exact, FailureDetails.describe(new RuntimeException(exact)));
  }

  @Test
  void causeObservationIsSingleLevelOnceAndCannotReplaceTheOriginalFailure() {
    var failure = new IllegalStateException("original"); var calls = new AtomicInteger();
    var envelope = new CompletionException("envelope", failure) { public Throwable getCause() { calls.incrementAndGet(); return failure; } };
    assertSame(failure, FailureDetails.cause(envelope)); assertEquals(1, calls.get());
    var unreadable = new CompletionException("retained envelope", failure) { public Throwable getCause() { throw new AssertionError("cause reader"); } };
    assertSame(unreadable, FailureDetails.cause(unreadable)); assertEquals("retained envelope", FailureDetails.describe(FailureDetails.cause(unreadable)));
    assertSame(failure, FailureDetails.cause(failure)); assertEquals(0, failure.getSuppressed().length);
  }
}
