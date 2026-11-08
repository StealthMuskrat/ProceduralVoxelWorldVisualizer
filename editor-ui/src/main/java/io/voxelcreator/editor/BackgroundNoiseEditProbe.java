package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production structural actions and resolution footer with held worker/UI boundaries. */
final class BackgroundNoiseEditProbe {
  private static PropertyPanel panel;

  static void fixture(ProjectData p) {
    var n = new Noise();
    n.id = "field";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 1.);
    var gain = new Parameter();
    gain.id = "gain";
    gain.value = ProjectIO.JSON.valueToTree(2.);
    var unused = new Parameter();
    unused.id = "unused";
    unused.value = ProjectIO.JSON.valueToTree(7.);
    n.sharedParameters.addAll(List.of(gain, unused));
    var add = ProcessingRegistry.create("ADD", "add");
    var binding = Binding.constant(0);
    binding.sourceKind = "Shared Parameter";
    binding.parameterUuid = gain.uuid;
    add.inputs.put("b", binding);
    var multiply = ProcessingRegistry.create("MULTIPLY", "multiply");
    multiply.inputs.put("a", Binding.earlier(add.uuid));
    multiply.inputs.put("b", Binding.constant(4));
    n.processing.addAll(List.of(add, multiply));
    n.outputBinding = Binding.earlier(multiply.uuid);
    p.noise2D.add(n);
  }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode :
        List.of("automatic", "resolve", "cancel", "escape", "retry", "stale", "close", "ready-cancel", "ready-escape", "ready-close", "ready-invalid-cancel", "ready-resolve-cancel")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      try (var s = new EditorState(new UserConfig(), false)) {
        require(s.change(BackgroundNoiseEditProbe::fixture), "Structural fixture rejected");
        s.select("noise2D", s.project().noise2D.getFirst().uuid);
        var draft = (Noise) s.draft;
        draft.id = "private";
        s.seedInputs.edit(s, "seedOffset", "Seed", "-");
        var preferences = ProjectIO.JSON.valueToTree(s.user);
        s.document.save(directory.resolve("before.json"));
        var beforeBytes = Files.readAllBytes(directory.resolve("before.json"));
        var tasks = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var reject = new AtomicBoolean(mode.equals("retry"));
        panel =
            new PropertyPanel(
                s,
                task -> {
                  if (reject.get()) throw new RejectedExecutionException("held structural queue");
                  tasks.add(task);
                },
                ui::add);
        UUID target =
            (List.of("resolve", "ready-invalid-cancel", "ready-resolve-cancel").contains(mode)
                    ? draft.sharedParameters.getFirst()
                    : draft.sharedParameters.getLast())
                .uuid;
        panel.probeNoiseAction(Request.simple(Action.DELETE_PARAMETER, target));
        var dialog = panel.probeNoiseEditDialog();
        var edit = dialog.session();
        frames(4);
        if (mode.equals("retry")) {
          require(edit.canRetry(), "Structural scheduler rejection trapped pending state");
          frames(12);
          require(tasks.isEmpty(), "Structural edit retried automatically");
          reject.set(false);
          click(dialog.control("resolution"));
        }
        if (mode.equals("resolve") || mode.equals("ready-resolve-cancel")) {
          finish(s, tasks);
          ui.remove().run();
          frames(3);
          require(edit.ready() && !edit.canStage(), "Referenced parameter unexpectedly staged");
          dialog.choose(Resolution.INLINE_PARAMETER, null);
        }
        var before = ProjectIO.JSON.valueToTree(s.project());
        var privateBefore = ProjectIO.JSON.valueToTree(draft);
        long history = s.document.historyBytes(), revision = s.revision;
        for (int i = 0; i < 12; i++) {
          frame();
          require(
              edit.phase() == NoiseEditSession.Phase.RUNNING && !edit.canStage(),
              "Structural edit skipped queued ownership");
        }
        finish(s, tasks);
        frames(12);
        require(
            edit.phase() == NoiseEditSession.Phase.RUNNING && ui.size() == 1,
            "Structural worker bypassed UI delivery");
        require(
            before.equals(ProjectIO.JSON.valueToTree(s.project()))
                && privateBefore.equals(ProjectIO.JSON.valueToTree(draft)),
            "Structural preparation published values");
        if (mode.startsWith("ready-")) {
          long version = edit.version();
          if (!mode.equals("ready-escape") && !mode.equals("ready-close")) hold(dialog.control("cancel"));
          ui.remove().run();
          require(edit.phase() == NoiseEditSession.Phase.RUNNING && edit.version() == version
              && !edit.canStage() && privateBefore.equals(ProjectIO.JSON.valueToTree(draft)),
              "Ready structural result changed preview authority or private values before controls");
          if (mode.equals("ready-escape")) {
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true); frame();
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(3);
          } else if (mode.equals("ready-close")) { panel.close(); frames(3); }
          else { ImGui.getIO().addMouseButtonEvent(0, false); frames(3); }
          require(edit.closed() && privateBefore.equals(ProjectIO.JSON.valueToTree(draft)),
              "Ready cancellation staged an obsolete structural edit");
        } else {
          if (mode.equals("cancel")) click(dialog.control("cancel"));
          else if (mode.equals("escape")) {
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
            frame();
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
            frames(3);
          } else if (mode.equals("close")) panel.close();
          else if (mode.equals("stale")) draft.id = "new-private";
          ui.remove().run();
        }
        // Readiness alone does not mutate Properties. The owning production draw stages an automatic
        // action.
        require(
            (mode.equals("stale")
                ? draft.id.equals("new-private")
                : privateBefore.equals(ProjectIO.JSON.valueToTree(draft))),
            "UI delivery staged before its action decision");
        frames(3);
        boolean staged = List.of("automatic", "resolve", "retry").contains(mode);
        if (mode.equals("resolve")) {
          click(dialog.control("undo"));
          require(edit.resolution() == Resolution.REJECT && !edit.canStage(),
              "Native resolution Undo did not supersede checking");
          finish(s, tasks);
          ui.remove().run();
          frames(3);
          require(edit.ready() && !edit.canStage() && !edit.closed(),
              "Native resolution Undo adopted the wrong preview");
          click(dialog.control("redo"));
          finish(s, tasks);
          ui.remove().run();
          frames(3);
          historyKey(ImGuiKey.Z);
          require(edit.resolution() == Resolution.REJECT && !edit.canStage(),
              "Focused modal did not own resolution Undo");
          finish(s, tasks);
          ui.remove().run();
          frames(3);
          historyKey(ImGuiKey.Y);
          require(edit.resolution() == Resolution.INLINE_PARAMETER && !edit.canStage(),
              "Focused modal did not own resolution Redo");
          finish(s, tasks);
          ui.remove().run();
          frames(3);
          require(
              !edit.closed() && edit.canStage() && draft.sharedParameters.size() == 2,
              "Resolution did not wait for explicit Apply");
          click(dialog.applyCenter());
          frames(2);
        }
        if (staged)
          require(
              edit.closed()
                  && draft.sharedParameters.size() == 1
                  && panel.probeNoiseEditDialog() == null,
              "Accepted structural edit did not stage once");
        else require(draft.sharedParameters.size() == 2, "Retired structural edit staged");
        if (mode.equals("stale"))
          require(
              edit.phase() == NoiseEditSession.Phase.STALE && !edit.canStage(),
              "Changed draft retained structural authority");
        require(
            before.equals(ProjectIO.JSON.valueToTree(s.project()))
                && history == s.document.historyBytes()
                && revision == s.revision,
            "Child structural staging committed history");
        require(
            s.draft == draft
                && s.seedInputs.text("seedOffset").equals("-")
                && preferences.equals(ProjectIO.JSON.valueToTree(s.user)),
            "Structural edit replaced private/raw/preferences input");
        if (staged) {
          require(!s.applyDraft(), "Structural staging bypassed invalid raw seed");
          s.seedInputs.edit(s, "seedOffset", "Seed", "0");
          require(s.applyDraft(), "Parent structural Apply failed");
          var after = ProjectIO.JSON.valueToTree(s.project());
          s.document.undo();
          require(before.equals(ProjectIO.JSON.valueToTree(s.project())), "Structural one Undo");
          s.document.redo();
          require(after.equals(ProjectIO.JSON.valueToTree(s.project())), "Structural one Redo");
        }
        require(
            Arrays.equals(beforeBytes, Files.readAllBytes(directory.resolve("before.json"))),
            "Structural work overwrote source bytes");
        s.document.save(directory.resolve("after.json"));
        cases.add(
            Map.of(
                "mode",
                mode,
                "staged",
                staged,
                "queuedFrames",
                12,
                "heldFrames",
                12,
                "privateRetained",
                true,
                "rawRetained",
                true,
                "preferencesRetained",
                true,
                "originalBytesRetained",
                true,
                "readyRetired",
                mode.startsWith("ready-"),
                "localChoiceHistory",
                mode.equals("resolve")));
        panel.close();
        frames(2);
      }
    }
    Files.writeString(
        out.resolve("noise-edit-native.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static void historyKey(int key) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(key, true);
    frame();
    io.addKeyEvent(key, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frames(3);
  }

  private static void finish(EditorState s, ArrayDeque<Runnable> tasks) throws Exception {
    s.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
  }

  private static void hold(float[] at) {
    require(at != null, "Missing ready structural Cancel control");
    var io = ImGui.getIO(); io.addMousePosEvent(at[0], at[1]); frame();
    io.addMouseButtonEvent(0, true); frame();
  }

  private static void click(float[] at) {
    require(at != null, "Structural control missing");
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
    ImGui.begin("Background noise structural reference");
    panel.probeNoiseEditPanel();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
