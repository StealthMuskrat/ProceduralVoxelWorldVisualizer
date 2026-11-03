package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Deferred production button recovery, driven by the existing layout probe without external IO. */
final class UpdateRecoveryReplay {
  private static final UpdateChecker.Release RELEASE =
      new UpdateChecker.Release("0.2.0", "https://releases.example.test/recovery");
  private final Deque<Runnable> workers = new ArrayDeque<>(), ui = new ArrayDeque<>();
  private final List<String> reported = new ArrayList<>(),
      opened = new ArrayList<>(),
      copied = new ArrayList<>();
  private final ImBoolean open = new ImBoolean(true);
  private final UpdateCheckSession session;
  private final UpdateCheckPanel panel;
  private String fault = "";
  private int fetches, configured, reporterFaults;

  private UpdateRecoveryReplay(EditorState state) {
    session =
        new UpdateCheckSession(
            state.user,
            "0.1.0",
            task -> {
              if (fault.equals("SCHEDULER")) throw new AssertionError("scheduler-error");
              workers.add(task);
            },
            action -> {
              if (fault.equals("HANDOFF")) throw new AssertionError("ui-handoff-error");
              ui.add(action);
              if (fault.equals("ENQUEUE")) throw new AssertionError("enqueue-then-reject");
            },
            (endpoint, origins) -> {
              fetches++;
              if (fault.equals("FETCH") || fault.equals("FETCH_REPORT"))
                throw new AssertionError("fetch-error");
              return RELEASE;
            },
            this::report);
    panel =
        new UpdateCheckPanel(
            session,
            "0.1.0",
            () -> {
              if (fault.equals("CONFIGURE")) throw new AssertionError("configure-error");
              configured++;
            },
            uri -> {
              if (fault.equals("REPLACE")) {
                session.cancel();
                require(session.start(), "Reentrant request refused");
                throw new AssertionError("obsolete-browser-error");
              }
              if (fault.equals("OPEN")) throw new AssertionError("browser-error");
              opened.add(uri.toString());
            },
            text -> {
              if (fault.equals("COPY_REPORT")) throw new AssertionError("clipboard-error");
              copied.add(text);
            },
            this::report);
    panel.probeControls();
  }

  private void report(String message) {
    reported.add(message);
    if (fault.equals("FETCH_REPORT") || fault.equals("COPY_REPORT")) {
      reporterFaults++;
      throw new AssertionError("reporter-error");
    }
  }

  static void run(Path out, EditorState state) throws Exception {
    Files.createDirectories(out);
    var project = ProjectIO.JSON.valueToTree(state.project());
    var draft = ProjectIO.JSON.valueToTree(state.draft);
    var preferences = ProjectIO.JSON.valueToTree(state.user);
    var metadata = ProjectIO.JSON.valueToTree(state.metadata);
    long revision = state.revision, history = state.document.historyBytes();
    var replay = new UpdateRecoveryReplay(state);
    var cases = new ArrayList<Map<String, Object>>();
    try {
      replay.frames(4);
      for (String mode : List.of("SCHEDULER", "FETCH", "HANDOFF", "ENQUEUE", "FETCH_REPORT")) {
        replay.fault = mode;
        replay.click("check");
        if (!mode.equals("SCHEDULER")) {
          require(replay.workers.size() == 1, "Check did not queue exactly one worker");
          int reports = replay.reported.size();
          replay.workers.removeFirst().run();
          require(replay.reported.size() == reports, "Worker called the UI error sink");
          replay.frames(4);
        }
        require(
            replay.session.view().phase() == UpdateCheckSession.Phase.FAILED
                && replay.session.view().result() == null,
            "Fault left a running request or exposed a result");
        if (mode.equals("FETCH_REPORT"))
          require(
              replay.session.view().message().contains("Error notification failed: reporter-error"),
              "Reporter fault hid primary feedback");
        replay.capture(cases, mode);
        replay.fault = "";
        replay.ready();
      }
      for (String mode : List.of("OPEN", "COPY_REPORT", "CONFIGURE")) {
        replay.fault = mode;
        var reviewed = replay.session.view().result();
        long version = replay.panel.actionScope.version();
        var ids = replay.panel.chrome.actionIds();
        replay.click(
            mode.equals("OPEN") ? "open" : mode.equals("COPY_REPORT") ? "copy" : "configure");
        require(
            replay.session.view().result() == reviewed
                && replay.panel.actionScope.version() == version
                && ids.equals(replay.panel.chrome.actionIds()),
            "Callback fault lost the reviewed result or retry IDs");
        require(
            replay.panel.probeMessage().contains("failed:"),
            "Callback fault escaped local feedback");
        if (mode.equals("COPY_REPORT"))
          require(
              replay.panel.probeMessage().contains("Error notification failed: reporter-error"),
              "Link reporter fault hid primary feedback");
        replay.capture(cases, mode);
        replay.fault = "";
        replay.click(
            mode.equals("OPEN") ? "open" : mode.equals("COPY_REPORT") ? "copy" : "configure");
      }
      replay.fault = "REPLACE";
      replay.click("open");
      require(
          replay.session.view().phase() == UpdateCheckSession.Phase.RUNNING
              && replay.panel.probeMessage().isEmpty(),
          "Old action error contaminated a replacement request");
      replay.capture(cases, "REPLACE");
      replay.fault = "";
      replay.workers.removeFirst().run();
      replay.frames(4);
      require(
          replay.session.view().phase() == UpdateCheckSession.Phase.READY
              && replay.panel.probeMessage().isEmpty(),
          "Replacement request could not publish cleanly");
      // A window already hidden by its host retires before observing a failed handoff.
      replay.fault = "HANDOFF";
      replay.click("check");
      replay.workers.removeFirst().run();
      int notifications = replay.reported.size();
      replay.open.set(false);
      replay.frame();
      require(
          replay.session.view().phase() == UpdateCheckSession.Phase.CANCELLED
              && replay.reported.size() == notifications,
          "Hidden result reported an obsolete task fault");
      replay.capture(cases, "HIDDEN");
      replay.fault = "";
      replay.open.set(true);
      replay.frames(4);
      replay.ready();
      require(
          replay.fetches == 12
              && replay.reported.size() == 9
              && replay.reporterFaults == 2
              && replay.configured == 1
              && replay.opened.equals(List.of(RELEASE.releaseUrl()))
              && replay.copied.equals(List.of(RELEASE.releaseUrl()))
              && replay.workers.isEmpty()
              && replay.ui.isEmpty(),
          "Unexpected recovery replay counts");
      require(
          project.equals(ProjectIO.JSON.valueToTree(state.project()))
              && draft.equals(ProjectIO.JSON.valueToTree(state.draft))
              && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
              && metadata.equals(ProjectIO.JSON.valueToTree(state.metadata))
              && revision == state.revision
              && history == state.document.historyBytes()
              && state.seedInputs.text("seedOffset").equals("-"),
          "Recovery changed authoring/preferences/raw input ownership");
      Files.writeString(
          out.resolve("update-recovery-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.ofEntries(
                  Map.entry("formatVersion", 1),
                  Map.entry("cases", cases),
                  Map.entry("fetchCalls", replay.fetches),
                  Map.entry("errorReports", replay.reported.size()),
                  Map.entry("reporterFaults", replay.reporterFaults),
                  Map.entry("configured", replay.configured),
                  Map.entry("openedTargets", replay.opened),
                  Map.entry("copiedTargets", replay.copied),
                  Map.entry("networkRequests", 0),
                  Map.entry("externalBrowserLaunches", 0),
                  Map.entry("projectPreserved", true),
                  Map.entry("physicalInputVerified", false))));
    } finally {
      replay.panel.close();
    }
  }

  private void capture(List<Map<String, Object>> cases, String mode) {
    var view = session.view();
    cases.add(
        Map.of(
            "mode",
            mode,
            "phase",
            view.phase(),
            "status",
            view.message(),
            "feedback",
            panel.probeMessage()));
  }

  private void ready() {
    click("check");
    require(workers.size() == 1, "Retry did not queue exactly one worker");
    workers.removeFirst().run();
    require(
        session.view().phase() == UpdateCheckSession.Phase.RUNNING,
        "Normal completion bypassed UI publication");
    frames(4);
    require(
        session.view().phase() == UpdateCheckSession.Phase.READY, "Explicit retry did not recover");
  }

  private void click(String key) {
    var at = panel.probeControl(key);
    require(at != null, "Recovery control missing: " + key);
    ImGui.getIO().addMousePosEvent(at[0], at[1]);
    frame();
    ImGui.getIO().addMouseButtonEvent(0, true);
    frame();
    ImGui.getIO().addMouseButtonEvent(0, false);
    frames(3);
  }

  private void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private void frame() {
    while (!ui.isEmpty()) ui.removeFirst().run();
    ImGui.getIO().setDisplaySize(1200, 900);
    ImGui.newFrame();
    ImGui.setNextWindowPos(10, 10, ImGuiCond.Always);
    ImGui.setNextWindowSize(1000, 700, ImGuiCond.Always);
    panel.draw(open);
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
