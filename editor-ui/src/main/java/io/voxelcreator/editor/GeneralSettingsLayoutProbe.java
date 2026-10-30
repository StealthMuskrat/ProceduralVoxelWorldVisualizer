package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred production settings layout/actions workflow, with no GLFW or renderer instance. */
public final class GeneralSettingsLayoutProbe {
  private static EditorState state;
  private static ProjectSettingsPanel project;
  private static LivePreferencesPanel live;
  private static DebugSettingsPanel debug;
  private static ProfilePanel captures;
  private static String mode = "project";
  private static int width = 1400, height = 1200, saves, saveErrors, limits, retries, starts, opens;
  private static boolean projectVisible = true;
  private static DialogBounds.Rect window, category;
  private static final UiFields fields = new UiFields();
  private static Path out, file;
  private static final Viewports.StreamingStatistics RENDERING =
      new Viewports.StreamingStatistics(4096, 1024, 2048, 256, 12, 2, 8, 4, 3, true);

  public static void main(String[] args) throws Exception {
    out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    file = out.resolve("user-config.json");
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
                  n.id = "committed";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var privateDraft = (Noise) state.draft;
        privateDraft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        project = new ProjectSettingsPanel(state);
        project.open(null);
        live =
            new LivePreferencesPanel(
                state.user,
                GeneralSettingsLayoutProbe::save,
                () -> limits++,
                () -> retries++,
                () ->
                    List.of(
                        "2D: DISABLED / 0 bytes / Shadows disabled",
                        "3D Noise: READY / 4194304 bytes / complete resident geometry",
                        "World: FAILED / 0 bytes / " + "recoverable detail ".repeat(100)));
        live.probeControls();
        captures = new ProfilePanel(state, ignored -> {});
        debug =
            new DebugSettingsPanel(
                state,
                () -> RENDERING,
                () -> {
                  starts++;
                  captures.start();
                },
                () -> opens++);
        projectType("seed", "-");
        projectType("minY", "+0002");
        projectType("maxY", "-0003");
        var raw = text();
        var feedback = project.feedback();
        show("editor");
        click("auto");
        var rate = live.inputBounds().get("rate");
        click(new float[] {rate.x() + .99f * rate.width(), rate.y() + rate.height() / 2});
        require(
            !state.user.editor.autoRegenerate && state.user.editor.opportunitiesPerSecond == 20,
            "Live editor controls failed");
        show("visualization");
        live.probeReveal("shadows");
        frames(5);
        click("shadows");
        choose("resolution", "2048");
        live.probeReveal("volume-shadows");
        frames(5);
        click("volume-shadows");
        choose("volume-steps", "32");
        click("retry");
        click("limits");
        require(
            retries == 1
                && limits == 1
                && state.user.visualization.shadowResolution == 2048
                && state.user.visualization.volumeShadowSteps == 32,
            "Display callbacks/options failed");
        click("save");
        require(
            saves == 1 && project.feedback() == feedback && text().equals(raw),
            "Ordinary save changed private project settings");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1200}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 300}};
        float[] global = {1, .5f, 1, 2.5f, 2.5f, 1};
        float[] multiplier = {1, 1, 1, 1, 2.5f, 1};
        var ids = new HashMap<String, Map<String, Integer>>();
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = global[i];
          for (String title : List.of("User Settings", "Project Settings", "Debug & Profiling"))
            state.user.appearance.windowScales.put(title, multiplier[i]);
          for (String form : List.of("project", "editor", "visualization", "debug")) {
            show(form);
            if (form.equals("project")) {
              click("problem");
              frames(5);
            }
            var chrome = chrome();
            require(DialogBounds.contains(window, chrome.body()), "Body outside client");
            for (var action : chrome.actions().values())
              require(DialogBounds.contains(window, action), "Action outside client");
            if (category != null)
              require(DialogBounds.contains(window, category), "Selector outside client");
            var fieldIds =
                form.equals("project")
                    ? project.fieldIds()
                    : form.equals("debug") ? Map.<String, Integer>of() : live.fieldIds();
            if (!form.equals("debug")) {
              if (!ids.containsKey(form)) ids.put(form, fieldIds);
              require(
                  ids.get(form).equals(fieldIds), "Settings field identity changed during reflow");
            }
            var inputs =
                form.equals("project")
                    ? project.inputBounds()
                    : form.equals("debug")
                        ? Map.<String, DialogBounds.Rect>of()
                        : live.inputBounds();
            for (var input : inputs.values())
              require(
                  input.width() > 0
                      && input.x() >= chrome.body().x()
                      && input.x() + input.width()
                          <= chrome.body().x() + chrome.body().width() + .1f,
                  "Input extends horizontally outside body");
            require(
                text().equals(raw) && project.feedback() == feedback,
                "Navigation/reflow replaced raw input or cached feedback");
            require(
                state.engine.profiler().current().isEmpty(), "Navigation started instrumentation");
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("scenario", i);
            receipt.put("form", form);
            receipt.put("window", window);
            receipt.put("body", chrome.body());
            receipt.put("actions", chrome.actions());
            receipt.put("ids", fieldIds);
            receipt.put("inputs", inputs);
            receipt.put("category", category);
            receipt.put("globalScale", global[i]);
            receipt.put("windowScale", multiplier[i]);
            receipt.put("projectText", text());
            cases.add(receipt);
          }
        }
        width = 1400;
        height = 1200;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.clear();
        show("debug");
        for (String section : DebugSettingsPanel.SECTIONS) {
          debug.section(section);
          frames(4);
          require(state.engine.profiler().current().isEmpty(), "Section started a capture");
        }
        click("profiler");
        require(
            opens == 1 && starts == 0 && !debug.recording(),
            "Open Profiler started instrumentation");
        click("capture");
        var capture = state.engine.profiler().current().orElseThrow();
        require(debug.recording() && starts == 1 && opens == 2, "Shared capture did not start");
        show("editor");
        show("debug");
        require(
            state.engine.profiler().current().orElseThrow() == capture && debug.recording(),
            "Page change replaced the shared capture");
        click("capture");
        require(
            !debug.recording() && !capture.recording() && starts == 1,
            "Shared capture did not stop");
        state.engine.profiler().clear();
        show("visualization");
        live.probeReveal("shadows");
        frames(4);
        click("shadows");
        click("retry");
        require(
            retries == 1 && state.user.visualization.shadowResolution == 2048,
            "Disabled shadow retry fired or discarded retained resolution");
        live.probeReveal("shadows");
        frames(4);
        click("shadows");
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "original");
        click("save");
        require(
            saveErrors == 1
                && saves == 1
                && Files.readString(file.resolve("keep")).equals("original"),
            "Ordinary save failure was not retained");
        width = 360;
        height = 640;
        frames(5);
        require(text().equals(raw), "Save failure/reflow discarded project text");
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click("save");
        require(
            saves == 2 && UserConfig.read(file).visualization.shadowResolution == 2048,
            "Ordinary save retry failed");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.engine == engine,
            "Preferences/navigation changed the document or engine owner");

        width = 1400;
        height = 1200;
        projectType("seed", Long.toString(Long.MIN_VALUE));
        // Coupled bounds have a real supported destination; no previous parsed values are used.
        click("problem");
        frames(6);
        require("maxY".equals(project.focusedField()), "Coupled bounds did not reveal maximum Y");
        type("+0009");
        require(project.feedback().isEmpty(), "Corrected project settings still invalid");
        var rejection =
            java.util.stream.IntStream.range(0, 300)
                .mapToObj(
                    i -> Diagnostic.error("FIXTURE_REJECT", "/fixture/" + i, "detail ".repeat(400)))
                .toList();
        state.document.setValidator(p -> rejection);
        click("apply");
        frames(4);
        require(
            projectVisible && project.candidateIssueCount() == 300 && project.diagnosticRows() == 8,
            "Candidate rejection lost bounded details");
        width = 360;
        height = 640;
        frames(5);
        require(
            DialogBounds.contains(window, project.layout.actions().get("apply"))
                && project.text("seed").equals(Long.toString(Long.MIN_VALUE)),
            "Rejected project input/actions lost during reflow");
        state.document.setValidator(NoiseCompiler::validate);
        click("apply");
        frames(3);
        require(
            !projectVisible
                && state.project().project.seed == Long.MIN_VALUE
                && state.project().project.minY == 2
                && state.project().project.maxY == 9,
            "Project Apply after rejection failed");
        var applied = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())), "Project one-step Undo");
        state.document.redo();
        state.revision++;
        require(
            applied.equals(ProjectIO.JSON.valueToTree(state.project())), "Project one-step Redo");
        project.open(null);
        projectVisible = true;
        width = 1400;
        height = 1200;
        projectType("seed", "17");
        require(state.change(p -> p.project.seed = 3), "Stale source fixture");
        width = 360;
        height = 640;
        frames(5);
        click("apply");
        require(
            state.project().project.seed == 3 && project.text("seed").equals("17"),
            "Stale project settings were applied");
        click("reload");
        frames(4);
        require(project.text("seed").equals("3"), "Explicit persistent reload failed");
        click("cancel");
        require(
            !projectVisible
                && state.draft == privateDraft
                && privateDraft.id.equals("private")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Cancel/settings consumed Properties input");
        state.document.save(out.resolve("general-settings-project.json"));
        Files.writeString(
            out.resolve("general-settings-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("ordinarySaveExcludedProjectDraft", true),
                    Map.entry("ordinarySaveRetry", true),
                    Map.entry("projectRejectionRetry", true),
                    Map.entry("projectUndoRedo", true),
                    Map.entry("staleReloadCancel", true),
                    Map.entry("privatePropertiesPreserved", true),
                    Map.entry("runtimeOwnerPreserved", true),
                    Map.entry("captureStarts", starts),
                    Map.entry("profilerOpens", opens),
                    Map.entry("retryCallbacks", retries),
                    Map.entry("limitCallbacks", limits),
                    Map.entry("diagnosticSections", DebugSettingsPanel.SECTIONS),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "GENERAL_SETTINGS_LAYOUT_NATIVE_PASS persistent actions / private project / live"
                + " preferences / shared capture");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void save() {
    try {
      state.user.save(file);
      saves++;
    } catch (Exception failure) {
      saveErrors++;
    }
  }

  private static Map<String, String> text() {
    var values = new LinkedHashMap<String, String>();
    ProjectSettingsSession.FIELDS.forEach(field -> values.put(field, project.text(field)));
    return values;
  }

  private static SettingsPageChrome chrome() {
    return switch (mode) {
      case "project" -> project.layout;
      case "editor" -> live.editorLayout;
      case "visualization" -> live.visualizationLayout;
      default -> debug.layout;
    };
  }

  private static void show(String form) {
    mode = form;
    frames(6);
  }

  private static void projectType(String field, String value) {
    mode = "project";
    require(
        project.open(
            new EditorEvents.Target(
                state.project().project.uuid, null, null, field, "/project/" + field, true)),
        "Project field route");
    projectVisible = true;
    for (int i = 0; i < 16; i++) {
      frame();
      if (field.equals(project.focusedField())) break;
    }
    require(field.equals(project.focusedField()), "Project field did not acknowledge focus");
    frames(3);
    type(value);
  }

  private static void choose(String key, String option) {
    live.probeReveal(key);
    frames(5);
    click(key);
    frames(3);
    click(key + ":" + option);
  }

  private static void type(String value) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(value);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void click(String key) {
    if (mode.equals("project")) {
      click(project.controlAt(key));
      return;
    }
    if (!mode.equals("debug")) {
      click(live.probeControl(key));
      return;
    }
    var rect = debug.layout.actions().get(key);
    require(rect != null, "Missing debug action " + key);
    click(new float[] {rect.x() + rect.width() / 2, rect.y() + rect.height() / 2});
  }

  private static void click(float[] point) {
    require(point != null, "Missing control in " + mode);
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
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
    state.drain();
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    String title =
        mode.equals("project")
            ? "Project Settings"
            : mode.equals("debug") ? "Debug & Profiling" : "User Settings";
    category = null;
    if (!mode.equals("project") || projectVisible) {
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.begin(title, ImGuiWindowFlags.NoSavedSettings);
      UiScale.window(state.user, title);
      window =
          new DialogBounds.Rect(
              ImGui.getWindowPosX(),
              ImGui.getWindowPosY(),
              ImGui.getWindowSizeX(),
              ImGui.getWindowSizeY());
      switch (mode) {
        case "project" -> {
          if (project.draw()) projectVisible = false;
        }
        case "debug" -> {
          debug.draw();
          category = debug.selector();
        }
        default -> {
          AppearanceSettingsPanel.category(
              fields, mode.equals("editor") ? "Editor" : "Visualization", ignored -> {});
          category = PopupLayout.itemBounds();
          if (mode.equals("editor")) live.drawEditor();
          else live.drawVisualization();
        }
      }
      ImGui.end();
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
