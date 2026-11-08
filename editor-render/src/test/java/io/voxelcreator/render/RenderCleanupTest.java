package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred CPU retirement definitions; actual GL owner detachment remains source-traced. */
class RenderCleanupTest {
  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }

  @Test
  void firstFaultKeepsIdentityWhileEveryCapturedCallbackIsAttempted() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) {
      Throwable first = error ? new AssertionError("shadow") : new IllegalStateException("shadow");
      Throwable second = error ? new IllegalStateException("scene") : new AssertionError("scene");
      var calls = new ArrayList<String>(); var released = new AtomicInteger();
      var failure = assertThrows(Throwable.class, () -> RenderCleanup.run(
          () -> { calls.add("shadow"); if (!after) raise(first); released.incrementAndGet(); raise(first); },
          () -> { calls.add("scene"); released.incrementAndGet(); raise(second); },
          () -> calls.add("tail")));
      assertSame(first, failure); assertEquals(List.of("shadow", "scene", "tail"), calls);
      assertEquals(after ? 2 : 1, released.get());
      assertEquals(0, first.getSuppressed().length); assertEquals(0, second.getSuppressed().length);
    }
  }

  @Test
  void sharedUnreadableFaultIsNeitherDescribedNorSuppressed() {
    var reads = new AtomicInteger(); var calls = new AtomicInteger();
    var failure = new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("text"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text"); }
    };
    assertSame(failure, assertThrows(AssertionError.class, () -> RenderCleanup.run(
        () -> { calls.incrementAndGet(); throw failure; },
        () -> { calls.incrementAndGet(); throw failure; })));
    assertEquals(2, calls.get()); assertEquals(0, reads.get()); assertEquals(0, failure.getSuppressed().length);
  }

  @Test
  void reentrantRetirementKeepsOuterCapturedCallbacksInOrder() {
    var calls = new ArrayList<String>(); var successor = new AtomicInteger();
    RenderCleanup.run(
        () -> { calls.add("old shadow"); RenderCleanup.run(() -> calls.add("nested")); successor.set(23); },
        () -> calls.add("old scene"));
    assertEquals(List.of("old shadow", "nested", "old scene"), calls); assertEquals(23, successor.get());
  }

  @Test
  void emptyAndHealthyRetirementsReturnNormallyWithoutReplayingCallbacks() {
    var calls = new AtomicInteger(); assertDoesNotThrow(() -> RenderCleanup.run());
    assertDoesNotThrow(() -> RenderCleanup.run(calls::incrementAndGet, calls::incrementAndGet));
    assertEquals(2, calls.get());
  }
}
