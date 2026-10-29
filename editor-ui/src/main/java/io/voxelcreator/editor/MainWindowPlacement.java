package io.voxelcreator.editor;

import static io.voxelcreator.editor.WindowPlacement.*;

import java.util.*;
import java.util.function.LongSupplier;

/** UI-thread owner of normal geometry, maximized preference and bounded topology recovery. */
final class MainWindowPlacement {
  record Sample(int x, int y, int width, int height, boolean iconified, boolean maximized) {}

  interface Host {
    Sample sample();

    List<WorkArea> workAreas();

    Insets frame();

    void restore();

    void bounds(Rect rect);

    void maximize();
  }

  private final UserConfig.Workspace workspace;
  private final Host host;
  private final LongSupplier clock;
  private List<WorkArea> topology = List.of();
  private long lastPoll, maximizeRequestedAt;
  private boolean initialized, adjusting, polled, awaitingMaximize;

  MainWindowPlacement(UserConfig.Workspace workspace, Host host) {
    this(workspace, host, System::nanoTime);
  }

  MainWindowPlacement(UserConfig.Workspace workspace, Host host, LongSupplier clock) {
    this.workspace = Objects.requireNonNull(workspace);
    this.host = Objects.requireNonNull(host);
    this.clock = Objects.requireNonNull(clock);
  }

  void restore() {
    apply(false);
  }

  void center() {
    apply(true);
  }

  private void apply(boolean center) {
    if (adjusting) throw new IllegalStateException("Main window placement is being restored");
    adjusting = true;
    try {
      var nextTopology = List.copyOf(host.workAreas());
      host.restore();
      var plan = WindowPlacement.restore(workspace, nextTopology, host.frame(), center);
      host.bounds(plan.normal());
      if (plan.maximized()) host.maximize();
      WindowPlacement.remember(workspace, plan.normal(), plan.maximized());
      topology = nextTopology;
      lastPoll = clock.getAsLong();
      awaitingMaximize = plan.maximized();
      maximizeRequestedAt = lastPoll;
      polled = initialized = true;
    } finally {
      adjusting = false;
    }
  }

  void capture() {
    if (!initialized || adjusting) return;
    capture(host.sample());
  }

  private void capture(Sample value) {
    if (value == null || value.iconified() || value.width() <= 0 || value.height() <= 0) return;
    if (awaitingMaximize) {
      long elapsed = clock.getAsLong() - maximizeRequestedAt;
      if (!value.maximized() && elapsed >= 0 && elapsed < 2_000_000_000L) return;
      awaitingMaximize = false;
    }
    workspace.maximized = value.maximized();
    if (!value.maximized() && value.width() >= MIN_WIDTH && value.height() >= MIN_HEIGHT)
      WindowPlacement.remember(
          workspace, new Rect(value.x(), value.y(), value.width(), value.height()), false);
  }

  void frame() {
    if (!initialized || adjusting) return;
    var value = host.sample();
    capture(value);
    if (value == null || value.iconified() || value.width() <= 0 || value.height() <= 0) return;
    long now = clock.getAsLong();
    if (polled && now >= lastPoll && now - lastPoll < 1_000_000_000L) return;
    lastPoll = now;
    polled = true;
    var areas = List.copyOf(host.workAreas());
    if (areas.isEmpty() || areas.equals(topology)) return;
    if (workspace.x != null
        && !WindowPlacement.reachable(
            new Rect(workspace.x, workspace.y, workspace.width, workspace.height),
            host.frame(),
            areas)) {
      // No monitor callback is replaced: ImGui retains its backend monitor ownership.
      apply(false);
    } else topology = areas;
  }
}
