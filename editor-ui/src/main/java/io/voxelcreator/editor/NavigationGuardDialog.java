package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/** Shared bounded source-navigation presentation; the caller retains transaction ownership. */
final class NavigationGuardDialog {
  final DialogLayout layout;
  private final Map<SearchNavigation.Decision, DialogBounds.Rect> buttons =
      new EnumMap<>(SearchNavigation.Decision.class);
  private String message = "";

  NavigationGuardDialog(EditorState state) {
    layout = new DialogLayout(state, "Properties", 600, 360);
  }

  void open(String title) {
    message = "";
    layout.open(title);
  }

  void draw(
      String title,
      String explanation,
      Function<SearchNavigation.Decision, SearchNavigation.Result> decide) {
    buttons.clear();
    if (!layout.begin(title)) return;
    float body = Math.max(1, ImGui.getContentRegionAvailY() - ImGui.getFrameHeightWithSpacing());
    UiScale.beginChild("navigation-explanation", 0, body, false);
    ImGui.textWrapped(explanation);
    if (!message.isEmpty()) ImGui.textWrapped(message);
    ImGui.endChild();

    // One persistent row keeps all three actions reachable while the explanation scrolls.
    float spacing = ImGui.getStyle().getItemSpacingX();
    float width = Math.max(1, (ImGui.getContentRegionAvailX() - 2 * spacing) / 3);
    SearchNavigation.Decision clicked = null;
    for (var decision : SearchNavigation.Decision.values()) {
      if (decision != SearchNavigation.Decision.APPLY) ImGui.sameLine();
      if (ImGui.button(
          decision.name(), Math.min(width, Math.max(1, ImGui.getContentRegionAvailX())), 0))
        clicked = decision;
      buttons.put(decision, PopupLayout.itemBounds());
      PopupLayout.clippedLabel(decision.name());
    }
    if (clicked == null && ImGui.isKeyPressed(ImGuiKey.Escape, false))
      clicked = SearchNavigation.Decision.CANCEL;
    if (clicked != null) {
      var result = decide.apply(clicked);
      if (result == SearchNavigation.Result.NEEDS_DECISION)
        message = "Apply failed. Correct the draft or choose Discard/Cancel.";
      else {
        message = "";
        ImGui.closeCurrentPopup();
      }
    }
    ImGui.endPopup();
  }

  float[] button(String key) {
    var bounds = buttons.get(SearchNavigation.Decision.valueOf(key));
    return bounds == null
        ? null
        : new float[] {bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2};
  }

  Map<SearchNavigation.Decision, DialogBounds.Rect> buttons() {
    return Map.copyOf(buttons);
  }

  String message() {
    return message;
  }
}
