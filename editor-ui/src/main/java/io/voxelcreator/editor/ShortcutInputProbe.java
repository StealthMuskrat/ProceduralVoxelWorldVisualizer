package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Explicit isolated native key-event/reference probe; never loads or edits normal user preferences.
 */
public final class ShortcutInputProbe {
  private static ImGuiIO io;
  private static final ImString text = new ImString("clipboard text", 128);

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Pass the isolated probe output directory");
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toAbsolutePath().toString());
    ImGui.createContext();
    try {
      io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(800, 600);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      var user = new UserConfig();
      press(Shortcuts.CTRL, "S");
      frame();
      expect(user, Shortcuts.Focus.GENERAL, "Save");
      end();
      frame();
      expect(user, Shortcuts.Focus.GENERAL, null);
      end();
      release(Shortcuts.CTRL, "S");
      idle();
      press(Shortcuts.CTRL | Shortcuts.SHIFT, "S");
      frame();
      expect(user, Shortcuts.Focus.GENERAL, "SaveAs");
      end();
      release(3, "S");
      idle();
      press(Shortcuts.CTRL, "C");
      frame();
      expect(user, Shortcuts.Focus.GENERAL, null);
      expect(user, Shortcuts.Focus.RESOURCES, "Copy");
      end();
      release(1, "C");
      idle();
      press(Shortcuts.CTRL, "Z");
      frame();
      expect(user, Shortcuts.Focus.CURVE, "CurveUndo");
      expect(user, Shortcuts.Focus.GENERAL, "Undo");
      expect(user, Shortcuts.Focus.FLYING, null);
      end();
      release(1, "Z");
      idle();
      // A real active text widget suppresses resource/global commands, leaving text editing native.
      frame();
      ImGui.setKeyboardFocusHere();
      ImGui.inputText("Edit text", text);
      end();
      frame();
      ImGui.inputText("Edit text", text);
      end();
      press(Shortcuts.CTRL, "S");
      frame();
      ImGui.inputText("Edit text", text);
      if (!ImGui.isAnyItemActive() && !io.getWantTextInput())
        throw new AssertionError("Text-input fixture was not active");
      expect(user, Shortcuts.Focus.RESOURCES, null);
      end();
      release(1, "S");
      idle();
      // Clear the text widget focus, then check a real modal popup.
      frame();
      ImGui.setWindowFocus((String) null);
      end();
      idle();
      press(Shortcuts.CTRL, "S");
      frame();
      ImGui.openPopup("Guard");
      if (!ImGui.beginPopupModal("Guard")) throw new AssertionError("Modal fixture failed");
      if (!ShortcutKeys.popup()) throw new AssertionError("Native popup was not observed");
      expect(user, Shortcuts.Focus.GENERAL, null);
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
      end();
      release(1, "S");
      idle();
      var capture = new ShortcutCapture();
      press(1, "M");
      frame();
      capture.start("Profiler");
      if (capture.poll(ShortcutKeys.held(), false, ShortcutKeys.pressed()).isPresent())
        throw new AssertionError("Capture consumed held launch keys");
      end();
      release(1, "M");
      frame();
      capture.poll(ShortcutKeys.held(), false, ShortcutKeys.pressed());
      end();
      press(Shortcuts.ALT | Shortcuts.SHIFT | Shortcuts.SUPER, "F9");
      frame();
      String chord = capture.poll(ShortcutKeys.held(), false, ShortcutKeys.pressed()).orElseThrow();
      end();
      release(14, "F9");
      idle();
      if (!chord.equals("Shift+Alt+Super+F9"))
        throw new AssertionError("Native captured modifiers: " + chord);
      try (var state = new EditorState(user, false)) {
        var noise = new Noise();
        noise.id = "probe";
        state.change(p -> p.noise2D.add(noise));
        state.select("noise2D", noise.uuid);
        String before = ProjectIO.JSON.valueToTree(state.project()).toString();
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var session = new ShortcutEditSession(user.input.shortcuts);
        session.edit("Profiler", chord);
        session.apply(user, output.resolve("user-config.json"));
        var restored = UserConfig.read(output.resolve("user-config.json"));
        if (!restored.input.shortcuts.equals(user.input.shortcuts))
          throw new AssertionError("Restarted binding map changed");
        press(14, "F9");
        frame();
        expect(restored, Shortcuts.Focus.GENERAL, "Profiler");
        end();
        release(14, "F9");
        idle();
        if (!before.equals(ProjectIO.JSON.valueToTree(state.project()).toString())
            || revision != state.revision
            || history != state.document.historyBytes()
            || dirty != state.document.dirty())
          throw new AssertionError("Preference apply changed project state");
        session.edit("Find", restored.input.shortcuts.get("Save"));
        try {
          session.apply(user, output.resolve("user-config.json"));
          throw new AssertionError("Conflicting remap published");
        } catch (IllegalArgumentException expected) {
        }
        if (!UserConfig.read(output.resolve("user-config.json"))
            .input
            .shortcuts
            .equals(restored.input.shortcuts))
          throw new AssertionError("Conflict changed saved preferences");
      }
      System.out.println(
          "SHORTCUT_INPUT_NATIVE_PASS exact modifiers / no repeats / context routing / active text"
              + " / modal exclusion / release-before-record / atomic apply / restart / conflict"
              + " rejection / project invariance");
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void frame() {
    io.setDeltaTime(1f / 60);
    ImGui.newFrame();
    ImGui.begin("Keyboard probe");
  }

  private static void end() {
    ImGui.end();
    ImGui.render();
  }

  private static void idle() {
    frame();
    end();
  }

  private static void expect(UserConfig user, Shortcuts.Focus focus, String expected) {
    String result =
        ShortcutKeys.match(user.input.shortcuts, focus, false)
            .map(Shortcuts.Action::id)
            .orElse(null);
    if (!Objects.equals(expected, result))
      throw new AssertionError(
          "Expected "
              + expected
              + " in "
              + focus
              + ", received "
              + result
              + " with modifiers "
              + ShortcutKeys.modifiers());
  }

  private static void press(int modifiers, String key) {
    mods(modifiers, true);
    io.addKeyEvent(ShortcutKeys.code(key), true);
  }

  private static void release(int modifiers, String key) {
    io.addKeyEvent(ShortcutKeys.code(key), false);
    mods(modifiers, false);
  }

  private static void mods(int value, boolean down) {
    int[] keys = {ImGuiKey.ModCtrl, ImGuiKey.ModShift, ImGuiKey.ModAlt, ImGuiKey.ModSuper};
    for (int i = 0; i < 4; i++) if ((value & (1 << i)) != 0) io.addKeyEvent(keys[i], down);
  }
}
