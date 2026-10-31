package io.voxelcreator.editor;

import static io.voxelcreator.editor.PerformancePreferences.Field.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred ownership around the actual preference sessions and their scoped file publication. */
class PreferenceActionScopeTest {
  @Test
  void privateLimitTextAndUnrelatedThemeChangesDoNotReplaceTheActionOwner() {
    var user = new UserConfig();
    var session = new PerformanceEditSession(user);
    var scope = new PreferenceActionScope();
    long first = scope.synchronize(PerformancePreferences.from(user));
    var old = PerformancePreferences.from(user);
    session.edit(CACHE_MIB, "17");
    user.appearance.theme = "Light";
    assertTrue(session.current(user));
    assertEquals(first, scope.synchronize(PerformancePreferences.from(user)));
    assertTrue(scope.current(PerformancePreferences.from(user)));
    assertEquals(old, PerformancePreferences.from(user));
    assertEquals("17", session.text().get(CACHE_MIB));
  }

  @Test
  void trustPerformanceAndScalePublicationRemainIndependent(@TempDir Path directory)
      throws Exception {
    var user = new UserConfig();
    user.appearance.windowScales.put("extension-window", .75f);
    var limits = new PerformanceEditSession(user);
    limits.edit(CACHE_MIB, "17");
    var limitScope = new PreferenceActionScope();
    limitScope.synchronize(PerformancePreferences.from(user));
    var scales = new UiScaleEditSession(user);
    scales.editGlobal("1.25");
    var scaleScope = new PreferenceActionScope();
    scaleScope.synchronize(UiScalePreferences.from(user));
    var trust = new UpdateTrustSession(user);
    trust.editOrigins("https://updates.example.test");
    trust.editEndpoint("https://updates.example.test/meta.json");
    var file = directory.resolve("user-config.json");
    trust.apply(user, file, trust.text());
    assertTrue(limitScope.current(PerformancePreferences.from(user)));
    assertTrue(scaleScope.current(UiScalePreferences.from(user)));
    limits.apply(user, file, limits.text());
    assertTrue(scaleScope.current(UiScalePreferences.from(user)));
    scales.apply(user, file, scales.text());
    var saved = UserConfig.read(file);
    assertEquals(17, saved.performance.cacheMiB);
    assertEquals(1.25f, saved.appearance.globalScale);
    assertEquals(.75f, saved.appearance.windowScales.get("extension-window").floatValue());
    assertEquals("https://updates.example.test/meta.json", saved.performance.updateEndpoint);
  }

  @Test
  void failedLimitSaveRetainsOwnerWhileExplicitRevertConsumesItsPrivateContext(
      @TempDir Path directory) throws Exception {
    var user = new UserConfig();
    var session = new PerformanceEditSession(user);
    session.edit(PREVIEW_CELLS, Long.toString(Long.MAX_VALUE));
    var scope = new PreferenceActionScope();
    long first = scope.synchronize(PerformancePreferences.from(user));
    var before = ProjectIO.JSON.valueToTree(user);
    var file = directory.resolve("user-config.json");
    Files.createDirectory(file);
    Files.writeString(file.resolve("keep"), "retained");
    assertThrows(RuntimeException.class, () -> session.apply(user, file, session.text()));
    assertEquals(first, scope.synchronize(PerformancePreferences.from(user)));
    assertEquals(Long.toString(Long.MAX_VALUE), session.text().get(PREVIEW_CELLS));
    assertEquals(before, ProjectIO.JSON.valueToTree(user));
    assertEquals("retained", Files.readString(file.resolve("keep")));
    session.revert(user);
    scope.resetDraft();
    assertFalse(scope.current(PerformancePreferences.from(user)));
    assertTrue(scope.synchronize(PerformancePreferences.from(user)) > first);
    assertEquals(before, ProjectIO.JSON.valueToTree(user));
  }

  @Test
  void inPlaceOwnedScaleChangesInvalidateActionsAndStillRequireAnExplicitRevert(
      @TempDir Path directory) throws Exception {
    var user = new UserConfig();
    var session = new UiScaleEditSession(user);
    session.editGlobal("1.25");
    var scope = new PreferenceActionScope();
    long first = scope.synchronize(UiScalePreferences.from(user));
    user.appearance.globalScale = 1.5f;
    assertFalse(scope.current(UiScalePreferences.from(user)));
    long second = scope.synchronize(UiScalePreferences.from(user));
    assertTrue(second > first);
    assertThrows(
        IllegalStateException.class,
        () -> session.apply(user, directory.resolve("not-written.json"), session.text()));
    assertFalse(Files.exists(directory.resolve("not-written.json")));
    user.appearance.globalScale = 2;
    assertFalse(scope.current(UiScalePreferences.from(user)));
    assertTrue(scope.synchronize(UiScalePreferences.from(user)) > second);
    session.revert(user);
    scope.resetDraft();
    assertFalse(scope.current(UiScalePreferences.from(user)));
    scope.synchronize(UiScalePreferences.from(user));
    session.editGlobal("2.25");
    assertTrue(scope.current(UiScalePreferences.from(user)));
    session.apply(user, directory.resolve("user-config.json"), session.text());
    scope.resetDraft();
    assertFalse(scope.current(UiScalePreferences.from(user)));
    assertEquals(
        2.25f, UserConfig.read(directory.resolve("user-config.json")).appearance.globalScale);
  }
}
