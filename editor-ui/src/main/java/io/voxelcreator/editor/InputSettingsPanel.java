package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import java.nio.file.Path;
import java.util.*;

/** Keyboard changes remain private until Apply writes and publishes a valid preference snapshot. */
public final class InputSettingsPanel {
  private final UserConfig user;
  private ShortcutEditSession session;
  private final ShortcutCapture capture = new ShortcutCapture();
  private final Map<String, ImString> texts = new LinkedHashMap<>();
  private final ImString filter = new ImString(256);
  private String message = "";
  private boolean drawn, consumed;

  public InputSettingsPanel(UserConfig user) {
    this.user = user;
  }

  private void ensure() {
    if (session == null) {
      session = new ShortcutEditSession(user.input.shortcuts);
      sync();
    }
  }

  private void sync() {
    texts.clear();
    session.values().forEach((key, value) -> texts.put(key, new ImString(value, 128)));
  }

  public boolean recording() {
    return capture.active() || consumed;
  }

  public void cancelCapture() {
    capture.cancel();
    consumed = false;
  }

  public void draw() {
    ensure();
    drawn = true;
    consumed = capture.active();
    ImGui.textWrapped(
        "Edit a chord or Record it. Modifiers: Ctrl, Shift, Alt, Super; letters, digits, F1-F12 and"
            + " named keys. Empty means unbound. Escape cancels recording.");
    ImGui.textDisabled(
        "Text editing, popups, active widgets and free-fly keep their own keyboard input.");
    ImGui.inputTextWithHint("##shortcut-filter", "Filter commands or contexts...", filter);
    var problems = session.problems();
    String query = filter.get().strip().toLowerCase(Locale.ROOT);
    var rows =
        Shortcuts.actions().stream()
            .filter(
                a ->
                    (a.label() + " " + a.id() + " " + a.scope())
                        .toLowerCase(Locale.ROOT)
                        .contains(query))
            .toList();
    ImGui.beginChild("shortcut-rows", 0, Math.max(160, ImGui.getContentRegionAvailY() - 160), true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var action = rows.get(i);
          ImGui.pushID(action.id());
          ImGui.text(action.label() + " / " + action.scope());
          ImGui.setNextItemWidth(230);
          if (ImGui.inputText("##chord", texts.get(action.id())))
            session.edit(action.id(), texts.get(action.id()).get());
          ImGui.sameLine();
          if (ImGui.smallButton("Record")) capture.start(action.id());
          ImGui.sameLine();
          if (ImGui.smallButton("Unbind")) {
            session.unbind(action.id());
            texts.get(action.id()).set("");
          }
          ImGui.sameLine();
          if (ImGui.smallButton("Default")) {
            session.reset(action.id());
            texts.get(action.id()).set(session.values().get(action.id()));
          }
          var issue = problems.stream().filter(p -> p.action().equals(action.id())).findFirst();
          if (issue.isPresent()) ImGui.textColored(1, .6f, .3f, 1, "! " + issue.get().message());
          else ImGui.textDisabled("Default: " + action.defaultBinding());
          ImGui.popID();
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    if (capture.active()) {
      String target = capture.action();
      ImGui.textColored(
          .6f,
          .85f,
          1,
          1,
          "Recording "
              + Shortcuts.action(target).label()
              + " / release held keys, then press the chord.");
      var value =
          capture.poll(
              ShortcutKeys.held(),
              ImGui.isKeyPressed(ImGuiKey.Escape, false),
              ShortcutKeys.pressed());
      value.ifPresent(
          chord -> {
            session.edit(target, chord);
            texts.get(target).set(chord);
          });
      if (ImGui.smallButton("Cancel Recording")) capture.cancel();
    }
    ImGui.text(
        (session.changed() ? "Unsaved binding draft" : "Bindings match active preferences")
            + " / "
            + problems.size()
            + " validation issues");
    if (!problems.isEmpty())
      ImGui.textWrapped("! " + problems.getFirst().action() + ": " + problems.getFirst().message());
    ImGui.beginDisabled(!problems.isEmpty() || recording());
    if (ImGui.button("Apply Bindings")) apply(UserConfig.directory().resolve("user-config.json"));
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Revert Bindings")) {
      capture.cancel();
      session.revert(user.input.shortcuts);
      sync();
      message = "Draft reverted";
    }
    ImGui.sameLine();
    if (ImGui.button("Reset Binding Defaults")) {
      capture.cancel();
      session.resetAll();
      sync();
      message = "Defaults staged; Apply publishes them";
    }
    ImGui.textWrapped(message);
    ImGui.textDisabled("Save Preferences saves active bindings; it does not apply this draft.");
  }

  private void apply(Path path) {
    try {
      session.apply(user, path);
      sync();
      message = "Bindings saved and activated";
    } catch (Exception e) {
      message = "! " + e.getMessage();
    }
  }

  /** Explicit native-smoke draft; normal startup never creates example remaps. */
  public void smokeDraft() {
    ensure();
    session.edit("Find", "Ctrl+S");
    texts.get("Find").set("Ctrl+S");
    if (session.problems().isEmpty() || !user.input.shortcuts.get("Find").equals("Ctrl+F"))
      throw new IllegalStateException("Shortcut draft published a conflict");
  }

  public void assertSmokeDrawn() {
    if (!drawn || session == null || session.problems().isEmpty())
      throw new IllegalStateException("Input draft/conflict controls not rendered");
    capture.cancel();
    session.revert(user.input.shortcuts);
    sync();
  }
}
