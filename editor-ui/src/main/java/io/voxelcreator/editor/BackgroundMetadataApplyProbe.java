package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production Application Settings controls with held preparation and delivery. */
final class BackgroundMetadataApplyProbe {
  private static ApplicationSettingsPanel panel;
  private static final String VALUE = "noise2D.algorithm.CONSTANT.value";

  static void run(Path out) throws Exception {
    String previous = System.getProperty("voxel.configDir");
    var cases = new ArrayList<Map<String, Object>>();
    try {
      for (String mode : List.of("apply", "cancel", "escape-ready", "revert-ready", "edit", "active", "hidden", "close", "retry")) {
        var directory = out.resolve(mode).toAbsolutePath(); Files.createDirectories(directory);
        System.setProperty("voxel.configDir", directory.toString());
        try (var s = new EditorState(new UserConfig(), false)) {
          s.metadata = AppMetadata.defaults();
          s.metadata.creationDefaults.put(VALUE, ProjectIO.JSON.valueToTree(.25));
          var target = directory.resolve("app-config.json"); s.metadata.save(target);
          byte[] original = Files.readAllBytes(target);
          require(s.change(p -> {
            var n = new Noise(); n.id = "existing"; n.algorithm.key = "CONSTANT";
            n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", .125);
            p.noise2D.add(n);
          }), "Profile fixture rejected");
          s.select("noise2D", s.project().noise2D.getFirst().uuid);
          var properties = (Noise) s.draft; properties.id = "pending";
          s.seedInputs.edit(s, "seedOffset", "Seed", "-");
          var project = ProjectIO.JSON.valueToTree(s.project());
          var user = ProjectIO.JSON.valueToTree(s.user);
          long revision = s.revision, history = s.document.historyBytes();
          var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
          var reject = new AtomicBoolean(mode.equals("retry"));
          panel = new ApplicationSettingsPanel(s, task -> {
            if (reject.get()) throw new RejectedExecutionException("held profile queue");
            tasks.add(task);
          }, ui::add);
          panel.showControl(VALUE, "Creation Defaults");
          panel.probeStageCreation(VALUE, "0.75");
          frames(4);
          click(panel.controlAt("apply"));
          var work = panel.probeApplyWork();
          if (mode.equals("retry")) {
            require(!work.running() && work.message().contains("failed"), "Profile retry missing");
            frames(12); require(tasks.isEmpty(), "Profile retried automatically");
            reject.set(false); click(panel.controlAt("apply"));
          }
          frames(12); require(work.running() && !work.ready(), "Profile bypassed worker");
          s.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
          frames(12);
          require(work.running() && !work.ready() && ui.size() == 1, "Profile bypassed UI delivery");
          require(Arrays.equals(original, Files.readAllBytes(target)), "Preparation replaced profile");
          if (mode.equals("escape-ready")) {
            ui.remove().run(); require(work.ready(), "Profile not ready");
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true); frame();
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(3);
          } else if (mode.equals("revert-ready")) {
            var at = panel.controlAt("revert"); var io = ImGui.getIO();
            io.addMousePosEvent(at[0], at[1]); frame();
            io.addMouseButtonEvent(0, true); frame();
            ui.remove().run(); require(work.ready(), "Profile not ready before Revert release");
            io.addMouseButtonEvent(0, false); frames(3);
          } else {
            if (mode.equals("cancel")) click(panel.controlAt("apply"));
            else if (mode.equals("edit")) panel.probeStageCreation(VALUE, "0.5");
            else if (mode.equals("active")) s.metadata.controls.get("noise").label = "New active";
            else if (mode.equals("hidden")) panel.suspendActions();
            else if (mode.equals("close")) panel.close();
            ui.remove().run();
            require(Arrays.equals(original, Files.readAllBytes(target)), "Delivery replaced profile");
            frames(3);
          }
          boolean applied = mode.equals("apply") || mode.equals("retry");
          require(!work.running(), "Profile remained pending");
          if (applied) require(s.metadata.creationDefaults.get(VALUE).doubleValue() == .75,
              "Wrong active profile");
          else require(Arrays.equals(original, Files.readAllBytes(target)), "Retired profile replaced file");
          require(project.equals(ProjectIO.JSON.valueToTree(s.project()))
              && user.equals(ProjectIO.JSON.valueToTree(s.user))
              && revision == s.revision && history == s.document.historyBytes()
              && s.draft == properties && properties.id.equals("pending")
              && s.seedInputs.text("seedOffset").equals("-"), "Profile modified project/private/preferences");
          var replay = ProjectIO.JSON.convertValue(project, ProjectData.class);
          replay.noise2D.add(EditorCreations.noise(s.metadata, 2, "future", null, "CONSTANT"));
          new ProjectIO().save(directory.resolve("created.json"), replay);
          try (var paths = Files.list(directory)) {
            require(paths.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Profile staging leaked");
          }
          cases.add(Map.of("mode", mode, "applied", applied, "queuedFrames", 12, "heldFrames", 12,
              "projectPreserved", true, "privatePreserved", true, "preferencesPreserved", true,
              "temporaryCleaned", true));
          panel.close(); frames(2);
        }
      }
      Files.writeString(out.resolve("profile-apply-native.json"), ProjectIO.JSON.writeValueAsString(
          Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
    } finally {
      if (previous == null) System.clearProperty("voxel.configDir");
      else System.setProperty("voxel.configDir", previous);
    }
  }

  private static void click(float[] at) {
    require(at != null, "Profile control missing"); var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]); frame();
    io.addMouseButtonEvent(0, true); frame(); io.addMouseButtonEvent(0, false); frames(3);
  }
  private static void frames(int count) { for (int i = 0; i < count; i++) frame(); }
  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1200, 900, ImGuiCond.Always);
    ImGui.begin("Background application profile reference"); panel.draw(); ImGui.end(); ImGui.render();
  }
  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
