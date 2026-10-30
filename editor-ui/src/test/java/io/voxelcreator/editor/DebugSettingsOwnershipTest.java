package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.Profiler;
import io.voxelcreator.model.ProjectIO;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred shared-state checks that require neither ImGui nor a renderer instance. */
class DebugSettingsOwnershipTest {
  @Test
  void sectionNavigationDoesNotReadRendererStartInstrumentationOrModifyTheDocument() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var calls = new AtomicInteger();
      var panel =
          new DebugSettingsPanel(
              state,
              () -> {
                calls.incrementAndGet();
                throw new AssertionError("Renderer read");
              },
              calls::incrementAndGet,
              calls::incrementAndGet);
      var project = ProjectIO.JSON.valueToTree(state.project());
      var user = ProjectIO.JSON.valueToTree(state.user);
      for (String section : DebugSettingsPanel.SECTIONS) {
        panel.section(section);
        assertEquals(section, panel.section());
        assertFalse(panel.recording());
      }
      assertEquals(0, calls.get());
      assertTrue(state.engine.profiler().current().isEmpty());
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(0, state.document.historyBytes());
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void externalCaptureReplacementAndStopAreObservedWithoutASecondBackingFlag() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var panel = new DebugSettingsPanel(state, () -> null, () -> {}, () -> {});
      var first = state.engine.profiler().start(Profiler.Options.defaults());
      assertTrue(panel.recording());
      state.engine.profiler().stop();
      assertFalse(panel.recording());
      assertFalse(first.recording());
      var second = state.engine.profiler().start(Profiler.Options.defaults());
      assertNotSame(first, second);
      assertTrue(panel.recording());
      state.engine.profiler().clear();
      assertFalse(panel.recording());
    }
  }

  @Test
  void unsupportedSectionKeepsTheExistingSelectionAndCatalogIsImmutable() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var panel = new DebugSettingsPanel(state, () -> null, () -> {}, () -> {});
      panel.section("Memory");
      assertThrows(IllegalArgumentException.class, () -> panel.section("memory"));
      assertThrows(IllegalArgumentException.class, () -> panel.section(null));
      assertEquals("Memory", panel.section());
      assertEquals(
          List.of(
              "General",
              "Generation",
              "World-Chunks",
              "Rendering",
              "Memory",
              "Scheduler",
              "LOD",
              "Diagnostics"),
          DebugSettingsPanel.SECTIONS);
      assertThrows(UnsupportedOperationException.class, () -> DebugSettingsPanel.SECTIONS.clear());
    }
  }
}
