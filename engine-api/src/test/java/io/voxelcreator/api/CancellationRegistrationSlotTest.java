package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class CancellationRegistrationSlotTest {
  @Test
  void installedAndLateRegistrationsCloseExactlyOnce() {
    for (boolean late : new boolean[] {false, true}) {
      var calls = new AtomicInteger();
      var slot = new CancellationRegistrationSlot();
      if (late) slot.close();
      slot.install(calls::incrementAndGet);
      slot.close();
      slot.close();
      assertEquals(1, calls.get());
    }
  }

  @Test
  void duplicateInstallationCannotReplaceTheOwnedRegistration() {
    var slot = new CancellationRegistrationSlot();
    var first = new AtomicInteger();
    var duplicate = new AtomicInteger();
    slot.install(first::incrementAndGet);
    slot.install(duplicate::incrementAndGet);
    assertEquals(0, first.get());
    assertEquals(1, duplicate.get());
    slot.close();
    assertEquals(1, first.get());
    assertThrows(NullPointerException.class, () -> slot.install(null));
  }

  @Test
  void concurrentInstallationAndRetirementNeverLoseTheRegistration() throws Exception {
    try (var tasks = Executors.newFixedThreadPool(2)) {
      for (int i = 0; i < 100; i++) {
        var slot = new CancellationRegistrationSlot();
        var calls = new AtomicInteger();
        var start = new CountDownLatch(1);
        var install =
            tasks.submit(
                () -> {
                  start.await();
                  slot.install(calls::incrementAndGet);
                  return null;
                });
        var retire =
            tasks.submit(
                () -> {
                  start.await();
                  slot.close();
                  return null;
                });
        start.countDown();
        install.get(3, TimeUnit.SECONDS);
        retire.get(3, TimeUnit.SECONDS);
        slot.close();
        assertEquals(1, calls.get());
      }
    }
  }

  @Test
  void requestPublicationBeforeInstallationClosesTheLateListener() {
    var token = new CancellationToken();
    var slot = new CancellationRegistrationSlot();
    var called = new AtomicInteger();
    var future = new RequestFuture<String>(slot::close);
    future.whenComplete((value, failure) -> slot.install(token.onCancel(called::incrementAndGet)));
    future.complete("value");
    assertTrue(token.cancel());
    assertEquals(0, called.get());
    assertEquals("value", future.join());
  }
}
