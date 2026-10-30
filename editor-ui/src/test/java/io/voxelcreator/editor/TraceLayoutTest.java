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

  @Test
  void containedWorkspaceNeverAddsFiveFramesToAnAlreadyPartitionedInspector() {
    assertEquals(240, TraceLayout.contained(240));
    assertEquals(12.75f, TraceLayout.contained(12.75f));
    assertEquals(1, TraceLayout.contained(0));
    assertEquals(1, TraceLayout.contained(-200));
    assertEquals(Float.MAX_VALUE, TraceLayout.contained(Float.MAX_VALUE));
    for (float scale : new float[] {.5f, 1, 2.5f, 6.25f})
      assertEquals(70 * scale, TraceLayout.contained(70 * scale));
  }

  @Test
  void containedWorkspaceRejectsNonfiniteNativeGeometry() {
    for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
      assertThrows(IllegalArgumentException.class, () -> TraceLayout.contained(invalid));
  }
}
