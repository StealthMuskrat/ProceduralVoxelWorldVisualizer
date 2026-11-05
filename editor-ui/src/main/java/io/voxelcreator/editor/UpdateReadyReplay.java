package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Deferred ready-frame update decisions with captured metadata and no external actions. */
final class UpdateReadyReplay {
  private static final UpdateChecker.Release RELEASE =
      new UpdateChecker.Release("0.2.0", "https://releases.example.test/ready");
  private final ArrayDeque<Runnable> workers = new ArrayDeque<>(), ui = new ArrayDeque<>();
  private final List<String> errors = new ArrayList<>(), opened = new ArrayList<>(), copied = new ArrayList<>();
  private final ImBoolean open = new ImBoolean(true);
  private final UpdateCheckSession session;
  private final UpdateCheckPanel panel;
  private boolean collapsed, inlineSuccessor;
  private int fetches, configured;

  private UpdateReadyReplay(EditorState state, String mode) {
    session = new UpdateCheckSession(state.user, "0.1.0",
        task -> { if (inlineSuccessor) task.run(); else workers.add(task); },
        callback -> { if (inlineSuccessor) callback.run(); else ui.add(callback); },
        (endpoint, origins) -> {
          fetches++;
          if (mode.equals("ready-failure-cancel")) throw new AssertionError("ready worker failure");
          return mode.equals("ready-invalid-cancel") ? new UpdateChecker.Release("invalid", RELEASE.releaseUrl()) : RELEASE;
        }, errors::add);
    panel = new UpdateCheckPanel(session, "0.1.0", () -> {
      configured++; inlineSuccessor = true; session.cancel(); session.start();
    }, uri -> opened.add(uri.toString()), copied::add, errors::add);
    panel.probeControls();
  }

  static void run(Path out, EditorState state) throws Exception {
    Files.createDirectories(out);
    var project = ProjectIO.JSON.valueToTree(state.project()); var draft = state.draft;
    var privateValues = ProjectIO.JSON.valueToTree(draft); var user = ProjectIO.JSON.valueToTree(state.user);
    var metadata = ProjectIO.JSON.valueToTree(state.metadata); var engine = state.engine;
    long revision = state.revision, bytes = state.document.historyBytes(), raw = state.seedInputs.version(state);
    boolean dirty = state.document.dirty(); var cases = new ArrayList<Map<String, Object>>();
    int fetches = 0, configured = 0;
    for (String mode : List.of("ready-cancel", "ready-escape", "ready-close", "ready-failure-cancel",
        "ready-invalid-cancel", "ready-collapse", "ready-configure")) {
      var replay = new UpdateReadyReplay(state, mode);
      try {
        replay.frames(4); replay.click("check");
        require(replay.workers.size() == 1, "One explicit ready update check");
        replay.workers.remove().run();
        var request = replay.session.requestToken(); long scope = replay.panel.actionScope.version();
        int cancelId = replay.panel.chrome.actionIds().get("cancel");
        if (mode.endsWith("cancel")) replay.hold("cancel");
        else if (mode.equals("ready-configure")) replay.hold("configure");
        replay.ui.remove().run();
        require(replay.session.view().phase() == UpdateCheckSession.Phase.RUNNING
            && replay.session.view().result() == null && replay.session.requestToken() == request
            && replay.panel.actionScope.version() == scope
            && replay.panel.chrome.actionIds().get("cancel") == cancelId && replay.errors.isEmpty(),
            "Ready metadata exposed a result/failure or changed Cancel authority");
        if (mode.equals("ready-escape")) ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
        else if (mode.equals("ready-close")) replay.open.set(false);
        else if (mode.equals("ready-collapse")) replay.collapsed = true;
        else ImGui.getIO().addMouseButtonEvent(0, false);
        replay.frame();
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
        if (mode.equals("ready-configure"))
          require(replay.session.view().phase() == UpdateCheckSession.Phase.RUNNING
              && replay.session.view().result() == null && replay.workers.isEmpty() && replay.ui.isEmpty()
              && replay.panel.probeMessage().isEmpty(), "Configure authorized or contaminated a ready successor");
        replay.frames(3);
        boolean accepted = mode.equals("ready-collapse") || mode.equals("ready-configure");
        require(replay.session.view().phase() == (accepted ? UpdateCheckSession.Phase.READY : UpdateCheckSession.Phase.CANCELLED)
            && (replay.session.view().result() != null) == accepted && replay.errors.isEmpty()
            && replay.opened.isEmpty() && replay.copied.isEmpty() && replay.workers.isEmpty() && replay.ui.isEmpty(),
            "Ready update decision published retired metadata, reported cancelled work or acted on a link");
        if (replay.collapsed) { replay.collapsed = false; replay.frames(3); }
        require(project.equals(ProjectIO.JSON.valueToTree(state.project())) && state.draft == draft
            && privateValues.equals(ProjectIO.JSON.valueToTree(draft)) && user.equals(ProjectIO.JSON.valueToTree(state.user))
            && metadata.equals(ProjectIO.JSON.valueToTree(state.metadata)) && revision == state.revision
            && bytes == state.document.historyBytes() && raw == state.seedInputs.version(state)
            && dirty == state.document.dirty() && state.engine == engine, "Ready update changed another owned domain");
        cases.add(Map.of("mode", mode, "phase", replay.session.view().phase(), "resultExposed", accepted,
            "stableReadyToken", true, "errors", replay.errors.size(), "fetchCalls", replay.fetches,
            "configured", replay.configured, "manualLinkActions", 0));
        fetches += replay.fetches; configured += replay.configured;
      } finally { replay.panel.close(); }
    }
    require(fetches == 8 && configured == 1, "Ready update request/configure counts");
    Files.writeString(out.resolve("update-ready-native.json"), ProjectIO.JSON.writeValueAsString(Map.ofEntries(
        Map.entry("formatVersion", 1), Map.entry("cases", cases), Map.entry("fetchCalls", fetches),
        Map.entry("configured", configured), Map.entry("errorReports", 0), Map.entry("projectPreserved", true),
        Map.entry("networkRequests", 0), Map.entry("externalBrowserLaunches", 0), Map.entry("physicalInputVerified", false))));
  }

  private void hold(String key) {
    var at = panel.probeControl(key); require(at != null, "Ready update control missing " + key);
    ImGui.getIO().addMousePosEvent(at[0], at[1]); frame();
    ImGui.getIO().addMouseButtonEvent(0, true); frame();
  }

  private void click(String key) {
    hold(key); ImGui.getIO().addMouseButtonEvent(0, false); frames(3);
  }

  private void frames(int count) { for (int i = 0; i < count; i++) frame(); }

  private void frame() {
    ImGui.getIO().setDisplaySize(1200, 900); ImGui.getIO().setFontGlobalScale(1); ImGui.newFrame();
    if (open.get()) {
      ImGui.setNextWindowPos(10, 10, ImGuiCond.Always); ImGui.setNextWindowSize(1000, 700, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always); ImGui.setNextWindowFocus();
    }
    panel.draw(open); ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }
}
