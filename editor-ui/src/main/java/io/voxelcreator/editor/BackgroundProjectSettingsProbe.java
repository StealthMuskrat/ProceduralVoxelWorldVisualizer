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

/** Deferred production settings controls with held worker completion and owner delivery. */
final class BackgroundProjectSettingsProbe {
  private static ProjectSettingsPanel panel;
  private static boolean visible;
  private static final Map<String, String> RAW =
      Map.of("seed", "+000017", "minY", "-1", "maxY", "+1");

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("success", "cancel", "escape", "raw", "retry", "close")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      try (var state = new EditorState(new UserConfig(), false)) {
        require(state.change(BackgroundProjectSettingsProbe::fixture), "Settings fixture");
        state.document.save(directory.resolve("before.json"));
        byte[] original = Files.readAllBytes(directory.resolve("before.json"));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        long seedVersion = state.seedInputs.version(state);
        var tasks = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(mode.equals("retry"));
        state.document.setValidator(
            p -> {
              if (fail.getAndSet(false)) throw new AssertionError("settings worker fixture");
              return NoiseCompiler.validate(p);
            });
        panel = new ProjectSettingsPanel(state, tasks::add, ui::add, () -> false);
        visible = true;
        try {
          require(panel.open(null), "Settings open");
          frames(4);
          for (String field : ProjectSettingsSession.FIELDS) {
            require(
                panel.open(
                    new EditorEvents.Target(
                        state.project().project.uuid,
                        null,
                        null,
                        field,
                        "/project/" + field,
                        true)),
                "Settings field route");
            for (int i = 0; i < 16 && !field.equals(panel.focusedField()); i++) frame();
            require(field.equals(panel.focusedField()), "Settings focus " + field);
            frames(3);
            type(RAW.get(field));
            require(panel.text(field).equals(RAW.get(field)), "Exact typed settings " + field);
          }
          click("apply");
          var session = panel.probeSession();
          require(
              session != null && session.applying() && tasks.size() == 1, "Single settings job");
          for (int i = 0; i < 12; i++) {
            frame();
            require(
                visible
                    && session.applying()
                    && state.project().project.seed == 5
                    && state.project().project.minY == -2
                    && state.project().project.maxY == 2,
                "Pending settings changed committed values");
            require(rawOwned(session), "Pending settings changed raw text");
          }
          state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
          require(
              session.applying() && state.project().project.seed == 5,
              "Worker published before UI delivery");
          if (mode.equals("cancel")) click("cancel");
          else if (mode.equals("escape")) {
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
            frame();
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
            frames(3);
          } else if (mode.equals("close")) {
            panel.close();
            visible = false;
          } else if (mode.equals("raw")) state.project().project.seed = 6;
          ui.remove().run();
          frames(3);
          if (mode.equals("retry")) {
            require(
                visible
                    && !session.applying()
                    && !panel.applyFailure().isBlank()
                    && panel.candidateIssueCount() == 0
                    && rawOwned(session)
                    && state.project().project.seed == 5,
                "Failed settings text or retry state");
            click("apply");
            require(session.applying() && tasks.size() == 1, "Settings retry capture");
            state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
            require(state.project().project.seed == 5, "Retry published before UI delivery");
            ui.remove().run();
            frames(3);
          }
          boolean applied = List.of("success", "retry").contains(mode);
          long expectedSeed = applied ? 17 : mode.equals("raw") ? 6 : 5;
          require(
              !session.applying()
                  && state.project().project.seed == expectedSeed
                  && state.project().project.minY == (applied ? -1 : -2)
                  && state.project().project.maxY == (applied ? 1 : 2),
              "Settings terminal state");
          require(rawOwned(session), "Retirement lost captured raw fields");
          if (mode.equals("raw")) {
            require(visible && !session.current(state), "Raw source failed to invalidate settings");
            click("cancel");
          }
          require(!visible, "Settings host remained open");
          state.document.save(directory.resolve("after.json"));
          if (applied) {
            var accepted = ProjectIO.JSON.valueToTree(state.project());
            state.document.undo();
            require(
                state.project().project.seed == 5
                    && state.project().project.minY == -2
                    && state.project().project.maxY == 2,
                "Settings single Undo");
            state.document.save(directory.resolve("undone.json"));
            state.document.redo();
            require(accepted.equals(ProjectIO.JSON.valueToTree(state.project())), "Settings Redo");
            state.document.save(directory.resolve("redone.json"));
          }
          require(
              state.draft == draft
                  && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
                  && seedVersion == state.seedInputs.version(state)
                  && "-".equals(state.seedInputs.text("seedOffset")),
              "Separate Properties changed");
          require(
              preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                  && Arrays.equals(original, Files.readAllBytes(directory.resolve("before.json"))),
              "Settings changed preferences or original saved bytes");
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
                  "privatePropertiesRetained",
                  true,
                  "rawTextOwned",
                  true,
                  "preferencesRetained",
                  true,
                  "originalBytesRetained",
                  true,
                  "oneUndoRedo",
                  applied));
        } finally {
          panel.close();
          visible = false;
        }
      }
    }
    Files.writeString(
        out.resolve("project-settings-apply.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static boolean rawOwned(ProjectSettingsSession session) {
    return ProjectSettingsSession.FIELDS.stream().allMatch(f -> RAW.get(f).equals(session.text(f)));
  }

  private static void fixture(ProjectData project) {
    project.project.seed = 5;
    project.project.minY = -2;
    project.project.maxY = 2;
    var noise = new Noise();
    noise.id = "ambient";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", 0.);
    var item = ProcessingRegistry.create("EXPRESSION", "context", 2);
    item.parameters.put(
        "expression", ProjectIO.JSON.valueToTree("x+projectSeed+worldMinY+worldMaxY+worldHeight"));
    noise.processing.add(item);
    noise.outputBinding = Binding.earlier(item.uuid);
    project.noise2D.add(noise);
    var voxel = new VoxelType();
    voxel.id = "solid";
    project.voxelTypes.add(voxel);
    var layer = new Layer();
    layer.id = "bounds";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = voxel.uuid;
    var operation = new Operation();
    operation.id = "all";
    operation.interpretation = "ALL";
    operation.noiseGeneratorUuid = noise.uuid;
    layer.operations.add(operation);
    project.worldGenerator.layers.add(layer);
  }

  private static void type(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void click(String action) {
    var at = panel.controlAt(action);
    require(at != null, "Settings action missing " + action);
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
    if (visible) {
      ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
      ImGui.setNextWindowSize(850, 650, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(false, ImGuiCond.Always);
      if (ImGui.begin("Project Settings") && panel.draw()) visible = false;
      ImGui.end();
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
