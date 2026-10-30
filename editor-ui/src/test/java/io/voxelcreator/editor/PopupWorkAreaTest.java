package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred monitor/client intersection vectors and coordinate-space/recovery ownership. */
class PopupWorkAreaTest {
  private final DialogBounds.Rect main = new DialogBounds.Rect(100, 100, 800, 500);
  private final DialogBounds.Rect monitor = new DialogBounds.Rect(0, 0, 1000, 700);

  private UiMonitorWorkAreas.Snapshot absolute(DialogBounds.Rect... monitors) {
    return new UiMonitorWorkAreas.Snapshot(true, List.of(monitors));
  }

  @Test
  void relativeClientCoordinatesIgnoreUnrelatedAbsoluteMonitorOrigins() {
    var client = new DialogBounds.Rect(0, 0, 360, 240);
    var p =
        PopupWorkArea.resolve(
            2,
            1,
            client,
            main,
            null,
            new UiMonitorWorkAreas.Snapshot(
                false, List.of(new DialogBounds.Rect(-3000, -2000, 800, 600))));
    assertEquals(new PopupWorkArea.Plan(2, client, false, false), p);
  }

  @Test
  void anEmptyAbsoluteInventoryPreservesTheOwnedClientInsteadOfGuessing() {
    var p = PopupWorkArea.resolve(2, 1, main, monitor, null, absolute());
    assertEquals(new PopupWorkArea.Plan(2, main, false, false), p);
  }

  @Test
  void partialOffscreenClientsIntersectTheSelectedMonitorWorkArea() {
    var client = new DialogBounds.Rect(-100, 100, 500, 400);
    var p = PopupWorkArea.resolve(2, 1, client, main, null, absolute(monitor));
    assertEquals(
        new PopupWorkArea.Plan(2, new DialogBounds.Rect(0, 100, 400, 400), true, false), p);
  }

  @Test
  void anAnchoredSelectorChoosesItsTriggerMonitorWhenTheHostSpansBoth() {
    var left = new DialogBounds.Rect(0, 0, 500, 700);
    var right = new DialogBounds.Rect(500, 0, 500, 660);
    var p =
        PopupWorkArea.resolve(
            1, 1, monitor, main, new DialogBounds.Rect(650, 100, 100, 30), absolute(left, right));
    assertEquals(new PopupWorkArea.Plan(1, right, true, false), p);
  }

  @Test
  void fullyOffscreenDetachedOwnersRecoverToTheVisibleMainClient() {
    var p =
        PopupWorkArea.resolve(
            -123, 1, new DialogBounds.Rect(-700, 100, 300, 400), main, null, absolute(monitor));
    assertEquals(new PopupWorkArea.Plan(1, main, true, true), p);
  }

  @Test
  void unusableVisibleSliversRecoverRatherThanShrinkingAPrivateDecisionToTwentyPixels() {
    var p =
        PopupWorkArea.resolve(
            2, 1, new DialogBounds.Rect(-480, 100, 500, 400), main, null, absolute(monitor));
    assertEquals(new PopupWorkArea.Plan(1, main, true, true), p);
  }

  @Test
  void genuinelySmallFullyVisibleClientsKeepTheirExistingSmallAreaPolicy() {
    var client = new DialogBounds.Rect(50, 50, 100, 80);
    assertEquals(
        new PopupWorkArea.Plan(2, client, true, false),
        PopupWorkArea.resolve(2, 1, client, main, null, absolute(monitor)));
  }

  @Test
  void negativeSecondaryOriginsAndTaskbarWorkInsetsRemainInAbsoluteCoordinates() {
    var secondary = new DialogBounds.Rect(-1000, -700, 1000, 660);
    var client = new DialogBounds.Rect(-1200, -800, 600, 500);
    var p = PopupWorkArea.resolve(-123, 1, client, main, null, absolute(secondary, monitor));
    assertEquals(
        new PopupWorkArea.Plan(-123, new DialogBounds.Rect(-1000, -700, 400, 400), true, false), p);
  }

  @Test
  void removingAnOwnersMonitorRecomputesRecoveryFromTheCurrentInventory() {
    var old = new DialogBounds.Rect(-1000, 0, 1000, 700);
    var client = new DialogBounds.Rect(-800, 100, 500, 400);
    assertEquals(
        2, PopupWorkArea.resolve(2, 1, client, main, null, absolute(old, monitor)).viewportId());
    assertEquals(
        new PopupWorkArea.Plan(1, main, true, true),
        PopupWorkArea.resolve(2, 1, client, main, null, absolute(monitor)));
  }

  @Test
  void aCompletelyOffscreenMainClientRetainsItsSpaceUntilMainPlacementRecoversIt() {
    var offscreen = new DialogBounds.Rect(-1000, 0, 800, 600);
    var p = PopupWorkArea.resolve(2, 1, null, offscreen, null, absolute(monitor));
    assertEquals(new PopupWorkArea.Plan(1, offscreen, false, true), p);
  }

  @Test
  void removedViewportClientsFallBackWithoutReusingTheirOldGeometry() {
    assertEquals(
        new PopupWorkArea.Plan(1, main, true, true),
        PopupWorkArea.resolve(
            -123, 1, null, main, new DialogBounds.Rect(-1000, -500, 200, 30), absolute(monitor)));
  }

  @Test
  void equalMonitorOverlapAndGapRetainInventoryOrder() {
    var left = new DialogBounds.Rect(0, 0, 500, 700);
    var right = new DialogBounds.Rect(500, 0, 500, 700);
    assertEquals(
        left, PopupWorkArea.resolve(1, 1, monitor, main, null, absolute(left, right)).bounds());
    assertEquals(
        right, PopupWorkArea.resolve(1, 1, monitor, main, null, absolute(right, left)).bounds());
  }

  @Test
  void touchingEdgesHaveNoIntersectionAndDoNotProduceZeroWidthRectangles() {
    assertNull(PopupWorkArea.intersection(new DialogBounds.Rect(-200, 0, 200, 300), monitor));
    assertNull(PopupWorkArea.intersection(new DialogBounds.Rect(0, -300, 200, 300), monitor));
  }

  @Test
  void translatingClientsMonitorsAndAnchorsTranslatesTheSameIntersection() {
    var client = new DialogBounds.Rect(-100, 100, 500, 400);
    var p = PopupWorkArea.resolve(2, 1, client, main, null, absolute(monitor));
    var moved =
        PopupWorkArea.resolve(
            2,
            1,
            new DialogBounds.Rect(-1700, -800, 500, 400),
            new DialogBounds.Rect(-1500, -800, 800, 500),
            null,
            absolute(new DialogBounds.Rect(-1600, -900, 1000, 700)));
    assertEquals(p.bounds().x() - 1600, moved.bounds().x());
    assertEquals(p.bounds().y() - 900, moved.bounds().y());
    assertEquals(p.bounds().width(), moved.bounds().width());
    assertEquals(p.bounds().height(), moved.bounds().height());
  }

  @Test
  void inventorySnapshotsCopyTheirValuesAndRejectNullEntries() {
    var source = new ArrayList<>(List.of(monitor));
    var snapshot = new UiMonitorWorkAreas.Snapshot(true, source);
    source.clear();
    assertEquals(List.of(monitor), snapshot.monitors());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.monitors().clear());
    assertThrows(
        NullPointerException.class,
        () -> new UiMonitorWorkAreas.Snapshot(true, Arrays.asList((DialogBounds.Rect) null)));
  }

  @Test
  void invalidMainIdentityOrMissingRequiredInputsRejectBeforePlanning() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PopupWorkArea.resolve(2, 0, main, main, null, absolute(monitor)));
    assertThrows(
        NullPointerException.class,
        () -> PopupWorkArea.resolve(2, 1, main, null, null, absolute(monitor)));
    assertThrows(
        NullPointerException.class, () -> PopupWorkArea.resolve(2, 1, main, main, null, null));
  }

  @Test
  void mainClientTaskbarIntersectionsAlsoConstrainMainOwnedDialogs() {
    var work = new DialogBounds.Rect(0, 40, 1000, 600);
    var client = new DialogBounds.Rect(0, 0, 1000, 700);
    assertEquals(
        new PopupWorkArea.Plan(1, work, true, false),
        PopupWorkArea.resolve(1, 1, client, client, null, absolute(work)));
  }

  @Test
  void unusableMainSliversDoNotReplaceTheFullMainClientWithAnInvisibleControlArea() {
    var client = new DialogBounds.Rect(-780, 0, 800, 600);
    assertEquals(
        new PopupWorkArea.Plan(1, client, false, false),
        PopupWorkArea.resolve(1, 1, client, client, null, absolute(monitor)));
  }

  @Test
  void logicalOwnersOnOneViewportRetainIndependentOpeningAnchors() {
    var hosts = new UiWindowHosts();
    var left = new DialogBounds.Rect(20, 50, 200, 400);
    var right = new DialogBounds.Rect(650, 50, 200, 400);
    hosts.observe("Resources", 1, 1, left);
    hosts.observe("Properties", 1, 1, right);
    assertEquals(left, hosts.bounds("Resources", 2));
    assertEquals(right, hosts.bounds("Properties", 2));
    assertEquals(1, hosts.viewport("Properties", 2));
    assertNull(hosts.bounds(null, 2));
    assertNull(hosts.bounds("Find", 2));
  }

  @Test
  void anIdOnlyObservationDoesNotReuseAnotherHostsOldOpeningRectangle() {
    var hosts = new UiWindowHosts();
    hosts.observe("Resources", 2, 1, main);
    hosts.observe("Resources", 3, 2);
    assertEquals(3, hosts.viewport("Resources", 2));
    assertNull(hosts.bounds("Resources", 2));
  }

  @Test
  void contextRewindAndExplicitClearDiscardOwnerCoordinatesTogetherWithIds() {
    var hosts = new UiWindowHosts();
    hosts.observe("Resources", 2, 100, main);
    assertNull(hosts.bounds("Resources", 1));
    assertEquals(0, hosts.viewport("Resources", 1));
    hosts.observe("Resources", 2, 2, main);
    hosts.clear();
    assertNull(hosts.bounds("Resources", 3));
    assertEquals(0, hosts.viewport("Resources", 3));
    assertThrows(IllegalArgumentException.class, () -> hosts.bounds("Resources", -1));
  }

  @Test
  void theOpeningOwnersRectangleChoosesItsMonitorInsteadOfTheSpanningMainCenter() {
    var left = new DialogBounds.Rect(0, 0, 500, 700);
    var right = new DialogBounds.Rect(500, 0, 500, 700);
    var hosts = new UiWindowHosts();
    hosts.observe("Properties", 1, 1, new DialogBounds.Rect(650, 100, 200, 300));
    var p =
        PopupWorkArea.resolve(
            hosts.viewport("Properties", 1),
            1,
            monitor,
            monitor,
            hosts.bounds("Properties", 1),
            absolute(left, right));
    assertEquals(right, p.bounds());
  }
}
