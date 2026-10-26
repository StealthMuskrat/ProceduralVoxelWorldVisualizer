package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Isolated native input against the production expression controls; no startup fixture. */
public final class ExpandedExpressionProbe {
  private static PropertyPanel panel;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1200, 900);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var state = new EditorState(new UserConfig(), false)) {
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "formula";
                  var item = ProcessingRegistry.create("EXPRESSION", "formula");
                  item.parameters.put("expression", ProjectIO.JSON.valueToTree("x+bias"));
                  item.inputs.put("bias", Binding.constant(2));
                  n.processing.add(item);
                  n.outputBinding = Binding.earlier(item.uuid);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        var n = state.project().noise2D.getFirst();
        state.select("noise2D", n.uuid);
        panel = new PropertyPanel(state);
        var draft = (Noise) state.draft;
        var item = draft.processing.getFirst();
        var committed = ProjectIO.JSON.valueToTree(state.project());
        long history = state.document.historyBytes(), revision = state.revision;
        frames(4);
        click(panel.probeExpressionControl("expand"));
        frames(4);
        require(panel.searchBlocked(), "Modal did not guard navigation");
        click(panel.probeExpandedExpressionControl("text"));
        replace("x+9");
        require(
            "x+bias".equals(item.parameters.get("expression").asText()), "Buffer leaked to draft");
        click(panel.probeExpandedExpressionControl("cancel"));
        frames(2);
        require(!panel.probeExpandedExpressionOpen(), "Cancel did not close");
        click(panel.probeExpressionControl("expand"));
        frames(4);
        click(panel.probeExpandedExpressionControl("text"));
        replace("x+8");
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        frames(2);
        require(!panel.probeExpandedExpressionOpen(), "Escape did not cancel");
        require(
            "x+bias".equals(item.parameters.get("expression").asText()), "Cancel changed draft");
        click(panel.probeExpressionControl("expand"));
        frames(4);
        click(panel.probeExpandedExpressionControl("text"));
        replace("x+\nbias*3");
        click(panel.probeExpandedExpressionControl("stage"));
        frames(2);
        require(!panel.probeExpandedExpressionOpen(), "Stage did not close");
        require(
            "x+\nbias*3".equals(item.parameters.get("expression").asText()), "Multiline text lost");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())), "Premature publication");
        require(
            history == state.document.historyBytes() && revision == state.revision,
            "Stage published history");
        require(state.applyDraft(), "Multiline Apply rejected");
        require(sample(state.project(), n.uuid) == 6, "Multiline arithmetic");
        state.document.undo();
        state.revision++;
        require(sample(state.project(), n.uuid) == 2, "One Undo");
        state.document.redo();
        state.revision++;
        require(sample(state.project(), n.uuid) == 6, "One Redo");
        state.document.save(out.resolve("expanded-expression-project.json"));
        Files.writeString(
            out.resolve("expanded-expression-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "before",
                    2,
                    "after",
                    6,
                    "privateBuffer",
                    true,
                    "privateStage",
                    true,
                    "cancel",
                    true,
                    "escape",
                    true,
                    "oneUndoRedo",
                    true,
                    "lineCount",
                    2)));
        System.out.println(
            "EXPANDED_EXPRESSION_NATIVE_PASS multiline / Cancel / Escape / private stage / Apply /"
                + " Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static double sample(ProjectData p, UUID id) {
    return new NoiseCompiler(p, p.project.seed).compile(id).sample(0, 0, 0);
  }

  private static void replace(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    var lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        io.addKeyEvent(ImGuiKey.Enter, true);
        frame();
        io.addKeyEvent(ImGuiKey.Enter, false);
        frame();
      }
      io.addInputCharactersUTF8(lines[i]);
      frame();
    }
    frames(2);
  }

  private static void click(float[] p) {
    require(p != null, "Control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0], p[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 800, ImGuiCond.Always);
    ImGui.begin("Expanded expression workflow");
    panel.probeExpressionPanel();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
