package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferred production export footer with real worker encoding and held UI delivery. */
final class BackgroundProfileExportProbe {
  private static ProfilePanel panel;
  private static EditorState state;
  private static final ImBoolean open = new ImBoolean(true);
  private static boolean collapsed;
  private static final Viewports.StreamingStatistics EMPTY =
      new Viewports.StreamingStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, false);

  static void run(Path out) throws Exception {
    var cases = new ArrayList<Map<String, Object>>(); Files.createDirectories(out);
    for (String mode : List.of("json", "csv", "ready-cancel", "ready-failure-cancel",
        "ready-escape", "ready-close", "ready-collapse", "ready-path")) {
      var directory = out.resolve(mode); Files.createDirectories(directory); open.set(true); collapsed = false;
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(project -> {
          var noise = new ProjectData.Noise(); noise.id = "field"; noise.algorithm.key = "CONSTANT";
          noise.algorithm.parameters.clear(); noise.algorithm.parameters.put("value", .125); project.noise2D.add(noise);
        }), "Profiler background fixture rejected");
        state.document.save(directory.resolve("before.json"));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (ProjectData.Noise) state.draft; draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project()); var preferences = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        var capture = state.engine.profiler().start(Profiler.Options.defaults());
        state.engine.profiler().scope(7, state.project().project.uuid, null)
            .gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 123, "reviewed,雪");
        state.engine.profiler().stop(); var frozen = capture.snapshot();
        boolean csv = mode.equals("csv"); var target = directory.resolve(csv ? "capture.csv" : "capture.json");
        Files.writeString(target, "previous capture");
        var workers = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
        var failedWrite = new java.util.concurrent.atomic.AtomicBoolean();
        var files = mode.equals("ready-failure-cancel") ? new ProfileExportFiles(new ProfileExportFiles.Storage() {
          public void write(Path path, Profiler.Snapshot value, boolean format) throws java.io.IOException {
            if (format) ProfileIO.writeCSV(path, value); else ProfileIO.writeJSON(path, value);
            failedWrite.set(true); throw new AssertionError("held capture preparation failure");
          }
        }) : new ProfileExportFiles();
        try (var owner = new ProfilePanel(state, targetLink -> {}, workers::add, ui::add, files)) {
          panel = owner; panel.probeWorkControls(); panel.probeReview(frozen); panel.probeExportPath(target.toString());
          frames(3); click(csv ? "export-csv" : "export-json"); var work = panel.probeExportWork();
          frames(12); require(work.running() && !work.ready() && workers.size() == 1, "Export bypassed worker");
          state.background.submit(workers.remove()).get(10, TimeUnit.SECONDS);
          frames(12); require(work.running() && !work.ready() && ui.size() == 1, "Export bypassed UI delivery");
          require(Files.readString(target).equals("previous capture"), "Worker replaced capture destination");
          long version = work.version();
          boolean heldCancel = mode.equals("ready-cancel") || mode.equals("ready-failure-cancel");
          if (heldCancel) {
            var at = panel.probeWorkControl("cancel-export"); require(at != null, "Cancel Export missing");
            ImGui.getIO().addMousePosEvent(at[0], at[1]); frame(); ImGui.getIO().addMouseButtonEvent(0, true); frame();
          }
          ui.remove().run();
          require(work.ready() && work.running() && version == work.version()
              && Files.readString(target).equals("previous capture"), "Delivery published or changed Cancel identity");
          if (heldCancel) ImGui.getIO().addMouseButtonEvent(0, false);
          else if (mode.equals("ready-escape")) ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
          else if (mode.equals("ready-close")) open.set(false);
          else if (mode.equals("ready-collapse")) collapsed = true;
          else if (mode.equals("ready-path")) panel.probeExportPath(target.resolveSibling("./capture.json").toString());
          frame(); ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(2);
          boolean published = mode.equals("json") || csv;
          require(!work.running() && panel.exportReview().available() != published, "Export did not settle with exact review ownership");
          if (published) {
            if (csv) ProfileIO.writeJSON(directory.resolve("capture.json"), frozen);
            else ProfileIO.writeCSV(directory.resolve("capture.csv"), frozen);
            var saved = ProjectIO.JSON.readTree(directory.resolve("capture.json").toFile());
            require(saved.path("captureId").asText().equals(frozen.captureId().toString())
                && saved.path("samples").size() == 1 && saved.path("samples").get(0).path("value").asLong() == 123,
                "Export lost frozen capture values");
          } else require(Files.readString(target).equals("previous capture"), "Retired export replaced previous destination");
          require(failedWrite.get() == mode.equals("ready-failure-cancel"), "Capture failure injection was not observed");
          require(state.events.snapshot().stream().noneMatch(e -> e.code().equals("PROFILE_EXPORT_FAILED")),
              "Retired preparation reported a primary capture export failure");
          require(project.equals(ProjectIO.JSON.valueToTree(state.project())) && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
              && revision == state.revision && history == state.document.historyBytes()
              && state.draft == draft && draft.id.equals("private") && state.seedInputs.text("seedOffset").equals("-"),
              "Capture export changed authored/private/preferences state");
          state.document.save(directory.resolve("after.json"));
          try (var paths = Files.list(directory)) {
            require(paths.noneMatch(p -> p.getFileName().toString().startsWith(".profile-")), "Capture staging leaked");
          }
          cases.add(Map.of("mode", mode, "published", published, "queuedFrames", 12, "heldFrames", 12,
              "readyRetired", !published, "failureHeld", failedWrite.get(), "projectPreserved", true,
              "privatePreserved", true, "preferencesPreserved", true, "temporaryCleaned", true));
        }
      }
    }
    Files.writeString(out.resolve("profile-export-background-native.json"), ProjectIO.JSON.writeValueAsString(
        Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static void click(String key) {
    var at = panel.probeWorkControl(key); require(at != null, "Profiler export control missing");
    var io = ImGui.getIO(); io.addMousePosEvent(at[0], at[1]); frame(); io.addMouseButtonEvent(0, true); frame();
    io.addMouseButtonEvent(0, false); frames(2);
  }
  private static void frames(int count) { for (int i = 0; i < count; i++) frame(); }
  private static void frame() {
    state.drain(); ImGui.newFrame(); ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1300, 1500, ImGuiCond.Always); ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
    panel.draw(open, EMPTY); ImGui.render();
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
