package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Deferred real Input Settings reflow/recording workflow; font magnification is synthetic. */
public final class InputLayoutProbe {
  private static EditorState state;
  private static InputSettingsPanel panel;
  private static final UiFields fields = new UiFields();
  private static int width = 1400, height = 1000, saves;
  private static float fontGlobal = 1, fontWindow = 1;
  private static DialogBounds.Rect window, category;
  private static boolean suppressed;
  private static String command;

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
                  n.id = "existing";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var file = out.resolve("user-config.json");
        state.user.save(file);
        panel = new InputSettingsPanel(state.user);
        frames(5);
        require(
            panel.submittedRows() > 0 && panel.submittedRows() < panel.totalRows(),
            "Unfiltered rows not clipped");
        var unfiltered =
            Map.of(
                "plan",
                panel.rowLayout(),
                "list",
                panel.listBounds(),
                "submitted",
                panel.submittedRows(),
                "total",
                panel.totalRows(),
                "steps",
                panel.rowSteps(),
                "strides",
                panel.rowStrides());
        type("filter", "Find");
        type("text:Find", "Ctrl+");
        var raw = panel.draftValues();
        require(!panel.applyEnabled(), "Invalid chord enabled Apply");
        click(panel.controlAt("save-preferences"));
        require(
            saves == 1
                && UserConfig.read(file).input.shortcuts.get("Find").equals("Ctrl+F")
                && panel.draftValues().equals(raw),
            "Ordinary save published private bindings");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1000}, {300, 640}, {260, 640}, {260, 640}, {720, 640}, {1000, 300}};
        float[] globals = {1, 1, 1.5f, 2.5f, 2.5f, 1};
        float[] multipliers = {1, 1, 1, 1, 2.5f, 1};
        Integer findId = null;
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          fontGlobal = globals[i];
          fontWindow = multipliers[i];
          frames(5);
          click(panel.controlAt("show-problem"));
          frames(7);
          var actions = panel.layout.actions();
          require(
              actions
                  .keySet()
                  .equals(Set.of("apply", "revert", "reset", "show-problem", "cancel-recording")),
              "Missing persistent Input action");
          require(
              DialogBounds.contains(window, category)
                  && DialogBounds.contains(window, panel.layout.body()),
              "Category/body outside client");
          for (var bounds : actions.values())
            require(DialogBounds.contains(window, bounds), "Footer outside client");
          require(
              panel.draftValues().equals(raw) && !panel.applyEnabled(),
              "Reflow/reveal changed raw bindings");
          var id = panel.fieldIds().get("Find");
          require(
              id != null && (findId == null || findId.equals(id)), "Find input identity changed");
          findId = id;
          long queries = panel.queries(), validations = panel.validations();
          frames(4);
          require(
              queries == panel.queries() && validations == panel.validations(),
              "Unchanged rows/feedback rebuilt");
          var row = new LinkedHashMap<String, DialogBounds.Rect>();
          for (String key : List.of("text:Find", "record:Find", "unbind:Find", "default:Find")) {
            var bounds = panel.controlBounds().get(key);
            require(
                bounds != null
                    && bounds.x() >= panel.listBounds().x() - .1
                    && bounds.x() + bounds.width()
                        <= panel.listBounds().x() + panel.listBounds().width() + .1,
                "Shortcut row needs horizontal scrolling");
            row.put(key, bounds);
          }
          for (var entry : panel.rowSteps().entrySet())
            require(
                entry.getValue() <= panel.rowLayout().rowHeight() + .1
                    && Math.abs(
                            panel.rowStrides().get(entry.getKey()) - panel.rowLayout().rowHeight())
                        < .1,
                "Native row exceeds its clipped stride");
          var receipt = new LinkedHashMap<String, Object>();
          receipt.put("scenario", i);
          receipt.put("window", window);
          receipt.put("category", category);
          receipt.put("body", panel.layout.body());
          receipt.put("actions", panel.layout.actions());
          receipt.put("list", panel.listBounds());
          receipt.put("plan", panel.rowLayout());
          receipt.put("row", row);
          receipt.put("steps", panel.rowSteps());
          receipt.put("strides", panel.rowStrides());
          receipt.put("findId", findId);
          receipt.put("raw", panel.draftValues());
          receipt.put("fontGlobalScale", fontGlobal);
          receipt.put("fontWindowScale", fontWindow);
          cases.add(receipt);
        }
        width = 1400;
        height = 1000;
        fontGlobal = fontWindow = 1;
        frames(6);
        click(panel.controlAt("show-problem"));
        frames(6);
        click(panel.controlAt("record:Find"));
        frames(2);
        require("Find".equals(panel.recordingAction()), "Record did not start");
        width = 720;
        height = 640;
        fontGlobal = fontWindow = 2.5f;
        frames(5);
        require("Find".equals(panel.recordingAction()), "Reflow replaced capture ownership");
        cancelWithSaveKey();
        require(
            panel.recordingAction() == null
                && suppressed
                && command == null
                && panel.draftValues().equals(raw),
            "Cancel captured/dispatched the same-frame Save key");
        key(ImGuiKey.ModCtrl, ImGuiKey.S, false);
        frames(3);
        require(!panel.recording(), "Cancel suppression did not expire");
        width = 1400;
        height = 1000;
        fontGlobal = fontWindow = 1;
        frames(6);
        click(panel.controlAt("show-problem"));
        frames(6);
        click(panel.controlAt("record:Find"));
        frames(2);
        require("Find".equals(panel.recordingAction()), "Second Record did not start");
        ImGui.getIO().addKeyEvent(ImGuiKey.ModCtrl, true);
        key(ImGuiKey.ModAlt, ImGuiKey.F8, true);
        frame();
        require(
            panel.recordingAction() == null
                && suppressed
                && command == null
                && panel.draftValue("Find").equals("Ctrl+Alt+F8"),
            "Captured chord was lost or dispatched");
        ImGui.getIO().addKeyEvent(ImGuiKey.ModCtrl, false);
        key(ImGuiKey.ModAlt, ImGuiKey.F8, false);
        frames(3);
        require(panel.applyEnabled(), "Captured valid chord did not enable Apply");
        var active = state.user.input.shortcuts;
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "original");
        click(panel.controlAt("apply"));
        width = 300;
        frames(5);
        require(
            state.user.input.shortcuts == active
                && panel.draftValue("Find").equals("Ctrl+Alt+F8")
                && Files.readString(file.resolve("keep")).equals("original"),
            "Failed save/reflow lost private bindings");
        state.user.workspace.width = 1234;
        state.user.appearance.theme = "Light";
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click(panel.controlAt("apply"));
        frames(3);
        var saved = UserConfig.read(file);
        require(
            saved.input.shortcuts.get("Find").equals("Ctrl+Alt+F8")
                && saved.input.shortcuts.get("Save").equals("Ctrl+S")
                && saved.workspace.width == 1234
                && saved.appearance.theme.equals("Light"),
            "Footer retry lost bindings/unrelated preferences");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.draft == draft
                && draft.id.equals("private")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Input modified project/private Properties");
        Files.writeString(
            out.resolve("input-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic"),
                    Map.entry("cases", cases),
                    Map.entry("unfiltered", unfiltered),
                    Map.entry("ordinarySaveExcludedDraft", true),
                    Map.entry("cancelBeforePoll", true),
                    Map.entry("captureSuppressed", true),
                    Map.entry("writeRetry", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "INPUT_LAYOUT_NATIVE_PASS responsive clipped rows, persistent actions, raw reveal,"
                + " cancel-before-poll, captured-key suppression and write retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void cancelWithSaveKey() {
    var at = panel.controlAt("cancel-recording");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    key(ImGuiKey.ModCtrl, ImGuiKey.S, true);
    frame();
  }

  private static void key(int modifier, int key, boolean down) {
    ImGui.getIO().addKeyEvent(modifier, down);
    ImGui.getIO().addKeyEvent(key, down);
  }

  private static void type(String control, String text) {
    click(panel.controlAt(control));
    key(ImGuiKey.ModCtrl, ImGuiKey.A, true);
    frame();
    key(ImGuiKey.ModCtrl, ImGuiKey.A, false);
    frame();
    ImGui.getIO().addInputCharactersUTF8(text);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Enter, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void click(float[] point) {
    require(point != null, "Missing Input target");
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
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(fontGlobal);
    ImGui.newFrame();
    panel.beginFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("User Settings", ImGuiWindowFlags.NoSavedSettings);
    ImGui.setWindowFontScale(fontWindow);
    window =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    AppearanceSettingsPanel.category(fields, "Input", ignored -> {});
    category = PopupLayout.itemBounds();
    panel.draw(
        () -> {
          try {
            state.user.save(Path.of(System.getProperty("voxel.configDir"), "user-config.json"));
            state.user.save(
                Path.of(System.getProperty("voxel.configDir"), "ordinary-preferences.json"));
            saves++;
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        });
    ImGui.end();
    suppressed = panel.recording();
    command =
        ShortcutKeys.match(state.user.input.shortcuts, Shortcuts.Focus.GENERAL, suppressed)
            .map(Shortcuts.Action::id)
            .orElse(null);
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
