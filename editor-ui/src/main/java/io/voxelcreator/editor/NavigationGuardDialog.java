package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import java.util.*;
import java.util.function.Function;

/** Shared bounded draft decision; caller retains the exact navigation or transaction owner. */
final class NavigationGuardDialog {
  final DialogLayout layout;
  final SettingsPageChrome chrome = new SettingsPageChrome(3);
  private final Map<SearchNavigation.Decision, DialogBounds.Rect> buttons =
      new EnumMap<>(SearchNavigation.Decision.class);
  private String message = "";
  private long requestVersion;
  private boolean revealBody, revealMessage;

  NavigationGuardDialog(EditorState state) {
    this(state, "Properties");
  }

  NavigationGuardDialog(EditorState state, String owner) {
    this(state, owner, 600, 360);
  }

  NavigationGuardDialog(EditorState state, String owner, float width, float height) {
    layout = new DialogLayout(state, owner, width, height);
  }

  void open(String title) {
    message = "";
    requestVersion++;
    revealBody = true;
    revealMessage = false;
    layout.open(title);
  }

  void close(String title) {
    buttons.clear();
    message = "";
    if (layout.begin(title)) {
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
    }
  }

  void draw(
      String title,
      String explanation,
      Function<SearchNavigation.Decision, SearchNavigation.Result> decide) {
    draw(
        title,
        () -> ImGui.textWrapped(explanation),
        decide,
        "Apply failed. Correct the draft or choose Discard/Cancel.");
  }

  void draw(
      String title,
      Runnable explanation,
      Function<SearchNavigation.Decision, SearchNavigation.Result> decide,
      String rejected) {
    buttons.clear();
    if (!layout.begin(title)) return;
    chrome.begin("navigation-explanation");
    if (revealBody) {
      ImGui.setScrollY(0);
      revealBody = false;
    }
    explanation.run();
    if (!message.isEmpty()) {
      ImGui.textWrapped(StatusBarText.clip(message, 4096));
      if (revealMessage) {
        ImGui.setScrollHereY(.75f);
        revealMessage = false;
      }
    }
    chrome.end();
    ImGui.pushID("draft-decision:" + requestVersion);
    SearchNavigation.Decision clicked = null;
    for (var decision : SearchNavigation.Decision.values()) {
      if (chrome.identifiedAction(decision.name(), decision.name())) clicked = decision;
      buttons.put(decision, PopupLayout.itemBounds());
    }
    ImGui.popID();
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) clicked = SearchNavigation.Decision.CANCEL;
    if (clicked != null) {
      var result = decide.apply(clicked);
      if (result == SearchNavigation.Result.NEEDS_DECISION) {
        message = Objects.requireNonNull(rejected);
        revealMessage = true;
      } else {
        message = "";
        ImGui.closeCurrentPopup();
      }
    }
    ImGui.endPopup();
  }

  float[] button(String key) {
    var box = buttons.get(SearchNavigation.Decision.valueOf(key));
    return box == null ? null : new float[] {box.x() + box.width() / 2, box.y() + box.height() / 2};
  }

  Map<SearchNavigation.Decision, DialogBounds.Rect> buttons() {
    return Map.copyOf(buttons);
  }

  String message() {
    return message;
  }

  long requestVersion() {
    return requestVersion;
  }
}
