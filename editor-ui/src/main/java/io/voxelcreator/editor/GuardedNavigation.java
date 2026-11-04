package io.voxelcreator.editor;

import imgui.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Draft guard for source links; rechecks freshness before decisions and after successful Apply. */
public final class GuardedNavigation implements AutoCloseable {
  private final PropertyPanel properties;
  private final EditorState state;
  private final Function<EditorEvents.Target, Boolean> revealer;
  private final SearchNavigation navigation;
  final NavigationGuardDialog dialog;
  private static final BooleanSupplier ALWAYS_CURRENT = () -> true;
  private BooleanSupplier fresh = ALWAYS_CURRENT;
  private boolean prompt, focus;
  private final boolean background;
  private boolean deciding;
  private boolean closed;

  public GuardedNavigation(EditorState state, PropertyPanel properties) {
    this(state, properties, properties::revealSearch);
  }

  GuardedNavigation(
      EditorState state,
      PropertyPanel properties,
      Function<EditorEvents.Target, Boolean> revealer) {
    this(state, properties, revealer, null, null);
  }

  static GuardedNavigation background(EditorState state, PropertyPanel properties) {
    return new GuardedNavigation(
        state, properties, properties::revealSearch, state.background, state.uiQueue::offer);
  }

  GuardedNavigation(
      EditorState state,
      PropertyPanel properties,
      Function<EditorEvents.Target, Boolean> revealer,
      Executor executor,
      Consumer<Runnable> ui) {
    this.state = state;
    this.properties = properties;
    this.revealer = java.util.Objects.requireNonNull(revealer);
    background = executor != null;
    navigation =
        executor == null
            ? new SearchNavigation(state)
            : new SearchNavigation(
                state,
                executor,
                ui,
                value -> completed(value.target(), value.result(), value.applied()));
    dialog = new NavigationGuardDialog(state);
  }

  public SearchNavigation.Result request(EditorEvents.Target target) {
    return request(target, ALWAYS_CURRENT);
  }

  public SearchNavigation.Result request(EditorEvents.Target target, BooleanSupplier fresh) {
    if (closed || navigation.pending() || properties.searchBlocked())
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
    } catch (RuntimeException | Error failure) {
      if (!background && failure instanceof Error error) throw error;
      if (requested && navigation.pending() && navigation.version() == owner + 1)
        navigation.clear();
      idle();
      report("SOURCE_NAVIGATION_FAILED", failure, false);
      return SearchNavigation.Result.UNAVAILABLE;
    }
  }

  public SearchNavigation.Result decide(SearchNavigation.Decision decision) {
    if (background && deciding && decision != SearchNavigation.Decision.CANCEL)
      return SearchNavigation.Result.NEEDS_DECISION;
    if (!navigation.pending()) return SearchNavigation.Result.UNAVAILABLE;
    var target = navigation.target();
    var check = fresh;
    long owner = navigation.version();
    boolean[] applied = {false};
    deciding = true;
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
              background
                  ? () -> check.getAsBoolean() && !properties.searchBlocked()
                  : ALWAYS_CURRENT,
              () -> {
                applied[0] = true;
                return check.getAsBoolean() && !properties.searchBlocked();
              });
      completed(target, result, applied[0]);
      return result;
    } catch (RuntimeException | Error failure) {
      if (!background && failure instanceof Error error) throw error;
      if (navigation.pending() && navigation.version() == owner) navigation.clear();
      idle();
      report("SOURCE_NAVIGATION_FAILED", failure, applied[0]);
      return SearchNavigation.Result.UNAVAILABLE;
    } finally {
      deciding = false;
    }
  }

  private void completed(
      EditorEvents.Target target, SearchNavigation.Result result, boolean applied) {
    idle();
    if (result == SearchNavigation.Result.NAVIGATED) reveal(target);
    else if (applied && !navigation.pending())
      state.status = "Draft applied. Source changed; request a new source link.";
    else if (background && result == SearchNavigation.Result.UNAVAILABLE && !navigation.pending())
      state.status =
          "Source link became stale. The private draft is preserved; request a current link.";
  }

  boolean preparing() {
    return navigation.preparing();
  }

  String progress() {
    return navigation.progress();
  }

  @Override
  public void close() {
    closed = true;
    navigation.close();
    prompt = focus = false;
    fresh = ALWAYS_CURRENT;
  }

  private void idle() {
    if (!navigation.pending()) {
      prompt = false;
      fresh = ALWAYS_CURRENT;
    }
  }

  private void report(String code, Throwable failure, boolean applied) {
    String text =
        StatusBarText.clip(
            (applied ? "Draft applied; source navigation failed: " : "")
                + BackgroundFailure.describe(failure),
            1024);
    try {
      state.error(code, EditorEvents.Subsystem.APPLICATION, text);
    } catch (RuntimeException | Error rejected) {
      if (!background) throw rejected;
    }
  }

  private void reveal(EditorEvents.Target target) {
    try {
      focus = Boolean.TRUE.equals(revealer.apply(target)) && !navigation.pending();
    } catch (RuntimeException | Error failure) {
      if (!background && failure instanceof Error error) throw error;
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
        navigation,
        this::decide);
  }
}
