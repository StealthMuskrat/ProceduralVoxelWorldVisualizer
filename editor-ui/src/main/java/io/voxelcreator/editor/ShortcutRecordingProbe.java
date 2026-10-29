package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred production Input-panel key/mouse recording, focus cancellation and write-retry scenario.
 */
public final class ShortcutRecordingProbe {
  private static InputSettingsPanel panel;
  private static UserConfig user;
  private static boolean visible = true, suppressed;
  private static String focus, command;

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1300, 1000);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      user = new UserConfig();
      Path file = out.resolve("user-config.json");
      user.save(file);
      var originalFile = Files.readAllBytes(file);
      try (var state = new EditorState(user, false)) {
        require(
            state.change(
                p -> {
                  var noise = new Noise();
                  noise.id = "existing";
                  noise.algorithm.key = "CONSTANT";
                  noise.algorithm.parameters.clear();
                  noise.algorithm.parameters.put("value", .125);
                  p.noise2D.add(noise);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        panel = new InputSettingsPanel(user);
        frames(4);
        long queries = panel.queries(), validations = panel.validations();
        int rows = panel.submittedRows();
        frames(5);
        require(
            panel.queries() == queries
                && panel.validations() == validations
                && rows > 0
                && rows < 20,
            "Unchanged binding rows/feedback rebuilt or escaped clipping");
        type("filter", "Find");
        start();
        key(ImGuiKey.ModCtrl, ImGuiKey.S, true);
        frame();
        require(
            panel.recordingAction() == null
                && suppressed
                && command == null
                && panel.draftValue("Find").equals("Ctrl+S")
                && user.input.shortcuts.get("Find").equals("Ctrl+F"),
            "Captured Save chord dispatched or published before Apply");
        key(ImGuiKey.ModCtrl, ImGuiKey.S, false);
        frames(2);
        type("text:Find", "Ctrl+Alt+F8");
        start();
        focus = "Other recorder window";
        key(ImGuiKey.ModCtrl, ImGuiKey.S, true);
        frame();
        require(
            panel.recordingAction() == null
                && suppressed
                && command == null
                && panel.draftValue("Find").equals("Ctrl+Alt+F8"),
            "Focus loss recorded another window's key");
        key(ImGuiKey.ModCtrl, ImGuiKey.S, false);
        frames(2);
        focus = "Input Recording";
        frame();
        key(ImGuiKey.ModCtrl, ImGuiKey.S, true);
        frame();
        require(!suppressed && "Save".equals(command), "Capture suppression did not expire");
        key(ImGuiKey.ModCtrl, ImGuiKey.S, false);
        frames(2);
        start();
        io.addFocusEvent(false);
        frame();
        require(panel.recordingAction() == null, "Application focus loss retained recording");
        io.addFocusEvent(true);
        frames(3);
        start();
        click("text:Find");
        require(panel.recordingAction() == null, "Typing did not cancel recording");
        type("text:Find", "Ctrl+");
        type("filter", "Save");
        require(!panel.applyEnabled(), "Hidden invalid chord enabled Apply");
        click("apply");
        require(
            Arrays.equals(originalFile, Files.readAllBytes(file)),
            "Hidden invalid chord changed preferences");
        click("show-problem");
        frames(2);
        require(
            panel.draftValue("Find").equals("Ctrl+"), "Problem navigation discarded raw binding");
        type("text:Find", "Ctrl+Alt+F8");
        start();
        var originalBindings = new LinkedHashMap<>(user.input.shortcuts);
        user.input.shortcuts.put("Help", "F6");
        frame();
        require(
            panel.recordingAction() == null && !panel.applyEnabled(),
            "Changed active bindings retained recording");
        user.input.shortcuts = new LinkedHashMap<>(originalBindings);
        frames(3);
        require(
            panel.draftValue("Find").equals("Ctrl+Alt+F8"),
            "Active-map guard replaced private text");
        start();
        visible = false;
        frame();
        require(
            suppressed && panel.recordingAction() == null,
            "Hidden panel lost cancel-frame suppression");
        frame();
        require(!suppressed, "Hidden panel left shortcut dispatch suppressed");
        visible = true;
        focus = "Input Recording";
        frames(3);
        user.workspace.width = 1234;
        user.appearance.theme = "Light";
        var active = user.input.shortcuts;
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "original");
        click("apply");
        frames(2);
        require(
            user.input.shortcuts == active
                && panel.draftValue("Find").equals("Ctrl+Alt+F8")
                && Files.readString(file.resolve("keep")).equals("original"),
            "Failed write changed active/private bindings");
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click("apply");
        frames(2);
        var saved = UserConfig.read(file);
        require(
            saved.input.shortcuts.get("Find").equals("Ctrl+Alt+F8")
                && saved.input.shortcuts.get("Save").equals("Ctrl+S")
                && saved.workspace.width == 1234
                && saved.appearance.theme.equals("Light"),
            "Retry lost canonical binding or latest unrelated preferences");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && state.draft == draft
                && draft.id.equals("private")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Recording/preferences changed project/history/private input");
        Files.writeString(
            out.resolve("shortcut-recording-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("completionSuppressed", true),
                    Map.entry("focusCancelled", true),
                    Map.entry("appFocusCancelled", true),
                    Map.entry("textEditCancelled", true),
                    Map.entry("activeChangeCancelled", true),
                    Map.entry("hideCancelled", true),
                    Map.entry("hiddenDraftPreserved", true),
                    Map.entry("writeRetry", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("cachedRows", true),
                    Map.entry("submittedRows", rows),
                    Map.entry("find", "Ctrl+Alt+F8"),
                    Map.entry("theme", "Light"),
                    Map.entry("width", 1234))));
        System.out.println(
            "SHORTCUT_RECORDING_NATIVE_PASS owned capture / focus / text / hidden errors /"
                + " suppression / write retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void start() {
    focus = "Input Recording";
    frame();
    click("record:Find");
    frames(2);
    require("Find".equals(panel.recordingAction()), "Record button did not own Find");
  }

  private static void key(int modifier, int key, boolean down) {
    ImGui.getIO().addKeyEvent(modifier, down);
    ImGui.getIO().addKeyEvent(key, down);
  }

  private static void type(String control, String value) {
    click(control);
    key(ImGuiKey.ModCtrl, ImGuiKey.A, true);
    frame();
    key(ImGuiKey.ModCtrl, ImGuiKey.A, false);
    frame();
    ImGui.getIO().addInputCharactersUTF8(value);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Enter, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void click(String name) {
    var at = panel.controlAt(name);
    require(at != null, "Missing Input control: " + name);
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
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
    ImGui.newFrame();
    panel.beginFrame();
    if (focus != null) {
      ImGui.setWindowFocus(focus);
      focus = null;
    }
    if (visible) {
      ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
      ImGui.setNextWindowSize(900, 800, ImGuiCond.Always);
      if (ImGui.begin("Input Recording")) panel.draw();
      else panel.cancelCapture();
      ImGui.end();
    } else panel.cancelCapture();
    ImGui.setNextWindowPos(930, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(300, 180, ImGuiCond.Always);
    ImGui.begin("Other recorder window", ImGuiWindowFlags.NoFocusOnAppearing);
    ImGui.text("Separate focus fixture");
    ImGui.end();
    suppressed = panel.recording();
    command =
        ShortcutKeys.match(user.input.shortcuts, Shortcuts.Focus.GENERAL, suppressed)
            .map(Shortcuts.Action::id)
            .orElse(null);
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
