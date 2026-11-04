package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production Curve Apply frames with held worker and UI delivery. */
final class BackgroundCurveApplyProbe {
  private static PropertyPanel panel;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("apply", "cancel-apply", "escape-ready", "cancel-ready", "cancel", "retry", "edit", "stale", "close")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      try (var s = new EditorState(new UserConfig(), false)) {
        require(s.change(p -> {
          var n = new Noise();
          n.id = "curve";
          var item = ProcessingRegistry.create("CURVE", "curve");
          var binding = Binding.constant(0);
          binding.sourceKind = "Ambient Value";
          binding.ambientKey = "x";
          item.inputs.put("a", binding);
          n.processing.add(item);
          n.outputBinding = Binding.earlier(item.uuid);
          p.noise2D.add(n);
        }), "Curve fixture rejected");
        s.select("noise2D", s.project().noise2D.getFirst().uuid);
        var draft = (Noise) s.draft;
        draft.id = "private";
        s.seedInputs.edit(s, "seedOffset", "Seed", "-");
        s.document.save(directory.resolve("before.json"));
        var originalBytes = Files.readAllBytes(directory.resolve("before.json"));
        var committed = ProjectIO.JSON.valueToTree(s.project());
        var privateBefore = ProjectIO.JSON.valueToTree(draft);
        var preferences = ProjectIO.JSON.valueToTree(s.user);
        long history = s.document.historyBytes(), revision = s.revision;
        var tasks = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var reject = new AtomicBoolean(mode.equals("retry"));
        panel = new PropertyPanel(s, task -> {
          if (reject.get()) throw new RejectedExecutionException("held curve queue");
          tasks.add(task);
        }, ui::add);
        panel.probeOpenCurve();
        var dialog = panel.probeCurveDialog();
        frames(4);
        require(dialog.focusControl(dialog.probeCurve().points.getLast().uuid, "y"), "Curve focus");
        for (int i = 0; i < 24 && !"y".equals(dialog.probeFocusedControl()); i++) frame();
        require("y".equals(dialog.probeFocusedControl()), "Curve Y was not revealed");
        type("3");
        click(dialog.probeControl("apply"));
        var edit = dialog.probePublication();
        if (mode.equals("retry")) {
          require(!edit.running() && !edit.error().isEmpty(), "Curve retry unavailable");
          frames(12);
          require(tasks.isEmpty(), "Curve Apply retried automatically");
          reject.set(false);
          click(dialog.probeControl("apply"));
        }
        for (int i = 0; i < 12; i++) {
          frame();
          require(edit.running() && !edit.ready(), "Curve Apply bypassed worker");
        }
        s.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
        frames(12);
        require(edit.running() && !edit.ready() && ui.size() == 1, "Curve Apply bypassed UI");
        require(privateBefore.equals(ProjectIO.JSON.valueToTree(draft)), "Worker staged curve");
        if (mode.equals("escape-ready")) {
          ui.remove().run();
          require(edit.ready(), "Delivered curve was not ready");
          ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
          frame();
          ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
          frames(3);
        } else if (mode.equals("cancel-ready")) {
          var at = dialog.probeControl("cancel");
          var io = ImGui.getIO();
          io.addMousePosEvent(at[0], at[1]);
          frame();
          io.addMouseButtonEvent(0, true);
          frame();
          ui.remove().run();
          require(edit.ready(), "Curve not ready before Cancel release");
          io.addMouseButtonEvent(0, false);
          frames(3);
        } else {
          if (mode.equals("cancel-apply")) click(dialog.probeControl("apply"));
          else if (mode.equals("cancel")) click(dialog.probeControl("cancel"));
          else if (mode.equals("close")) panel.close();
          else if (mode.equals("edit")) {
            dialog.shortcut("CurveUndo");
            frames(2);
          } else if (mode.equals("stale")) {
            var item = draft.processing.getFirst();
            item.parameters.put("curve", item.parameters.get("curve").deepCopy());
          }
          ui.remove().run();
          require(privateBefore.equals(ProjectIO.JSON.valueToTree(draft)), "Delivery staged before input decision");
          frames(3);
        }
        boolean staged = mode.equals("apply") || mode.equals("retry");
        require(committed.equals(ProjectIO.JSON.valueToTree(s.project()))
            && history == s.document.historyBytes() && revision == s.revision,
            "Curve staging committed project/history");
        require(s.draft == draft && draft.id.equals("private")
            && s.seedInputs.text("seedOffset").equals("-")
            && preferences.equals(ProjectIO.JSON.valueToTree(s.user)), "Curve private input changed");
        if (staged) {
          require(edit.closed() && panel.probeCurveDialog() == null, "Curve Apply did not close");
          require(draft.processing.getFirst().parameters.get("curve").path("points").get(1).path("y").asDouble() == 3,
              "Wrong curve staged");
          require(!s.applyDraft(), "Curve bypassed invalid parent seed");
          s.seedInputs.edit(s, "seedOffset", "Seed", "0");
          require(s.applyDraft(), "Parent Curve Apply failed");
          var after = ProjectIO.JSON.valueToTree(s.project());
          s.document.undo();
          require(committed.equals(ProjectIO.JSON.valueToTree(s.project())), "One curve Undo");
          s.document.redo();
          require(after.equals(ProjectIO.JSON.valueToTree(s.project())), "One curve Redo");
        } else {
          require(privateBefore.equals(ProjectIO.JSON.valueToTree(draft)), "Retired curve staged");
          if (!mode.equals("cancel") && !mode.equals("cancel-ready") && !mode.equals("close"))
            require(panel.probeCurveDialog() == dialog && !edit.running(), "Private curve was lost");
        }
        require(Arrays.equals(originalBytes, Files.readAllBytes(directory.resolve("before.json"))), "Source bytes changed");
        s.document.save(directory.resolve("after.json"));
        cases.add(Map.of("mode", mode, "staged", staged, "queuedFrames", 12, "heldFrames", 12,
            "privateRetained", true, "rawRetained", true, "preferencesRetained", true,
            "originalBytesRetained", true));
        panel.close();
        frames(2);
      }
    }
    Files.writeString(out.resolve("curve-apply-native.json"), ProjectIO.JSON.writeValueAsString(
        Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static void type(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(2);
  }

  private static void click(float[] at) {
    require(at != null, "Curve control missing");
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
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1200, 900, ImGuiCond.Always);
    ImGui.begin("Background curve Apply reference");
    panel.probeCurvePanel();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
