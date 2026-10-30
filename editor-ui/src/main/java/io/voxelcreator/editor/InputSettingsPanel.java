package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import java.nio.file.Path;
import java.util.*;

/** Keyboard changes remain private until Apply writes and publishes a valid preference snapshot. */
public final class InputSettingsPanel {
  private final UserConfig user;
  final SettingsPageChrome layout = new SettingsPageChrome(5);
  private ShortcutRowLayout.Plan rowLayout;
  private DialogBounds.Rect listBounds;
  private final Map<String, Integer> fieldIds = new LinkedHashMap<>();
  private final Map<String, Float> rowSteps = new LinkedHashMap<>();
  private final Map<String, Float> rowStrides = new LinkedHashMap<>();
  private final Map<String, DialogBounds.Rect> controlBounds = new LinkedHashMap<>();
  private String reveal;
  private boolean revealList;
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
    draw(null);
  }

  void draw(Runnable savePreferences) {
    ensure();
    controls.clear();
    controlBounds.clear();
    fieldIds.clear();
    rowSteps.clear();
    rowStrides.clear();
    submitted = 0;
    drawn = true;
    layout.begin("input-settings-body");
    ImGui.textWrapped(
        "Edit a chord or Record it. Modifiers: Ctrl, Shift, Alt, Super; letters, digits, F1-F12 and"
            + " named keys. Empty means unbound. Escape cancels recording.");
    ImGui.textWrapped(
        "Text editing, popups, active widgets and free-fly keep their own keyboard input.");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
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
        ShortcutRowLayout.viewport(
            ImGui.getContentRegionAvailY(),
            layout.body().height(),
            ImGui.getFrameHeightWithSpacing()),
        true);
    listBounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    rowLayout =
        ShortcutRowLayout.prepare(
            ImGui.getContentRegionAvailX(),
            ImGui.calcTextSize("Ctrl+Shift+F12").x + 2 * ImGui.getStyle().getFramePaddingX(),
            Math.max(
                    ImGui.calcTextSize("Record").x,
                    Math.max(ImGui.calcTextSize("Unbind").x, ImGui.calcTextSize("Default").x))
                + 2 * ImGui.getStyle().getFramePaddingX(),
            ImGui.getTextLineHeightWithSpacing(),
            ImGui.getFrameHeightWithSpacing(),
            ImGui.getStyle().getItemSpacingX());
    if (scroll != null) {
      ImGui.setScrollY(scroll);
      scroll = null;
    }
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size(), rowLayout.rowHeight());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var action = rows.get(i);
          submitted++;
          float top = ImGui.getCursorPosY();
          ImGui.pushID(action.id());
          rowText(action.label() + " / " + action.scope(), false);
          ImGui.setNextItemWidth(rowLayout.inputWidth());
          fieldIds.put(action.id(), ImGui.getID("##chord"));
          boolean focus = action.id().equals(reveal);
          if (focus) ImGui.setKeyboardFocusHere();
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
          if (focus) {
            ImGui.setScrollHereY(.5f);
            reveal = null;
          }
          actionPosition(0);
          ImGui.beginDisabled(!current || capture.active());
          if (rowButton("Record")) capture.start(session, action.id());
          control("record:" + action.id());
          ImGui.endDisabled();
          actionPosition(1);
          if (rowButton("Unbind")) {
            capture.cancel();
            session.unbind(action.id());
            texts.get(action.id()).set("");
          }
          control("unbind:" + action.id());
          actionPosition(2);
          if (rowButton("Default")) {
            capture.cancel();
            session.reset(action.id());
            texts.get(action.id()).set(session.values().get(action.id()));
          }
          control("default:" + action.id());
          var issue = problems.stream().filter(p -> p.action().equals(action.id())).findFirst();
          rowText(
              issue.isPresent()
                  ? "! " + issue.get().message()
                  : "Default: " + action.defaultBinding(),
              issue.isPresent());
          ImGui.popID();
          rowSteps.put(action.id(), ImGui.getCursorPosY() - top);
          // A tail item reserves fractional native rounding without changing the clipped stride.
          ImGui.setCursorPosY(top + rowLayout.rowHeight() - ImGui.getStyle().getItemSpacingY());
          ImGui.dummy(0, 0);
          rowStrides.put(action.id(), ImGui.getCursorPosY() - top);
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    if (revealList) {
      ImGui.setScrollHereY(.5f);
      revealList = false;
    }
    if (capture.active())
      ImGui.textWrapped(
          "Recording "
              + Shortcuts.action(capture.action()).label()
              + " / release held keys, then press the chord.");
    problems = session.problems();
    var shown = displayed();
    String displayedProblem = "";
    try {
      session.requireDisplayed(shown);
    } catch (RuntimeException invalid) {
      displayedProblem = detail(invalid);
    }
    ImGui.textWrapped(
        (session.changed() ? "Unsaved binding draft" : "Bindings match active preferences")
            + " / "
            + problems.size()
            + " validation issues");
    if (!problems.isEmpty())
      ImGui.textWrapped("! " + problems.getFirst().action() + ": " + problems.getFirst().message());
    if (!displayedProblem.isEmpty()) ImGui.textWrapped("! " + bounded(displayedProblem));
    String firstProblem = ShortcutProblemNavigation.first(session.values(), shown, problems);
    if (!message.isEmpty()) ImGui.textWrapped(bounded(message));
    ImGui.textWrapped("Save Preferences saves active bindings; it does not apply this draft.");
    if (savePreferences != null) {
      if (DialogLayout.button("Save Preferences")) {
        capture.cancel();
        savePreferences.run();
      }
      control("save-preferences");
    }
    layout.end();
    applyEnabled =
        problems.isEmpty() && displayedProblem.isEmpty() && !recording() && session.current(user);
    ImGui.beginDisabled(!applyEnabled);
    if (layout.action("apply", "Apply Bindings"))
      apply(UserConfig.directory().resolve("user-config.json"));
    control("apply");
    ImGui.endDisabled();
    if (layout.action("revert", "Revert Bindings")) {
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
    if (layout.action("reset", "Reset Binding Defaults")) {
      capture.cancel();
      session.resetAll();
      sync();
      message = "Defaults staged; Apply publishes them";
    }
    control("reset");
    ImGui.beginDisabled(firstProblem == null);
    if (layout.action("show-problem", "Show First Problem")) {
      capture.cancel();
      filter.set(firstProblem);
      scroll = 0f;
      reveal = firstProblem;
      revealList = true;
    }
    control("show-problem");
    ImGui.endDisabled();
    ImGui.beginDisabled(!capture.active());
    if (layout.action("cancel-recording", "Cancel Recording")) capture.cancel();
    control("cancel-recording");
    ImGui.endDisabled();
    // Footer cancellation must precede capture polling; suppression remains owned for this frame.
    pollCapture(editingText);
  }

  private void actionPosition(int index) {
    if (rowLayout.inline() || index % rowLayout.actionColumns() != 0) ImGui.sameLine();
  }

  private boolean rowButton(String label) {
    boolean clicked =
        ImGui.button(
            label,
            Math.min(rowLayout.buttonWidth(), Math.max(1, ImGui.getContentRegionAvailX())),
            0);
    PopupLayout.clippedLabel(label);
    return clicked;
  }

  private void rowText(String full, boolean warning) {
    String preview = StatusBarText.clip(full, 160);
    if (warning) ThemeStyle.text("Warning", preview);
    else ImGui.textDisabled(preview);
    if (ImGui.isItemHovered()) UiScale.tooltip(full);
  }

  private void pollCapture(boolean editingText) {
    if (!capture.active()) return;
    capture
        .poll(
            session,
            ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)
                && !ImGui.getIO().getAppFocusLost()
                && !ShortcutKeys.popup()
                && !editingText,
            ShortcutKeys.held(),
            ImGui.isKeyPressed(ImGuiKey.Escape, false),
            ShortcutKeys.pressed())
        .ifPresent(
            value -> {
              texts.get(value.action()).set(value.chord());
              message = "";
            });
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
    controlBounds.put(key, PopupLayout.itemBounds());
    controls.put(
        key,
        new float[] {
          (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
          (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
        });
  }

  ShortcutRowLayout.Plan rowLayout() {
    return rowLayout;
  }

  DialogBounds.Rect listBounds() {
    return listBounds;
  }

  Map<String, Integer> fieldIds() {
    return Map.copyOf(fieldIds);
  }

  Map<String, Float> rowSteps() {
    return Map.copyOf(rowSteps);
  }

  Map<String, Float> rowStrides() {
    return Map.copyOf(rowStrides);
  }

  Map<String, DialogBounds.Rect> controlBounds() {
    return Map.copyOf(controlBounds);
  }

  Map<String, String> draftValues() {
    return Map.copyOf(displayed());
  }

  int totalRows() {
    return rows.size();
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
