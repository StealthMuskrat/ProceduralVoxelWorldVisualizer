package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShadowPreferencesTest {
  @TempDir Path directory;

  @Test
  void historicalPreferencesKeepShadowsOptInAndDefaultResolution() throws Exception {
    var user = new UserConfig();
    assertFalse(user.visualization.shadows);
    assertEquals(1024, user.visualization.shadowResolution);
    ObjectNode tree = ProjectIO.JSON.valueToTree(user);
    ((ObjectNode) tree.path("visualization"))
        .remove(java.util.List.of("shadows", "shadowResolution"));
    var path = directory.resolve("historical.json");
    Files.writeString(path, tree.toString());
    var read = UserConfig.read(path);
    assertFalse(read.visualization.shadows);
    assertEquals(1024, read.visualization.shadowResolution);
  }

  @Test
  void invalidResolutionsAndCoercedTypesAreRejected() throws Exception {
    for (int resolution : new int[] {0, 256, 513, 4096}) {
      var user = new UserConfig();
      user.visualization.shadowResolution = resolution;
      assertThrows(IllegalArgumentException.class, user::validate);
    }
    for (String field : java.util.List.of("shadows", "shadowResolution"))
      for (String value : java.util.List.of("null", "\"1024\"", "true", "512.5")) {
        if (field.equals("shadows") && value.equals("true")) continue;
        ObjectNode tree = ProjectIO.JSON.valueToTree(new UserConfig());
        ((ObjectNode) tree.path("visualization")).set(field, ProjectIO.JSON.readTree(value));
        var path = directory.resolve("invalid.json");
        Files.writeString(path, tree.toString());
        assertThrows(Exception.class, () -> UserConfig.read(path));
      }
  }

  @Test
  void allowedSettingsPersistWithoutLosingOtherPreferences() throws Exception {
    var user = new UserConfig();
    user.workspace.width = 1234;
    user.visualization.shadows = true;
    for (int resolution : new int[] {512, 1024, 2048}) {
      user.visualization.shadowResolution = resolution;
      var path = directory.resolve("settings.json");
      user.save(path);
      var read = UserConfig.read(path);
      assertTrue(read.visualization.shadows);
      assertEquals(resolution, read.visualization.shadowResolution);
      assertEquals(1234, read.workspace.width);
    }
    try (var paths = Files.list(directory)) {
      assertEquals(1, paths.count());
    }
  }

  @Test
  void visualPreferencesLeaveAuthoredDocumentAndHistoryUnchanged() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      state.user.visualization.shadows = true;
      state.user.visualization.shadowResolution = 2048;
      state.user.save(directory.resolve("settings.json"));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }
}
