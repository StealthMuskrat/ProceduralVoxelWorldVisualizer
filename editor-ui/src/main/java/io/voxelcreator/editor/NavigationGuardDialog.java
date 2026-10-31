package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import java.util.*;
import java.util.function.Function;

/**
 * Shared draft decision; delivery follows popup unwind, while callers retain transaction authority.
 */
final class NavigationGuardDialog {
  final DialogLayout layout;
  final SettingsPageChrome chrome = new SettingsPageChrome(3);
  private final Map<SearchNavigation.Decision, DialogBounds.Rect> buttons =
      new EnumMap<>(SearchNavigation.Decision.class);
  private String message = "";
  private final NavigationDecisionScope decisions = new NavigationDecisionScope();
  private int drawnFrame = Integer.MIN_VALUE;
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
    buttons.clear();
    message = "";
    decisions.open();
    revealBody = true;
    revealMessage = false;
    layout.open(title);
  }

  void close(String title) {
    decisions.close();
    buttons.clear();
    message = "";
    if (layout.begin(title)) {
      try {
        ImGui.closeCurrentPopup();
      } finally {
        ImGui.endPopup();
      }
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
    int frame = ImGui.getFrameCount();
    if (drawnFrame == frame) return;
    drawnFrame = frame;
    buttons.clear();
    if (!decisions.active() || !layout.begin(title)) return;
    var ticket = decisions.capture(frame);
    SearchNavigation.Decision clicked = null;
    try {
      chrome.begin("navigation-explanation");
      try {
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
      } finally {
        chrome.end();
      }
      ImGui.pushID("draft-decision:" + ticket.version());
      try {
        for (var decision : SearchNavigation.Decision.values()) {
          if (chrome.identifiedAction(decision.name(), decision.name())) clicked = decision;
          buttons.put(decision, PopupLayout.itemBounds());
        }
      } finally {
        ImGui.popID();
      }
      if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) clicked = SearchNavigation.Decision.CANCEL;
    } finally {
      ImGui.endPopup();
    }
    if (clicked != null && decisions.consume(ticket, ImGui.getFrameCount())) {
      var result = decide.apply(clicked);
      // A callback may open another request or close this dialog explicitly.
      if (!decisions.owns(ticket)) return;
      if (result == SearchNavigation.Result.NEEDS_DECISION) {
        message = Objects.requireNonNull(rejected);
        revealMessage = true;
      } else {
        close(title);
      }
    }
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
    return decisions.version();
  }
}
