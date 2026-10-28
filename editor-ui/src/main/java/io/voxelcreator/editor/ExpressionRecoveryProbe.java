package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Isolated native input against the production expression controls; no startup fixture. */
public final class ExpressionRecoveryProbe {
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
                  item.inputs.put("é", Binding.constant(2));
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
        item.inputs.get("bias").type = "Vector3";
        frames(4);
        click(panel.probeExpressionControl("input"));
        require(
            "inputs/bias".equals(panel.probeExpressionTarget().parameter()),
            "Inline input destination");
        require(state.draft == draft, "Input navigation discarded draft");
        item.inputs.get("bias").type = "Scalar";
        click(panel.probeExpressionControl("text"));
        replace("sin(");
        require(!state.applyDraft(), "Invalid expression accepted");
        require(state.draft == draft, "Rejected draft replaced");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())), "Premature publication");
        replace("x+é+unknown");
        frames(3);
        click(panel.probeExpressionControl("focus"));
        require(
            "expression".equals(panel.probeExpressionTarget().parameter()),
            "Focus action destination");
        for (int i = 0; i < 10 && !panel.probeExpressionActive(); i++) frame();
        require(panel.probeExpressionActive(), "Focus request was not acknowledged");
        frames(3);
        // No mouse click on the text: the production focus action must accept keyboard repair.
        require(
            new ExpressionSelection.Range(5, 12).equals(panel.probeExpressionSelection()),
            "Unicode parser span was not selected in UTF-8 bytes");
        io.addInputCharactersUTF8("bias");
        frames(3);
        require(
            "x+é+bias".equals(item.parameters.get("expression").asText()),
            "Selected-token replacement");
        io.addInputCharactersUTF8("+0");
        frames(3);
        require(
            "x+é+bias+0".equals(item.parameters.get("expression").asText()),
            "Keyboard focus repair failed: " + item.parameters.get("expression"));
        require(state.applyDraft(), "Repair rejected");
        require(sample(state.project(), n.uuid) == 4, "Repaired arithmetic");
        state.document.undo();
        state.revision++;
        require(sample(state.project(), n.uuid) == 2, "One Undo");
        state.document.redo();
        state.revision++;
        require(sample(state.project(), n.uuid) == 4, "One Redo");
        state.document.save(out.resolve("expression-recovery-project.json"));
        Files.writeString(
            out.resolve("expression-recovery-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "inputTarget",
                    "inputs/bias",
                    "before",
                    2,
                    "after",
                    4,
                    "keyboardFocusRepair",
                    true,
                    "rejectedDraftPreserved",
                    true,
                    "oneUndoRedo",
                    true,
                    "unicodeTokenSelection",
                    true,
                    "oneShotSelection",
                    true)));
        System.out.println(
            "EXPRESSION_RECOVERY_NATIVE_PASS input action / keyboard focus / rejected draft / Apply"
                + " / Undo-Redo");
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
    io.addInputCharactersUTF8(text);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
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
    ImGui.begin("Expression recovery");
    panel.probeExpressionPanel();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
