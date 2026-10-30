package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred project/profile decision arbitration with native footers and explicit save boundaries.
 */
public final class SettingsDecisionProbe {
  private static final String VALUE = "noise2D.algorithm.CONSTANT.value";
  private static EditorState state;
  private static ProjectSettingsPanel project;
  private static ApplicationSettingsPanel app;
  private static String form = "Project";
  private static boolean visible = true;
  private static int width = 1200, height = 900;
  private static DialogBounds.Rect window;

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
        state.metadata = AppMetadata.defaults();
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "existing";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Settings decision fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var privateDraft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(privateDraft);
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var engine = state.engine;
        var file = out.resolve("app-config.json");
        state.metadata.save(file);
        var original = Files.readAllBytes(file);
        Files.write(out.resolve("profile-before.json"), original);
        var active = state.metadata;
        project = new ProjectSettingsPanel(state);
        openProject("-");
        project.probeText("minY", "+0002");
        project.probeText("maxY", "-0003");
        app = new ApplicationSettingsPanel(state);
        app.showControl(VALUE, "Creation Defaults");
        app.probeStageCreation(VALUE, "1e-");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        for (String mode : List.of("Project", "Application")) {
          form = mode;
          Long request = null;
          Map<String, Integer> ids = null;
          for (int i = 0; i < sizes.length; i++) {
            dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
            frames(8);
            if (request == null) {
              request = scope().version();
              ids = chrome().actionIds();
            }
            require(
                request == scope().version() && ids.equals(chrome().actionIds()),
                "Settings reflow changed request ownership");
            cases.add(receipt(i, globals[i], scales[i]));
          }
        }
        require(
            project.text("seed").equals("-")
                && app.rawText(VALUE).equals(List.of("1e-"))
                && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Layout changed retained settings or project");
        dimensions(1200, 900, 1, 1);
        form = "Project";
        project.cancel();
        openProject("17");
        frames(5);
        releaseWithEscape(control("apply"));
        require(
            !visible && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Project Escape lost to Apply");
        openProject("17");
        frames(5);
        press(control("apply"));
        long old = scope().version();
        var oldIds = chrome().actionIds();
        project.cancel();
        openProject("23");
        frame();
        release();
        frames(4);
        require(
            visible
                && scope().version() > old
                && !oldIds.get("apply").equals(chrome().actionIds().get("apply"))
                && project.text("seed").equals("23")
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Held Project Apply activated a successor");
        click(control("cancel"));
        openProject("17");
        frames(4);
        require(state.change(p -> p.project.seed = 9), "Stale reload fixture rejected");
        frames(4);
        var changed = ProjectIO.JSON.valueToTree(state.project());
        releaseWithEscape(control("reload"));
        require(
            !visible && changed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Escape reloaded a stale form");
        state.document.undo();
        state.revision++;
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Stale reload fixture Undo failed");
        openProject("+00019");
        var pending = project.text("seed");
        form = "Application";
        for (String action : List.of("apply", "revert", "builtins")) {
          stageProfile("0.625");
          frames(5);
          releaseWithEscape(control(action));
          require(
              app.rawText(VALUE).equals(List.of("0.625"))
                  && state.metadata == active
                  && Arrays.equals(original, Files.readAllBytes(file)),
              "Profile Escape saved or replaced its draft");
          frames(4);
          old = scope().version();
          oldIds = chrome().actionIds();
          press(control(action));
          stageProfile("0.875");
          frame();
          release();
          frames(4);
          require(
              scope().version() > old
                  && !oldIds.get(action).equals(chrome().actionIds().get(action))
                  && app.rawText(VALUE).equals(List.of("0.875"))
                  && state.metadata == active
                  && Arrays.equals(original, Files.readAllBytes(file)),
              "Held profile action replaced/published a successor");
        }
        stageProfile("0.625");
        frames(5);
        long retry = scope().version();
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "retained");
        click(control("apply"));
        require(
            scope().version() == retry
                && state.metadata == active
                && app.rawText(VALUE).equals(List.of("0.625"))
                && Files.readString(file.resolve("keep")).equals("retained"),
            "Failed profile save consumed request or input");
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click(control("apply"));
        frames(4);
        require(
            state.metadata != active
                && AppMetadata.read(file).creationDefaults.get(VALUE).asDouble() == .625
                && project.text("seed").equals(pending)
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Profile retry mixed settings owners");
        Files.write(out.resolve("profile-after.json"), Files.readAllBytes(file));
        var future = new ProjectData();
        future.noise2D.add(EditorCreations.noise(state.metadata, 2, "future", null));
        new ProjectIO().save(out.resolve("future-profile-item.json"), future);
        app.probeStageCreation(VALUE, "0.875");
        var pendingProfile = app.rawText(VALUE);
        form = "Project";
        project.cancel();
        openProject("-");
        project.probeText("minY", "-2");
        project.probeText("maxY", "3");
        frames(5);
        click(control("apply"));
        require(
            visible
                && project.text("seed").equals("-")
                && project.candidateIssueCount() > 0
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Invalid Project Apply lost input");
        click(control("problem"));
        for (int i = 0; i < 40 && !"seed".equals(project.focusedField()); i++) frame();
        require("seed".equals(project.focusedField()), "Project feedback focus failed");
        frames(3);
        replace(Long.toString(Long.MIN_VALUE));
        new ProjectIO().save(out.resolve("settings-before.json"), state.project());
        click(control("apply"));
        require(
            !visible
                && state.project().project.seed == Long.MIN_VALUE
                && state.project().project.minY == -2
                && state.project().project.maxY == 3
                && app.rawText(VALUE).equals(pendingProfile),
            "Project settings publication mixed private owners");
        var after = ProjectIO.JSON.valueToTree(state.project());
        new ProjectIO().save(out.resolve("settings-after.json"), state.project());
        state.document.undo();
        state.revision++;
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())), "One settings Undo failed");
        new ProjectIO().save(out.resolve("settings-undone.json"), state.project());
        state.document.redo();
        state.revision++;
        require(
            after.equals(ProjectIO.JSON.valueToTree(state.project())), "One settings Redo failed");
        new ProjectIO().save(out.resolve("settings-redone.json"), state.project());
        require(
            state.draft == privateDraft
                && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && app.rawText(VALUE).equals(pendingProfile)
                && state.engine == engine
                && state.engine.profiler().current().isEmpty(),
            "Settings decisions changed independent private/runtime ownership");
        require(
            !Files.exists(out.resolve("user-config.json")),
            "Settings decisions unexpectedly saved User Config");
        Files.writeString(
            out.resolve("settings-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("projectCancelBeforeMutation", true),
                    Map.entry("projectReloadEscape", true),
                    Map.entry("heldSuccessorsBlocked", true),
                    Map.entry("profileEscapePreserved", true),
                    Map.entry("profileRetryRetained", true),
                    Map.entry("futureItemsOnly", true),
                    Map.entry("feedbackFocus", true),
                    Map.entry("privateOwnersPreserved", true),
                    Map.entry("oneProjectUndoRedo", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "SETTINGS_DECISIONS_NATIVE_PASS scoped project/profile decisions / Escape / retained"
                + " retry / isolated explicit publication");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void openProject(String seed) {
    require(project.open(null), "Project open rejected");
    visible = true;
    project.probeText("seed", seed);
  }

  private static void stageProfile(String value) {
    app.probeRevert();
    app.probeStageCreation("noise2D.algorithm", "CONSTANT");
    app.probeStageCreation(VALUE, value);
    app.showControl(VALUE, "Creation Defaults");
  }

  private static SettingsPageChrome chrome() {
    return form.equals("Project") ? project.layout : app.layout;
  }

  private static SettingsActionScope scope() {
    return form.equals("Project") ? project.actionScope : app.actionScope;
  }

  private static float[] control(String key) {
    return form.equals("Project") ? project.controlAt(key) : app.controlAt(key);
  }

  private static Map<String, Object> receipt(int scenario, float global, float scale) {
    return Map.ofEntries(
        Map.entry("form", form),
        Map.entry("scenario", scenario),
        Map.entry("globalScale", global),
        Map.entry("windowScale", scale),
        Map.entry("window", window),
        Map.entry("body", chrome().body()),
        Map.entry("actions", chrome().actions()),
        Map.entry("ids", chrome().actionIds()),
        Map.entry("request", scope().version()));
  }

  private static void dimensions(int w, int h, float global, float scale) {
    width = w;
    height = h;
    state.user.appearance.globalScale = global;
    state.user.appearance.windowScales.put("Project Settings", scale);
    state.user.appearance.windowScales.put("Application Settings", scale);
  }

  private static void replace(String value) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void press(float[] at) {
    require(at != null, "Missing settings action");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static void click(float[] at) {
    press(at);
    release();
    frames(4);
  }

  private static void releaseWithEscape(float[] at) {
    press(at);
    var io = ImGui.getIO();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    String name = form.equals("Project") ? "Project Settings" : "Application Settings";
    ImGui.begin(name, ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, name);
    window =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    if (form.equals("Project")) {
      if (visible && project.draw()) visible = false;
    } else app.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
