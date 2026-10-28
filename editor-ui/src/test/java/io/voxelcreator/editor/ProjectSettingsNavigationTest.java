package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectSettingsNavigationTest {
  private EditorEvents.Target target(String parameter, String pointer) {
    return new EditorEvents.Target(UUID.randomUUID(), null, null, parameter, pointer, true);
  }

  @Test
  void explicitFieldsAndExactPointersSelectOnlySupportedProjectControls() {
    for (var field : ProjectSettingsSession.FIELDS) {
      assertEquals(field, ProjectSettingsSession.field(target(field, "/project/maxY")));
      assertEquals(field, ProjectSettingsSession.field(target("project/" + field, "")));
      assertEquals(field, ProjectSettingsSession.field(target(null, "/project/" + field)));
    }
    assertEquals("maxY", ProjectSettingsSession.field(target("project", "/project")));
    assertNull(ProjectSettingsSession.field(target("formatVersion", "/formatVersion")));
    assertNull(ProjectSettingsSession.field(target(null, "/project/rootOrder/noise2D/0")));
    assertNull(ProjectSettingsSession.field(target("unknown", "/project/seed")));
  }

  @Test
  void resourceAndForeignTargetsCannotReplaceAPendingSettingsBuffer() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var panel = new ProjectSettingsPanel(state);
      assertTrue(panel.open(null));
      assertEquals("0", panel.text("seed"));
      assertFalse(panel.open(target("seed", "/project/seed")));
      assertFalse(
          panel.open(
              new EditorEvents.Target(
                  state.project().project.uuid, UUID.randomUUID(), null, "seed", "", true)));
      assertEquals("0", panel.text("seed"));
      assertTrue(
          panel.open(
              new EditorEvents.Target(
                  state.project().project.uuid, null, null, "minY", "/project/minY", true)));
      assertEquals("-64", panel.text("minY"));
      panel.cancel();
    }
  }

  @Test
  void unsupportedProjectMetadataKeepsDetailsWithoutAnUnavailableSettingsControl() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var uuid = state.project().project.uuid;
      assertFalse(
          state.canNavigate(
              new EditorEvents.Target(uuid, null, null, "formatVersion", "/formatVersion", true)));
      assertFalse(
          state.canNavigate(
              new EditorEvents.Target(
                  uuid, null, null, null, "/project/rootOrder/noise2D/0", true)));
      assertTrue(
          state.canNavigate(
              new EditorEvents.Target(uuid, null, null, "project", "/project", true)));
    }
  }
}
