package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred real Properties footer with held preparation/UI delivery and complete saved replay. */
final class PropertiesApplyProbe {
  private static PropertyPanel panel;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    ImGui.getIO().setDisplaySize(1400, 1000);
    for (String mode : List.of("success", "cancel", "escape", "raw", "retry", "close")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      try (var state = new EditorState(new UserConfig(), false)) {
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.put("Properties", 1f);
        require(
            state.change(
                p -> {
                  p.project.seed = 17;
                  var n = new Noise();
                  n.id = "field";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Apply fixture");
        state.document.save(directory.resolve("before.json"));
        byte[] bytes = Files.readAllBytes(directory.resolve("before.json"));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).algorithm.parameters.put("value", .625);
        var draft = state.draft;
        var draftValue = ProjectIO.JSON.valueToTree(draft);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var tasks = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(mode.equals("retry"));
        state.document.setValidator(
            p -> {
              if (fail.getAndSet(false)) throw new AssertionError("held Apply failure");
              return NoiseCompiler.validate(p);
            });
        panel = new PropertyPanel(state, tasks::add, ui::add);
        var apply = panel.probeApplySession();
        try {
          frames(4);
          click(panel.probeAction("apply"));
          require(apply.running(), "Footer Apply did not start");
          for (int i = 0; i < 12; i++) {
            frame();
            require(
                apply.running()
                    && state.project().noise2D.getFirst().algorithm.parameters.get("value") == .125,
                "Apply changed committed values while preparing");
            require(panel.chrome.body() != null, "Properties body missing");
            for (var box : panel.chrome.actions().values())
              require(box.width() > 0 && box.height() > 0, "Collapsed Apply action");
          }
          state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
          require(apply.running() && state.draft == draft, "Worker published without UI delivery");
          if (mode.equals("cancel")) click(panel.probeAction("apply"));
          else if (mode.equals("escape")) {
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
            frame();
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
            frames(3);
          } else if (mode.equals("raw")) state.project().project.seed = 18;
          else if (mode.equals("close")) panel.close();
          ui.remove().run();
          frames(3);
          if (mode.equals("retry")) {
            require(
                apply.view().phase() == PropertiesApplySession.Phase.FAILED && state.draft == draft,
                "Worker failure lost private correction");
            click(panel.probeAction("apply"));
            state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
            ui.remove().run();
            frames(3);
          }
          boolean applied = mode.equals("success") || mode.equals("retry");
          require(
              apply.view().phase()
                  == (applied
                      ? PropertiesApplySession.Phase.APPLIED
                      : mode.equals("raw")
                          ? PropertiesApplySession.Phase.STALE
                          : mode.equals("close")
                              ? PropertiesApplySession.Phase.CLOSED
                              : PropertiesApplySession.Phase.CANCELLED),
              "Unexpected Apply terminal phase");
          if (!applied)
            require(
                state.draft == draft && draftValue.equals(ProjectIO.JSON.valueToTree(state.draft)),
                "Retired Apply replaced private values");
          require(
              preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                  && Arrays.equals(bytes, Files.readAllBytes(directory.resolve("before.json"))),
              "Apply changed preferences or original bytes");
          state.document.save(directory.resolve("after.json"));
          if (applied) {
            var accepted = ProjectIO.JSON.valueToTree(state.project());
            state.document.undo();
            require(
                state.project().noise2D.getFirst().algorithm.parameters.get("value") == .125,
                "Apply Undo");
            state.document.save(directory.resolve("undone.json"));
            state.document.redo();
            require(accepted.equals(ProjectIO.JSON.valueToTree(state.project())), "Apply Redo");
            state.document.save(directory.resolve("redone.json"));
          }
          Files.writeString(
              directory.resolve("preferences.json"), ProjectIO.JSON.writeValueAsString(state.user));
          cases.add(
              Map.of(
                  "mode",
                  mode,
                  "applied",
                  applied,
                  "responsiveFrames",
                  12,
                  "privateBoundary",
                  true,
                  "preferencesRetained",
                  true,
                  "originalBytesRetained",
                  true,
                  "oneUndoRedo",
                  applied));
        } finally {
          panel.close();
        }
      }
    }
    Files.writeString(
        out.resolve("properties-apply.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static void click(float[] at) {
    require(at != null, "Apply control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(30, 30, ImGuiCond.Always);
    ImGui.setNextWindowSize(650, 800, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(false, ImGuiCond.Always);
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
