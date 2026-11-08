package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production modal reflow, clipped error focus and private-only staging/cancellation. */
public final class ExpressionLayoutProbe {
  private static EditorState state;
  private static Noise draft;
  private static Item item;
  private static ExpressionEditSession session;
  private static ExpressionEditDialog dialog;
  private static int width = 1200, height = 900, accepted;
  private static boolean done;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "formula";
                  var expression = ProcessingRegistry.create("EXPRESSION", "formula");
                  expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x+bias"));
                  expression.inputs.put("bias", Binding.constant(2));
                  expression.inputs.put("é", Binding.constant(0));
                  n.processing.add(expression);
                  n.outputBinding = Binding.earlier(expression.uuid);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        draft = (Noise) state.draft;
        item = draft.processing.getFirst();
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(state.project());
        var engine = state.engine;
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] global = {1, .5f, 1, 2.5f, 2.5f, 1}, scale = {1, 1, 1, 1, 2.5f, 1};
        Integer widgetId = null;
        // Reflow one opening; separate openings must not reuse its native text history.
        open("é+unknown", false);
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = global[i];
          state.user.appearance.windowScales.put("Properties", scale[i]);
          var user = ProjectIO.JSON.valueToTree(state.user);
          frames(10);
          clickTitle();
          dialog.probeScroll(1000000);
          frames(3);
          click("selectError");
          frames(10);
          var range = new ExpressionSelection.Range(3, 10);
          require(
              range.equals(dialog.selectedRange()),
              "Persistent Show Error lost its Unicode parser range");
          var modal = dialog.layout.bounds();
          var chrome = dialog.chrome;
          require(
              DialogBounds.contains(dialog.layout.workArea().bounds(), modal),
              "Expanded modal left its work area");
          require(DialogBounds.contains(modal, chrome.body()), "Expanded body outside modal");
          for (var action : chrome.actions().values())
            require(
                DialogBounds.contains(modal, action), "Persistent expression action outside modal");
          require(
              PopupWorkArea.intersection(chrome.body(), dialog.editorBounds()) != null,
              "Error focus did not reveal the editor viewport");
          if (widgetId == null) widgetId = dialog.editorWidgetId();
          require(
              widgetId == dialog.editorWidgetId(), "Expanded input identity changed during reflow");
          var receipt = new LinkedHashMap<String, Object>();
          receipt.put("scenario", i);
          receipt.put("work", dialog.layout.workArea().bounds());
          receipt.put("modal", modal);
          receipt.put("body", chrome.body());
          receipt.put("actions", chrome.actions());
          receipt.put("editor", dialog.editorBounds());
          receipt.put("feedback", dialog.feedbackBounds());
          receipt.put("line", dialog.lineHeight());
          receipt.put("widgetId", widgetId);
          receipt.put("scope", dialog.actionScope());
          receipt.put("selection", range);
          receipt.put("globalScale", global[i]);
          receipt.put("windowScale", scale[i]);
          cases.add(receipt);
          require(
              dialog.bufferText().equals("é+unknown")
                  && ProjectIO.JSON.valueToTree(state.user).equals(user),
              "Reflow changed private text or preferences");
        }
        click("cancel");
        require(
            done && session.closed() && accepted == 0,
            "Persistent Cancel staged or retained the buffer");
        width = 1200;
        height = 900;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.clear();
        var nativeHistory = nativeHistory();
        open("x+9", false);
        frames(8);
        releaseStageWithEscape();
        require(
            done
                && session.closed()
                && accepted == 0
                && item.parameters.get("expression").asText().equals("x+bias"),
            "Escape did not win over simultaneous staging");
        open("x+bias*2", true);
        frames(8);
        click("stage");
        frames(3);
        require(
            done
                && session.closed()
                && accepted == 1
                && item.parameters.get("expression").asText().equals("x+bias*2"),
            "Refresh failure repeated or lost accepted text");
        require(
            state.events.snapshot().stream()
                    .filter(e -> e.code().equals("EXPRESSION_EDIT_REFRESH_FAILED"))
                    .count()
                == 1,
            "Refresh failure was not reported exactly once");
        open("x".repeat(ExpressionEditSession.MAX_CHARACTERS + 1), false);
        frames(8);
        click("stage");
        require(
            !done && !session.closed() && accepted == 1,
            "Oversized buffer staged an earlier value");
        click("selectError");
        frames(4);
        require(
            dialog.selectedRange() == null
                && PopupWorkArea.intersection(dialog.chrome.body(), dialog.feedbackBounds())
                    != null,
            "Limit failure did not reveal feedback");
        dialog.probeScroll(0);
        frames(4);
        click("text");
        replace("x+bias");
        require(dialog.selectedRange() == null, "Editing retained a prior parser selection");
        click("stage");
        require(
            done && accepted == 2 && item.parameters.get("expression").asText().equals("x+bias"),
            "Corrected buffer failed to stage");
        open("x+9", false);
        frames(8);
        item.parameters.put(
            "expression", ProjectIO.JSON.valueToTree(item.parameters.get("expression").asText()));
        frames(3);
        click("stage");
        require(
            !done && !session.closed() && accepted == 2 && dialog.bufferText().equals("x+9"),
            "Stale source retargeted staging");
        click("selectError");
        frames(3);
        click("cancel");
        require(done, "Stale Cancel was inaccessible");
        var bias = item.inputs.get("bias");
        item.inputs.put("bias", Binding.constant(2));
        item.inputs.get("bias").type = "Vector3";
        open("x+bias", false);
        frames(8);
        click("selectError");
        frames(4);
        require(
            dialog.selectedRange() == null
                && PopupWorkArea.intersection(dialog.chrome.body(), dialog.feedbackBounds())
                    != null,
            "Named-input error used a fabricated text span");
        click("cancel");
        item.inputs.put("bias", bias);
        open("x+", false);
        frames(8);
        click("stage");
        require(
            done && accepted == 3 && item.parameters.get("expression").asText().equals("x+"),
            "Text-only staging unexpectedly required valid syntax");
        require(
            state.draft == draft
                && draft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Expanded actions replaced unrelated private input");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.engine == engine
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.engine.profiler().current().isEmpty(),
            "Expanded actions published project/runtime changes");
        Files.writeString(
            out.resolve("expression-layout-project.json"),
            ProjectIO.JSON.writeValueAsString(state.project()));
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("fontSource", "user-scale-fixture");
        report.put("cases", cases);
        report.put("nativeHistory", nativeHistory);
        report.put("acceptedStages", accepted);
        report.put("finalPrivateText", item.parameters.get("expression").asText());
        for (String flag :
            List.of(
                "persistentCancel",
                "unicodeErrorReveal",
                "cancelBeforeStage",
                "refreshConsumed",
                "oversizeRetry",
                "staleOwner",
                "namedInputFeedback",
                "incompleteTextStaged",
                "projectPreserved")) report.put(flag, true);
        for (String flag :
            List.of(
                "internalMultilineFontVerified",
                "physicalDpiVerified",
                "physicalDetachedVerified",
                "gpuFontTextureVerified",
                "imeVerified")) report.put(flag, false);
        Files.writeString(
            out.resolve("expression-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(report));
        System.out.println(
            "EXPRESSION_LAYOUT_NATIVE_PASS persistent decisions / measured editor / Unicode error"
                + " reveal / private stage and retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static List<Map<String, Object>> nativeHistory() {
    var receipts = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("cancel", "escape", "close", "session-cancel", "stage")) {
      String source = item.parameters.get("expression").asText();
      var before = ProjectIO.JSON.valueToTree(draft);
      int[] stages = {0};
      openHistory(stages);
      frames(8);
      int oldId = dialog.editorWidgetId();
      String oldScope = dialog.actionScope();
      click("text");
      var io = ImGui.getIO();
      io.addKeyEvent(ImGuiKey.ModCtrl, true);
      io.addKeyEvent(ImGuiKey.End, true);
      frame();
      io.addKeyEvent(ImGuiKey.ModCtrl, false);
      io.addKeyEvent(ImGuiKey.End, false);
      frame();
      io.addInputCharactersUTF8("+9");
      frames(3);
      require(dialog.bufferText().equals(source + "+9"), "Native append did not edit this opening");
      restoreHistory(source, ImGuiKey.Z);
      restoreHistory(source + "+9", ImGuiKey.Y);
      restoreHistory(source, ImGuiKey.Z);
      require(oldId == dialog.editorWidgetId(), "Native text ID changed within its opening");
      switch (mode) {
        case "cancel" -> click("cancel");
        case "escape" -> {
          io.addKeyEvent(ImGuiKey.Escape, true);
          frame();
          io.addKeyEvent(ImGuiKey.Escape, false);
          frames(3);
        }
        case "close" -> { dialog.close(); frames(4); }
        case "session-cancel" -> { session.cancel(); frames(4); }
        case "stage" -> click("stage");
        default -> throw new AssertionError(mode);
      }
      require(done && session.closed(), "Native text predecessor did not close");
      require(stages[0] == (mode.equals("stage") ? 1 : 0), "Unexpected native history staging");
      openHistory(stages);
      frames(8);
      int newId = dialog.editorWidgetId();
      require(newId != oldId, "A new opening reused its predecessor's text ID");
      historyKey(ImGuiKey.Y);
      require(dialog.bufferText().equals(source) && session.text().equals(source),
          "A fresh opening revived a discarded native Redo");
      historyKey(ImGuiKey.Z);
      require(dialog.bufferText().equals(source) && session.text().equals(source),
          "A fresh opening resurrected discarded native history");
      receipts.add(Map.of(
          "mode", mode, "oldWidgetId", oldId, "newWidgetId", newId,
          "oldScope", oldScope, "newScope", dialog.actionScope(),
          "source", source, "restored", dialog.bufferText(), "stages", stages[0]));
      click("cancel");
      require(before.equals(ProjectIO.JSON.valueToTree(draft)), "Native history changed Properties");
    }
    return receipts;
  }

  private static void openHistory(int[] stages) {
    session = new ExpressionEditSession(state, draft, item);
    dialog = new ExpressionEditDialog(state, session, () -> stages[0]++);
    done = false;
  }

  private static void restoreHistory(String expected, int key) {
    for (int i = 0; i < 4 && !dialog.bufferText().equals(expected); i++) historyKey(key);
    require(dialog.bufferText().equals(expected), "Native expression Undo/Redo lost exact text");
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

  private static void open(String value, boolean failRefresh) {
    session = new ExpressionEditSession(state, draft, item);
    session.edit(value);
    dialog =
        new ExpressionEditDialog(
            state,
            session,
            () -> {
              accepted++;
              if (failRefresh) throw new IllegalStateException("refresh fixture");
            });
    done = false;
  }

  private static void clickTitle() {
    var modal = dialog.layout.bounds();
    click(new float[] {modal.x() + modal.width() / 2, modal.y() + 5});
  }

  private static void releaseStageWithEscape() {
    var at = dialog.control("stage");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(3);
  }

  private static void click(String key) {
    click(dialog.control(key));
  }

  private static void click(float[] at) {
    require(at != null, "Missing expanded control");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void replace(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Expanded expression layout fixture");
    UiScale.window(state.user, "Properties");
    DialogLayout.observe(state, "Properties");
    if (!done) done = dialog.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
