package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import org.junit.jupiter.api.Test;

/**
 * Deferred analytical modal bounds, lifetime stability and logical hosting viewport definitions.
 */
class DialogBoundsTest {
  @Test
  void firstOpeningCentersPreferredSizeInsideReservedWorkMargins() {
    var geometry = new DialogBounds(640, 300);
    var plan = geometry.prepare(1, new DialogBounds.Rect(0, 0, 1280, 720));
    assertEquals(new DialogBounds.Rect(320, 210, 640, 300), plan.bounds());
    assertTrue(plan.position());
    assertTrue(plan.size());
    assertEquals(160, plan.minimumWidth());
    assertEquals(120, plan.minimumHeight());
    assertNull(geometry.observed());
  }

  @Test
  void secondaryNegativeWorkOriginsStayInTheirOwnCoordinateSpace() {
    var plan =
        new DialogBounds(640, 300).prepare(-123, new DialogBounds.Rect(-1920, -900, 1280, 720));
    assertEquals(new DialogBounds.Rect(-1600, -690, 640, 300), plan.bounds());
    assertTrue(
        DialogBounds.contains(
            DialogBounds.inset(new DialogBounds.Rect(-1920, -900, 1280, 720)), plan.bounds()));
  }

  @Test
  void oversizeDefaultsShrinkToAvailableWidthAndHeightTogether() {
    var plan = new DialogBounds(800, 600).prepare(1, new DialogBounds.Rect(0, 0, 360, 240));
    assertEquals(new DialogBounds.Rect(8, 8, 344, 224), plan.bounds());
    assertEquals(160, plan.minimumWidth());
    assertEquals(120, plan.minimumHeight());
  }

  @Test
  void tinyAreasReduceMinimumsAndMarginsWithoutInvertedConstraints() {
    var plan = new DialogBounds(640, 300).prepare(1, new DialogBounds.Rect(0, 0, 100, 80));
    assertEquals(new DialogBounds.Rect(8, 8, 84, 64), plan.bounds());
    assertEquals(84, plan.minimumWidth());
    assertEquals(64, plan.minimumHeight());
    var pixel = new DialogBounds(640, 300).prepare(1, new DialogBounds.Rect(0, 0, 1, 1));
    assertEquals(new DialogBounds.Rect(0, 0, 1, 1), pixel.bounds());
    assertEquals(1, pixel.minimumWidth());
    assertEquals(1, pixel.minimumHeight());
  }

  @Test
  void preferredSizesBelowNativeMinimumsAreExpandedBeforePositioning() {
    var plan = new DialogBounds(1, 2).prepare(1, new DialogBounds.Rect(0, 0, 640, 480));
    assertEquals(new DialogBounds.Rect(240, 180, 160, 120), plan.bounds());
  }

  @Test
  void unchangedVisibleDialogDoesNotSubmitPositionsOrSizesThatFightUserDragging() {
    var geometry = new DialogBounds(640, 300);
    var work = new DialogBounds.Rect(0, 0, 800, 600);
    geometry.prepare(1, work);
    geometry.observe(new DialogBounds.Rect(10, 20, 450, 230));
    var plan = geometry.prepare(1, work);
    assertFalse(plan.position());
    assertFalse(plan.size());
    assertEquals(new DialogBounds.Rect(10, 20, 450, 230), plan.bounds());
  }

  @Test
  void outsideDragRepositionsWithoutReplacingTheUsersValidSize() {
    var geometry = new DialogBounds(640, 300);
    var work = new DialogBounds.Rect(0, 0, 800, 600);
    geometry.prepare(1, work);
    geometry.observe(new DialogBounds.Rect(-300, -400, 450, 230));
    var plan = geometry.prepare(1, work);
    assertTrue(plan.position());
    assertFalse(plan.size());
    assertEquals(new DialogBounds.Rect(8, 8, 450, 230), plan.bounds());
  }

  @Test
  void ownerViewportChangeIsObservedEvenWhenItsWorkRectangleIsTheSame() {
    var geometry = new DialogBounds(640, 300);
    var work = new DialogBounds.Rect(0, 0, 800, 600);
    geometry.prepare(1, work);
    geometry.observe(new DialogBounds.Rect(10, 20, 450, 230));
    var plan = geometry.prepare(-123, work);
    assertTrue(plan.position());
    assertFalse(plan.size());
    assertEquals(new DialogBounds.Rect(10, 20, 450, 230), plan.bounds());
  }

  @Test
  void workAreaShrinkFitsObservedSizeAndPositionInOnePlan() {
    var geometry = new DialogBounds(640, 300);
    geometry.prepare(1, new DialogBounds.Rect(0, 0, 1000, 900));
    geometry.observe(new DialogBounds.Rect(100, 100, 800, 600));
    var plan = geometry.prepare(1, new DialogBounds.Rect(0, 0, 600, 400));
    assertTrue(plan.position());
    assertTrue(plan.size());
    assertEquals(new DialogBounds.Rect(8, 8, 584, 384), plan.bounds());
  }

  @Test
  void changedWorkOriginClampsTheLastPositionInsteadOfRecenteringEveryFrame() {
    var geometry = new DialogBounds(640, 300);
    geometry.prepare(1, new DialogBounds.Rect(0, 0, 800, 600));
    geometry.observe(new DialogBounds.Rect(10, 20, 450, 230));
    var plan = geometry.prepare(2, new DialogBounds.Rect(-1000, -500, 600, 400));
    assertTrue(plan.position());
    assertFalse(plan.size());
    assertEquals(new DialogBounds.Rect(-858, -338, 450, 230), plan.bounds());
  }

  @Test
  void explicitNewLifetimeDropsOldMeasurementsAndRestoresPreferredGeometry() {
    var geometry = new DialogBounds(640, 300);
    var work = new DialogBounds.Rect(0, 0, 1280, 720);
    geometry.prepare(1, work);
    geometry.observe(new DialogBounds.Rect(10, 20, 450, 230));
    geometry.reset();
    assertNull(geometry.observed());
    var plan = geometry.prepare(1, work);
    assertEquals(new DialogBounds.Rect(320, 210, 640, 300), plan.bounds());
    assertTrue(plan.position());
    assertTrue(plan.size());
  }

  @Test
  void invalidShapesAndMissingTargetsRejectBeforeChangingAValidMeasurement() {
    var geometry = new DialogBounds(640, 300);
    var original = new DialogBounds.Rect(10, 20, 450, 230);
    geometry.observe(original);
    for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, 0, -1}) {
      assertThrows(IllegalArgumentException.class, () -> new DialogBounds(invalid, 300));
      assertThrows(IllegalArgumentException.class, () -> new DialogBounds.Rect(0, 0, 640, invalid));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> geometry.prepare(0, new DialogBounds.Rect(0, 0, 1280, 720)));
    assertEquals(original, geometry.observed());
    var extreme =
        new DialogBounds(640, 300)
            .prepare(
                1,
                new DialogBounds.Rect(
                    Float.MAX_VALUE / 2,
                    -Float.MAX_VALUE / 2,
                    Float.MAX_VALUE / 4,
                    Float.MAX_VALUE / 4));
    assertTrue(Float.isFinite(extreme.bounds().x()));
    assertTrue(Float.isFinite(extreme.bounds().y()));
  }

  @Test
  void logicalWindowHostsRetainIndependentUnsignedIdsAndUseZeroForMissingOwners() {
    var hosts = new UiWindowHosts();
    hosts.observe("Resources", -123, 1);
    hosts.observe("Properties", 45, 1);
    assertEquals(-123, hosts.viewport("Resources", 2));
    assertEquals(45, hosts.viewport("Properties", 2));
    assertEquals(0, hosts.viewport("Find", 2));
    assertEquals(0, hosts.viewport(null, 2));
    assertThrows(IllegalArgumentException.class, () -> hosts.observe("Resources", 0, 3));
    assertEquals(-123, hosts.viewport("Resources", 3));
  }

  @Test
  void contextFrameRestartAndExplicitClearDiscardOldHostAssociations() {
    var hosts = new UiWindowHosts();
    hosts.observe("Properties", 45, 100);
    assertEquals(0, hosts.viewport("Properties", 1));
    hosts.observe("Properties", 99, 2);
    assertEquals(99, hosts.viewport("Properties", 3));
    hosts.clear();
    assertEquals(0, hosts.viewport("Properties", 3));
    assertThrows(IllegalArgumentException.class, () -> hosts.viewport("Properties", -1));
  }

  @Test
  void dialogMeasurementsAndHostObservationsDoNotChangePreferencesOrProjectHistory()
      throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var user = ProjectIO.JSON.valueToTree(state.user);
      var project = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      state.dialogHosts.observe("Properties", 45, 1);
      var geometry = new DialogBounds(640, 300);
      var plan = geometry.prepare(45, new DialogBounds.Rect(-1920, 0, 1920, 1080));
      geometry.observe(plan.bounds());
      geometry.reset();
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertFalse(state.document.dirty());
    }
  }
}
