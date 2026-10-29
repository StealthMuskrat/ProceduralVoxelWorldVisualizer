package io.voxelcreator.editor;

import static io.voxelcreator.editor.WindowPlacement.*;
import static io.voxelcreator.editor.WindowPlacementTest.workspace;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred host ordering, minimization, monitor removal and user-only capture definitions. */
class MainWindowPlacementTest {
  @TempDir Path directory;

  @Test
  void startupRestoresNormalBoundsBeforeMaximizingAndSuppressesNestedCapture() {
    var workspace = workspace(100, 100, 800, 600);
    workspace.maximized = true;
    var host = new Host();
    var owner = new MainWindowPlacement(workspace, host);
    host.duringBounds = owner::capture;
    owner.restore();
    assertEquals(List.of("restore", "bounds", "maximize"), host.calls);
    assertEquals(new Rect(100, 100, 800, 600), host.bounds);
    assertTrue(workspace.maximized);
    host.sample = new MainWindowPlacement.Sample(0, 0, 1920, 1080, false, true);
    owner.capture();
    assertEquals(800, workspace.width);
    assertEquals(100, workspace.x.intValue());
  }

  @Test
  void minimizingNormalAndMaximizedWindowsRetainsTheirLastNonMinimizedModeAndNormalBounds() {
    var workspace = workspace(100, 100, 800, 600);
    var host = new Host();
    var owner = new MainWindowPlacement(workspace, host);
    owner.restore();
    host.sample = new MainWindowPlacement.Sample(150, 175, 900, 700, false, false);
    owner.capture();
    assertEquals(900, workspace.width);
    host.sample = new MainWindowPlacement.Sample(-32000, -32000, 0, 0, true, false);
    owner.capture();
    assertFalse(workspace.maximized);
    assertEquals(150, workspace.x.intValue());
    host.sample = new MainWindowPlacement.Sample(0, 0, 1920, 1080, false, true);
    owner.capture();
    host.sample = new MainWindowPlacement.Sample(-32000, -32000, 100, 100, true, false);
    owner.capture();
    assertTrue(workspace.maximized);
    assertEquals(900, workspace.width);
    host.sample = new MainWindowPlacement.Sample(150, 175, 900, 700, false, false);
    owner.capture();
    assertFalse(workspace.maximized);
  }

  @Test
  void unstableZeroOrSubminimumSamplesCannotReplaceNormalGeometry() {
    var workspace = workspace(100, 100, 800, 600);
    var host = new Host();
    var owner = new MainWindowPlacement(workspace, host);
    owner.capture();
    assertTrue(host.calls.isEmpty());
    owner.restore();
    for (var value :
        Arrays.asList(
            null,
            new MainWindowPlacement.Sample(0, 0, 0, 0, false, false),
            new MainWindowPlacement.Sample(0, 0, 399, 299, false, false))) {
      host.sample = value;
      owner.capture();
      assertEquals(800, workspace.width);
      assertEquals(100, workspace.x.intValue());
    }
  }

  @Test
  void topologyPollingIsBoundedAndOnlyAnUnreachableRememberedWindowIsRecovered() {
    var workspace = workspace(-1800, 100, 800, 600);
    var host = new Host();
    host.areas = List.of(new WorkArea(0, 0, 1920, 1080), new WorkArea(-1920, 0, 1920, 1080));
    var clock = new AtomicLong();
    var owner = new MainWindowPlacement(workspace, host, clock::get);
    owner.restore();
    host.calls.clear();
    int polls = host.polls;
    host.areas = List.of(new WorkArea(0, 0, 1920, 1080));
    clock.set(999_999_999);
    owner.frame();
    assertEquals(polls, host.polls);
    assertTrue(host.calls.isEmpty());
    clock.incrementAndGet();
    owner.frame();
    assertEquals(List.of("restore", "bounds"), host.calls);
    assertEquals(0, workspace.x.intValue());
    host.calls.clear();
    clock.addAndGet(1_000_000_000L);
    owner.frame();
    assertTrue(host.calls.isEmpty());
    host.areas = List.of(new WorkArea(0, 0, 1920, 1080), new WorkArea(1920, 0, 1920, 1080));
    clock.addAndGet(1_000_000_000L);
    owner.frame();
    assertTrue(host.calls.isEmpty());
  }

  @Test
  void transientMissingMonitorInventoryDoesNotMoveTheWindowOrConsumeLaterRecovery() {
    var workspace = workspace(-1800, 100, 800, 600);
    var host = new Host();
    host.areas = List.of(new WorkArea(0, 0, 1920, 1080), new WorkArea(-1920, 0, 1920, 1080));
    var clock = new AtomicLong();
    var owner = new MainWindowPlacement(workspace, host, clock::get);
    owner.restore();
    host.calls.clear();
    host.areas = List.of();
    clock.set(1_000_000_000L);
    owner.frame();
    assertTrue(host.calls.isEmpty());
    host.areas = List.of(new WorkArea(0, 0, 1920, 1080));
    clock.set(2_000_000_000L);
    owner.frame();
    assertEquals(List.of("restore", "bounds"), host.calls);
  }

  @Test
  void explicitCenterRestoresFromMaximizedWithoutResettingDockingOrVisibility() {
    var workspace = workspace(-1800, 100, 800, 600);
    workspace.maximized = true;
    workspace.docking = "private layout";
    workspace.visibleWindows.add("Logs");
    var host = new Host();
    var owner = new MainWindowPlacement(workspace, host);
    owner.restore();
    host.calls.clear();
    owner.center();
    assertEquals(List.of("restore", "bounds"), host.calls);
    assertEquals(new Rect(560, 240, 800, 600), host.bounds);
    assertFalse(workspace.maximized);
    assertEquals("private layout", workspace.docking);
    assertEquals(List.of("Logs"), workspace.visibleWindows);
  }

  @Test
  void failedNativeBoundsDoNotPublishRecoveredPreferencesAndExplicitRetryWorks() {
    var workspace = workspace(-10000, 100, 800, 600);
    var host = new Host();
    var owner = new MainWindowPlacement(workspace, host);
    host.failure = true;
    assertThrows(IllegalStateException.class, owner::restore);
    assertEquals(-10000, workspace.x.intValue());
    host.failure = false;
    owner.restore();
    assertEquals(0, workspace.x.intValue());
    host.duringBounds = () -> assertThrows(IllegalStateException.class, owner::center);
    owner.center();
    assertEquals(560, workspace.x.intValue());
  }

  @Test
  void savedPositionModeAndShutdownCaptureRemainUserOnlyAndRetainCurrentEngine() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var project = ProjectIO.JSON.valueToTree(state.project());
      var engine = state.engine;
      long revision = state.revision, history = state.document.historyBytes();
      var host = new Host();
      var owner = new MainWindowPlacement(state.user.workspace, host);
      owner.restore();
      host.sample = new MainWindowPlacement.Sample(-1200, -400, 1234, 789, false, false);
      owner.capture();
      host.sample = new MainWindowPlacement.Sample(0, 0, 1920, 1080, false, true);
      owner.capture();
      Path file = directory.resolve("user.json");
      state.user.save(file);
      var restored = UserConfig.read(file).workspace;
      assertEquals(-1200, restored.x.intValue());
      assertEquals(-400, restored.y.intValue());
      assertEquals(1234, restored.width);
      assertEquals(789, restored.height);
      assertTrue(restored.maximized);
      assertSame(engine, state.engine);
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void monitorRemovalDoesNotUnminimizeAndRecoveryWaitsForAVisibleSample() {
    var workspace = workspace(-1800, 100, 800, 600);
    var host = new Host();
    host.areas = List.of(new WorkArea(0, 0, 1920, 1080), new WorkArea(-1920, 0, 1920, 1080));
    var clock = new AtomicLong();
    var owner = new MainWindowPlacement(workspace, host, clock::get);
    owner.restore();
    host.calls.clear();
    host.sample = new MainWindowPlacement.Sample(-32000, -32000, 0, 0, true, false);
    host.areas = List.of(new WorkArea(0, 0, 1920, 1080));
    clock.set(1_000_000_000L);
    owner.frame();
    assertTrue(host.calls.isEmpty());
    assertEquals(-1800, workspace.x.intValue());
    host.sample = new MainWindowPlacement.Sample(-1800, 100, 800, 600, false, false);
    owner.frame();
    assertEquals(List.of("restore", "bounds"), host.calls);
  }

  @Test
  void asynchronousMaximizeIsNotErasedByEarlyNormalSamplesAndHasABoundedUnsupportedFallback() {
    var workspace = workspace(100, 100, 800, 600);
    workspace.maximized = true;
    var host = new Host();
    var clock = new AtomicLong();
    var owner = new MainWindowPlacement(workspace, host, clock::get);
    owner.restore();
    owner.capture();
    assertTrue(workspace.maximized);
    clock.set(1_999_999_999L);
    owner.capture();
    assertTrue(workspace.maximized);
    host.sample = new MainWindowPlacement.Sample(0, 0, 1920, 1080, false, true);
    owner.capture();
    assertTrue(workspace.maximized);
    assertEquals(800, workspace.width);
    owner.restore();
    host.sample = new MainWindowPlacement.Sample(100, 100, 800, 600, false, false);
    clock.addAndGet(2_000_000_000L);
    owner.capture();
    assertFalse(workspace.maximized);
  }

  private static final class Host implements MainWindowPlacement.Host {
    List<WorkArea> areas = List.of(new WorkArea(0, 0, 1920, 1080));
    final List<String> calls = new ArrayList<>();
    MainWindowPlacement.Sample sample =
        new MainWindowPlacement.Sample(100, 100, 800, 600, false, false);
    Rect bounds;
    int polls;
    boolean failure;
    Runnable duringBounds = () -> {};

    public MainWindowPlacement.Sample sample() {
      return sample;
    }

    public List<WorkArea> workAreas() {
      polls++;
      return areas;
    }

    public Insets frame() {
      return Insets.NONE;
    }

    public void restore() {
      calls.add("restore");
    }

    public void bounds(Rect rect) {
      calls.add("bounds");
      if (failure) throw new IllegalStateException("fixture native failure");
      bounds = rect;
      sample =
          new MainWindowPlacement.Sample(
              rect.x(), rect.y(), rect.width(), rect.height(), false, false);
      duringBounds.run();
    }

    public void maximize() {
      calls.add("maximize");
    }
  }
}
