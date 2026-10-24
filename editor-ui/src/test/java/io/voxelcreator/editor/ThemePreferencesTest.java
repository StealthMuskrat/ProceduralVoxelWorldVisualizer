package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import imgui.flag.ImGuiCol;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.editor.ThemePalette.Rgba;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemePreferencesTest {
  @TempDir Path directory;

  private static ThemePalette custom() {
    return ThemePalette.starting("Light")
        .color(ImGuiCol.Text, new Rgba(.1f, .2f, .3f, 1))
        .status("Warning", new Rgba(.4f, .2f, 0, 1));
  }

  @Test
  void immutableOwnedSnapshotsAndRegistryCoverEveryPinnedColor() {
    var user = new UserConfig();
    float[] array = {.1f, .2f, .3f, 1};
    user.appearance.colors.put(ImGuiCol.Text, array);
    var palette = ThemePalette.from(user.appearance);
    array[0] = 1;
    assertEquals(.1f, palette.colors().get(ImGuiCol.Text).r());
    float[] output = palette.colors().get(ImGuiCol.Text).array();
    output[0] = 0;
    assertEquals(.1f, palette.colors().get(ImGuiCol.Text).r());
    palette.writeTo(user.appearance);
    user.appearance.colors.get(ImGuiCol.Text)[0] = .5f;
    assertEquals(.1f, palette.colors().get(ImGuiCol.Text).r());
    assertThrows(UnsupportedOperationException.class, () -> palette.colors().clear());
    assertEquals(ImGuiCol.COUNT, ThemeRegistry.COLORS.size());
    for (int i = 0; i < ImGuiCol.COUNT; i++) assertEquals(i, ThemeRegistry.COLORS.get(i).key());
  }

  @Test
  void malformedTypesRangesRolesAndStartingAppearancesReject() {
    for (float v : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f})
      assertThrows(IllegalArgumentException.class, () -> new Rgba(v, 0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> Rgba.of(new float[3]));
    assertThrows(IllegalArgumentException.class, () -> ThemePalette.starting("Custom"));
    assertThrows(IllegalArgumentException.class, () -> custom().color(-1, new Rgba(0, 0, 0, 1)));
    assertThrows(
        IllegalArgumentException.class, () -> custom().color(ImGuiCol.COUNT, new Rgba(0, 0, 0, 1)));
    assertThrows(
        IllegalArgumentException.class, () -> custom().status("Unknown", new Rgba(0, 0, 0, 1)));
  }

  @Test
  void privateDraftResetRevertAndPeriodicSaveExcludePreview() throws Exception {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(custom());
    assertTrue(edit.changed());
    assertEquals("Dark", user.appearance.theme);
    Path path = directory.resolve("user.json");
    user.save(path);
    assertEquals(
        ThemePalette.starting("Dark"), ThemePalette.from(UserConfig.read(path).appearance));
    edit.revert(user);
    assertFalse(edit.changed());
    edit.edit(custom());
    edit.edit(ThemePalette.starting("Dark"));
    assertFalse(edit.changed());
  }

  @Test
  void applyMergesLatestNonThemePreferencesAndSurvivesRestart() throws Exception {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(custom());
    user.appearance.globalScale = 1.5f;
    user.appearance.windowScales.put("Themes", 1.25f);
    user.input.shortcuts.put("Profiler", "Alt+F9");
    user.workspace.width = 1234;
    Path path = directory.resolve("user.json");
    edit.apply(user, path);
    var restored = UserConfig.read(path);
    assertEquals(custom(), ThemePalette.from(restored.appearance));
    assertEquals(1.5f, restored.appearance.globalScale);
    assertEquals(1.25f, restored.appearance.windowScales.get("Themes"));
    assertEquals("Alt+F9", restored.input.shortcuts.get("Profiler"));
    assertEquals(1234, restored.workspace.width);
    assertFalse(edit.changed());
  }

  @Test
  void failedReplacementKeepsActiveThemeFileContentsAndPrivateDraft() throws Exception {
    var user = new UserConfig();
    var edit = new ThemeEditSession(user);
    edit.edit(custom());
    Path occupied = directory.resolve("occupied");
    Files.createDirectory(occupied);
    Files.writeString(occupied.resolve("keep"), "retained");
    assertThrows(IllegalStateException.class, () -> edit.apply(user, occupied));
    assertEquals(ThemePalette.starting("Dark"), ThemePalette.from(user.appearance));
    assertTrue(edit.changed());
    assertEquals("retained", Files.readString(occupied.resolve("keep")));
    try (var files = Files.list(directory)) {
      assertEquals(List.of(occupied), files.toList());
    }
  }

  @Test
  void staleBaseRejectsAndRevertAllowsSaveWithoutFalseMapOrderConflicts() throws Exception {
    var user = new UserConfig();
    var first = new ThemeEditSession(user);
    var stale = new ThemeEditSession(user);
    Path path = directory.resolve("user.json");
    first.edit(custom());
    first.apply(user, path);
    byte[] bytes = Files.readAllBytes(path);
    stale.edit(ThemePalette.starting("Light"));
    assertThrows(IllegalStateException.class, () -> stale.apply(user, path));
    assertArrayEquals(bytes, Files.readAllBytes(path));
    stale.revert(user);
    user.appearance.colors = new LinkedHashMap<>(user.appearance.colors);
    stale.apply(user, path);
  }

  @Test
  void strictReadRejectsMalformedColorNodesVersionsAndTrailingContent() throws Exception {
    Path path = directory.resolve("invalid.json");
    for (String colors :
        List.of(
            "null",
            "[]",
            "{\"0\":[0,0,0]}",
            "{\"00\":[0,0,0,1]}",
            "{\"999\":[0,0,0,1]}",
            "{\"0\":[\"0\",0,0,1]}",
            "{\"0\":[null,0,0,1]}",
            "{\"0\":[-0.1,0,0,1]}")) {
      var tree = (ObjectNode) ProjectIO.JSON.valueToTree(new UserConfig());
      ((ObjectNode) tree.path("appearance")).set("colors", ProjectIO.JSON.readTree(colors));
      Files.writeString(path, tree.toString());
      assertThrows(Exception.class, () -> UserConfig.read(path), colors);
    }
    for (String text :
        List.of(
            "",
            "{}",
            "{\"formatVersion\":1.0}",
            "{\"formatVersion\":2}",
            "{\"formatVersion\":1} {}",
            "{\"formatVersion\":1} garbage")) {
      Files.writeString(path, text);
      assertThrows(Exception.class, () -> UserConfig.read(path), text);
    }
    var tree = (ObjectNode) ProjectIO.JSON.valueToTree(new UserConfig());
    ((ObjectNode) tree.path("appearance").path("statusColors"))
        .putArray("Bogus")
        .add(0)
        .add(0)
        .add(0)
        .add(1);
    Files.writeString(path, tree.toString());
    assertThrows(Exception.class, () -> UserConfig.read(path));
  }

  @Test
  void historicalPaletteLoadsWithoutStatusMapAndWithoutRewritingBytes() throws Exception {
    var tree = (ObjectNode) ProjectIO.JSON.valueToTree(new UserConfig());
    var appearance = (ObjectNode) tree.path("appearance");
    appearance.remove("statusColors");
    appearance.put("theme", "Light");
    ((ObjectNode) appearance.path("colors")).putArray("0").add(.1).add(.2).add(.3).add(1);
    Path path = directory.resolve("legacy.json");
    Files.writeString(path, tree.toString());
    byte[] bytes = Files.readAllBytes(path);
    var user = UserConfig.read(path);
    assertTrue(user.appearance.statusColors.isEmpty());
    assertEquals("Light", user.appearance.theme);
    assertArrayEquals(bytes, Files.readAllBytes(path));
    assertNotEquals(
        ThemePalette.starting("Dark").status("Error"),
        ThemePalette.from(user.appearance).status("Error"));
  }

  @Test
  void invalidCurrentPreferencesCannotPublishCandidateOrOverwriteSavedBytes() throws Exception {
    var user = new UserConfig();
    Path path = directory.resolve("user.json");
    user.save(path);
    byte[] bytes = Files.readAllBytes(path);
    var edit = new ThemeEditSession(user);
    edit.edit(custom());
    user.appearance.globalScale = Float.NaN;
    assertThrows(IllegalArgumentException.class, () -> edit.apply(user, path));
    assertEquals("Dark", user.appearance.theme);
    assertArrayEquals(bytes, Files.readAllBytes(path));
  }

  @Test
  void themeOperationsPreserveExistingProjectDraftHistoryDirtyAndNumericalResults()
      throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var n = new Noise();
      n.id = "constant";
      n.algorithm.key = "CONSTANT";
      n.algorithm.parameters.clear();
      n.algorithm.parameters.put("value", .375);
      state.change(p -> p.noise2D.add(n));
      state.select("noise2D", n.uuid);
      var project = ProjectIO.JSON.valueToTree(state.project());
      Object draft = state.draft;
      long revision = state.revision, history = state.document.historyBytes();
      boolean dirty = state.document.dirty();
      var compiled = new NoiseCompiler(state.project(), 123).compile(n.uuid);
      var edit = new ThemeEditSession(state.user);
      edit.edit(custom());
      edit.apply(state.user, directory.resolve("user.json"));
      edit.revert(state.user);
      for (int x = -8; x <= 8; x++) assertEquals(.375, compiled.sample(x, 0, -x));
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertSame(draft, state.draft);
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertEquals(dirty, state.document.dirty());
    }
  }
}
