package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production noise numeric rows, native focus/input and explicit Apply/Undo replay. */
public final class PropertyNumericProbe {
  private static EditorState state;
  private static PropertyPanel properties;
  private static int width = 1200, height = 900;
  private static float scale = 1;

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
        require(state.change(PropertyNumericProbe::fixture), "Numeric fixture rejected");
        state.document.save(out.resolve("before.json"));
        state.user.save(out.resolve("preferences-before.json"));
        var before = ProjectIO.JSON.valueToTree(state.project());
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        var cases = new ArrayList<Map<String, Object>>();
        properties = new PropertyPanel(state);
        for (int dimension : new int[] {2, 3}) {
          var noise =
              (dimension == 2 ? state.project().noise2D : state.project().noise3D).getFirst();
          state.select("noise" + dimension + "D", noise.uuid);
          var draft = state.draft;
          var draftBefore = ProjectIO.JSON.valueToTree(draft);
          for (String role :
              List.of("source", "transform", "warp", "remap", "scalar", "vector", "parameter")) {
            int[][] sizes = {
              {1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}
            };
            float[] scales = {1, .5f, 1, 2.5f, 6.25f, 1};
            for (int i = 0; i < sizes.length; i++) {
              width = sizes[i][0];
              height = sizes[i][1];
              scale = scales[i];
              focus((Noise) draft, role);
              frames(8);
              require(!properties.probeNumericRows().isEmpty(), "Numeric rows missing");
              cases.add(
                  Map.of(
                      "dimension",
                      dimension,
                      "role",
                      role,
                      "scenario",
                      i,
                      "scale",
                      scale,
                      "body",
                      properties.chrome.body(),
                      "rows",
                      properties.probeNumericRows().values()));
            }
          }
          require(
              state.draft == draft && draftBefore.equals(ProjectIO.JSON.valueToTree(draft)),
              "Layout or navigation changed the private noise");
        }
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && !state.document.dirty(),
            "Numeric layout changed canonical state");
        width = 1200;
        height = 900;
        scale = 1;
        var noise = state.project().noise2D.getFirst();
        state.select("noise2D", noise.uuid);
        focus((Noise) state.draft, "source");
        frames(8);
        require(
            "null:algorithm/parameters/value".equals(properties.probeFocusedProperty()),
            "Source navigation did not focus exact text");
        type("1.0000000000000002");
        require(source() == Math.nextUp(1.0), "Focused exact text lost adjacent-double precision");
        var sourceRow = sourceRow();
        click(point(sourceRow.slider(), .371));
        double slider = source();
        require(
            slider >= -1 && slider <= 1 && slider != Math.rint(slider * 100000) / 100000,
            "Source slider did not preserve precision beyond its five-place readout");
        click(point(sourceRow().input(), .25));
        type("0.625");
        require(source() == .625, "Source exact input failed");
        var numericGuidance = guidanceReplay((Noise) state.draft, out);
        focus((Noise) state.draft, "parameter");
        frames(8);
        type("1e100");
        require(
            ((Noise) state.draft).sharedParameters.getFirst().value.doubleValue() == 1e100,
            "Shared parameter text was clamped to its slider suggestion");
        type("0.75");
        require(
            ((Noise) state.draft).sharedParameters.getFirst().value.doubleValue() == .75,
            "Focused shared parameter text failed");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history,
            "Private numeric edits committed before Apply");
        require(state.applyDraft(), "Numeric Apply rejected");
        new ProjectIO().save(out.resolve("applied.json"), state.project());
        state.document.undo();
        state.revision++;
        new ProjectIO().save(out.resolve("undone.json"), state.project());
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Numeric edit was not one Undo");
        state.document.redo();
        state.revision++;
        state.refreshDraft();
        new ProjectIO().save(out.resolve("redone.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Numeric rows changed preferences");
        Files.writeString(
            out.resolve("property-numeric-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "fontSource",
                    "synthetic-global-font-fixture",
                    "cases",
                    cases,
                    "sliderValue",
                    slider,
                    "exactFocusEdited",
                    true,
                    "outsideSuggestionEdited",
                    true,
                    "privateUntilApply",
                    true,
                    "oneUndoRedo",
                    true,
                    "physicalDpiVerified",
                    false,
                    "numericGuidance",
                    numericGuidance)));
        System.out.println(
            "PROPERTY_NUMERIC_NATIVE_PASS production noise rows / exact focus / private Apply and"
                + " Undo");
      }
      PropertiesApplyProbe.run(out.resolve("background-apply"));
    } finally {
      ImGui.destroyContext();
    }
  }

  private static List<Map<String, Object>> guidanceReplay(Noise noise, Path out) throws Exception {
    var before = ProjectIO.JSON.valueToTree(noise);
    var committed = ProjectIO.JSON.valueToTree(state.project());
    var receipts = new ArrayList<UiTooltip.Receipt>();
    var result = new ArrayList<Map<String, Object>>();
    UiTooltip.observe(receipts::add);
    try {
      for (String role : List.of("source", "transform", "warp", "remap")) {
        focus(noise, role);
        frames(8);
        String topic;
        UiFields.NumericRow row;
        if (role.equals("source")) {
          topic = "noise2D.algorithm.CONSTANT.value";
          row = sourceRow();
        } else {
          var item =
              role.equals("transform")
                  ? noise.coordinateTransforms.getFirst()
                  : noise.processing.stream()
                      .filter(i -> i.id.equals(role))
                      .findFirst()
                      .orElseThrow();
          String key =
              role.equals("transform") ? "x" : role.equals("warp") ? "amplitude" : "inputMin";
          topic =
              role.equals("transform")
                  ? "transform2D.TRANSLATE.x"
                  : "processing." + item.kind + ".parameter." + key;
          row = numericRow(properties.probeParameter(item.uuid, key));
        }
        var control = state.metadata.controls.get(topic);
        String original = control.tooltip;
        String configured = "Numeric fixture 100% ### snow 雪 / " + role;
        control.tooltip = configured;
        try {
          for (boolean slider : new boolean[] {true, false}) {
            var bounds = slider ? row.slider() : row.input();
            var at = point(bounds, .5);
            receipts.clear();
            ImGui.getIO().addMousePosEvent(at[0], at[1]);
            frames(5);
            require(
                receipts.stream()
                    .anyMatch(
                        r ->
                            r.preview().contains(configured)
                                && r.preview().contains("Value:")
                                && r.preview().contains("Suggested slider range")),
                "Configured metadata did not reach " + role + (slider ? " slider" : " text"));
            state.helpRequests.take();
            click(at, 1);
            var help = properties.contextHelp();
            require(
                help.actionId() != 0 && help.actionCenter() != null,
                "Numeric context Help did not open");
            click(help.actionCenter());
            require(
                state.helpRequests.take().orElseThrow().equals(topic),
                "Numeric context Help routed another metadata topic");
            result.add(
                Map.of(
                    "role",
                    role,
                    "part",
                    slider ? "slider" : "text",
                    "topic",
                    topic,
                    "nativeId",
                    slider ? row.sliderId() : row.inputId(),
                    "configuredTooltip",
                    configured));
          }
          var catalog = new HelpCatalog(state.metadata);
          Files.writeString(
              out.resolve("numeric-help-" + role + ".txt"), catalog.topic(topic).copyText(catalog));
        } finally {
          control.tooltip = original;
        }
      }
    } finally {
      UiTooltip.observe(null);
    }
    require(
        before.equals(ProjectIO.JSON.valueToTree(noise))
            && committed.equals(ProjectIO.JSON.valueToTree(state.project())),
        "Numeric tooltip/Help changed private or committed values");
    return List.copyOf(result);
  }

  private static void fixture(ProjectData project) {
    for (int dimension : new int[] {2, 3}) {
      var n =
          EditorCreations.noise(
              state.metadata, dimension, "numeric-" + dimension, null, "CONSTANT");
      n.algorithm.parameters.put("value", .125);
      n.coordinateTransforms.add(
          EditorCreations.transform(state.metadata, dimension, "TRANSLATE", "transform"));
      n.processing.add(
          EditorCreations.processing(state.metadata, "WARP_SAMPLE", "warp", dimension));
      n.processing.add(EditorCreations.processing(state.metadata, "REMAP", "remap", dimension));
      n.processing.add(EditorCreations.processing(state.metadata, "CONSTANT", "scalar", dimension));
      var vector = EditorCreations.processing(state.metadata, "CONSTANT", "vector", dimension);
      vector.resultType = "Vector" + dimension;
      vector.parameters.put(
          "value",
          ProjectIO.JSON.valueToTree(
              dimension == 2 ? new double[] {0, 0} : new double[] {0, 0, 0}));
      n.processing.add(vector);
      var parameter = new Parameter();
      parameter.id = "parameter";
      parameter.type = "Scalar";
      parameter.value = ProjectIO.JSON.valueToTree(.25);
      n.sharedParameters.add(parameter);
      (dimension == 2 ? project.noise2D : project.noise3D).add(n);
    }
  }

  private static void focus(Noise n, String role) {
    UUID control;
    String path;
    if (role.equals("source")) {
      control = null;
      path = "algorithm/parameters/value";
    } else if (role.equals("parameter")) {
      control = n.sharedParameters.getFirst().uuid;
      path = "parameters/value";
    } else {
      var item =
          role.equals("transform")
              ? n.coordinateTransforms.getFirst()
              : n.processing.stream().filter(i -> i.id.equals(role)).findFirst().orElseThrow();
      control = item.uuid;
      path =
          "parameters/"
              + switch (role) {
                case "transform" -> "x";
                case "warp" -> "amplitude";
                case "remap" -> "inputMin";
                case "vector" -> "value/0";
                default -> "value";
              };
    }
    require(
        properties.goToProblem(
            new EditorEvents.Target(
                state.project().project.uuid, n.uuid, control, path, "", false)),
        "Numeric focus target rejected: " + role);
  }

  private static UiFields.NumericRow sourceRow() {
    return numericRow(properties.probeSourceControl("value"));
  }

  private static UiFields.NumericRow numericRow(float[] p) {
    require(p != null, "Missing numeric control center");
    return properties.probeNumericRows().values().stream()
        .filter(
            r ->
                p[0] >= r.input().x()
                    && p[0] <= r.input().x() + r.input().width()
                    && p[1] >= r.input().y()
                    && p[1] <= r.input().y() + r.input().height())
        .findFirst()
        .orElseThrow();
  }

  private static double source() {
    return ((Noise) state.draft).algorithm.parameters.get("value");
  }

  private static void type(String text) {
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

  private static float[] point(DialogBounds.Rect r, double fraction) {
    return new float[] {r.x() + (float) (r.width() * fraction), r.y() + r.height() / 2};
  }

  private static void click(float[] p) {
    click(p, 0);
  }

  private static void click(float[] p, int button) {
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0], p[1]);
    frame();
    io.addMouseButtonEvent(button, true);
    frame();
    io.addMouseButtonEvent(button, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(scale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    properties.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
