package io.voxelcreator.editor;

import imgui.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Draft guard for source links; rechecks freshness before decisions and after successful Apply. */
public final class GuardedNavigation {
  private final PropertyPanel properties;
  private final EditorState state;
  private final Function<EditorEvents.Target, Boolean> revealer;
  private final SearchNavigation navigation;
  final NavigationGuardDialog dialog;
  private static final BooleanSupplier ALWAYS_CURRENT = () -> true;
  private BooleanSupplier fresh = ALWAYS_CURRENT;
  private boolean prompt, focus;

  public GuardedNavigation(EditorState state, PropertyPanel properties) {
    this(state, properties, properties::revealSearch);
  }

  GuardedNavigation(
      EditorState state,
      PropertyPanel properties,
      Function<EditorEvents.Target, Boolean> revealer) {
    this.state = state;
    this.properties = properties;
    this.revealer = java.util.Objects.requireNonNull(revealer);
    navigation = new SearchNavigation(state);
    dialog = new NavigationGuardDialog(state);
  }

  public SearchNavigation.Result request(EditorEvents.Target target) {
    return request(target, ALWAYS_CURRENT);
  }

  public SearchNavigation.Result request(EditorEvents.Target target, BooleanSupplier fresh) {
    if (navigation.pending() || properties.searchBlocked())
      return SearchNavigation.Result.UNAVAILABLE;
    long owner = navigation.version();
    boolean requested = false;
    try {
      if (!fresh.getAsBoolean()
          || navigation.version() != owner
          || navigation.pending()
          || properties.searchBlocked()) return SearchNavigation.Result.UNAVAILABLE;
      this.fresh = fresh;
      requested = true;
      var result = navigation.request(target);
      if (result == SearchNavigation.Result.NEEDS_DECISION) prompt = true;
      else {
        idle();
        if (result == SearchNavigation.Result.NAVIGATED) reveal(target);
      }
      return result;
    } catch (RuntimeException failure) {
      if (requested && navigation.pending() && navigation.version() == owner + 1)
        navigation.clear();
      idle();
      report("SOURCE_NAVIGATION_FAILED", failure, false);
      return SearchNavigation.Result.UNAVAILABLE;
    }
  }

  public SearchNavigation.Result decide(SearchNavigation.Decision decision) {
    if (!navigation.pending()) return SearchNavigation.Result.UNAVAILABLE;
    var target = navigation.target();
    var check = fresh;
    long owner = navigation.version();
    boolean[] applied = {false};
    try {
      if (decision != SearchNavigation.Decision.CANCEL
          && (properties.searchBlocked()
              || !check.getAsBoolean()
              || navigation.version() != owner)) {
        if (navigation.version() == owner) navigation.clear();
        idle();
        return SearchNavigation.Result.UNAVAILABLE;
      }
      var result =
          navigation.decide(
              decision,
              () -> {
                applied[0] = true;
                return check.getAsBoolean() && !properties.searchBlocked();
              });
      idle();
      if (result == SearchNavigation.Result.NAVIGATED) reveal(target);
      else if (applied[0] && !navigation.pending())
        state.status = "Draft applied. Source changed; request a new source link.";
      return result;
    } catch (RuntimeException failure) {
      if (navigation.pending() && navigation.version() == owner) navigation.clear();
      idle();
      report("SOURCE_NAVIGATION_FAILED", failure, applied[0]);
      return SearchNavigation.Result.UNAVAILABLE;
    }
  }

  private void idle() {
    if (!navigation.pending()) {
      prompt = false;
      fresh = ALWAYS_CURRENT;
    }
  }

  private void report(String code, RuntimeException failure, boolean applied) {
    var detail = failure.getMessage();
    if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
    state.error(
        code,
        EditorEvents.Subsystem.APPLICATION,
        StatusBarText.clip(
            (applied ? "Draft applied; source navigation failed: " : "") + detail, 1024));
  }

  private void reveal(EditorEvents.Target target) {
    try {
      focus = Boolean.TRUE.equals(revealer.apply(target)) && !navigation.pending();
    } catch (RuntimeException failure) {
      focus = false;
      report("SOURCE_REVEAL_FAILED", failure, false);
    }
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
