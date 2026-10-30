package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TraceLayoutTest {
  @Test
  void listsUseMeasuredRowsWithinTheirOwnBodyBudgets() {
    assertEquals(60, TraceLayout.steps(1000, 20));
    assertEquals(100, TraceLayout.values(1000, 20));
    assertEquals(20, TraceLayout.steps(100, 20));
    assertEquals(30, TraceLayout.values(100, 20));
    assertEquals(1, TraceLayout.steps(1, 81.25f));
    assertEquals(1, TraceLayout.values(1, 81.25f));
  }

  @Test
  void workspaceReservesFiveFramesAndFiniteExtremesDoNotOverflow() {
    assertEquals(100, TraceLayout.workspace(-50, 20));
    assertEquals(500, TraceLayout.workspace(500, 20));
    assertEquals(Float.MAX_VALUE, TraceLayout.workspace(Float.MAX_VALUE, Float.MAX_VALUE));
    assertTrue(Float.isFinite(TraceLayout.values(Float.MAX_VALUE, Float.MAX_VALUE)));
  }

  @Test
  void invalidNativeMetricsRejectBeforeAllocation() {
    assertThrows(IllegalArgumentException.class, () -> TraceLayout.workspace(Float.NaN, 20));
    assertThrows(IllegalArgumentException.class, () -> TraceLayout.workspace(100, 0));
    assertThrows(IllegalArgumentException.class, () -> TraceLayout.steps(-1, 20));
    assertThrows(
        IllegalArgumentException.class, () -> TraceLayout.values(100, Float.POSITIVE_INFINITY));
  }
}
