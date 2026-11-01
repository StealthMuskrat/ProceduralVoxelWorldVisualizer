package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred production creation source picker, optional defaults and frozen guarded publication. */
public final class NoiseCreationChoiceProbe {
  private static EditorState state;
  private static ResourcePanel resources;
  private static int width = 1200, height = 900;
  private static final List<Map<String, Object>> cases = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        resources = new ResourcePanel(state, new PropertyPanel(state));
        set("noise2D.algorithm", "CONSTANT");
        set("noise2D.algorithm.CONSTANT.value", .125);
        set("noise3D.algorithm", "GRADIENT_Y");
        set("noise3D.algorithm.CONSTANT.value", -.25);
        state.user.save(out.resolve("preferences-before.json"));
        new ProjectIO().save(out.resolve("empty.json"), state.project());
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        var oldScale = state.user.appearance.windowScales.get("Resources");
        float oldGlobal = state.user.appearance.globalScale;
        for (int i = 0; i < sizes.length; i++)
          for (int dimension : new int[] {2, 3}) {
            width = sizes[i][0];
            height = sizes[i][1];
            state.user.appearance.globalScale = globals[i];
            state.user.appearance.windowScales.put("Resources", scales[i]);
            open(dimension, "unpublished");
            var layout = resources.probeFormLayout("Create");
            var chrome = resources.probeFormChrome("Create");
            cases.add(
                Map.of(
                    "scenario",
                    i,
                    "dimension",
                    dimension,
                    "globalScale",
                    globals[i],
                    "windowScale",
                    scales[i],
                    "work",
                    layout.workArea().bounds(),
                    "modal",
                    layout.bounds(),
                    "body",
                    chrome.body(),
                    "actions",
                    chrome.actions()));
            require(
                resources.probeCreationAlgorithm() == null, "Reopening retained a prior choice");
            click(action("cancel"));
            require(
                state.project().noise2D.isEmpty() && state.project().noise3D.isEmpty(),
                "Cancelled form created a resource");
          }
        width = 1200;
        height = 900;
        state.user.appearance.globalScale = oldGlobal;
        if (oldScale == null) state.user.appearance.windowScales.remove("Resources");
        else state.user.appearance.windowScales.put("Resources", oldScale);
        open(3, "cancelled");
        choose("CONSTANT");
        var before = ProjectIO.JSON.valueToTree(state.project());
        press(action("primary"));
        ImGui.getIO().addMouseButtonEvent(0, false);
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
        frame();
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
        frames(2);
        require(
            !resources.probeFormOpen("Create")
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Escape did not cancel chosen-source creation");
        open(2, "default-2");
        choose("GRADIENT_Y");
        var picker = resources.probeCreateAlgorithmPicker();
        click(picker.buttonCenter());
        click(picker.noneCenter());
        require(resources.probeCreationAlgorithm() == null, "Creation default was not restored");
        click(action("primary"));
        require(
            state.project().noise2D.getFirst().algorithm.key.equals("CONSTANT"),
            "Default source was ignored");
        open(2, "gradient-2");
        choose("GRADIENT_Y");
        click(action("primary"));
        require(
            state.project().noise2D.getLast().algorithm.key.equals("GRADIENT_Y"),
            "Explicit 2D source was ignored");
        open(3, "constant-3");
        choose("CONSTANT");
        click(action("primary"));
        require(
            state.project().noise3D.getFirst().algorithm.key.equals("CONSTANT"),
            "Explicit 3D source was ignored");
        new ProjectIO().save(out.resolve("created.json"), state.project());
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).seedOffset = 17;
        set("noise2D.algorithm.CONSTANT.value", -.75);
        open(2, "frozen-2");
        choose("CONSTANT");
        click(action("primary"));
        require(
            resources.probeEditPending() && state.project().noise2D.size() == 2,
            "Private input was discarded or creation published without a decision");
        set("noise2D.algorithm.CONSTANT.value", .875);
        frames(3);
        click(resources.probeEditButton("APPLY"));
        require(
            !resources.probeEditPending()
                && state.project().noise2D.getLast().algorithm.parameters.get("value") == -.75
                && state.project().noise2D.getFirst().seedOffset == 17,
            "Prepared source/defaults were rebuilt during the guard");
        new ProjectIO().save(out.resolve("applied.json"), state.project());
        var applied = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        new ProjectIO().save(out.resolve("undone.json"), state.project());
        state.document.redo();
        state.revision++;
        require(
            applied.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Guarded creation Redo changed its definition");
        new ProjectIO().save(out.resolve("redone.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("noise-creation-choice-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "fontSource",
                    "synthetic-global-window-fixture",
                    "cases",
                    cases,
                    "optionalDefault",
                    true,
                    "explicitDimensions",
                    true,
                    "cancelBeforeCreate",
                    true,
                    "preparedDefinitionRetained",
                    true,
                    "oneUndoRedo",
                    true,
                    "physicalDpiVerified",
                    false)));
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void set(String key, Object value) {
    state.metadata.creationDefaults.put(key, ProjectIO.JSON.valueToTree(value));
  }

  private static void open(int dimension, String id) {
    resources.probeOpenNoiseCreation(dimension);
    resources.probeFormName(id);
    frames(5);
    require(resources.probeFormOpen("Create"), "Creation form did not open");
  }

  private static void choose(String key) {
    var picker = resources.probeCreateAlgorithmPicker();
    click(picker.buttonCenter());
    ImGui.getIO().addInputCharactersUTF8(key);
    frames(3);
    require(picker.submitted() == 1, "Source filter did not isolate its registry key");
    click(picker.option(key));
    require(
        key.equals(resources.probeCreationAlgorithm()),
        "Source picker did not retain the explicit choice");
  }

  private static float[] action(String key) {
    var r = resources.probeFormChrome("Create").actions().get(key);
    require(r != null, "Missing creation action");
    return new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2};
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Noise creation caller");
    UiScale.window(state.user, "Resources");
    int root = ImGui.getID("creation-root");
    resources.probeDrawForms();
    resources.probeDrawDecisions();
    require(root == ImGui.getID("creation-root"), "Creation leaked native scopes");
    ImGui.end();
    ImGui.render();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void press(float[] at) {
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
  }

  private static void click(float[] at) {
    require(at != null, "Missing choice control");
    press(at);
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
    frames(2);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
