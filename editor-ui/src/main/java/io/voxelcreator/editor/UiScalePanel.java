package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Searchable private global/window scale controls; native application follows successful save. */
final class UiScalePanel {
  private final UserConfig user;
  final PreferenceActionScope actionScope = new PreferenceActionScope();
  final SettingsPageChrome layout = new SettingsPageChrome(3);
  private DialogBounds.Rect targets;
  private float targetRowHeight;
  private final Map<String, Integer> controlIds = new HashMap<>();
  private final Map<String, SettingsPageLayout.Pair> rowPlans = new HashMap<>();
  private String reveal;
  private final Path file;
  private final Consumer<String> errors;
  private UiScaleEditSession session;
  private ImString global;
  private final ImString filter = new ImString(1024);
  private final Map<String, ImString> windows = new LinkedHashMap<>();
  private final Set<String> enabled = new HashSet<>();
  private final Map<String, float[]> controls = new HashMap<>();
  private String selected = "Resources", message = "";
  private boolean probing, applyEnabled;

  UiScalePanel(UserConfig user, Path file, Consumer<String> errors) {
    this.user = Objects.requireNonNull(user);
    this.file = Objects.requireNonNull(file);
    this.errors = Objects.requireNonNull(errors);
  }

  private void ensure() {
    if (session == null) {
      session = new UiScaleEditSession(user);
      sync();
    }
  }

  private void sync() {
    actionScope.resetDraft();
    global = new ImString(session.text().global(), 4 * UiScaleEditSession.MAX_TEXT + 1);
    session
        .text()
        .windows()
        .forEach(
            (name, raw) ->
                windows.put(name, new ImString(raw, 4 * UiScaleEditSession.MAX_TEXT + 1)));
    enabled.clear();
    enabled.addAll(session.text().enabled());
  }

  void draw() {
    draw(() -> {}, () -> {});
  }

  void draw(Runnable heading, Runnable trailing) {
    ensure();
    long request = actionScope.synchronize(activeValues());
    if (probing) {
      controls.clear();
      controlIds.clear();
      rowPlans.clear();
    }
    layout.begin("ui-scale-body");
    heading.run();
    ImGui.textWrapped(
        "Your text factor uses global scale multiplied by the window multiplier. Display scaling is"
            + " applied automatically. Changes take effect after Apply UI Scales saves them.");
    control("blur");
    scale("Global UI scale", global, UiScaleEditSession.GLOBAL, raw -> session.editGlobal(raw));
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    ImGui.inputTextWithHint("##scale-filter", "Filter window names...", filter);
    control("filter");
    UiScale.beginChild(
        "scale-targets",
        0,
        SettingsPageLayout.choices(
            ImGui.getContentRegionAvailY(), ImGui.getTextLineHeightWithSpacing()),
        true);
    targets =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    targetRowHeight = ImGui.getTextLineHeightWithSpacing();
    String query = filter.get().toLowerCase(Locale.ROOT);
    for (var name : UiScalePreferences.WINDOWS)
      if (name.toLowerCase(Locale.ROOT).contains(query)) {
        if (ImGui.selectable(name + "###scale-target-" + name, name.equals(selected)))
          selected = name;
        control("target-" + name);
        PopupLayout.clippedLabel(name);
      }
    ImGui.endChild();
    ImGui.textWrapped("Window: " + selected);
    var override = new ImBoolean(enabled.contains(selected));
    if (ImGui.checkbox("##scale-enabled", override)) {
      if (override.get()) enabled.add(selected);
      else enabled.remove(selected);
      act(
          () -> {
            session.override(selected, override.get());
            message = "";
          });
    }
    control("override");
    DialogLayout.sameLine("Use a window multiplier");
    ImGui.textWrapped("Use a window multiplier");
    ImGui.beginDisabled(!enabled.contains(selected));
    scale(
        "Window multiplier",
        windows.get(selected),
        selected,
        raw -> session.editWindow(selected, raw));
    ImGui.endDisabled();
    if (!enabled.contains(selected)) ImGui.textWrapped("This window inherits the global scale");
    var feedback = session.feedback();
    feedback.problems().forEach((name, problem) -> ImGui.textWrapped("! " + name + ": " + problem));
    String displayedProblem = "";
    var shown = displayed();
    try {
      session.requireDisplayed(shown);
    } catch (RuntimeException mismatch) {
      displayedProblem = Objects.requireNonNullElse(mismatch.getMessage(), mismatch.toString());
    }
    if (!displayedProblem.isEmpty()) ImGui.textWrapped("! " + displayedProblem);
    boolean current = session.current(user);
    if (!current)
      ImGui.textWrapped(
          "Active scales changed. Revert reloads them; pending inputs have been retained.");
    applyEnabled =
        current && session.changed() && feedback.value() != null && displayedProblem.isEmpty();
    String firstProblem =
        UiScaleProblemNavigation.first(session.text(), shown, feedback.problems());
    if (session.changed())
      ImGui.textWrapped("Pending UI scales are excluded from Save Preferences");
    if (!message.isEmpty()) ImGui.textWrapped(message);
    trailing.run();
    layout.end();
    ImGui.pushID("ui-scale-request:" + request);
    ImGui.beginDisabled(!applyEnabled);
    boolean apply = layout.identifiedAction("apply", "Apply UI Scales");
    control("apply");
    ImGui.endDisabled();
    boolean revert = layout.identifiedAction("revert", "Revert UI Scales");
    control("revert");
    ImGui.beginDisabled(firstProblem == null);
    boolean invalid = layout.identifiedAction("invalid", "Show Invalid Scale");
    control("invalid");
    ImGui.endDisabled();
    ImGui.popID();
    if (!actionScope.current(activeValues()) || ImGui.isKeyPressed(ImGuiKey.Escape, false)) return;
    if (revert)
      act(
          () -> {
            session.revert(user);
            sync();
            message = "Active UI scales reloaded";
          });
    else if (apply && applyEnabled)
      act(
          () -> {
            session.apply(user, file, displayed());
            sync();
            message = "UI scales saved";
          });
    else if (invalid && firstProblem != null) {
      if (!UiScaleEditSession.GLOBAL.equals(firstProblem)) {
        selected = firstProblem;
        filter.set(firstProblem);
      }
      reveal = firstProblem;
    }
  }

  private record ActiveValues(float global, Map<String, Float> overrides) {
    ActiveValues {
      overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
    }
  }

  private ActiveValues activeValues() {
    var owned = new LinkedHashMap<String, Float>();
    for (var name : UiScalePreferences.WINDOWS)
      if (user.appearance.windowScales.containsKey(name))
        owned.put(name, user.appearance.windowScales.get(name));
    return new ActiveValues(user.appearance.globalScale, owned);
  }

  void probeEditGlobal(String value) {
    ensure();
    session.editGlobal(value);
    global.set(value);
  }

  void probeEditWindow(String name, String value) {
    ensure();
    session.override(name, true);
    session.editWindow(name, value);
    enabled.add(name);
    windows.get(name).set(value);
  }

  void probeRevert() {
    ensure();
    session.revert(user);
    sync();
  }

  private void scale(String label, ImString text, String key, Consumer<String> edit) {
    float value = 1;
    boolean valid = true;
    try {
      value = UiScaleEditSession.parse(text.get());
    } catch (RuntimeException invalid) {
      valid = false;
    }
    ImGui.textWrapped(label);
    var plan = SettingsPageChrome.pair();
    if (probing) rowPlans.put(key, plan);
    ImGui.beginDisabled(!valid);
    ImGui.setNextItemWidth(plan.sliderWidth());
    if (probing) controlIds.put(key + "-slider", ImGui.getID("##scale-slider-" + key));
    float[] slider = {value};
    if (ImGui.sliderFloat("##scale-slider-" + key, slider, .5f, 2.5f, "%.2fx")) {
      String raw = Float.toString(slider[0]);
      text.set(raw);
      act(
          () -> {
            edit.accept(raw);
            message = "";
          });
    }
    control(key + "-slider");
    ImGui.endDisabled();
    if (plan.inline()) ImGui.sameLine();
    ImGui.setNextItemWidth(plan.inputWidth());
    if (probing) controlIds.put(key, ImGui.getID("##scale-text-" + key));
    boolean focus = key.equals(reveal);
    if (focus) ImGui.setKeyboardFocusHere();
    if (ImGui.inputText("##scale-text-" + key, text))
      act(
          () -> {
            edit.accept(text.get());
            message = "";
          });
    control(key);
    if (focus) {
      ImGui.setScrollHereY(.5f);
      reveal = null;
    }
  }

  private UiScaleEditSession.Text displayed() {
    var raw = new LinkedHashMap<String, String>();
    windows.forEach((name, value) -> raw.put(name, value.get()));
    return new UiScaleEditSession.Text(global.get(), raw, enabled);
  }

  private void act(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException failure) {
      String detail =
          Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName());
      if (failure.getCause() != null)
        detail +=
            ": "
                + Objects.requireNonNullElse(
                    failure.getCause().getMessage(), failure.getCause().getClass().getSimpleName());
      message = "! " + (detail.length() > 1024 ? detail.substring(0, 1023) + "…" : detail);
      errors.accept(message);
    }
  }

  private void control(String key) {
    if (probing)
      controls.put(
          key,
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          });
  }

  void probeControls() {
    probing = true;
  }

  float[] probeControl(String key) {
    return controls.get(key);
  }

  UiScaleEditSession.Text probeText() {
    return session.text();
  }

  boolean probeApplyEnabled() {
    return applyEnabled;
  }

  Map<String, Integer> probeIds() {
    return Map.copyOf(controlIds);
  }

  Map<String, SettingsPageLayout.Pair> probeRows() {
    return Map.copyOf(rowPlans);
  }

  DialogBounds.Rect targetBounds() {
    return targets;
  }

  float targetRowHeight() {
    return targetRowHeight;
  }

  String probeSelected() {
    return selected;
  }

  long validations() {
    return session.validations();
  }
}
