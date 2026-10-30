package io.voxelcreator.editor;

import imgui.ImGui;
import java.util.*;
import java.util.function.Consumer;

/** Production Appearance composition; pending scales and active ordinary saves remain distinct. */
final class AppearanceSettingsPanel {
  private final UserConfig user;
  private final UiScalePanel scales;
  private final Runnable themeChanged, openThemes, savePreferences;
  private final UiFields fields = new UiFields();
  private final Map<String, float[]> controls = new HashMap<>();

  AppearanceSettingsPanel(
      UserConfig user,
      UiScalePanel scales,
      Runnable themeChanged,
      Runnable openThemes,
      Runnable savePreferences) {
    this.user = Objects.requireNonNull(user);
    this.scales = Objects.requireNonNull(scales);
    this.themeChanged = Objects.requireNonNull(themeChanged);
    this.openThemes = Objects.requireNonNull(openThemes);
    this.savePreferences = Objects.requireNonNull(savePreferences);
  }

  static void category(UiFields fields, String current, Consumer<String> change) {
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice(
        "##settings-category",
        current,
        List.of("Appearance", "Workspace", "Input", "Editor", "Visualization", "Performance"),
        change);
    fields.tooltip("Choose a settings category");
  }

  void draw() {
    controls.clear();
    scales.draw(
        () -> {
          ImGui.textWrapped("Theme");
          ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
          fields.choice(
              "##appearance-theme",
              user.appearance.theme,
              List.of("Dark", "Light"),
              value -> {
                user.appearance.theme = value;
                themeChanged.run();
              });
          control("theme");
        },
        () -> {
          if (DialogLayout.button("Open Themes")) openThemes.run();
          control("themes");
          DialogLayout.sameLine("Save Preferences");
          if (DialogLayout.button("Save Preferences")) savePreferences.run();
          control("save");
        });
  }

  private void control(String key) {
    var bounds = PopupLayout.itemBounds();
    controls.put(
        key, new float[] {bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2});
  }

  float[] controlAt(String key) {
    return controls.get(key);
  }
}
