package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production limits/scales/trust footers with held worker and UI publication. */
final class BackgroundPreferenceProbe {
  private static PerformancePanel limits;
  private static UiScalePanel scales;
  private static UpdateTrustPanel trust;
  private static EditorState state;
  private static int kind;
  private static final String ENDPOINT = "https://updates.example.invalid/latest.json";
  private static final String ORIGIN = "https://updates.example.invalid";

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    float previousScale = ImGui.getIO().getFontGlobalScale();
    var cases = new ArrayList<Map<String, Object>>();
    try {
      for (String mode : List.of("limits-apply", "limits-cancel", "limits-retry", "scales-apply", "scales-escape", "scales-edit", "trust-apply", "trust-revert", "trust-hidden", "limits-ready-cancel", "limits-ready-failure-cancel")) {
        var directory = out.resolve(mode); Files.createDirectories(directory);
        var brokenWrite = new AtomicBoolean();
        var failedWrite = new AtomicBoolean();
        var user = mode.equals("limits-ready-failure-cancel")
            ? new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
              public void write(Path temp, byte[] bytes) throws java.io.IOException {
                Files.write(temp, bytes);
                if (brokenWrite.get()) { failedWrite.set(true); throw new AssertionError("held preference preparation"); }
              }
              public void replace(Path temp, Path target) throws java.io.IOException {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
              }
            })) : new UserConfig();
        user.performance.workers = 2;
        user.workspace.width = 1234; user.appearance.windowScales.put("Extension Pane", 1.75f);
        var file = directory.resolve("user-config.json"); user.save(file);
        user.save(directory.resolve("user-before.json"));
        byte[] original = Files.readAllBytes(file);
        try (var s = new EditorState(user, false)) {
          state = s;
          require(s.change(p -> {
            var n = new Noise(); n.id = "field"; n.algorithm.key = "CONSTANT";
            n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", .125); p.noise2D.add(n);
          }), "Preference fixture rejected");
          s.document.save(directory.resolve("before.json"));
          s.select("noise2D", s.project().noise2D.getFirst().uuid);
          var draft = (Noise) s.draft; draft.id = "private";
          s.seedInputs.edit(s, "seedOffset", "Seed", "-");
          var project = ProjectIO.JSON.valueToTree(s.project());
          long revision = s.revision, history = s.document.historyBytes(); var engine = s.engine;
          var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
          var reject = new AtomicBoolean(mode.equals("limits-retry"));
          java.util.concurrent.Executor worker = task -> {
            if (reject.get()) throw new RejectedExecutionException("held preference queue"); tasks.add(task);
          };
          var reports = new ArrayList<String>();
          kind = mode.startsWith("limits") ? 0 : mode.startsWith("scales") ? 1 : 2;
          if (kind == 0) {
            limits = new PerformancePanel(s, file, reports::add, worker, ui::add); limits.probeControls();
            limits.probeEdit(PerformancePreferences.Field.CACHE_MIB, "256");
          } else if (kind == 1) {
            scales = new UiScalePanel(user, file, reports::add, worker, ui::add); scales.probeControls();
            scales.probeEditGlobal("1.25"); scales.probeEditWindow("Resources", "1.5");
          } else {
            trust = new UpdateTrustPanel(user, file, reports::add, worker, ui::add); trust.probeControls();
            trust.probeEdit(ENDPOINT, ORIGIN);
          }
          frames(4); click(control("apply")); var work = work();
          if (mode.equals("limits-retry")) {
            require(!work.running() && work.message().contains("failed"), "Preference retry unavailable");
            frames(12); require(tasks.isEmpty(), "Preference retried unchanged frames");
            reject.set(false); click(control("apply"));
          }
          frames(12); require(work.running() && !work.ready(), "Preferences bypassed worker");
          brokenWrite.set(mode.equals("limits-ready-failure-cancel"));
          s.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
          frames(12); require(work.running() && !work.ready() && ui.size() == 1, "Preferences bypassed UI delivery");
          require(Arrays.equals(original, Files.readAllBytes(file)), "Preparation replaced preferences");
          boolean readyRetired = mode.startsWith("limits-ready-");
          boolean failureHeld = failedWrite.get();
          require(failureHeld == mode.equals("limits-ready-failure-cancel"), "Preparation failure injection was not observed");
          if (readyRetired) {
            var at = control("apply"); var io = ImGui.getIO();
            io.addMousePosEvent(at[0], at[1]); frame(); io.addMouseButtonEvent(0, true); frame();
            long version = work.version();
            ui.remove().run();
            require(work.ready() && work.running() && version == work.version(), "Ready preference changed Cancel identity");
            require(reports.isEmpty(), "Preparation failure reported before current Cancel");
            io.addMouseButtonEvent(0, false); frames(3);
            require(reports.isEmpty(), "Current Cancel reported retired preparation failure");
          } else if (mode.equals("scales-escape")) {
            ui.remove().run(); require(work.ready(), "Preferences not ready");
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true); frame();
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(3);
          } else if (mode.equals("trust-revert")) {
            var at = control("revert"); var io = ImGui.getIO();
            io.addMousePosEvent(at[0], at[1]); frame(); io.addMouseButtonEvent(0, true); frame();
            ui.remove().run(); require(work.ready(), "Preferences not ready before Revert release");
            io.addMouseButtonEvent(0, false); frames(3);
          } else {
            if (mode.equals("limits-cancel")) click(control("apply"));
            else if (mode.equals("scales-edit")) scales.probeEditGlobal("1.250");
            else if (mode.equals("trust-hidden")) trust.suspendActions();
            ui.remove().run();
            require(Arrays.equals(original, Files.readAllBytes(file)), "Delivery published before panel input decision");
            frames(3);
          }
          boolean applied = mode.endsWith("apply") || mode.equals("limits-retry");
          require(!work.running(), "Preference Apply remained pending");
          if (applied) {
            if (kind == 0) require(user.performance.cacheMiB == 256, "Wrong limits activated");
            else if (kind == 1) require(user.appearance.globalScale == 1.25f
                && user.appearance.windowScales.get("Resources") == 1.5f, "Wrong scales activated");
            else require(user.performance.updateEndpoint.equals(ENDPOINT)
                && user.performance.trustedOrigins.equals(List.of(ORIGIN)), "Wrong trust activated");
            require(ProjectIO.JSON.valueToTree(user).equals(ProjectIO.JSON.readTree(file.toFile())), "Saved/active preferences differ");
          } else require(Arrays.equals(original, Files.readAllBytes(file)), "Retired preference write replaced file");
          require(user.appearance.windowScales.get("Extension Pane") == 1.75f
              && user.workspace.width == 1234, "Unrelated preferences changed");
          require(project.equals(ProjectIO.JSON.valueToTree(s.project()))
              && revision == s.revision && history == s.document.historyBytes() && s.engine == engine
              && s.draft == draft && draft.id.equals("private") && s.seedInputs.text("seedOffset").equals("-"),
              "Preferences changed project/history/engine/private input");
          s.document.save(directory.resolve("after.json"));
          try (var paths = Files.list(directory)) {
            require(paths.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Unpublished preference sibling leaked");
          }
          cases.add(Map.of("mode", mode, "applied", applied, "queuedFrames", 12, "heldFrames", 12,
              "projectRetained", true, "privateRetained", true, "engineRetained", true, "temporaryCleaned", true, "readyRetired", readyRetired, "failureHeld", failureHeld));
          close(); frames(2);
        }
      }
      BackgroundAppearanceInputProbe.run(out.resolve("appearance-input"));
      PreferenceSaveProbe.run(out.resolve("active-save"));
      Files.writeString(out.resolve("preferences-background-native.json"), ProjectIO.JSON.writeValueAsString(
          Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
    } finally { ImGui.getIO().setFontGlobalScale(previousScale); }
  }

  private static float[] control(String name) {
    return switch (kind) { case 0 -> limits.probeControl(name); case 1 -> scales.probeControl(name); default -> trust.probeControl(name); };
  }
  private static PreferenceApplySession work() {
    return switch (kind) { case 0 -> limits.probeApplyWork(); case 1 -> scales.probeApplyWork(); default -> trust.probeApplyWork(); };
  }
  private static void close() {
    switch (kind) { case 0 -> limits.close(); case 1 -> scales.close(); case 2 -> trust.close(); }
  }
  private static void click(float[] at) {
    require(at != null, "Preference control missing"); var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]); frame(); io.addMouseButtonEvent(0, true); frame();
    io.addMouseButtonEvent(0, false); frames(3);
  }
  private static void frames(int count) { for (int i = 0; i < count; i++) frame(); }
  private static void frame() {
    var io = ImGui.getIO(); io.setDisplaySize(1600, 1300); io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame(); ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1500, 1200, ImGuiCond.Always); ImGui.begin("Background preference reference");
    UiScale.window(state.user, "User Settings");
    switch (kind) { case 0 -> limits.draw(); case 1 -> scales.draw(); case 2 -> trust.draw(); }
    ImGui.end(); ImGui.render();
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
