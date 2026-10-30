package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred independent runtime DPI composition, sample ownership and preference separation. */
class UiDpiPolicyTest {
  @Test
  void unscaledDisplaysKeepTheExistingGlobalTimesWindowComposition() {
    var f = new UiDpiPolicy.Frame(1, 1, 1.25f);
    assertEquals(1.25f, f.fontGlobalScale());
    assertEquals(1.875f, f.effective(1.5f, 1));
  }

  @Test
  void pixelCoordinateHighDpiDoublesTextAtTwoHundredPercent() {
    var f = new UiDpiPolicy.Frame(2, 1, 1.25f);
    assertEquals(2.5f, f.fontGlobalScale());
    assertEquals(3.75f, f.effective(1.5f, 2));
  }

  @Test
  void retinaFramebufferDensityAvoidsApplyingTheSameFactorTwice() {
    var f = new UiDpiPolicy.Frame(2, 2, 1.25f);
    assertEquals(1.25f, f.fontGlobalScale());
    assertEquals(1.875f, f.effective(1.5f, 2));
    assertEquals(3.75f, f.effective(1.5f, 2) * f.framebufferScale());
  }

  @Test
  void detachedViewportRatiosAreRelativeToTheSampledMainContentScale() {
    var f = new UiDpiPolicy.Frame(2, 1, 1.25f);
    assertEquals(.5f, f.viewportRatio(1));
    assertEquals(1.5f, f.viewportRatio(3));
    assertEquals(1.875f, f.effective(1.5f, 1));
    assertEquals(5.625f, f.effective(1.5f, 3));
  }

  @Test
  void physicalFontHeightDoesNotDependOnFramebufferDensityForTheSameContentDpi() {
    var a = new UiDpiPolicy.Frame(2, 1, 1.25f);
    var b = new UiDpiPolicy.Frame(2, 2, 1.25f);
    assertEquals(
        a.effective(1.5f, 3) * a.framebufferScale(), b.effective(1.5f, 3) * b.framebufferScale());
  }

  @Test
  void invalidOrZeroTransientSamplesRetainTheLastValidFrameValues() {
    var policy = new UiDpiPolicy();
    policy.sample(2, 2, 1);
    for (float raw : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY})
      assertEquals(new UiDpiPolicy.Frame(2, 2, 1.25f), policy.sample(raw, raw, 1.25f));
  }

  @Test
  void independentSamplingRetainsOnlyTheUnavailableComponent() {
    var policy = new UiDpiPolicy();
    policy.sample(2, 2, 1);
    assertEquals(new UiDpiPolicy.Frame(3, 2, 1), policy.sample(3, 0, 1));
    assertEquals(new UiDpiPolicy.Frame(3, 1, 1), policy.sample(0, 1, 1));
  }

  @Test
  void validExtremeSamplesClampToTheDocumentedFiniteRuntimeRange() {
    var f = new UiDpiPolicy().sample(Float.MAX_VALUE, Float.MIN_VALUE, 2.5f);
    assertEquals(8, f.mainContentScale());
    assertEquals(.5f, f.framebufferScale());
    assertTrue(Float.isFinite(f.effective(2.5f, Float.MAX_VALUE)));
  }

  @Test
  void unavailableDetachedDpiUsesMainScaleInsteadOfShrinkingToOne() {
    var f = new UiDpiPolicy.Frame(2, 1, 1);
    assertEquals(1, f.viewportRatio(0));
    assertEquals(1, f.viewportRatio(Float.NaN));
  }

  @Test
  void invalidUserPreferencesRejectBeforeChangingTheSampleCache() {
    var policy = new UiDpiPolicy();
    policy.sample(2, 2, 1);
    assertThrows(IllegalArgumentException.class, () -> policy.sample(3, 1, 0));
    assertEquals(new UiDpiPolicy.Frame(2, 2, 1), policy.sample(0, 0, 1));
  }

  @Test
  void immutableFramesRejectNonNormalizedOrInvalidInputs() {
    assertThrows(IllegalArgumentException.class, () -> new UiDpiPolicy.Frame(0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new UiDpiPolicy.Frame(1, Float.NaN, 1));
    assertThrows(IllegalArgumentException.class, () -> new UiDpiPolicy.Frame(16, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new UiDpiPolicy.Frame(1, 1, 3));
  }

  @Test
  void movingAPopupBetweenDpiTargetsAdjustsTheInheritedFontExactlyOnce() {
    assertEquals(26, UiDpiPolicy.inheritedFont(52, 2, 1));
    assertEquals(78, UiDpiPolicy.inheritedFont(52, 2, 3));
    assertEquals(52, UiDpiPolicy.inheritedFont(52, 2, 2));
  }

  @Test
  void inheritedFontMathRejectsUnavailableNativeMetrics() {
    assertThrows(IllegalArgumentException.class, () -> UiDpiPolicy.inheritedFont(0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> UiDpiPolicy.inheritedFont(13, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> UiDpiPolicy.inheritedFont(13, 1, Float.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () -> UiDpiPolicy.inheritedFont(Float.MAX_VALUE, 1, Float.MAX_VALUE));
  }

  @Test
  void runtimeSamplingDoesNotMaterializePreferencesOrChangeProjectHistory() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var user = ProjectIO.JSON.valueToTree(state.user);
      var project = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var policy = new UiDpiPolicy();
      policy.sample(2, 1, state.user.appearance.globalScale);
      policy.sample(3, 2, 1.25f);
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }

  @Test
  void runtimeRegistryPreservesUserScaleBoundsAndDescribesDensitySeparately() {
    var registry = UiScalePreferences.registry();
    assertEquals(2, registry.get("registryVersion"));
    assertEquals(.5f, registry.get("minimum"));
    assertEquals(2.5f, registry.get("maximum"));
    assertEquals(.5f, registry.get("minimumContentScale"));
    assertEquals(8f, registry.get("maximumContentScale"));
    assertEquals(4, registry.get("fontRasterDensity"));
    assertEquals(19, ((List<?>) registry.get("windows")).size());
    assertEquals(13, UiFontAtlas.LOGICAL_SIZE);
    assertEquals(16L << 20, UiFontAtlas.MAXIMUM_BYTES);
  }
}
