package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One draft guard for trace/log/profiler links; freshness is rechecked before every decision. */
public final class GuardedNavigation {
  private final EditorState state;
  private final PropertyPanel properties;
  private final SearchNavigation navigation;
  private BooleanSupplier fresh = () -> true;
  private boolean prompt, focus;
  private String message = "";
  private final Map<String, float[]> buttons = new HashMap<>();

  public GuardedNavigation(EditorState state, PropertyPanel properties) {
    this.state = state;
    this.properties = properties;
    navigation = new SearchNavigation(state);
  }

  public SearchNavigation.Result request(EditorEvents.Target target) {
    return request(target, () -> true);
  }

  public SearchNavigation.Result request(EditorEvents.Target target, BooleanSupplier fresh) {
    if (properties.searchBlocked() || !fresh.getAsBoolean())
      return SearchNavigation.Result.UNAVAILABLE;
    this.fresh = fresh;
    var result = navigation.request(target);
    if (result == SearchNavigation.Result.NEEDS_DECISION) prompt = true;
    else if (result == SearchNavigation.Result.NAVIGATED) reveal(target);
    return result;
  }

  public SearchNavigation.Result decide(SearchNavigation.Decision decision) {
    var target = navigation.target();
    if (decision != SearchNavigation.Decision.CANCEL && !fresh.getAsBoolean()) {
      navigation.clear();
      return SearchNavigation.Result.UNAVAILABLE;
    }
    var result = navigation.decide(decision);
    if (result == SearchNavigation.Result.NAVIGATED) reveal(target);
    return result;
  }

  private void reveal(EditorEvents.Target target) {
    focus = properties.revealSearch(target);
  }

  public boolean pending() {
    return navigation.pending();
  }

  float[] button(String key) {
    return buttons.get(key);
  }

  public void draw() {
    if (focus) {
      ImGui.setWindowFocus(properties.navigationWindow());
      focus = false;
    }
    if (prompt) {
      ImGui.openPopup("Leave Property Draft for Source Link");
      prompt = false;
    }
    if (!ImGui.beginPopupModal(
        "Leave Property Draft for Source Link", ImGuiWindowFlags.AlwaysAutoResize)) return;
    ImGui.textWrapped(
        "Apply the current property draft, discard it for this source link, or cancel.");
    if (!message.isEmpty()) ImGui.textWrapped(message);
    for (var decision : SearchNavigation.Decision.values()) {
      if (decision != SearchNavigation.Decision.APPLY) ImGui.sameLine();
      boolean clicked = ImGui.button(decision.name());
      var a = ImGui.getItemRectMin();
      var b = ImGui.getItemRectMax();
      buttons.put(decision.name(), new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
      if (clicked) {
        var result = decide(decision);
        if (result == SearchNavigation.Result.NEEDS_DECISION)
          message = "Apply failed. Correct the draft or choose Discard/Cancel.";
        else {
          message =
              result == SearchNavigation.Result.UNAVAILABLE
                  ? "Source changed; request the link again."
                  : "";
          ImGui.closeCurrentPopup();
        }
      }
    }
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      decide(SearchNavigation.Decision.CANCEL);
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
  }
}
