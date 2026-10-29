package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred analytical floating/docked lifetimes and monitor-coordinate selection. */
class FloatingWindowBoundsTest {
  private final DialogBounds.Rect main = new DialogBounds.Rect(0, 0, 800, 600);
  private final DialogBounds.Rect left = new DialogBounds.Rect(-1000, -500, 1000, 700);

  @Test
  void nativeFirstUseSettingsAreNotReplacedBeforeTheFirstObservation() {
    assertNull(new FloatingWindowBounds(840, 760).prepare(false, main));
  }

  @Test
  void validMeasuredUserSizeAndPositionRemainStableAfterInitialAreaObservation() {
    var geometry = new FloatingWindowBounds(840, 760);
    var original = new DialogBounds.Rect(20, 30, 400, 300);
    geometry.observe(original, 1, false, false);
    geometry.prepare(false, main);
    var plan = geometry.prepare(false, main);
    assertEquals(original, plan.bounds());
    assertFalse(plan.position());
    assertFalse(plan.size());
  }

  @Test
  void oversizedOffscreenWindowsFitInOneCorrection() {
    var geometry = new FloatingWindowBounds(840, 760);
    geometry.observe(new DialogBounds.Rect(-200, -300, 840, 760), 1, false, false);
    var plan = geometry.prepare(false, new DialogBounds.Rect(0, 0, 360, 240));
    assertEquals(new DialogBounds.Rect(8, 8, 344, 224), plan.bounds());
    assertTrue(plan.position());
    assertTrue(plan.size());
  }

  @Test
  void pointerDownDefersCorrectionUntilReleaseWithoutErasingMeasuredBounds() {
    var geometry = new FloatingWindowBounds(840, 760);
    var original = new DialogBounds.Rect(-200, -300, 840, 760);
    geometry.observe(original, 1, false, false);
    assertNull(geometry.prepare(true, main));
    assertEquals(original, geometry.bounds());
    assertEquals(new DialogBounds.Rect(8, 8, 784, 584), geometry.prepare(false, main).bounds());
  }

  @Test
  void dockedObservationsDoNotReplaceFloatingMeasurementsOrConstrainDockNodes() {
    var geometry = new FloatingWindowBounds(840, 760);
    var original = new DialogBounds.Rect(20, 30, 400, 300);
    geometry.observe(original, 1, false, false);
    geometry.observe(main, 1, true, false);
    assertEquals(original, geometry.bounds());
    assertNull(geometry.prepare(false, main));
  }

  @Test
  void collapsedTitleBarsDoNotOverwriteTheLastExpandedHeight() {
    var geometry = new FloatingWindowBounds(840, 760);
    var original = new DialogBounds.Rect(20, 30, 400, 300);
    geometry.observe(original, 1, false, false);
    geometry.observe(new DialogBounds.Rect(20, 30, 400, 25), 1, false, true);
    assertEquals(original, geometry.bounds());
    assertNull(geometry.prepare(false, main));
  }

  @Test
  void undockingResumesFittingFromTheNewNativeFloatingRectangle() {
    var geometry = new FloatingWindowBounds(840, 760);
    geometry.observe(main, 1, true, false);
    geometry.observe(new DialogBounds.Rect(780, 550, 400, 300), -123, false, false);
    assertEquals(-123, geometry.viewport());
    assertEquals(new DialogBounds.Rect(392, 292, 400, 300), geometry.prepare(false, main).bounds());
  }

  @Test
  void unavailableWorkAreasDoNotInventDetachedViewportConstraints() {
    var geometry = new FloatingWindowBounds(840, 760);
    geometry.observe(main, 1, false, false);
    assertNull(geometry.prepare(false, null));
    assertEquals(main, geometry.bounds());
  }

  @Test
  void relativeMainCoordinatesNeverMixWithAbsoluteMonitorCoordinates() {
    assertEquals(main, FloatingWindowBounds.work(false, true, main, left, List.of(left)));
  }

  @Test
  void absoluteCoordinatesChooseTheLargestActualIntersection() {
    var window = new DialogBounds.Rect(-100, 20, 600, 300);
    assertEquals(main, FloatingWindowBounds.work(true, false, main, window, List.of(left, main)));
  }

  @Test
  void disconnectedWindowsChooseNearestRectangleGapInsteadOfCenterDistance() {
    var far = new DialogBounds.Rect(2000, 0, 800, 600);
    var window = new DialogBounds.Rect(900, 20, 400, 300);
    assertEquals(main, FloatingWindowBounds.work(true, false, main, window, List.of(far, main)));
  }

  @Test
  void equalOverlapAndDistanceRetainMonitorInventoryOrder() {
    var a = new DialogBounds.Rect(-800, 0, 800, 600);
    var window = new DialogBounds.Rect(-100, 20, 200, 300);
    assertEquals(a, FloatingWindowBounds.work(true, false, main, window, List.of(a, main)));
    assertEquals(main, FloatingWindowBounds.work(true, false, main, window, List.of(main, a)));
  }

  @Test
  void anEmptyAbsoluteInventoryFallsBackOnlyForTheMainHost() {
    assertNull(FloatingWindowBounds.work(true, false, main, left, List.of()));
    assertEquals(main, FloatingWindowBounds.work(true, true, main, left, List.of()));
  }

  @Test
  void removedMonitorRecoveryUsesRemainingMonitorBoundsWithoutSelfShrinking() {
    var geometry = new FloatingWindowBounds(840, 760);
    var window = new DialogBounds.Rect(-800, -300, 600, 400);
    geometry.observe(window, -123, false, false);
    var work = FloatingWindowBounds.work(true, false, main, window, List.of(main));
    var corrected = geometry.prepare(false, work).bounds();
    assertEquals(new DialogBounds.Rect(8, 8, 600, 400), corrected);
    geometry.observe(corrected, -123, false, false);
    var next = geometry.prepare(false, work);
    assertEquals(corrected, next.bounds());
    assertFalse(next.position());
    assertFalse(next.size());
  }

  @Test
  void invalidObservationDoesNotReplaceAValidOwnerOrMeasurement() {
    var geometry = new FloatingWindowBounds(840, 760);
    geometry.observe(main, 1, false, false);
    assertThrows(IllegalArgumentException.class, () -> geometry.observe(left, 0, false, false));
    assertThrows(NullPointerException.class, () -> geometry.observe(null, 2, false, false));
    assertEquals(main, geometry.bounds());
    assertEquals(1, geometry.viewport());
  }

  @Test
  void extremeFiniteRectanglesKeepMonitorSelectionFiniteAndOrdered() {
    var positive = new DialogBounds.Rect(Float.MAX_VALUE / 2, 0, Float.MAX_VALUE / 4, 600);
    var negative = new DialogBounds.Rect(-Float.MAX_VALUE / 2, 0, Float.MAX_VALUE / 4, 600);
    assertEquals(
        positive,
        FloatingWindowBounds.work(true, false, main, positive, List.of(negative, positive)));
  }
}
