package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class CancellationRegistrationTest {
  @Test
  void registrationsAreIndependentDisposableAndCalledOnce() {
    var token = new CancellationToken();
    var calls = new AtomicInteger();
    var a = token.onCancel(calls::incrementAndGet);
    token.onCancel(calls::incrementAndGet);
    a.close();
    a.close();
    assertTrue(token.cancel());
    assertFalse(token.cancel());
    assertEquals(1, calls.get());
    token.onCancel(calls::incrementAndGet);
    assertEquals(2, calls.get());
  }

  @Test
  void listenerFailureDoesNotPreventOtherCancellationCallbacks() {
    var token = new CancellationToken();
    var called = new AtomicBoolean();
    token.onCancel(
        () -> {
          throw new IllegalArgumentException();
        });
    token.onCancel(() -> called.set(true));
    assertTrue(token.cancel());
    assertTrue(called.get());
  }

  @Test
  void parentSuppliersRemainCooperativeAndInitialCancellationIsObserved() {
    var parent = new AtomicBoolean();
    var token = new CancellationToken(parent::get);
    var calls = new AtomicInteger();
    token.onCancel(calls::incrementAndGet);
    parent.set(true);
    assertTrue(token.isCancelled());
    assertEquals(0, calls.get());
    token.onCancel(calls::incrementAndGet);
    assertEquals(1, calls.get());
    assertThrows(CancellationException.class, token::throwIfCancelled);
    token.cancel();
    assertEquals(2, calls.get());
  }

  @Test
  void cancellationAndRegistrationRaceNeverLosesOrDuplicatesCallback() throws Exception {
    try (var worker = Executors.newFixedThreadPool(2)) {
      for (int i = 0; i < 100; i++) {
        var token = new CancellationToken();
        var calls = new AtomicInteger();
        var start = new CountDownLatch(1);
        var register =
            worker.submit(
                () -> {
                  start.await();
                  token.onCancel(calls::incrementAndGet);
                  return null;
                });
        var cancel =
            worker.submit(
                () -> {
                  start.await();
                  token.cancel();
                  return null;
                });
        start.countDown();
        register.get(2, TimeUnit.SECONDS);
        cancel.get(2, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
      }
    }
  }
}
