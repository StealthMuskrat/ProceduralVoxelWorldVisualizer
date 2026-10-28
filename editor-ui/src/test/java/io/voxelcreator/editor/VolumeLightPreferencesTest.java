package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VolumeLightPreferencesTest {
  @TempDir Path directory;

  @Test
  void historicalPreferencesKeepVolumeLightingOptIn() throws Exception {
    ObjectNode tree = ProjectIO.JSON.valueToTree(new UserConfig());
    ((ObjectNode) tree.path("visualization")).remove(List.of("volumeShadows", "volumeShadowSteps"));
    var path = directory.resolve("historical.json");
    Files.writeString(path, tree.toString());
    var user = UserConfig.read(path);
    assertFalse(user.visualization.volumeShadows);
    assertEquals(16, user.visualization.volumeShadowSteps);
  }

  @Test
  void malformedTypesAndUnboundedStepCountsReject() throws Exception {
    for (int steps : new int[] {0, 7, 17, 33, Integer.MAX_VALUE}) {
      var user = new UserConfig();
      user.visualization.volumeShadowSteps = steps;
      assertThrows(IllegalArgumentException.class, user::validate);
    }
    for (String field : List.of("volumeShadows", "volumeShadowSteps"))
      for (String value : List.of("null", "\"16\"", "16.5")) {
        ObjectNode tree = ProjectIO.JSON.valueToTree(new UserConfig());
        ((ObjectNode) tree.path("visualization")).set(field, ProjectIO.JSON.readTree(value));
        var path = directory.resolve("invalid.json");
        Files.writeString(path, tree.toString());
        assertThrows(Exception.class, () -> UserConfig.read(path));
      }
  }

  @Test
  void savedChoicesNeverChangeTheProjectOrHistory() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      for (int steps : new int[] {8, 16, 32}) {
        state.user.visualization.volumeShadows = true;
        state.user.visualization.volumeShadowSteps = steps;
        var path = directory.resolve("settings.json");
        state.user.save(path);
        var read = UserConfig.read(path);
        assertTrue(read.visualization.volumeShadows);
        assertEquals(steps, read.visualization.volumeShadowSteps);
      }
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }
}
