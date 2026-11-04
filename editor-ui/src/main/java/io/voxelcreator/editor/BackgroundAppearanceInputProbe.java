package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production theme/binding actions with held worker, delivery and native ownership. */
final class BackgroundAppearanceInputProbe {
  private static ThemePanel theme;
  private static InputSettingsPanel bindings;
  private static EditorState state;
  private static boolean appearance, active;
  private static final ImBoolean open = new ImBoolean(true);

  private static ThemePalette palette() {
    return ThemePalette.starting("Light")
        .color(ImGuiCol.Text, new ThemePalette.Rgba(.125f, .25f, .5f, 1))
        .status("Warning", new ThemePalette.Rgba(.5f, .25f, .125f, .75f));
  }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    float previousScale = ImGui.getIO().getFontGlobalScale();
    var cases = new ArrayList<Map<String, Object>>();
    try {
      for (String mode : List.of("theme-apply", "theme-cancel", "theme-raw", "theme-owner",
          "theme-reopen", "theme-retry", "bindings-apply", "bindings-escape", "bindings-record",
          "bindings-revert", "bindings-hidden", "bindings-retry")) {
        var directory = out.resolve(mode); Files.createDirectories(directory);
        var user = new UserConfig(); user.performance.workers = 2;
        user.workspace.width = 1234; user.appearance.windowScales.put("Extension Pane", 1.75f);
        ThemeStyle.apply(ThemePalette.from(user.appearance));
        var file = directory.resolve("user-config.json"); user.save(file);
        user.save(directory.resolve("user-before.json")); byte[] original = Files.readAllBytes(file);
        try (var s = new EditorState(user, false)) {
          state = s;
          require(s.change(p -> {
            var n = new Noise(); n.id = "field"; n.algorithm.key = "CONSTANT";
            n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", .125); p.noise2D.add(n);
          }), "Appearance/input fixture rejected");
          s.document.save(directory.resolve("before.json"));
          s.select("noise2D", s.project().noise2D.getFirst().uuid);
          var draft = (Noise) s.draft; draft.id = "private";
          s.seedInputs.edit(s, "seedOffset", "Seed", "-");
          var project = ProjectIO.JSON.valueToTree(s.project());
          long revision = s.revision, history = s.document.historyBytes(); var engine = s.engine;
          var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
          var reject = new AtomicBoolean(mode.endsWith("retry"));
          Executor worker = task -> {
            if (reject.get()) throw new RejectedExecutionException("held appearance/input queue"); tasks.add(task);
          };
          appearance = mode.startsWith("theme"); active = true; open.set(true);
          if (appearance) {
            theme = new ThemePanel(user, file, text -> {}, worker, ui::add);
            theme.probeControls(); theme.probeEdit(palette());
          } else {
            bindings = new InputSettingsPanel(user, file, text -> {}, worker, ui::add);
            bindings.probeEdit("Profiler", "alt+f9");
          }
          frames(4);
          if (!appearance) type("filter", "Profiler");
          click(primary()); var work = work();
          if (mode.endsWith("retry")) {
            require(!work.running() && work.message().contains("failed"), "Appearance/input retry unavailable");
            frames(12); require(tasks.isEmpty(), "Appearance/input retried unchanged frames");
            reject.set(false); click(primary());
          }
          frames(12); require(work.running() && !work.ready(), "Appearance/input bypassed worker");
          s.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
          frames(12); require(work.running() && !work.ready() && ui.size() == 1, "Appearance/input bypassed UI delivery");
          require(Arrays.equals(original, Files.readAllBytes(file)), "Preparation replaced preferences");
          Object foreignStyle = null;
          if (mode.equals("theme-cancel") || mode.equals("bindings-revert") || mode.equals("bindings-record")) {
            String action = mode.equals("theme-cancel") ? primary()
                : mode.equals("bindings-revert") ? "revert" : "record:Profiler";
            hold(action); ui.remove().run(); require(work.ready(), "Appearance/input completion not ready");
            ImGui.getIO().addMouseButtonEvent(0, false); frames(3);
            if (mode.equals("bindings-record")) {
              require("Profiler".equals(bindings.recordingAction()), "Ready Apply displaced recording");
              ImGui.getIO().addKeyEvent(ImGuiKey.ModAlt, true);
              ImGui.getIO().addKeyEvent(ImGuiKey.F8, true); frame();
              require(bindings.recording(), "Captured key lost frame suppression");
              ImGui.getIO().addKeyEvent(ImGuiKey.F8, false);
              ImGui.getIO().addKeyEvent(ImGuiKey.ModAlt, false); frames(3);
              require("Alt+F8".equals(bindings.draftValue("Profiler")), "Recording draft lost");
            }
          } else if (mode.equals("bindings-escape")) {
            ui.remove().run(); require(work.ready(), "Bindings not ready before Escape");
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true); frame();
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(3);
          } else if (mode.equals("theme-owner")) {
            ui.remove().run(); require(work.ready(), "Theme not ready before takeover");
            ThemeStyle.apply(ThemePalette.starting("Dark")); foreignStyle = ThemeStyle.owner();
            frames(3); require(theme.probePaused(), "Theme did not yield foreign style");
          } else {
            if (mode.equals("theme-raw")) {
              type("channel-text:status:Warning:R", "+.500");
              require("+.500".equals(theme.probeChannelText("status:Warning", 0)), "Exact RGBA text lost");
            } else if (mode.equals("theme-reopen")) {
              open.set(false); frame(); open.set(true); frames(3);
              require(theme.probeDraft().equals(ThemePalette.from(user.appearance)), "Reopened theme retained old draft");
            } else if (mode.equals("bindings-hidden")) bindings.cancelCapture();
            ui.remove().run();
            require(Arrays.equals(original, Files.readAllBytes(file)), "Delivery published before panel decision");
            frames(3);
          }
          boolean applied = mode.endsWith("apply") || mode.endsWith("retry");
          require(!work.running(), "Appearance/input Apply remained pending");
          if (applied) {
            if (appearance) require(palette().equals(ThemePalette.from(user.appearance)), "Wrong palette activated");
            else require("Alt+F9".equals(user.input.shortcuts.get("Profiler"))
                && "Alt+F9".equals(bindings.draftValue("Profiler")), "Wrong canonical binding activated");
            require(ProjectIO.JSON.valueToTree(user).equals(ProjectIO.JSON.readTree(file.toFile())), "Saved/active preferences differ");
          } else require(Arrays.equals(original, Files.readAllBytes(file)), "Retired appearance/input replaced file");
          require(user.appearance.windowScales.get("Extension Pane") == 1.75f && user.workspace.width == 1234,
              "Unrelated appearance/input preferences changed");
          require(project.equals(ProjectIO.JSON.valueToTree(s.project())) && revision == s.revision
              && history == s.document.historyBytes() && s.engine == engine && s.draft == draft
              && draft.id.equals("private") && s.seedInputs.text("seedOffset").equals("-"),
              "Appearance/input changed project/history/engine/private input");
          close(); frames(2);
          if (appearance) {
            if (foreignStyle != null) require(ThemeStyle.owner() == foreignStyle, "Close replaced foreign style");
            else require(ThemeStyle.snapshot().equals(ThemeStyle.resolve(ThemePalette.from(user.appearance))),
                "Theme close failed to restore accepted active palette");
          }
          s.document.save(directory.resolve("after.json"));
          try (var paths = Files.list(directory)) {
            require(paths.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Appearance/input sibling leaked");
          }
          cases.add(Map.of("mode", mode, "applied", applied, "queuedFrames", 12, "heldFrames", 12,
              "projectRetained", true, "privateRetained", true, "engineRetained", true, "temporaryCleaned", true));
        } finally { close(); }
      }
      Files.writeString(out.resolve("appearance-input-background-native.json"), ProjectIO.JSON.writeValueAsString(
          Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
    } finally { ImGui.getIO().setFontGlobalScale(previousScale); }
  }

  private static String primary() { return appearance ? "save" : "apply"; }
  private static float[] control(String name) { return appearance ? theme.probeControl(name) : bindings.controlAt(name); }
  private static PreferenceApplySession work() { return appearance ? theme.probeApplyWork() : bindings.probeApplyWork(); }
  private static void close() {
    active = false;
    if (appearance && theme != null) theme.dispose();
    else if (bindings != null) bindings.close();
  }
  private static void hold(String name) {
    var at = control(name); require(at != null, "Missing appearance/input control: " + name);
    var io = ImGui.getIO(); io.addMousePosEvent(at[0], at[1]); frame();
    io.addMouseButtonEvent(0, true); frame();
  }
  private static void click(String name) { hold(name); ImGui.getIO().addMouseButtonEvent(0, false); frames(3); }
  private static void type(String name, String text) {
    click(name); var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true); io.addKeyEvent(ImGuiKey.A, true); frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false); io.addKeyEvent(ImGuiKey.A, false); frame();
    io.addInputCharactersUTF8(text); frame();
    io.addKeyEvent(ImGuiKey.Enter, true); frame(); io.addKeyEvent(ImGuiKey.Enter, false); frames(3);
  }
  private static void frames(int count) { for (int i = 0; i < count; i++) frame(); }
  private static void frame() {
    var io = ImGui.getIO(); io.setDisplaySize(1600, 1300); io.setFontGlobalScale(state.user.appearance.globalScale);
    if (!appearance && bindings != null) bindings.beginFrame();
    ImGui.newFrame(); ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1500, 1200, ImGuiCond.Always);
    if (appearance && active) theme.draw(open);
    else {
      ImGui.begin("Background binding reference"); UiScale.window(state.user, "User Settings");
      if (active) bindings.draw(); ImGui.end();
    }
    ImGui.render();
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
