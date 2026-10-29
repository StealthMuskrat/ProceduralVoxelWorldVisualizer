package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred workspace reset ordering, visibility persistence, size and dock identity contracts. */
class WorkspaceLifecycleTest {
  @TempDir Path directory;

  @Test
  void sameFramePeriodicCaptureCannotEraseARequestedDefaultLayout() {
    var user = new UserConfig();
    user.workspace.docking = "loaded layout";
    var layout = new WorkspaceLayout(user.workspace);
    layout.reset(user.workspace);
    assertFalse(
        layout.capture(
            user.workspace,
            () -> {
              fail("Captured obsolete native INI");
              return "old";
            }));
    assertEquals("", user.workspace.docking);
    assertTrue(layout.pending());
    var builds = new AtomicInteger();
    assertTrue(layout.build(builds::incrementAndGet));
    assertFalse(layout.pending());
    assertTrue(layout.capture(user.workspace, () -> "new defaults"));
    assertEquals("new defaults", user.workspace.docking);
    assertEquals(1, builds.get());
  }

  @Test
  void loadedLayoutDoesNotBuildDefaultsUntilExplicitlyRequested() {
    var user = new UserConfig();
    user.workspace.docking = "loaded layout";
    var layout = new WorkspaceLayout(user.workspace);
    assertFalse(layout.build(() -> fail("Rebuilt authored layout")));
    assertTrue(layout.capture(user.workspace, () -> "updated layout"));
    assertEquals("updated layout", user.workspace.docking);
  }

  @Test
  void failedBuilderKeepsPendingRequestForRetryWithoutCapturingPartialLayout() {
    var user = new UserConfig();
    var layout = new WorkspaceLayout(user.workspace);
    assertThrows(
        IllegalStateException.class,
        () ->
            layout.build(
                () -> {
                  throw new IllegalStateException("builder fixture");
                }));
    assertTrue(layout.pending());
    assertFalse(layout.capture(user.workspace, () -> "partial"));
    assertTrue(layout.build(() -> {}));
    assertFalse(layout.pending());
  }

  @Test
  void newerResetRequestedDuringBuildIsNotConsumedByTheOlderBuilder() {
    var user = new UserConfig();
    var layout = new WorkspaceLayout(user.workspace);
    layout.build(() -> layout.reset(user.workspace));
    assertTrue(layout.pending());
    assertTrue(layout.build(() -> {}));
    assertFalse(layout.pending());
  }

  @Test
  void captureCannotPublishOlderIniAfterItsCallbackRequestsAndBuildsANewLayout() {
    var user = new UserConfig();
    user.workspace.docking = "loaded";
    var layout = new WorkspaceLayout(user.workspace);
    assertFalse(
        layout.capture(
            user.workspace,
            () -> {
              layout.reset(user.workspace);
              layout.build(() -> {});
              return "obsolete";
            }));
    assertEquals("", user.workspace.docking);
  }

  @Test
  void visibilityProjectionIsCompleteImmutableAndUnchangedCapturesRetainSavedList() {
    var user = new UserConfig();
    var restored = WorkspaceVisibility.restore(user.workspace);
    assertEquals(new HashSet<>(WorkspaceVisibility.WINDOWS), restored.keySet());
    assertTrue(restored.values().stream().noneMatch(Boolean::booleanValue));
    assertThrows(UnsupportedOperationException.class, restored::clear);
    var values = new HashMap<>(restored);
    values.put("Profiler", true);
    values.put("Logs", true);
    WorkspaceVisibility.capture(user.workspace, values);
    assertEquals(List.of("Logs", "Profiler"), user.workspace.visibleWindows);
    var same = user.workspace.visibleWindows;
    WorkspaceVisibility.capture(user.workspace, new TreeMap<>(values));
    assertSame(same, user.workspace.visibleWindows);
    values.put("Logs", false);
    assertTrue(user.workspace.visibleWindows.contains("Logs"));
  }

  @Test
  void invalidVisibilityCannotReplaceTheLastSavedFlags() {
    for (var invalid :
        List.of(
            List.of("Unknown"),
            List.of("Logs", "Logs"),
            Arrays.asList("Logs", null),
            List.of("Check for Updates")))
      assertThrows(IllegalArgumentException.class, () -> WorkspaceVisibility.validate(invalid));
    assertThrows(IllegalArgumentException.class, () -> WorkspaceVisibility.validate(null));
    var user = new UserConfig();
    var before = user.workspace.visibleWindows;
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceVisibility.capture(user.workspace, Map.of("Logs", true)));
    assertSame(before, user.workspace.visibleWindows);
  }

  @Test
  void savedVisibilityRestartsAndHistoricalFilesDefaultClosedWithoutBeingRewritten()
      throws Exception {
    var user = new UserConfig();
    user.workspace.visibleWindows.addAll(List.of("Logs", "Help", "Profiler"));
    var file = directory.resolve("user.json");
    user.save(file);
    assertEquals(user.workspace.visibleWindows, UserConfig.read(file).workspace.visibleWindows);
    ObjectNode historical = ProjectIO.JSON.valueToTree(user);
    ((ObjectNode) historical.path("workspace")).remove("visibleWindows");
    Files.writeString(file, historical.toString());
    var original = Files.readAllBytes(file);
    assertTrue(UserConfig.read(file).workspace.visibleWindows.isEmpty());
    assertArrayEquals(original, Files.readAllBytes(file));
  }

  @Test
  void normalSizesPersistWithoutMinimizedMaximizedOrSubminimumGeometryPollution() {
    var user = new UserConfig();
    WorkspaceVisibility.captureSize(user.workspace, 1234, 789, false, false);
    for (var size : List.of(new int[] {0, 0}, new int[] {399, 789}, new int[] {1234, 299}))
      WorkspaceVisibility.captureSize(user.workspace, size[0], size[1], false, false);
    WorkspaceVisibility.captureSize(user.workspace, 1920, 1080, false, true);
    WorkspaceVisibility.captureSize(user.workspace, 0, 0, true, false);
    assertEquals(1234, user.workspace.width);
    assertEquals(789, user.workspace.height);
  }

  @Test
  void unsignedDockIdentityIsAppliedOnceAndUndockDoesNotForgetThePriorTarget() {
    var dock = new PanelDockState();
    int identity = 0xF1234567;
    dock.observe(identity);
    dock.undock();
    assertEquals(0, dock.take(id -> false).orElseThrow());
    assertTrue(dock.take(id -> true).isEmpty());
    dock.observe(0);
    assertTrue(dock.redock(id -> id == identity));
    assertEquals(identity, dock.take(id -> id == identity).orElseThrow());
    assertTrue(dock.take(id -> true).isEmpty());
  }

  @Test
  void removedDockTargetNeverBecomesANewFloatingNodeAndNewObservedTargetsCanBeUsed() {
    var dock = new PanelDockState();
    dock.observe(42);
    assertTrue(dock.redock(id -> id == 42));
    assertTrue(dock.take(id -> false).isEmpty());
    assertFalse(dock.canRedock(id -> false));
    assertFalse(dock.redock(id -> false));
    dock.observe(77);
    assertTrue(dock.redock(id -> id == 77));
    assertEquals(77, dock.take(id -> true).orElseThrow());
  }

  @Test
  void defaultLayoutResetClearsBothPendingDockActionAndFormerTarget() {
    var dock = new PanelDockState();
    dock.observe(42);
    dock.undock();
    dock.reset();
    assertTrue(dock.take(id -> true).isEmpty());
    assertFalse(dock.canRedock(id -> true));
    dock.observe(99);
    assertTrue(dock.redock(id -> true));
    dock.reset();
    assertTrue(dock.take(id -> true).isEmpty());
  }

  @Test
  void preferenceCaptureDoesNotStartProfilingOrModifyProjectState() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var project = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var flags = new HashMap<>(WorkspaceVisibility.restore(state.user.workspace));
      flags.put("Profiler", true);
      WorkspaceVisibility.capture(state.user.workspace, flags);
      state.user.save(directory.resolve("user.json"));
      assertTrue(state.engine.profiler().current().isEmpty());
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertFalse(state.document.dirty());
    }
  }
}
