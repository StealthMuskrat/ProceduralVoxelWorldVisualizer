package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production inline typing, clean/combined action progress, cancellation and retry. */
final class BackgroundResourceEditProbe {
  private static ResourcePanel resources;
  private static boolean focus;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode :
        List.of("clean", "apply", "discard", "cancel", "escape", "retry", "raw", "close", "ready-cancel", "ready-escape", "ready-clean-cancel", "ready-discard-cancel")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      try (var state = new EditorState(new UserConfig(), false);
          var properties = new PropertyPanel(state)) {
        require(
            state.change(
                p -> {
                  p.project.seed = 5;
                  p.project.minY = -2;
                  p.project.maxY = 2;
                  for (String id : List.of("a", "b")) {
                    var n = new Noise();
                    n.id = id;
                    n.algorithm.key = "CONSTANT";
                    n.algorithm.parameters.clear();
                    n.algorithm.parameters.put("value", id.equals("a") ? .125 : .375);
                    p.noise2D.add(n);
                  }
                  var v = new VoxelType();
                  v.id = "solid";
                  p.voxelTypes.add(v);
                  var layer = new Layer();
                  layer.id = "bounds";
                  layer.defaults.action = "SET";
                  layer.defaults.voxelTypeUuid = v.uuid;
                  var op = new Operation();
                  op.id = "density";
                  op.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
                  op.interpretation = "DENSITY";
                  op.overrides.put("threshold", ProjectIO.JSON.valueToTree(.5));
                  layer.operations.add(op);
                  p.worldGenerator.layers.add(layer);
                }),
            "Background resource fixture");
        state.document.save(directory.resolve("before.json"));
        byte[] original = Files.readAllBytes(directory.resolve("before.json"));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        boolean clean = mode.equals("clean") || mode.equals("ready-clean-cancel");
        boolean discard = mode.equals("discard") || mode.equals("ready-discard-cancel");
        if (!clean) ((Noise) state.draft).algorithm.parameters.put("value", .625);
        if (discard) state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        Files.writeString(directory.resolve("preferences-before.json"), preferences.toString());
        var tasks = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(mode.equals("retry"));
        state.document.setValidator(
            p -> {
              if (fail.getAndSet(false)) throw new AssertionError("resource worker fixture");
              return NoiseCompiler.validate(p);
            });
        resources = new ResourcePanel(state, properties, tasks::add, ui::add, () -> false);
        try {
          focus = true;
          frames(4);
          rename();
          var edits = resources.probeEditSession();
          if (!clean) {
            require(
                edits.pending() && !edits.preparing() && tasks.isEmpty(),
                "Modified draft did not wait for a decision");
            click(discard ? "DISCARD" : "APPLY");
          }
          require(
              edits.preparing() && !edits.busy() && tasks.size() == 1,
              "Single cancellable resource task");
          long work = resources.editGuard.requestVersion();
          for (int i = 0; i < 12; i++) {
            frame();
            require(
                edits.preparing()
                    && state.draft == draft
                    && state.project().noise2D.getFirst().id.equals("a")
                    && state.project().noise2D.getFirst().algorithm.parameters.get("value") == .125,
                "Resource preparation replaced committed/private values");
            require(
                work == resources.editGuard.requestVersion(),
                "Resource gesture IDs changed while preparing");
            if (discard)
              require(
                  "-".equals(state.seedInputs.text("seedOffset")),
                  "Discard disposed input before publication");
          }
          state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
          require(
              edits.preparing()
                  && state.draft == draft
                  && state.project().noise2D.getFirst().id.equals("a"),
              "Worker published before UI delivery");
          if (mode.startsWith("ready-")) {
            long prepared = edits.workVersion();
            if (!mode.equals("ready-escape")) hold("CANCEL");
            ui.remove().run();
            require(edits.preparing() && edits.workVersion() == prepared
                && work == resources.editGuard.requestVersion() && state.draft == draft
                && state.project().noise2D.getFirst().id.equals("a"),
                "Ready resource delivery published or changed Cancel authority");
            if (mode.equals("ready-escape")) {
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true); frame();
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(3);
            } else {
              ImGui.getIO().addMouseButtonEvent(0, false); frames(3);
            }
          } else {
            if (mode.equals("cancel")) click("CANCEL");
            else if (mode.equals("escape")) {
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
              frame();
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
              frames(3);
            } else if (mode.equals("raw")) state.project().project.seed = 6;
            else if (mode.equals("close")) resources.close();
            ui.remove().run();
            frames(3);
          }
          if (mode.equals("retry")) {
            require(
                edits.pending()
                    && !edits.preparing()
                    && state.draft == draft
                    && edits.progress().contains("failed")
                    && tasks.isEmpty(),
                "Resource failure/retry state");
            click("APPLY");
            state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
            require(state.draft == draft, "Retry published before delivery");
            ui.remove().run();
            frames(3);
          }
          boolean applied = List.of("clean", "apply", "discard", "retry").contains(mode);
          boolean privateApplied = List.of("apply", "retry").contains(mode);
          require(
              !edits.pending()
                  && !edits.preparing()
                  && !edits.busy()
                  && state.project().noise2D.getFirst().id.equals(applied ? "renamed" : "a")
                  && state.project().noise2D.getFirst().algorithm.parameters.get("value")
                      == (privateApplied ? .625 : .125),
              "Terminal resource values");
          if (!applied)
            require(
                state.draft == draft
                    && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft)),
                "Retired action lost private input");
          if (discard)
            require(
                applied ? !state.seedInputs.pending(state) : "-".equals(state.seedInputs.text("seedOffset")),
                "Discard cancellation/publication lost raw input ownership");
          state.document.save(directory.resolve("after.json"));
          if (applied) {
            var accepted = ProjectIO.JSON.valueToTree(state.project());
            state.document.undo();
            state.document.save(directory.resolve("undone.json"));
            require(
                state.project().noise2D.getFirst().id.equals("a")
                    && state.project().noise2D.getFirst().algorithm.parameters.get("value") == .125,
                "Combined action one Undo");
            state.document.redo();
            require(
                accepted.equals(ProjectIO.JSON.valueToTree(state.project())),
                "Combined action Redo");
            state.document.save(directory.resolve("redone.json"));
          }
          require(
              preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                  && Arrays.equals(original, Files.readAllBytes(directory.resolve("before.json"))),
              "Resource action changed preferences/original bytes");
          Files.writeString(
              directory.resolve("preferences.json"), ProjectIO.JSON.writeValueAsString(state.user));
          cases.add(
              Map.of(
                  "mode",
                  mode,
                  "applied",
                  applied,
                  "privateApplied",
                  privateApplied,
                  "responsiveFrames",
                  12,
                  "privateUntilPublication",
                  true,
                  "oneUndoRedo",
                  applied,
                  "preferencesRetained",
                  true,
                  "originalBytesRetained",
                  true,
                  "readyRetired",
                  mode.startsWith("ready-")));
        } finally {
          resources.close();
          frames(2);
        }
      }
    }
    Files.writeString(
        out.resolve("background-resource-edit.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static void rename() {
    resources.shortcut("Rename");
    focus = true;
    frames(3);
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8("renamed");
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
    require(resources.probeEditPending(), "Inline rename did not retain the resource request");
  }

  private static void hold(String key) {
    var at = resources.probeEditButton(key); require(at != null, "Resource action missing " + key);
    var io = ImGui.getIO(); io.addMousePosEvent(at[0], at[1]); frame();
    io.addMouseButtonEvent(0, true); frame();
  }

  private static void click(String key) {
    var at = resources.probeEditButton(key);
    require(at != null, "Resource action missing " + key);
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
    ImGui.getIO().setDisplaySize(1400, 1000);
    ImGui.getIO().setFontGlobalScale(1);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(700, 850, ImGuiCond.Always);
    if (focus) {
      ImGui.setNextWindowFocus();
      focus = false;
    }
    resources.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
