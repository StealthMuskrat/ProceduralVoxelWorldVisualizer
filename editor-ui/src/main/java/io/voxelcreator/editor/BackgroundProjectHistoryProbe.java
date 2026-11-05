package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectHistorySession.*;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deferred history confirmation/progress controls across held worker and owner-delivery boundaries.
 */
final class BackgroundProjectHistoryProbe {
  private static ProjectHistoryPanel panel;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("clean", "discard", "cancel", "escape", "retry", "close", "ready-cancel", "ready-escape", "ready-clean-cancel", "ready-close")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      try (var state = new EditorState(new UserConfig(), false)) {
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
            "History source fixture");
        require(
            state.change(p -> p.noise2D.getFirst().algorithm.parameters.put("value", .625)),
            "History edited fixture");
        state.document.save(directory.resolve("before.json"));
        byte[] bytes = Files.readAllBytes(directory.resolve("before.json"));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        boolean clean = mode.equals("clean") || mode.equals("ready-clean-cancel");
        if (!clean) {
          ((Noise) state.draft).id = "private";
          state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        }
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        long historyBytes = state.document.historyBytes();
        var tasks = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(mode.equals("retry"));
        state.document.setValidator(
            p -> {
              if (fail.getAndSet(false)) throw new AssertionError("history worker fixture");
              return NoiseCompiler.validate(p);
            });
        panel =
            new ProjectHistoryPanel(
                state,
                () -> false,
                () -> state.reconcileSelection(state.multiSelection, state.selected),
                tasks::add,
                ui::add);
        try {
          panel.request(Direction.UNDO);
          frames(4);
          if (!clean) click("DISCARD");
          for (int i = 0; i < 12; i++) {
            frame();
            require(
                panel.session.preparing() && state.draft == draft,
                "Preparation discarded private input");
            require(
                state.project().noise2D.getFirst().algorithm.parameters.get("value") == .625,
                "Worker preparation published history");
            require(
                DialogBounds.contains(panel.layout.bounds(), panel.chrome.body()),
                "History body escaped modal");
            for (var box : panel.chrome.actions().values())
              require(
                  DialogBounds.contains(panel.layout.bounds(), box),
                  "History action escaped modal");
          }
          state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
          require(
              panel.session.preparing() && state.draft == draft,
              "Worker published before owner delivery");
          if (mode.startsWith("ready-")) {
            long work = panel.session.workVersion(), request = panel.session.requestVersion();
            if (mode.equals("ready-cancel") || mode.equals("ready-clean-cancel")) hold("CANCEL");
            ui.remove().run();
            require(panel.session.preparing() && panel.session.workVersion() == work
                && panel.session.requestVersion() == request && state.draft == draft
                && state.project().noise2D.getFirst().algorithm.parameters.get("value") == .625,
                "Ready history delivery published or changed Cancel authority");
            if (mode.equals("ready-escape")) {
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true); frame();
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(3);
            } else if (mode.equals("ready-close")) { panel.close(); frames(3); }
            else { ImGui.getIO().addMouseButtonEvent(0, false); frames(3); }
          } else {
            if (mode.equals("cancel")) click("CANCEL");
            else if (mode.equals("escape")) {
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
              frame();
              ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
              frames(3);
            } else if (mode.equals("close")) panel.close();
            ui.remove().run();
            frames(3);
          }
          if (mode.equals("retry")) {
            require(
                !panel.session.pending()
                    && state.draft == draft
                    && state.document.historyBytes() == historyBytes,
                "Rejected history lost private input or stacks");
            panel.request(Direction.UNDO);
            frames(3);
            click("DISCARD");
            state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
            ui.remove().run();
            frames(3);
          }
          boolean moved = List.of("clean", "discard", "retry").contains(mode);
          require(
              !panel.session.pending() && state.document.historyBytes() == historyBytes,
              "History terminal state/accounting");
          if (moved)
            require(
                state.project().noise2D.getFirst().algorithm.parameters.get("value") == .125
                    && state.document.canRedo(),
                "Prepared Undo did not move exactly one entry");
          else
            require(
                state.draft == draft
                    && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
                    && !state.document.canRedo(),
                "Retired history changed private values or stacks");
          state.document.save(directory.resolve("after.json"));
          if (moved) {
            panel.request(Direction.REDO);
            frames(3);
            require(panel.session.preparing(), "Clean Redo did not prepare");
            state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
            ui.remove().run();
            frames(3);
            require(
                state.project().noise2D.getFirst().algorithm.parameters.get("value") == .625
                    && state.document.historyBytes() == historyBytes,
                "Prepared Redo");
            state.document.save(directory.resolve("redone.json"));
          }
          require(
              preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                  && Arrays.equals(bytes, Files.readAllBytes(directory.resolve("before.json"))),
              "History wrote preferences or original source bytes");
          Files.writeString(
              directory.resolve("preferences.json"), ProjectIO.JSON.writeValueAsString(state.user));
          cases.add(
              Map.of(
                  "mode",
                  mode,
                  "moved",
                  moved,
                  "responsiveFrames",
                  12,
                  "discardOnlyOnPublication",
                  true,
                  "preferencesRetained",
                  true,
                  "originalBytesRetained",
                  true,
                  "historyBytesUnchanged",
                  true,
                  "readyRetired",
                  mode.startsWith("ready-")));
        } finally {
          panel.close();
        }
      }
    }
    Files.writeString(
        out.resolve("background-history.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static void hold(String key) {
    var at = panel.control(key); require(at != null, "History control missing");
    var io = ImGui.getIO(); io.addMousePosEvent(at[0], at[1]); frame();
    io.addMouseButtonEvent(0, true); frame();
  }

  private static void click(String key) {
    var at = panel.control(key);
    require(at != null, "History control missing");
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
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
