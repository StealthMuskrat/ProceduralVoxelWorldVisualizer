package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import java.util.*;
import java.util.function.Consumer;

/** Scrollable runtime/license sections with persistent complete-copy and help actions. */
final class AboutPanel {
  final FloatingWindowLayout windowLayout = new FloatingWindowLayout(680, 500);
  final SettingsPageChrome chrome = new SettingsPageChrome(2);
  private final UserConfig user;
  private final EditorState state;
  private final AboutDetails details;
  private final Consumer<String> copy;
  private final Runnable help;
  private final UiFields fields = new UiFields();
  private String section = "Runtime";
  private String actionSection;
  private final SettingsActionScope actions = new SettingsActionScope();
  private final String actionId = UUID.randomUUID().toString();
  private final Map<String, float[]> controls = new HashMap<>();
  private DialogBounds.Rect selectorBounds;

  AboutPanel(EditorState state, AboutDetails details, Consumer<String> copy, Runnable help) {
    this.state = Objects.requireNonNull(state);
    user = state.user;
    this.details = Objects.requireNonNull(details);
    this.copy = Objects.requireNonNull(copy);
    this.help = Objects.requireNonNull(help);
  }

  void draw(ImBoolean open) {
    if (!open.get()) {
      actions.release();
      return;
    }
    windowLayout.prepare();
    boolean visible = ImGui.begin("About", open);
    UiScale.window(user, "About");
    windowLayout.observe();
    if (!visible || !open.get()) {
      actions.release();
      ImGui.end();
      return;
    }
    controls.clear();
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice(
        "##about-section", section, List.of("Runtime", "Licenses"), value -> section = value);
    selectorBounds = PopupLayout.itemBounds();
    chrome.begin("about-body");
    if (section.equals("Runtime"))
      details.runtime().forEach((key, value) -> ImGui.textWrapped(key + ": " + value));
    else {
      ImGui.textWrapped(AboutDetails.OWNERSHIP);
      ImGui.separator();
      ImGui.textWrapped(AboutDetails.NOTICES);
      for (var license : AboutDetails.LICENSES) {
        ImGui.separator();
        ImGui.textWrapped(license.component() + " / " + license.version());
        ImGui.textWrapped(license.terms() + " — " + license.scope());
      }
    }
    chrome.end();
    String shown = section;
    if (!shown.equals(actionSection)) actionSection = shown;
    long version = actions.synchronize(details, actionSection);
    ImGui.pushID("about-actions:" + actionId + ":" + version);
    boolean copied = chrome.identifiedAction("copy", "Copy");
    control("copy");
    boolean helped = chrome.identifiedAction("help", "Help");
    control("help");
    ImGui.popID();
    if (!ImGui.isKeyPressed(ImGuiKey.Escape, false)
        && shown.equals(section)
        && actions.current(details, actionSection)) {
      if (copied)
        external(
            "ABOUT_COPY_FAILED",
            () ->
                copy.accept(
                    shown.equals("Runtime") ? details.runtimeText() : details.licenseText()));
      else if (helped) external("ABOUT_HELP_FAILED", help);
    }
    ImGui.end();
  }

  private void external(String code, Runnable callback) {
    try {
      callback.run();
    } catch (RuntimeException failure) {
      String detail = failure.getMessage();
      if (detail == null || detail.isBlank()) detail = failure.getClass().getName();
      state.error(code, EditorEvents.Subsystem.APPLICATION, StatusBarText.clip(detail, 1024));
    }
  }

  String section() {
    return section;
  }

  private void control(String key) {
    var rect = PopupLayout.itemBounds();
    controls.put(key, new float[] {rect.x() + rect.width() / 2, rect.y() + rect.height() / 2});
  }

  void section(String value) {
    if (!List.of("Runtime", "Licenses").contains(value))
      throw new IllegalArgumentException("Unknown About section");
    section = value;
  }

  float[] controlAt(String key) {
    return controls.get(key);
  }

  DialogBounds.Rect selectorBounds() {
    return selectorBounds;
  }
}
