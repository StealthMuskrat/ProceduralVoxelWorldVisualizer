package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ViewportFlightTest {
  @Test
  void inspectorSuspendsCaptureUntilClosedInAnEligibleViewport() {
    var flight = new ViewportFlight();
    assertEquals(ViewportFlight.Mode.ORBIT, flight.mode());
    flight.enter();
    flight.inspect();
    for (boolean eligible : new boolean[] {true, false, true}) {
      flight.reconcile(eligible, true);
      assertEquals(ViewportFlight.Mode.INSPECTOR, flight.mode());
      assertFalse(flight.captured());
    }
    flight.reconcile(true, false);
    assertTrue(flight.captured());
  }

  @Test
  void closingInspectorElsewhereNeverStealsCaptureWhenFocusReturns() {
    var flight = new ViewportFlight();
    flight.enter();
    flight.inspect();
    flight.reconcile(false, false);
    assertEquals(ViewportFlight.Mode.PAUSED, flight.mode());
    for (int i = 0; i < 5; i++) flight.reconcile(true, false);
    assertFalse(flight.captured());
    flight.enter();
    assertTrue(flight.captured());
  }

  @Test
  void lostEligibilityOrTabChangeRequiresExplicitResume() {
    var flight = new ViewportFlight();
    flight.enter();
    flight.reconcile(false, false);
    flight.reconcile(true, false);
    assertEquals(ViewportFlight.Mode.PAUSED, flight.mode());
    flight.enter();
    flight.pause();
    flight.reconcile(true, false);
    assertEquals(ViewportFlight.Mode.PAUSED, flight.mode());
    flight.enter();
    flight.inspect();
    flight.pause();
    flight.reconcile(true, false);
    assertEquals(ViewportFlight.Mode.PAUSED, flight.mode());
  }

  @Test
  void exitIsFinalUntilAnotherExplicitEntryFromEveryMode() {
    for (var mode : ViewportFlight.Mode.values()) {
      var flight = new ViewportFlight();
      if (mode != ViewportFlight.Mode.ORBIT) flight.enter();
      if (mode == ViewportFlight.Mode.INSPECTOR) flight.inspect();
      if (mode == ViewportFlight.Mode.PAUSED) flight.pause();
      flight.exit();
      flight.reconcile(true, false);
      flight.inspect();
      flight.pause();
      assertEquals(ViewportFlight.Mode.ORBIT, flight.mode());
      flight.enter();
      assertTrue(flight.captured());
    }
  }
}
