package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred analytical chrome partition, priority, scale and text-bound definitions. */
class StatusBarLayoutTest {
  private static final StatusBarLayout.Metrics METRICS =
      new StatusBarLayout.Metrics(13, 19, 8, 80, List.of(60f, 26f), List.of(140f, 90f, 35f));

  private static StatusBarLayout.Plan plan(float width, float height) {
    return StatusBarLayout.prepare(new DialogBounds.Rect(0, 0, width, height), METRICS);
  }

  @Test
  void wideBarsReserveOneMeasuredActionRowAndLeaveTheDockAboveIt() {
    var p = plan(800, 600);
    assertFalse(p.twoRows());
    assertEquals(new StatusBarLayout.Rect(0, 573, 800, 27), p.bar());
    assertEquals(new StatusBarLayout.Rect(0, 0, 800, 573), p.dock());
    assertEquals(new StatusBarLayout.Rect(652, 577, 140, 19), p.notifications());
    assertEquals(new StatusBarLayout.Rect(584, 577, 60, 19), p.settings());
    assertEquals(new StatusBarLayout.Rect(8, 580, 568, 13), p.status());
  }

  @Test
  void narrowBarsMoveStatusOntoASeparateRowWithoutOverlayingTheDock() {
    var p = plan(300, 240);
    assertTrue(p.twoRows());
    assertEquals(new StatusBarLayout.Rect(0, 192, 300, 48), p.bar());
    assertEquals(new StatusBarLayout.Rect(8, 196, 284, 13), p.status());
    assertEquals(new StatusBarLayout.Rect(152, 217, 140, 19), p.notifications());
    assertEquals(new StatusBarLayout.Rect(8, 217, 60, 19), p.settings());
  }

  @Test
  void notificationLabelCompactsBeforeDroppingTheSettingsTarget() {
    var p = plan(180, 240);
    assertEquals(1, p.notificationLabel());
    assertEquals(0, p.settingsLabel());
    assertEquals(90, p.notifications().width());
    assertEquals(60, p.settings().width());
  }

  @Test
  void veryNarrowBarsUseCompactSettingsAndNotificationLabels() {
    var p = plan(100, 240);
    assertEquals(2, p.notificationLabel());
    assertEquals(1, p.settingsLabel());
    assertEquals(35, p.notifications().width());
    assertEquals(26, p.settings().width());
  }

  @Test
  void notificationHitSpaceHasPriorityWhenBothActionsCannotFit() {
    var p = plan(50, 240);
    assertEquals(34, p.notifications().width());
    assertEquals(0, p.settings().width());
    assertEquals(42, p.notifications().x() + p.notifications().width());
  }

  @Test
  void pixelAlignmentRoundsInsideFractionalWorkEdgesWithoutGapsOrOverlap() {
    var p = StatusBarLayout.prepare(new DialogBounds.Rect(10.25f, 20.5f, 800.5f, 600.25f), METRICS);
    assertEquals(new StatusBarLayout.Rect(11, 21, 799, 572), p.dock());
    assertEquals(new StatusBarLayout.Rect(11, 593, 799, 27), p.bar());
    assertEquals(p.dock().y() + p.dock().height(), p.bar().y());
  }

  @Test
  void negativeSecondaryCoordinateOriginsTranslateTheSameLayout() {
    var original = plan(800, 600);
    var moved = StatusBarLayout.prepare(new DialogBounds.Rect(-1600, -900, 800, 600), METRICS);
    assertEquals(original.bar().x() - 1600, moved.bar().x());
    assertEquals(original.bar().y() - 900, moved.bar().y());
    assertEquals(original.notifications().x() - 1600, moved.notifications().x());
  }

  @Test
  void aOnePixelHeightKeepsDockHeightPositiveAndSkipsTheInvisibleStrip() {
    var p = plan(800, 1);
    assertEquals(1, p.dock().height());
    assertFalse(p.bar().visible());
    assertFalse(p.notifications().visible());
    assertFalse(p.settings().visible());
  }

  @Test
  void tinyPositiveBarsKeepNotificationRectanglesInsideTheirHeight() {
    var p = plan(50, 5);
    assertEquals(1, p.dock().height());
    assertEquals(4, p.bar().height());
    assertEquals(p.bar().height(), p.notifications().height());
    assertEquals(p.bar().y(), p.notifications().y());
  }

  @Test
  void metricsCopyLabelWidthsAndRejectInvalidShapesOrNonfiniteValues() {
    var settings = new ArrayList<>(List.of(60f, 26f));
    var metrics = new StatusBarLayout.Metrics(13, 19, 8, 80, settings, List.of(140f, 90f, 35f));
    settings.set(0, 999f);
    assertEquals(60f, metrics.settings().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> metrics.settings().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new StatusBarLayout.Metrics(13, 19, 8, 80, List.of(60f), List.of(140f, 90f, 35f)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StatusBarLayout.Metrics(
                Float.NaN, 19, 8, 80, List.of(60f, 26f), List.of(140f, 90f, 35f)));
    assertThrows(IllegalArgumentException.class, () -> new StatusBarLayout.Rect(0, 0, -1, 1));
  }

  @Test
  void statusTextHasBoundedCodePointSafeSingleLinePresentation() {
    assertEquals("Ready", StatusBarText.clip(null, 120));
    assertEquals("Ready", StatusBarText.clip("", 120));
    assertEquals("a  b c", StatusBarText.clip("a\r\nb\tc", 120));
    assertEquals("a😀…", StatusBarText.clip("a😀z", 2));
    assertEquals("a😀", StatusBarText.clip("a😀", 2));
    assertThrows(IllegalArgumentException.class, () -> StatusBarText.clip("ready", 0));
  }

  @Test
  void finiteExtremeMetricsProduceFiniteDimensionsAndValidVariantIndices() {
    var metrics =
        new StatusBarLayout.Metrics(
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            List.of(Float.MAX_VALUE, 1f),
            List.of(Float.MAX_VALUE, 2f, 1f));
    var p = StatusBarLayout.prepare(new DialogBounds.Rect(0, 0, 800, 600), metrics);
    assertTrue(Float.isFinite(p.bar().height()));
    assertTrue(Float.isFinite(p.notifications().width()));
    assertTrue(p.notificationLabel() >= 0 && p.notificationLabel() <= 2);
  }
}
