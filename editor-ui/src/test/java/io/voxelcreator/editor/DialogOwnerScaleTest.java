package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.util.List;
import org.junit.jupiter.api.Test;

class DialogOwnerScaleTest {
  @Test
  void denseAtlasMetricsRetainFractionalOwnerFontSizes() {
    assertEquals(6.5f, UiDpiPolicy.ownedFont(52, .25f, 1, .5f, 1));
    assertEquals(81.25f, UiDpiPolicy.ownedFont(52, .25f, 2.5f, 2.5f, 1));
  }

  @Test
  void theActiveFontRemainsAuthoritativeInsteadOfHardCodingThirteen() {
    assertEquals(15, UiDpiPolicy.ownedFont(16, 1, 1.25f, 1.5f, .5f));
    assertEquals(60, UiDpiPolicy.ownedFont(20, .5f, 2, 2, 1.5f));
  }

  @Test
  void ownerCompositionAgreesWithIndependentMainAndDetachedDpiArithmetic() {
    for (var frame :
        List.of(
            new UiDpiPolicy.Frame(1, 1, 1),
            new UiDpiPolicy.Frame(2, 1, 1.25f),
            new UiDpiPolicy.Frame(2, 2, 2.5f),
            new UiDpiPolicy.Frame(3, 2, .5f))) {
      for (float target : new float[] {1, 2, 3})
        for (float window : new float[] {.5f, 1, 2.5f}) {
          float expected =
              13 * frame.userGlobalScale() * window * target / frame.framebufferScale();
          assertEquals(
              expected,
              UiDpiPolicy.ownedFont(
                  52, .25f, frame.fontGlobalScale(), window, frame.viewportRatio(target)),
              .0001);
        }
    }
  }

  @Test
  void rasterDensityDoesNotChangeTheLogicalOwnerSize() {
    for (float raster : new float[] {1, 2, 4, 8})
      assertEquals(29.25f, UiDpiPolicy.ownedFont(13 * raster, 1 / raster, 1.5f, 1.5f, 1));
  }

  @Test
  void invalidNativeMetricsAndUnrepresentableProductsAreRejected() {
    for (float invalid : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
      assertThrows(
          IllegalArgumentException.class, () -> UiDpiPolicy.ownedFont(invalid, .25f, 1, 1, 1));
      assertThrows(
          IllegalArgumentException.class, () -> UiDpiPolicy.ownedFont(52, invalid, 1, 1, 1));
      assertThrows(
          IllegalArgumentException.class, () -> UiDpiPolicy.ownedFont(52, .25f, invalid, 1, 1));
      assertThrows(
          IllegalArgumentException.class, () -> UiDpiPolicy.ownedFont(52, .25f, 1, 1, invalid));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> UiDpiPolicy.ownedFont(Float.MAX_VALUE, Float.MAX_VALUE, 1, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> UiDpiPolicy.ownedFont(Float.MIN_VALUE, Float.MIN_VALUE, 1, 1, 1));
  }

  @Test
  void ownerMultipliersKeepTheExistingPreferenceBounds() {
    for (float invalid : new float[] {.49f, 2.51f, Float.NaN, Float.POSITIVE_INFINITY})
      assertThrows(
          IllegalArgumentException.class, () -> UiDpiPolicy.ownedFont(52, .25f, 1, invalid, 1));
  }

  @Test
  void unknownOwnersRejectBeforeAnyNativePopupCanBeEntered() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new DialogLayout(state, "Missing window", 600, 360));
      assertDoesNotThrow(() -> new DialogLayout(state, null, 600, 360));
      for (var owner : List.of("Properties", "Resources", "Find", "Visualizers"))
        assertDoesNotThrow(() -> new DialogLayout(state, owner, 600, 360));
    }
  }

  @Test
  void queryingAbsentOwnerOverridesDoesNotMaterializePreferencesOrChangeTheProject() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var preferences = ProjectIO.JSON.valueToTree(state.user);
      var project = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      for (var owner : UiScalePreferences.WINDOWS)
        assertEquals(
            13,
            UiDpiPolicy.ownedFont(52, .25f, 1, UiScalePreferences.window(state.user, owner), 1));
      assertEquals(preferences, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }
}
