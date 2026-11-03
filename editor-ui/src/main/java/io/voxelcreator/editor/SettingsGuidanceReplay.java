package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Deferred production Help routes, invoked by the existing Project Settings native probe. */
final class SettingsGuidanceReplay {
  private final EditorState state;
  private final ProjectSettingsPanel project;
  private final PerformancePanel performance;
  private final ProfilePanel capture;
  private final ImBoolean captureOpen = new ImBoolean(true);
  private final List<Map<String, Object>> routes = new ArrayList<>();
  private final List<UiTooltip.Receipt> receipts = new ArrayList<>();
  private final Path out;
  private String host = "project";

  private SettingsGuidanceReplay(Path out, EditorState state) {
    this.out = out;
    this.state = state;
    project = new ProjectSettingsPanel(state);
    performance =
        new PerformancePanel(
            state,
            out.resolve("must-not-save.json"),
            message -> {
              throw new AssertionError(message);
            });
    capture =
        new ProfilePanel(
            state,
            target -> {
              throw new AssertionError("Help navigated authoring data");
            });
    performance.probeControls();
    capture.probeWorkControls();
  }

  static void run(Path out, EditorState state) throws Exception {
    Files.createDirectories(out);
    var committed = ProjectIO.JSON.valueToTree(state.project());
    var privateDraft = ProjectIO.JSON.valueToTree(state.draft);
    var user = ProjectIO.JSON.valueToTree(state.user);
    var metadata = ProjectIO.JSON.valueToTree(state.metadata);
    long revision = state.revision, history = state.document.historyBytes();
    var engine = state.engine;
    var currentCapture = engine.profiler().current();
    var replay = new SettingsGuidanceReplay(out, state);
    UiTooltip.observe(replay.receipts::add);
    try {
      require(replay.project.open(null), "Project Settings fixture did not open");
      replay.project.probeText("seed", "9007199254740993");
      replay.project.probeText("minY", Integer.toString(Integer.MIN_VALUE));
      replay.project.probeText("maxY", Integer.toString(Integer.MAX_VALUE));
      replay.frames(5);
      for (String field : List.of("seed", "minY", "maxY")) {
        var bounds =
            field.equals("seed")
                ? replay.project.seedRow().slider()
                : replay.project.boundRows().get(field).slider();
        var input =
            field.equals("seed")
                ? replay.project.seedRow().input()
                : replay.project.boundRows().get(field).input();
        String raw = replay.project.text(field);
        replay.numeric(
            "project/" + field,
            SettingsControlMetadata.project(field),
            raw,
            bounds,
            input,
            replay.project.contextHelp());
        require(
            raw.equals(replay.project.text(field)), "Project Help changed private integer input");
      }
      replay.project.probeText("seed", "-");
      replay.frames(3);
      require(!replay.project.seedRow().enabled(), "Rejected seed slider remained enabled");
      replay.numeric(
          "project/seed-invalid",
          SettingsControlMetadata.project("seed"),
          "-",
          replay.project.seedRow().slider(),
          replay.project.seedRow().input(),
          replay.project.contextHelp());
      require(replay.project.text("seed").equals("-"), "Help replaced rejected seed spelling");
      replay.host = "performance";
      replay.performance.probeEdit(PerformancePreferences.Field.PREVIEW_CELLS, "9007199254740993");
      replay.frames(5);
      for (var field :
          List.of(
              PerformancePreferences.Field.WORKERS, PerformancePreferences.Field.PREVIEW_CELLS)) {
        var row = replay.performance.numericRows().get(field);
        String raw = replay.performance.probeText().get(field);
        replay.numeric(
            "performance/" + field.key(),
            SettingsControlMetadata.performance(field),
            raw,
            row.slider(),
            row.input(),
            replay.performance.contextHelp());
        require(
            raw.equals(replay.performance.probeText().get(field)),
            "Performance Help changed private input");
      }
      replay.host = "capture";
      replay.capture.probeCaptureInput(ProfileCaptureInputs.Field.DURATION, "+0010000");
      replay.frames(5);
      var captureText = replay.capture.captureText();
      var row = replay.capture.captureRows().get(ProfileCaptureInputs.Field.DURATION);
      replay.numeric(
          "capture/duration",
          SettingsControlMetadata.capture(ProfileCaptureInputs.Field.DURATION),
          "+0010000",
          row.slider(),
          row.input(),
          replay.capture.contextHelp());
      replay.control(
          "capture/work",
          "settings.capture.work",
          replay.capture.probeWorkControl("enabled"),
          replay.capture.contextHelp());
      require(
          captureText.equals(replay.capture.captureText())
              && !replay.capture.captureFeedback().options().worldContributions(),
          "Capture Help changed next-capture options");
      require(
          replay.routes.size() == 15 && !Files.exists(out.resolve("must-not-save.json")),
          "Unexpected Help routes or preferences write");
      require(
          committed.equals(ProjectIO.JSON.valueToTree(state.project()))
              && privateDraft.equals(ProjectIO.JSON.valueToTree(state.draft))
              && user.equals(ProjectIO.JSON.valueToTree(state.user))
              && metadata.equals(ProjectIO.JSON.valueToTree(state.metadata))
              && revision == state.revision
              && history == state.document.historyBytes()
              && engine == state.engine
              && currentCapture.equals(engine.profiler().current()),
          "Settings Help changed authoring/preferences/engine/capture ownership");
      Files.writeString(
          out.resolve("settings-guidance-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "formatVersion",
                  1,
                  "routes",
                  replay.routes,
                  "statePreserved",
                  true,
                  "preferencesWritten",
                  false,
                  "captureStarted",
                  false,
                  "physicalInputVerified",
                  false)));
    } finally {
      UiTooltip.observe(null);
      replay.project.cancel();
      replay.performance.suspendActions();
      replay.capture.suspendActions();
    }
  }

  private void numeric(
      String name,
      String topic,
      String raw,
      DialogBounds.Rect slider,
      DialogBounds.Rect input,
      ContextHelp help)
      throws Exception {
    var control = state.metadata.controls.get(topic);
    String old = control.tooltip;
    String configured = "Settings fixture 100% ### snow 雪 / " + name;
    control.tooltip = configured;
    try {
      for (boolean sliding : new boolean[] {true, false}) {
        var bounds = sliding ? slider : input;
        float[] at = {bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2};
        receipts.clear();
        ImGui.getIO().addMousePosEvent(at[0], at[1]);
        frames(4);
        require(
            receipts.stream()
                .anyMatch(
                    receipt ->
                        receipt.preview().contains(configured)
                            && receipt.preview().contains("Text: " + raw)),
            "Configured tooltip lost raw integer text");
        openHelp(at, topic, help);
        routes.add(
            Map.of("case", name, "topic", topic, "part", sliding ? "slider" : "text", "raw", raw));
      }
      var catalog = new HelpCatalog(state.metadata);
      Files.writeString(
          out.resolve("settings-help-" + name.replace('/', '-') + ".txt"),
          catalog.topic(topic).copyText(catalog));
    } finally {
      control.tooltip = old;
    }
  }

  private void control(String name, String topic, float[] at, ContextHelp help) {
    var control = state.metadata.controls.get(topic);
    String old = control.tooltip;
    control.tooltip = "Configured next-capture work guide";
    try {
      receipts.clear();
      ImGui.getIO().addMousePosEvent(at[0], at[1]);
      frames(4);
      require(
          receipts.stream()
              .anyMatch(
                  receipt -> receipt.preview().contains("Configured next-capture work guide")),
          "Configured checkbox guidance missing");
      openHelp(at, topic, help);
      routes.add(Map.of("case", name, "topic", topic, "part", "control", "raw", "false"));
    } finally {
      control.tooltip = old;
    }
  }

  private void openHelp(float[] at, String topic, ContextHelp help) {
    state.helpRequests.take();
    click(at, 1);
    require(
        help != null && help.actionId() != 0 && help.actionCenter() != null,
        "Settings context Help did not open");
    click(help.actionCenter(), 0);
    require(
        state.helpRequests.take().orElseThrow().equals(topic),
        "Settings Help routed another topic");
  }

  private void click(float[] at, int button) {
    ImGui.getIO().addMousePosEvent(at[0], at[1]);
    frame();
    ImGui.getIO().addMouseButtonEvent(button, true);
    frame();
    ImGui.getIO().addMouseButtonEvent(button, false);
    frames(3);
  }

  private void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 820, ImGuiCond.Always);
    if (host.equals("capture"))
      capture.draw(
          captureOpen, new Viewports.StreamingStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, false));
    else {
      ImGui.begin(host.equals("project") ? "Project Settings" : "User Settings");
      if (host.equals("project")) project.draw();
      else performance.draw();
      ImGui.end();
    }
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
