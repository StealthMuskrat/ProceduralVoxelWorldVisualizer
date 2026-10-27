package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PreviewAdmissionTest {
  @Test
  void oneRejectedViewDoesNotBlockAnotherAndRetryRechecksNewBudgets() {
    var first = new PreviewAdmission();
    var second = new PreviewAdmission();
    assertFalse(first.check(0, 256, "Grayscale", 65535, 96, 8L << 20).allowed());
    assertTrue(first.blocked());
    assertFalse(second.blocked());
    assertTrue(second.check(0, 256, "Heatmap", 65536, 96, 4L << 20).allowed());
    // Changing settings alone cannot create an automatic retry loop.
    assertFalse(first.check(0, 256, "Grayscale", 65536, 96, 4L << 20).allowed());
    first.changed();
    assertTrue(first.check(0, 256, "Grayscale", 65536, 96, 4L << 20).allowed());
    assertFalse(first.blocked());
  }

  @Test
  void cellAndDimensionBoundsDistinguish2DAnd3D() {
    var gate = new PreviewAdmission();
    assertTrue(gate.check(0, 256, "Grayscale", 65536, 32, Long.MAX_VALUE).allowed());
    assertFalse(gate.check(1, 33, "Volume", Long.MAX_VALUE, 32, Long.MAX_VALUE).allowed());
    gate.changed();
    assertTrue(gate.check(1, 32, "Volume", 32768, 32, Long.MAX_VALUE).allowed());
    assertFalse(gate.check(2, 32, "Final", 32767, 32, Long.MAX_VALUE).allowed());
  }

  @Test
  void conservativeByteEstimatesHaveExactInclusiveAdmissionBoundaries() {
    for (int kind : new int[] {0, 1, 2})
      for (String mode : new String[] {"Grayscale", "Volume", "Point Cloud", "Final"}) {
        var gate = new PreviewAdmission();
        long expected =
            kind == 0
                ? 64L * 16 * 16
                : (mode.equals("Volume") ? 12L : mode.equals("Point Cloud") ? 96L : 3024L) * 4096;
        var admitted = gate.check(kind, 16, mode, Long.MAX_VALUE, 256, expected);
        assertTrue(admitted.allowed());
        assertEquals(expected, admitted.workingBytes());
        assertFalse(gate.check(kind, 16, mode, Long.MAX_VALUE, 256, expected - 1).allowed());
      }
  }

  @Test
  void isosurfaceAdmissionIncludesCornerGridAndPeakMeshGrowth() {
    var gate = new PreviewAdmission();
    long expected = 3 * 1008L * 16 * 16 * 16 + 8 * 17L * 17 * 17 + 65536;
    assertEquals(
        expected, gate.check(1, 16, "Isosurface", Long.MAX_VALUE, 256, expected).workingBytes());
    assertFalse(gate.check(1, 16, "Isosurface", Long.MAX_VALUE, 256, expected - 1).allowed());
  }

  @Test
  void queueFailureRequiresChangedRequestAndRetainsReasonAcrossPolling() {
    var gate = new PreviewAdmission();
    gate.queueFull();
    for (int i = 0; i < 10; i++) {
      var result = gate.check(0, 256, "Grayscale", 65536, 256, 4L << 20);
      assertFalse(result.allowed());
      assertTrue(result.message().contains("queue is full"));
    }
    gate.changed();
    assertTrue(gate.check(0, 256, "Grayscale", 65536, 256, 4L << 20).allowed());
  }

  @Test
  void maximumExtentUsesLongArithmeticAndInvalidRequestsRejectBeforeBlocking() {
    var gate = new PreviewAdmission();
    assertEquals(
        3024L * 256 * 256 * 256,
        gate.check(2, 256, "Final", Long.MAX_VALUE, 256, Long.MAX_VALUE).workingBytes());
    assertThrows(
        IllegalArgumentException.class,
        () -> gate.check(1, 257, "Volume", Long.MAX_VALUE, 256, Long.MAX_VALUE));
    assertThrows(
        IllegalArgumentException.class,
        () -> gate.check(0, 256, "Grayscale", -1, 256, Long.MAX_VALUE));
    assertFalse(gate.blocked());
  }
}
