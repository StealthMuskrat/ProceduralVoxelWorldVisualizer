package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.ProjectData.Curve;
import org.junit.jupiter.api.Test;

class CurvePreviewTest {
  @Test
  void finiteObservationsRetainCanonicalRawValuesAndEndpoints() {
    var c = new Curve();
    c.points.getFirst().y = -3;
    c.points.getLast().y = 5;
    var p = CurvePreview.capture(new ScalarCurve(c), 0, 1);
    assertEquals(513, p.size());
    assertEquals(0, p.failures());
    assertNull(p.firstFailure());
    for (int i = 0; i < p.size(); i++) {
      assertTrue(p.valid(i));
      assertEquals(i / 512., p.input(i));
      assertEquals(-3 + 8 * i / 512., p.value(i));
    }
  }

  @Test
  void extensionOverflowIsAGapAndLaterFiniteSamplesRemainAvailable() {
    var c = new Curve();
    c.outsideDomain = "EXTEND";
    c.points.getLast().y = 1e308;
    var p = CurvePreview.capture(new ScalarCurve(c), -4.5, 5.5);
    assertTrue(p.failures() > 0 && p.failures() < p.size());
    assertFalse(p.valid(0));
    assertEquals(0, p.firstFailure().sample());
    assertEquals(-4.5, p.firstFailure().input());
    assertTrue(p.firstFailure().message().contains("Nonfinite"));
    assertTrue(p.valid(256));
    assertEquals(.5, p.input(256));
    assertEquals(5e307, p.value(256));
  }

  @Test
  void repeatAndMirrorTinyDomainsRemainFiniteAfterWrappingFallback() {
    for (String outside : new String[] {"REPEAT", "MIRROR"}) {
      var c = new Curve();
      c.max = Double.MIN_VALUE;
      c.points.getLast().x = Double.MIN_VALUE;
      c.points.getLast().y = Double.MIN_VALUE;
      c.outsideDomain = outside;
      var p = CurvePreview.capture(new ScalarCurve(c), -1, 1);
      assertEquals(0, p.failures());
      assertTrue(p.valid(256));
      assertEquals(0, p.value(256));
      assertNull(p.firstFailure());
      for (int i = 0; i < p.size(); i++) assertEquals(0, p.value(i));
    }
  }

  @Test
  void normalizedDisplaySamplingDoesNotOverflowTheIndexProduct() {
    var p = CurvePreview.capture(new ScalarCurve(new Curve()), 0, 8e307);
    assertEquals(0, p.failures());
    assertEquals(4e307, p.input(256));
    assertEquals(8e307, p.input(512));
    assertEquals(0, p.value(0));
    assertEquals(1, p.value(512));
    for (int i = 0; i < p.size(); i++) assertTrue(Double.isFinite(p.input(i)));
  }

  @Test
  void invalidViewsRejectBeforeSamplingAndSnapshotsRemainOwned() {
    var c = new Curve();
    var p = CurvePreview.capture(new ScalarCurve(c), 0, 1);
    c.points.getLast().y = 9;
    assertEquals(1, p.value(512));
    assertThrows(
        IllegalArgumentException.class, () -> CurvePreview.capture(new ScalarCurve(c), 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> CurvePreview.capture(new ScalarCurve(c), Double.NaN, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> CurvePreview.capture(new ScalarCurve(c), -1e308, 1e308));
    assertThrows(IllegalArgumentException.class, () -> CurvePreview.capture(null, 0, 1));
  }
}
