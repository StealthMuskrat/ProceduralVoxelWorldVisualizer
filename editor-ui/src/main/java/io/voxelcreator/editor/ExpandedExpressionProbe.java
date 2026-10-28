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
                  item.inputs.put("é", Binding.constant(0));
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
        panel.probeOpenExpandedExpression();
        frames(2);
        require(
            "x+9".equals(panel.probeExpandedExpressionText()),
            "A second expanded-editor request replaced the private buffer");
        require(
            "x+bias".equals(item.parameters.get("expression").asText()), "Buffer leaked to draft");
        replace("é+\nunknown");
        click(panel.probeExpandedExpressionControl("selectError"));
        frames(4);
        require(
            new ExpressionSelection.Range(4, 11).equals(panel.probeExpandedExpressionSelection()),
            "Expanded multiline UTF-8 span");
        io.addInputCharactersUTF8("bias");
        frames(3);
        require(
            "é+\nbias".equals(panel.probeExpandedExpressionText()), "Expanded token replacement");
        io.addInputCharactersUTF8("+0");
        frames(3);
        require(
            "é+\nbias+0".equals(panel.probeExpandedExpressionText()),
            "Expanded selection repeated");
        require(
            "x+bias".equals(item.parameters.get("expression").asText()),
            "Selection leaked to draft");
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
        publicationOwnership(state.project());
        Files.writeString(
            out.resolve("expanded-expression-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("before", 2),
                    Map.entry("after", 6),
                    Map.entry("privateBuffer", true),
                    Map.entry("privateStage", true),
                    Map.entry("cancel", true),
                    Map.entry("escape", true),
                    Map.entry("oneUndoRedo", true),
                    Map.entry("lineCount", 2),
                    Map.entry("tokenSelection", Map.of("unicode", true, "oneShot", true)),
                    Map.entry("duplicateOpenPreserved", true),
                    Map.entry("refreshFailureConsumed", true),
                    Map.entry("closedDrawConsumed", true),
                    Map.entry("staleSourcePreserved", true),
                    Map.entry("oversizedRetry", true),
                    Map.entry("rawSeedPreserved", true))));
        System.out.println(
            "EXPANDED_EXPRESSION_NATIVE_PASS multiline / Cancel / Escape / private stage / Apply /"
                + " Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static ExpressionEditDialog standalone;
  private static boolean standaloneDone;

  /** Separate fixtures retain the original saved arithmetic project and parent Undo/Redo flow. */
  private static void publicationOwnership(ProjectData project) {
    try (var state = new EditorState(new UserConfig(), false)) {
      require(
          state.change(p -> p.noise2D.add(NoiseAuthoring.copy(project.noise2D.getFirst()))),
          "Publication fixture rejected");
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var owner = (Noise) state.draft;
      var item = owner.processing.getFirst();
      var session = new ExpressionEditSession(state, owner, item);
      session.edit("x+bias*3+0");
      int[] refreshes = {0};
      standalone =
          new ExpressionEditDialog(
              state,
              session,
              () -> {
                refreshes[0]++;
                throw new IllegalStateException();
              });
      standaloneDone = false;
      frames(4);
      owner.id = "pending-id";
      require(
          state.seedInputs.edit(state, "seedOffset", "Seed", "+").isEmpty(),
          "Raw seed fixture rejected");
      var committed = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      click(standalone.control("stage"));
      frames(4);
      require(
          standaloneDone && session.closed() && refreshes[0] == 1,
          "Refresh failure reopened or repeated accepted staging");
      require(
          "x+bias*3+0".equals(item.parameters.get("expression").asText()),
          "Refresh failure lost accepted private text");
      require(
          state.events.snapshot().stream()
                  .filter(e -> e.code().equals("EXPRESSION_EDIT_REFRESH_FAILED"))
                  .filter(e -> e.message().contains("IllegalStateException") && e.actionRequired())
                  .count()
              == 1,
          "Refresh failure was not reported separately");
      require(
          owner.id.equals("pending-id") && state.seedInputs.text("seedOffset").equals("+"),
          "Publication replaced unrelated private input");
      require(!state.applyDraft(), "Publication bypassed invalid raw seed");
      try {
        session.stage(state);
        throw new AssertionError("Consumed expression staged again");
      } catch (IllegalStateException expected) {
        // Later explicit publication cannot replay the accepted callback.
      }

      var stale = new ExpressionEditSession(state, owner, item);
      stale.edit("x+9");
      standalone =
          new ExpressionEditDialog(
              state,
              stale,
              () -> {
                throw new AssertionError("Stale source staged");
              });
      standaloneDone = false;
      frames(4);
      var original = item.parameters.get("expression");
      var replacement = ProjectIO.JSON.valueToTree(original.asText());
      require(original != replacement, "Same-value replacement fixture did not replace identity");
      item.parameters.put("expression", replacement);
      frames(2);
      click(standalone.control("stage"));
      frames(2);
      require(
          !standaloneDone
              && !stale.closed()
              && standalone.bufferText().equals("x+9")
              && item.parameters.get("expression") == replacement,
          "Stale source retargeted or lost private input");
      click(standalone.control("cancel"));
      frames(2);
      require(standaloneDone && stale.closed(), "Stale Cancel retained the session");

      var retry = new ExpressionEditSession(state, owner, item);
      var tooLong = "x".repeat(ExpressionEditSession.MAX_CHARACTERS + 1);
      retry.edit(tooLong);
      int[] accepted = {0};
      standalone = new ExpressionEditDialog(state, retry, () -> accepted[0]++);
      standaloneDone = false;
      frames(4);
      click(standalone.control("stage"));
      frames(2);
      require(
          !standaloneDone
              && !retry.closed()
              && accepted[0] == 0
              && standalone.bufferText().equals(tooLong),
          "Oversized text used an earlier buffer");
      click(standalone.control("text"));
      replace("x+bias*3");
      click(standalone.control("stage"));
      frames(3);
      require(
          standaloneDone
              && retry.closed()
              && accepted[0] == 1
              && item.parameters.get("expression").asText().equals("x+bias*3"),
          "Corrected text did not stage exactly once");
      require(
          committed.equals(ProjectIO.JSON.valueToTree(state.project()))
              && revision == state.revision
              && history == state.document.historyBytes(),
          "Publication fixtures changed the project/history");
      require(
          state.draft == owner
              && owner.id.equals("pending-id")
              && state.seedInputs.text("seedOffset").equals("+"),
          "Retry replaced unrelated private input");
    } finally {
      standalone = null;
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
    if (standalone == null) panel.probeExpressionPanel();
    else standaloneDone = standalone.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
