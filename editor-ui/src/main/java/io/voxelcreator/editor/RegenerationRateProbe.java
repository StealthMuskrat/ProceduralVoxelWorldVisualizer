package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred actual rate slider/text, retained errors, live persistence and input retirement. */
final class RegenerationRateProbe {
  private static EditorState state;
  private static LivePreferencesPanel panel;
  private static Path destination;
  private static int width = 1400, height = 900, saves;
  private static boolean visible = true;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      saves = 0;
      require(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "field";
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", .125);
                p.noise2D.add(n);
              }),
          "Rate fixture rejected");
      state.document.save(out.resolve("project-before.json"));
      byte[] projectBytes = Files.readAllBytes(out.resolve("project-before.json"));
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      ((Noise) state.draft).id = "private-field";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var project = ProjectIO.JSON.valueToTree(state.project());
      var draft = state.draft;
      var privateValues = ProjectIO.JSON.valueToTree(draft);
      var selected = state.selected;
      long revision = state.revision, history = state.document.historyBytes();
      var lifetime = state.document.sessionToken();
      var engine = state.engine;
      state.metadata.controls.get("settings.editor.opportunitiesPerSecond").tooltip =
          "Configured exact rate 100% ### snow 雪";
      panel =
          new LivePreferencesPanel(
              state,
              () -> {
                state.user.save(destination);
                saves++;
              },
              () -> {},
              () -> {},
              List::of);
      panel.probeControls();
      var cases = new ArrayList<Map<String, Object>>();
      int[][] sizes = {{1400, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 300}};
      float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, windows = {1, 1, 1, 1, 2.5f, 1};
      Integer inputId = null, sliderId = null;
      for (int i = 0; i < sizes.length; i++) {
        width = sizes[i][0];
        height = sizes[i][1];
        state.user.appearance.globalScale = globals[i];
        state.user.appearance.windowScales.put("User Settings", windows[i]);
        frames(6);
        var row = panel.rateRow();
        require(
            row.enabled() && row.sliderId() != row.inputId(),
            "Rate pair unavailable or shares an ID");
        require(
            (inputId == null || inputId == row.inputId())
                && (sliderId == null || sliderId == row.sliderId()),
            "Rate reflow replaced active input identity");
        inputId = row.inputId();
        sliderId = row.sliderId();
        require(
            row.inline()
                ? row.slider().x() + row.slider().width() <= row.input().x() + .02
                : row.slider().y() + row.slider().height() <= row.input().y() + .02,
            "Rate pair overlaps");
        for (var rect : List.of(row.slider(), row.input()))
          require(
              rect.width() > 0
                  && rect.x() >= panel.editorLayout.body().x() - .1
                  && rect.x() + rect.width()
                      <= panel.editorLayout.body().x() + panel.editorLayout.body().width() + .1,
              "Rate pair escaped available width");
        cases.add(
            Map.of(
                "scenario",
                i,
                "width",
                width,
                "height",
                height,
                "globalScale",
                globals[i],
                "windowScale",
                windows[i],
                "row",
                row,
                "body",
                panel.editorLayout.body(),
                "actions",
                panel.editorLayout.actions()));
      }
      width = 1400;
      height = 900;
      state.user.appearance.globalScale = 1;
      state.user.appearance.windowScales.put("User Settings", 1f);
      frames(5);
      state.user.save(out.resolve("preferences-before.json"));
      var preferences = ProjectIO.JSON.valueToTree(state.user);
      edit("+0007");
      require(
          state.user.editor.opportunitiesPerSecond == 7 && panel.rateText().equals("+0007"),
          "Exact rate text was rounded or canonicalized");
      int stableInput = panel.rateRow().inputId();
      frames(4);
      require(
          panel.rateRow().inputId() == stableInput && panel.rateText().equals("+0007"),
          "Idle frame replaced exact rate text");
      var rejected = new ArrayList<String>();
      for (String raw : List.of("-", "0", "21", "1.0", "1e1", " 7", "2147483648")) {
        edit(raw);
        require(
            !panel.rateProblem().isBlank()
                && panel.rateText().equals(raw)
                && state.user.editor.opportunitiesPerSecond == 7
                && !panel.rateRow().enabled(),
            "Rejected rate altered live preference");
        click(panel.probeControl("rate"), 0);
        require(
            panel.rateText().equals(raw) && state.user.editor.opportunitiesPerSecond == 7,
            "Disabled slider discarded rejected text");
        rejected.add(raw);
      }
      var help = new ArrayList<String>();
      for (String part : List.of("rate", "rate-text")) {
        panel.probeReveal(part);
        frames(4);
        click(panel.probeControl(part), 1);
        frames(3);
        require(
            panel.rateHelp().actionId() != 0 && panel.rateHelp().actionCenter() != null,
            "Missing rate context Help");
        click(panel.rateHelp().actionCenter(), 0);
        frames(3);
        String topic = state.helpRequests.take().orElseThrow();
        require(
            topic.equals("settings.editor.opportunitiesPerSecond"),
            "Rate part routed to wrong Help");
        help.add(topic);
      }
      destination = out.resolve("preferences-invalid.json");
      click(panel.probeControl("save"), 0);
      require(
          UserConfig.read(destination).editor.opportunitiesPerSecond == 7
              && panel.rateText().equals("2147483648"),
          "Save Preferences adopted or erased rejected rate text");
      panel.probeReveal("restore-rate");
      frames(5);
      click(panel.probeControl("restore-rate"), 0);
      require(
          panel.rateText().equals("7") && panel.rateProblem().isEmpty(),
          "Restore Active Rate changed live value or retained error");
      var row = panel.rateRow();
      press(
          new float[] {
            row.slider().x() + .5f * row.slider().width(),
            row.slider().y() + row.slider().height() / 2
          });
      int held = state.user.editor.opportunitiesPerSecond, oldId = panel.rateRow().sliderId();
      visible = false;
      frames(3);
      visible = true;
      frames(3);
      require(
          panel.rateRow().sliderId() != oldId && state.user.editor.opportunitiesPerSecond == held,
          "Hidden held slider continued on a reopened pane");
      ImGui.getIO().addMouseButtonEvent(0, false);
      frames(3);
      row = panel.rateRow();
      click(
          new float[] {
            row.slider().x() + .99f * row.slider().width(),
            row.slider().y() + row.slider().height() / 2
          },
          0);
      require(
          state.user.editor.opportunitiesPerSecond == 20 && panel.rateText().equals("20"),
          "Rate slider failed to reach exact upper bound");
      edit("21");
      oldId = panel.rateRow().inputId();
      var replacement = new UserConfig.Editor();
      replacement.opportunitiesPerSecond = 20;
      state.user.editor = replacement;
      frames(4);
      require(
          panel.rateText().equals("20") && panel.rateRow().inputId() != oldId,
          "Replacement preference source reused rejected text or native identity");
      destination = out.resolve("preferences-final.json");
      click(panel.probeControl("save"), 0);
      ((com.fasterxml.jackson.databind.node.ObjectNode) preferences.get("editor"))
          .put("opportunitiesPerSecond", 20);
      require(
          preferences.equals(ProjectIO.JSON.valueToTree(state.user)) && saves == 2,
          "Rate edits changed unrelated preferences");
      require(
          project.equals(ProjectIO.JSON.valueToTree(state.project()))
              && state.draft == draft
              && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
              && selected.equals(state.selected)
              && state.multiSelection.equals(Set.of(selected))
              && state.seedInputs.text("seedOffset").equals("-")
              && state.revision == revision
              && state.document.historyBytes() == history
              && state.document.sessionToken() == lifetime
              && state.engine == engine
              && Arrays.equals(
                  projectBytes, Files.readAllBytes(out.resolve("project-before.json"))),
          "Rate controls changed project/private/history/runtime ownership");
      state.document.save(out.resolve("project-after.json"));
      Files.writeString(
          out.resolve("rate-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "formatVersion",
                  1,
                  "cases",
                  cases,
                  "rejected",
                  rejected,
                  "help",
                  help,
                  "saves",
                  saves,
                  "heldPaneRetired",
                  true,
                  "replacementRetired",
                  true,
                  "projectPrivateRetained",
                  true,
                  "physicalInputVerified",
                  false)));
    } finally {
      ImGui.getIO().addMouseButtonEvent(0, false);
      visible = true;
    }
  }

  private static void edit(String raw) {
    panel.probeReveal("rate-text");
    frames(3);
    click(panel.probeControl("rate-text"), 0);
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(raw);
    frames(4);
  }

  private static void press(float[] at) {
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
  }

  private static void click(float[] at, int button) {
    require(at != null, "Missing rate control");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(button, true);
    frame();
    io.addMouseButtonEvent(button, false);
    frame();
    frames(3);
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    if (visible) {
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.begin("User Settings", ImGuiWindowFlags.NoSavedSettings);
      UiScale.window(state.user, "User Settings");
      panel.drawEditor();
      ImGui.end();
    } else panel.suspendEditorActions();
    ImGui.render();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
