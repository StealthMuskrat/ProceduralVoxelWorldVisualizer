package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred same-UUID reopen without a replacement callback, including exact native rename IDs. */
final class ResourcePaneLifetimeProbe {
  private static ResourcePanel panel;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("COPY", "CUT", "RENAME", "PENDING", "PREPARED", "STABLE")) {
      try (var state = new EditorState(new UserConfig(), false)) {
        require(
            state.change(
                p -> {
                  p.project.seed = 41;
                  var n = new Noise();
                  n.id = "field";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .25);
                  p.noise2D.add(n);
                }),
            "Resource lifetime fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var source = out.resolve(mode + "-source.json");
        state.document.save(source);
        panel = new ResourcePanel(state, new PropertyPanel(state));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var token = state.document.sessionToken();
        panel.shortcut(mode.equals("CUT") ? "Cut" : "Copy");
        if (mode.equals("PENDING")) {
          ((Noise) state.draft).id = "private";
          panel.shortcut("Paste");
          require(panel.probeEditPending(), "Missing pending clipboard edit");
        }
        if (mode.equals("PREPARED")) panel.showManager();
        if (mode.equals("RENAME") || mode.equals("STABLE")) panel.probeVoxelRename(state.selected);
        frame();
        frame();
        long opening = panel.lifetimeState().opening();
        int oldRename = panel.probeRenameNativeId();
        float[] pressed = null;
        if (mode.equals("RENAME")) {
          pressed = panel.probeVoxelRenameInput();
          require(pressed != null && oldRename != 0, "Missing inline rename input");
          ImGui.getIO().addMousePosEvent(pressed[0], pressed[1]);
          ImGui.getIO().addMouseButtonEvent(0, true);
          frame();
        }
        if (mode.equals("STABLE")) {
          require(state.change(p -> p.project.seed = 42), "Stable edit rejected");
          state.document.undo();
          state.document.redo();
          state.document.save(source);
        } else if (mode.equals("PREPARED")) {
          var prepared = state.document.requestOpen(source).prepare();
          state.document.applyOpen(prepared);
        } else require(state.document.open(source).isEmpty(), "Same-UUID reopen rejected");
        var before = ProjectIO.JSON.valueToTree(state.project());
        var draft = state.draft;
        frame();
        frame();
        if (pressed != null) {
          ImGui.getIO().addMouseButtonEvent(0, false);
          frame();
        }
        boolean stable = mode.equals("STABLE");
        var lifetime = panel.lifetimeState();
        require(
            stable ? lifetime.opening() == opening : lifetime.opening() > opening,
            "Resource pane did not distinguish document lifetime from ordinary edits");
        require(
            stable
                ? token == state.document.sessionToken()
                : token != state.document.sessionToken(),
            "Fixture document lifetime incorrect");
        require(stable == lifetime.clipboard(), "Clipboard lifetime ownership incorrect");
        require(!lifetime.manager() && !lifetime.drag(), "Old manager/drag retained");
        int newRename = panel.probeRenameNativeId();
        if (mode.equals("RENAME")) {
          require(lifetime.rename() == null && newRename == 0, "Old inline rename retained");
          panel.probeVoxelRename(state.selected);
          frame();
          frame();
          newRename = panel.probeRenameNativeId();
          require(
              newRename != 0 && newRename != oldRename, "Native rename ID carried across reopen");
          ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
          frame();
          ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
          frame();
        } else if (stable)
          require(
              oldRename != 0 && oldRename == newRename,
              "Ordinary history/Save reset native editing identity");
        if (mode.equals("PENDING"))
          require(
              !panel.probeEditPending() && ((Noise) state.draft).id.equals("private"),
              "Old action retained or private draft discarded");
        if (!stable) panel.shortcut("Paste");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())) && state.draft == draft,
            "Retired pane action mutated current project/private ownership");
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Pane retirement changed preferences");
        require(!state.document.dirty(), "Pane retirement dirtied current checkpoint");
        Files.writeString(
            out.resolve(mode + "-active.json"), ProjectIO.JSON.writeValueAsString(state.project()));
        cases.add(
            Map.of(
                "mode",
                mode,
                "oldOpening",
                opening,
                "newOpening",
                lifetime.opening(),
                "clipboard",
                lifetime.clipboard(),
                "oldRenameId",
                oldRename,
                "newRenameId",
                newRename,
                "seed",
                state.project().project.seed,
                "privatePreserved",
                true,
                "preferencesPreserved",
                true));
        panel.reset();
        frame();
        frame();
      }
    }
    Files.writeString(
        out.resolve("resource-pane-lifetime.json"),
        ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "cases", cases)));
  }

  private static void frame() {
    ImGui.getIO().setDisplaySize(1400, 900);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(600, 800, ImGuiCond.Always);
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
