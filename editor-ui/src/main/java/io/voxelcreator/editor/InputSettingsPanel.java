package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import java.nio.file.Path;
import java.util.*;

/** Keyboard changes remain private until Apply writes and publishes a valid preference snapshot. */
public final class InputSettingsPanel {
  private final UserConfig user;
  private ShortcutEditSession session;
  private final ShortcutRecording capture;
  private final Map<String, ImString> texts = new LinkedHashMap<>();
  private final ImString filter = new ImString(256);
  private String message = "";
  private boolean drawn, applyEnabled;
  private String previousQuery;
  private List<Shortcuts.Action> rows = List.of();
  private final Map<String, float[]> controls = new HashMap<>();
  private long queries;
  private int submitted;
  private Float scroll;

  public InputSettingsPanel(UserConfig user) {
    this.user = Objects.requireNonNull(user);
    capture = new ShortcutRecording(user);
  }

  private void ensure() {
    if (session == null) {
      session = new ShortcutEditSession(user.input.shortcuts);
      sync();
    }
  }

  private void sync() {
    texts.clear();
    session
        .values()
        .forEach(
            (key, value) ->
                texts.put(
                    key, new ImString(value, 4 * ShortcutEditSession.MAX_BUFFER_CHARACTERS + 1)));
  }

  public void beginFrame() {
    capture.beginFrame();
  }

  public boolean recording() {
    return capture.suppressed();
  }

  public void cancelCapture() {
    capture.cancel();
  }

  public void draw() {
    ensure();
    controls.clear();
    submitted = 0;
    drawn = true;
    ImGui.textWrapped(
        "Edit a chord or Record it. Modifiers: Ctrl, Shift, Alt, Super; letters, digits, F1-F12 and"
            + " named keys. Empty means unbound. Escape cancels recording.");
    ImGui.textDisabled(
        "Text editing, popups, active widgets and free-fly keep their own keyboard input.");
    ImGui.inputTextWithHint("##shortcut-filter", "Filter commands or contexts...", filter);
    control("filter");
    boolean editingText = ImGui.isItemActive();
    if (editingText) capture.cancel();
    var problems = session.problems();
    boolean current = session.current(user);
    if (!current)
      ImGui.textWrapped(
          "Active bindings changed. Your private draft is preserved; Revert Bindings before"
              + " recording or applying.");
    String query = filter.get().strip().toLowerCase(Locale.ROOT);
    if (!query.equals(previousQuery)) {
      rows =
          Shortcuts.actions().stream()
              .filter(
                  a ->
                      (a.label() + " " + a.id() + " " + a.scope())
                          .toLowerCase(Locale.ROOT)
                          .contains(query))
              .toList();
      previousQuery = query;
      queries++;
    }
    UiScale.beginChild(
        "shortcut-rows",
        0,
        Math.max(160, ImGui.getContentRegionAvailY() - 210),
        true,
        ImGuiWindowFlags.HorizontalScrollbar);
    if (scroll != null) {
      ImGui.setScrollY(scroll);
      scroll = null;
    }
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(
          rows.size(),
          2 * ImGui.getTextLineHeightWithSpacing() + ImGui.getFrameHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var action = rows.get(i);
          submitted++;
          ImGui.pushID(action.id());
          ImGui.text(action.label() + " / " + action.scope());
          ImGui.setNextItemWidth(230);
          if (ImGui.inputText("##chord", texts.get(action.id()))) {
            capture.cancel();
            try {
              session.edit(action.id(), texts.get(action.id()).get());
              message = "";
            } catch (RuntimeException invalid) {
              message = detail(invalid);
            }
          }
          if (ImGui.isItemActive()) {
            editingText = true;
            capture.cancel();
          }
          control("text:" + action.id());
          ImGui.sameLine();
          ImGui.beginDisabled(!current || capture.active());
          if (ImGui.smallButton("Record")) capture.start(session, action.id());
          control("record:" + action.id());
          ImGui.endDisabled();
          ImGui.sameLine();
          if (ImGui.smallButton("Unbind")) {
            capture.cancel();
            session.unbind(action.id());
            texts.get(action.id()).set("");
          }
          control("unbind:" + action.id());
          ImGui.sameLine();
          if (ImGui.smallButton("Default")) {
            capture.cancel();
            session.reset(action.id());
            texts.get(action.id()).set(session.values().get(action.id()));
          }
          control("default:" + action.id());
          var issue = problems.stream().filter(p -> p.action().equals(action.id())).findFirst();
          if (issue.isPresent()) ThemeStyle.text("Warning", "! " + issue.get().message());
          else ImGui.textDisabled("Default: " + action.defaultBinding());
          ImGui.popID();
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    if (capture.active()) {
      String target = capture.action();
      ThemeStyle.text(
          "Information",
          "Recording "
              + Shortcuts.action(target).label()
              + " / release held keys, then press the chord.");
      boolean cancelled = ImGui.smallButton("Cancel Recording");
      control("cancel-recording");
      if (cancelled) capture.cancel();
      var value =
          cancelled
              ? Optional.<ShortcutRecording.Captured>empty()
              : capture.poll(
                  session,
                  ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)
                      && !ImGui.getIO().getAppFocusLost()
                      && !ShortcutKeys.popup()
                      && !editingText,
                  ShortcutKeys.held(),
                  ImGui.isKeyPressed(ImGuiKey.Escape, false),
                  ShortcutKeys.pressed());
      value.ifPresent(
          captured -> {
            texts.get(captured.action()).set(captured.chord());
            message = "";
          });
    }
    problems = session.problems();
    String displayedProblem = "";
    try {
      session.requireDisplayed(displayed());
    } catch (RuntimeException invalid) {
      displayedProblem = detail(invalid);
    }
    ImGui.text(
        (session.changed() ? "Unsaved binding draft" : "Bindings match active preferences")
            + " / "
            + problems.size()
            + " validation issues");
    if (!problems.isEmpty())
      ImGui.textWrapped("! " + problems.getFirst().action() + ": " + problems.getFirst().message());
    if (!displayedProblem.isEmpty()) ImGui.textWrapped("! " + bounded(displayedProblem));
    if (!problems.isEmpty()) {
      if (ImGui.smallButton("Show First Problem")) {
        capture.cancel();
        filter.set(problems.getFirst().action());
        scroll = 0f;
      }
      control("show-problem");
    }
    applyEnabled =
        problems.isEmpty() && displayedProblem.isEmpty() && !recording() && session.current(user);
    ImGui.beginDisabled(!applyEnabled);
    if (ImGui.button("Apply Bindings")) apply(UserConfig.directory().resolve("user-config.json"));
    control("apply");
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Revert Bindings")) {
      capture.cancel();
      try {
        session.revert(user.input.shortcuts);
        sync();
        message = "Draft reverted";
      } catch (RuntimeException invalid) {
        message = "! " + detail(invalid);
      }
    }
    control("revert");
    ImGui.sameLine();
    if (ImGui.button("Reset Binding Defaults")) {
      capture.cancel();
      session.resetAll();
      sync();
      message = "Defaults staged; Apply publishes them";
    }
    control("reset");
    ImGui.textWrapped(bounded(message));
    ImGui.textDisabled("Save Preferences saves active bindings; it does not apply this draft.");
  }

  private void apply(Path path) {
    try {
      session.apply(user, path, displayed());
      sync();
      message = "Bindings saved and activated";
    } catch (Exception e) {
      message = "! " + detail(e);
    }
  }

  private Map<String, String> displayed() {
    var displayed = new LinkedHashMap<String, String>();
    texts.forEach((id, value) -> displayed.put(id, value.get()));
    return displayed;
  }

  private static String detail(Exception error) {
    var value = error.getMessage();
    return value == null || value.isBlank() ? error.toString() : value;
  }

  private static String bounded(String value) {
    return value.length() > 1024 ? value.substring(0, 1023) + "…" : value;
  }

  private void control(String key) {
    controls.put(
        key,
        new float[] {
          (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
          (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
        });
  }

  float[] controlAt(String key) {
    return controls.get(key);
  }

  String recordingAction() {
    return capture.action();
  }

  String draftValue(String key) {
    return texts.get(key).get();
  }

  boolean applyEnabled() {
    return applyEnabled;
  }

  long queries() {
    return queries;
  }

  long validations() {
    return session.validations();
  }

  int submittedRows() {
    return submitted;
  }

  /** Explicit native-smoke draft; normal startup never creates example remaps. */
  public void smokeDraft() {
    ensure();
    session.edit("Find", "Ctrl+S");
    texts.get("Find").set("Ctrl+S");
    if (session.problems().isEmpty() || !user.input.shortcuts.get("Find").equals("Ctrl+F"))
      throw new IllegalStateException("Shortcut draft published a conflict");
  }

  public void assertSmokeDrawn() {
    if (!drawn || session == null || session.problems().isEmpty())
      throw new IllegalStateException("Input draft/conflict controls not rendered");
    capture.cancel();
    session.revert(user.input.shortcuts);
    sync();
  }
}
