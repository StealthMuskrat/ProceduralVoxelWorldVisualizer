package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Deferred production GL/worker Error recovery; reuses the caller's native context. */
final class InspectorWorkerRecoveryProbe {
  private static EditorState state;
  private static Viewports views;
  private static final BlockPos LOGGED = new BlockPos(2, 0, 3), ENTERED = new BlockPos(7, 0, 3);

  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      state.user.editor.autoRegenerate = false;
      state.user.editor.opportunitiesPerSecond = 20;
      state.user.visualization.streaming = false;
      state.user.visualization.unlit = true;
      state.user.visualization.grid = false;
      require(state.change(TraceWorkflowProbe::fixture), "Worker recovery fixture rejected");
      state.document.save(out.resolve("before.json"));
      var before = ProjectIO.JSON.valueToTree(state.project());
      var preferences = ProjectIO.JSON.valueToTree(state.user);
      long revision = state.revision, history = state.document.historyBytes();
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var draft = state.draft;
      ((ProjectData.Noise) draft).id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var privateValues = ProjectIO.JSON.valueToTree(draft);
      var properties = new PropertyPanel(state);
      var rows = new ArrayList<Map<String, Object>>();
      try (var viewport = new Viewports(state, properties, window)) {
        views = viewport;
        views.tracePanel(new TracePanel(state, new GuardedNavigation(state, properties)));
        views.probeWorld(state.project().worldGenerator.layers.getFirst().uuid, "After", 8, .25);
        until(views::probeWorldReady);
        views.probeTraceCoordinate(LOGGED);
        until(() -> !views.probeCoordinateTracePending());
        var historical = views.probeInspectorTrace();
        require(
            historical != null && historical.trace().position().equals(LOGGED),
            "Initial trace missing");
        views.probeTraceCoordinate(ENTERED);
        until(() -> !views.probeCoordinateTracePending());
        require(state.traces.size() == 2, "Initial traces missing");
        for (boolean pick : new boolean[] {false, true})
          for (boolean escaped : new boolean[] {false, true})
            for (boolean unreadable : new boolean[] {false, true}) {
              Error failure =
                  unreadable
                      ? new AssertionError() {
                        public String getMessage() {
                          throw new AssertionError("unreadable failure");
                        }
                      }
                      : new AssertionError("controlled-inspector-worker");
              int count = state.traces.size();
              var prior = pick ? views.probeInspectorTrace() : historical;
              views.probeInspectorWorkerFailure(failure, escaped);
              if (pick) {
                views.probeWorldPick();
                require(views.probeWorldPickPending(), "Production pick was not admitted");
              } else {
                views.inspectTrace(TraceCoordinateIntent.capture(state, historical));
                require(
                    views.probeCoordinateTracePending(),
                    "Production coordinate trace was not admitted");
              }
              until(() -> !views.probeCoordinateTracePending() && !views.probeWorldPickPending());
              require(
                  views.probeInspectorTrace() == prior && state.traces.size() == count,
                  "Failed work replaced a trace or created a trace log");
              require(
                  ENTERED.equals(views.probeInspectorCoordinate()), "Failure changed entered XYZ");
              require(
                  views
                      .probeInspectorTaskFeedback()
                      .contains(pick ? "Pick failed" : "Trace failed"),
                  "Failure did not present recovery feedback");
              require(failure.getSuppressed().length == 0, "Reporting mutated worker failure");
              if (pick) {
                require(
                    views.probeInspectorRetry() == null,
                    "Pick failure invented a coordinate retry");
                views.probeTraceCoordinate(ENTERED);
              } else {
                require(
                    LOGGED.equals(views.probeInspectorRetry()),
                    "Failure lost the exact logged target");
                retry();
              }
              until(() -> !views.probeCoordinateTracePending());
              var result = views.probeInspectorTrace().trace();
              require(
                  result.position().equals(pick ? ENTERED : LOGGED)
                      && result.finalType() == 1
                      && state.traces.size() == count + 1
                      && views.probeInspectorRetry() == null,
                  "Explicit successor did not recover exactly once");
              require(
                  ENTERED.equals(views.probeInspectorCoordinate()),
                  "Recovery adopted the logged coordinate as input");
              rows.add(
                  Map.of(
                      "pick",
                      pick,
                      "escaped",
                      escaped,
                      "unreadable",
                      unreadable,
                      "target",
                      result.position(),
                      "field",
                      result.steps().getFirst().field(),
                      "finalType",
                      result.finalType()));
            }
        var readyRows = new ArrayList<Map<String, Object>>();
        for (String mode : List.of("trace-cancel", "pick-cancel", "trace-failure-cancel",
            "trace-escape-cancel", "trace-close", "trace-edit")) {
          int count = state.traces.size();
          var prior = mode.equals("pick-cancel") ? views.probeInspectorTrace() : historical;
          long notifications = notifications();
          if (mode.equals("trace-failure-cancel"))
            views.probeInspectorWorkerFailure(new AssertionError("ready-inspector-cancel"), false);
          if (mode.equals("pick-cancel")) views.probeWorldPick();
          else views.inspectTrace(TraceCoordinateIntent.capture(state, historical));
          untilHeld(views::probeInspectorWorkerDone);
          for (int i = 0; i < 12; i++) frame(false);
          require(!views.probeInspectorReady() && (views.probeCoordinateTracePending() || views.probeWorldPickPending()),
              "Inspector bypassed UI delivery");
          String key = mode.equals("trace-close") ? "inspector-close" : "trace-cancel";
          var at = views.probeAdmissionControl(key); require(at != null, "Ready Inspector control missing");
          var io = ImGui.getIO(); io.addMousePosEvent(at[0], at[1]); frame(false);
          io.addMouseButtonEvent(0, true); frame(false);
          state.drain();
          require(views.probeInspectorReady() && views.probeInspectorTrace() == prior && state.traces.size() == count,
              "Delivery adopted Inspector result before current controls");
          if (mode.equals("trace-escape-cancel")) {
            io.addKeyEvent(ImGuiKey.Escape, true); frame();
            require(views.probeInspectorReady() && views.probeCoordinateTracePending()
                && views.probeInspectorTrace() == prior && state.traces.size() == count,
                "Escape suppression frame adopted ready Inspector result");
            io.addKeyEvent(ImGuiKey.Escape, false);
          }
          if (mode.equals("trace-edit")) views.probeInspectorCoordinateEdit("X", "8");
          io.addMouseButtonEvent(0, false); frame(); frame();
          require(!views.probeCoordinateTracePending() && !views.probeWorldPickPending()
              && views.probeInspectorTrace() == prior && state.traces.size() == count
              && views.probeInspectorRetry() == null && notifications() == notifications,
              "Retired ready Inspector result changed trace/log/retry/failure feedback");
          if (mode.equals("trace-edit")) views.probeInspectorCoordinateEdit("X", "7");
          require(views.probeInspectorCoordinate().equals(ENTERED), "Ready cancellation changed entered XYZ");
          readyRows.add(Map.of("mode", mode, "heldFrames", 12, "readyRetired", true,
              "escapeSuppressed", mode.equals("trace-escape-cancel"), "traceLogsBefore", count,
              "traceLogsAfter", state.traces.size(), "notificationsRetained", true, "coordinateRetained", true));
        }
        require(rows.size() == 8 && readyRows.size() == 6 && state.traces.size() == 10, "Unexpected recovery dimensions");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && !state.document.dirty(),
            "Recovery changed committed data/history");
        require(
            state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-"),
            "Recovery changed private input");
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Recovery changed preferences");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        Files.writeString(
            out.resolve("inspector-worker-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "cases",
                    rows,
                    "traceLogs",
                    state.traces.size(),
                    "readyCases",
                    readyRows,
                    "projectPreserved",
                    true,
                    "privatePreserved",
                    true,
                    "preferencesPreserved",
                    true,
                    "physicalInputVerified",
                    false)));
      }
    }
  }

  private static void retry() {
    frame();
    var point = views.probeAdmissionControl("trace-refresh");
    require(point != null, "Retry Trace control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void until(BooleanSupplier ready) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!ready.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end) throw new AssertionError("Inspector recovery timeout");
      Thread.yield();
    }
    frame();
  }

  private static long notifications() {
    return state.events.snapshot().stream().filter(e -> e.code().equals("APPLICATION_MESSAGE"))
        .mapToLong(EditorEvents.Entry::count).sum();
  }

  private static void untilHeld(BooleanSupplier ready) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!ready.getAsBoolean()) {
      frame(false);
      if (System.nanoTime() > end) throw new AssertionError("Held Inspector timeout");
      Thread.yield();
    }
  }

  private static void frame() { frame(true); }

  private static void frame(boolean drain) {
    if (drain) state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(950, 750, ImGuiCond.Always);
    views.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
