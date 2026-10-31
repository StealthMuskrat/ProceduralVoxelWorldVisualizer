package io.voxelcreator.editor;

import imgui.*;
import java.util.function.BooleanSupplier;

/** One draft guard for trace/log/profiler links; freshness is rechecked before every decision. */
public final class GuardedNavigation {
  private final PropertyPanel properties;
  private final SearchNavigation navigation;
  final NavigationGuardDialog dialog;
  private BooleanSupplier fresh = () -> true;
  private boolean prompt, focus;

  public GuardedNavigation(EditorState state, PropertyPanel properties) {
    this.properties = properties;
    navigation = new SearchNavigation(state);
    dialog = new NavigationGuardDialog(state);
  }

  public SearchNavigation.Result request(EditorEvents.Target target) {
    return request(target, () -> true);
  }

  public SearchNavigation.Result request(EditorEvents.Target target, BooleanSupplier fresh) {
    if (navigation.pending() || properties.searchBlocked() || !fresh.getAsBoolean())
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
      prompt = false;
      return SearchNavigation.Result.UNAVAILABLE;
    }
    var result = navigation.decide(decision);
    if (result != SearchNavigation.Result.NEEDS_DECISION) prompt = false;
    if (result == SearchNavigation.Result.NAVIGATED) reveal(target);
    return result;
  }

  private void reveal(EditorEvents.Target target) {
    focus = properties.revealSearch(target);
  }

  public boolean pending() {
    return navigation.pending();
  }

  boolean blocked() {
    return properties.searchBlocked();
  }

  float[] button(String key) {
    return dialog.button(key);
  }

  public void draw() {
    if (focus) {
      ImGui.setWindowFocus(properties.navigationWindow());
      focus = false;
    }
    if (!navigation.pending()) {
      prompt = false;
      dialog.close("Leave Property Draft for Source Link");
      return;
    }
    if (prompt) {
      dialog.open("Leave Property Draft for Source Link");
      prompt = false;
    }
    dialog.draw(
        "Leave Property Draft for Source Link",
        "Apply the current property draft, discard it for this source link, or cancel.",
        this::decide);
  }
}
