package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import imgui.flag.ImGuiCol;
import io.voxelcreator.editor.ThemePalette.Rgba;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deferred preview ownership, immutable feedback, projection and publication recovery contracts.
 */
class ThemeWorkspaceTest {
  @TempDir Path directory;

  private static final ThemePalette CUSTOM =
      ThemePalette.starting("Light")
          .color(ImGuiCol.Text, new Rgba(.1f, .2f, .3f, 1))
          .status("Warning", new Rgba(.4f, .2f, 0, 1));

  private static final class Style implements ThemePreview.Style {
    Object token = new Object();
    ThemePalette palette = ThemePalette.starting("Dark");
    int writes;

    public Object owner() {
      return token;
    }

    public Object apply(ThemePalette value) {
      palette = value;
      writes++;
      return token = new Object();
    }
  }

  @Test
  void stalePreferenceChangeRestoresLatestActiveThemeWithoutLosingPrivatePalette() {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(CUSTOM);
    var style = new Style();
    var preview = new ThemePreview(user, edit, style);
    preview.refresh();
    ThemePalette.starting("Light").writeTo(user.appearance);
    preview.refresh();
    assertTrue(preview.paused());
    assertEquals(ThemePalette.starting("Light"), style.palette);
    assertSame(CUSTOM, edit.draft());
    int writes = style.writes;
    preview.refresh();
    preview.close();
    preview.close();
    assertEquals(writes, style.writes);
  }

  @Test
  void externalStyleReplacementYieldsEvenWhenPaletteValuesAreEqualAndCloseDoesNotRestore() {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(CUSTOM);
    var style = new Style();
    var preview = new ThemePreview(user, edit, style);
    preview.refresh();
    Object external = style.apply(CUSTOM);
    preview.refresh();
    assertTrue(preview.paused());
    assertSame(external, style.owner());
    preview.close();
    assertSame(external, style.owner());
    assertSame(CUSTOM, edit.draft());
  }

  @Test
  void explicitResumeRetainsPrivatePaletteAndCloseRestoresCurrentPreferences() {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(CUSTOM);
    var style = new Style();
    var preview = new ThemePreview(user, edit, style);
    preview.refresh();
    style.apply(ThemePalette.starting("Light"));
    preview.refresh();
    preview.resume();
    assertFalse(preview.paused());
    assertSame(CUSTOM, style.palette);
    preview.close();
    assertEquals(ThemePalette.starting("Dark"), style.palette);
  }

  @Test
  void staleBaseCannotResumeOrSaveAndExplicitRevertReloadsActiveValues() throws Exception {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(CUSTOM);
    var style = new Style();
    var preview = new ThemePreview(user, edit, style);
    preview.refresh();
    var active = ThemePalette.starting("Light").status("Error", new Rgba(.5f, 0, 0, 1));
    active.writeTo(user.appearance);
    preview.refresh();
    assertThrows(IllegalStateException.class, preview::resume);
    assertThrows(
        IllegalStateException.class, () -> edit.apply(user, directory.resolve("blocked.json")));
    assertFalse(Files.exists(directory.resolve("blocked.json")));
    assertSame(CUSTOM, edit.draft());
    preview.revert();
    assertEquals(active, edit.draft());
    assertEquals(active, style.palette);
    assertFalse(preview.paused());
    assertFalse(edit.changed());
  }

  @Test
  void unrelatedPreferencesAndEqualValueEditsDoNotReinstallOrInvalidatePreview() throws Exception {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(CUSTOM);
    var style = new Style();
    var preview = new ThemePreview(user, edit, style);
    preview.refresh();
    Object owner = style.owner();
    user.workspace.width = 1234;
    user.appearance.globalScale = 1.5f;
    edit.edit(new ThemePalette(CUSTOM.base(), CUSTOM.colors(), CUSTOM.statuses()));
    for (int i = 0; i < 5; i++) preview.refresh();
    assertSame(owner, style.owner());
    assertEquals(1, style.writes);
    var path = directory.resolve("user.json");
    edit.apply(user, path, edit.draft());
    preview.refresh();
    assertSame(owner, style.owner());
    var saved = UserConfig.read(path);
    assertEquals(CUSTOM, ThemePalette.from(saved.appearance));
    assertEquals(1234, saved.workspace.width);
    assertEquals(1.5f, saved.appearance.globalScale);
    preview.close();
    assertEquals(CUSTOM, style.palette);
  }

  @Test
  void closedPreviewCannotReacquireAndRefreshCannotPublishLaterDraftEdits() {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    var style = new Style();
    var preview = new ThemePreview(user, edit, style);
    preview.refresh();
    preview.close();
    var token = style.owner();
    edit.edit(CUSTOM);
    preview.refresh();
    assertTrue(preview.closed());
    assertSame(token, style.owner());
    assertThrows(IllegalStateException.class, preview::resume);
    assertThrows(IllegalStateException.class, preview::revert);
  }

  @Test
  void invalidActiveThemePausesSafelyAndFailedRevertRetainsOpeningDraft() {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(CUSTOM);
    var style = new Style();
    var preview = new ThemePreview(user, edit, style);
    preview.refresh();
    user.appearance.colors = null;
    preview.refresh();
    assertTrue(preview.paused());
    assertEquals(ThemePalette.starting("Dark"), style.palette);
    assertFalse(edit.current(user));
    assertThrows(RuntimeException.class, preview::revert);
    assertSame(CUSTOM, edit.draft());
    assertEquals(ThemePalette.starting("Dark"), edit.base());
    var token = style.owner();
    preview.close();
    assertSame(token, style.owner());
  }

  @Test
  void failedSaveKeepsPreviewOwnershipAndRetryMergesLatestNonThemePreferences() throws Exception {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(CUSTOM);
    var style = new Style();
    var preview = new ThemePreview(user, edit, style);
    preview.refresh();
    var token = style.owner();
    var target = directory.resolve("occupied");
    Files.createDirectory(target);
    Files.writeString(target.resolve("keep"), "retained");
    assertThrows(IllegalStateException.class, () -> edit.apply(user, target, CUSTOM));
    preview.refresh();
    assertSame(token, style.owner());
    assertSame(CUSTOM, edit.draft());
    assertTrue(edit.changed());
    assertEquals("retained", Files.readString(target.resolve("keep")));
    user.workspace.width = 1234;
    edit.apply(user, directory.resolve("retry.json"), CUSTOM);
    assertEquals(1234, UserConfig.read(directory.resolve("retry.json")).workspace.width);
    assertFalse(edit.changed());
    preview.close();
    assertEquals(CUSTOM, style.palette);
  }

  private static Map<Integer, Rgba> resolved(ThemePalette value) {
    var colors = new HashMap<Integer, Rgba>();
    for (int i = 0; i < ImGuiCol.COUNT; i++) colors.put(i, new Rgba(0, 0, 0, 1));
    colors.put(ImGuiCol.Text, new Rgba(1, 1, 1, 1));
    colors.putAll(value.colors());
    return colors;
  }

  @Test
  void feedbackReusesExactImmutableMapAndAuditForEquivalentPaletteValues() {
    var feedback = new ThemeFeedback(ThemeWorkspaceTest::resolved);
    var palette = ThemePalette.starting("Dark");
    feedback.update(palette);
    var colors = feedback.colors();
    var checks = feedback.checks();
    feedback.update(new ThemePalette("Dark", Map.of(), Map.of()));
    assertSame(colors, feedback.colors());
    assertSame(checks, feedback.checks());
    assertEquals(1, feedback.evaluations());
    assertEquals(26, checks.size());
    assertEquals(21, checks.getFirst().ratio(), 1e-12);
    assertThrows(UnsupportedOperationException.class, colors::clear);
    assertThrows(UnsupportedOperationException.class, checks::clear);
  }

  @Test
  void statusOnlyEditsRecomputeFeedbackEvenWhenResolvedPaletteColorsAreEqual() {
    var feedback = new ThemeFeedback(ThemeWorkspaceTest::resolved);
    var original = ThemePalette.starting("Dark");
    feedback.update(original);
    var colors = feedback.colors();
    var before = feedback.checks();
    var edited = original.status("Error", new Rgba(0, 0, 0, 1));
    feedback.update(edited);
    assertEquals(colors, feedback.colors());
    assertNotSame(before, feedback.checks());
    assertSame(edited, feedback.palette());
    assertEquals(2, feedback.evaluations());
    assertEquals(
        1,
        feedback.checks().stream()
            .filter(c -> c.label().equals("Error text / WindowBg"))
            .findFirst()
            .orElseThrow()
            .ratio());
  }

  @Test
  void failedFeedbackResolutionCannotPublishPartialOrMismatchedResults() {
    var fail = new AtomicBoolean();
    var feedback = new ThemeFeedback(p -> fail.get() ? Map.of() : resolved(p));
    var original = ThemePalette.starting("Dark");
    feedback.update(original);
    var colors = feedback.colors();
    var checks = feedback.checks();
    fail.set(true);
    assertThrows(IllegalArgumentException.class, () -> feedback.update(CUSTOM));
    assertSame(original, feedback.palette());
    assertSame(colors, feedback.colors());
    assertSame(checks, feedback.checks());
    assertEquals(1, feedback.evaluations());
    fail.set(false);
    feedback.update(CUSTOM);
    assertSame(CUSTOM, feedback.palette());
    assertEquals(2, feedback.evaluations());
  }

  @Test
  void paletteProjectionCoversEveryColorAndStatusExactlyOnceWithStableKeys() {
    var rows = new ThemeRows().get("All", "");
    assertEquals(ImGuiCol.COUNT + 4, rows.size());
    assertEquals(rows.size(), rows.stream().map(ThemeRows.Row::key).distinct().count());
    for (int i = 0; i < ImGuiCol.COUNT; i++) {
      int key = i;
      assertEquals(1, rows.stream().filter(r -> r.color() == key && r.role() == null).count());
    }
    for (var role : ThemePalette.ROLES)
      assertEquals(1, rows.stream().filter(r -> role.equals(r.role())).count());
    assertThrows(UnsupportedOperationException.class, rows::clear);
  }

  @Test
  void projectionCachesNormalizedQueriesAndCombinesGroupAndNameSearch() {
    var projection = new ThemeRows();
    var all = projection.get("All", "");
    var error = projection.get("Status messages", " error ");
    assertEquals(1, error.size());
    assertEquals("Error", error.getFirst().role());
    assertSame(error, projection.get("Status messages", "ERROR"));
    assertEquals(2, projection.queries());
    assertTrue(projection.get("Text and selection", "WindowBg").isEmpty());
    var group = projection.get("All", "borders and resizing");
    assertFalse(group.isEmpty());
    assertTrue(
        group.stream().allMatch(r -> r.group().equals("Borders and resizing") && all.contains(r)));
  }

  @Test
  void unknownProjectionGroupCannotReplaceTheLastOwnedProjection() {
    var projection = new ThemeRows();
    var before = projection.get("All", "Text");
    assertThrows(IllegalArgumentException.class, () -> projection.get("unknown", ""));
    assertSame(before, projection.get("All", "text"));
    assertEquals(1, projection.queries());
  }

  @Test
  void publicationRequiresExactDisplayedPaletteBeforeFilesystemChanges() throws Exception {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(CUSTOM);
    var target = directory.resolve("must-not-exist/user.json");
    assertThrows(
        IllegalStateException.class,
        () -> edit.apply(user, target, ThemePalette.starting("Light")));
    assertFalse(Files.exists(target.getParent()));
    assertSame(CUSTOM, edit.draft());
    assertEquals("Dark", user.appearance.theme);
    edit.apply(user, target, new ThemePalette(CUSTOM.base(), CUSTOM.colors(), CUSTOM.statuses()));
    assertEquals(CUSTOM, ThemePalette.from(UserConfig.read(target).appearance));
  }
}
