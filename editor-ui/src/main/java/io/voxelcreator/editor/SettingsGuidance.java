package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiHoveredFlags;
import java.util.function.Supplier;

/** UI attachment only; raw input and action publication remain with each original form. */
final class SettingsGuidance {
  private final Supplier<AppMetadata> metadata;
  private final ContextHelp help;

  SettingsGuidance(EditorState state) {
    if (state == null) {
      var defaults = AppMetadata.defaults();
      metadata = () -> defaults;
      help = null;
    } else {
      metadata = () -> state.metadata;
      help = new ContextHelp(state.helpRequests);
    }
  }

  ContextHelp contextHelp() {
    return help;
  }

  void integer(
      String topic, int nativeId, String raw, long minimum, long maximum, boolean current) {
    if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled))
      show(
          SettingsControlMetadata.integerTooltip(
              metadata.get(), topic, raw, minimum, maximum, current));
    attach(topic, nativeId);
  }

  void item(String topic, int nativeId, String detail) {
    if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled))
      show(SettingsControlMetadata.tooltip(metadata.get(), topic, detail));
    attach(topic, nativeId);
  }

  private void show(String text) {
    UiScale.tooltip(help == null ? text.replace("\nRight-click for Help.", "") : text);
  }

  private void attach(String topic, int nativeId) {
    if (help != null) help.item(topic, NumericGuidance.identity(topic, nativeId), true);
  }
}
