package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.type.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Searchable private global/window scale controls; native application follows successful save. */
final class UiScalePanel {
  private final UserConfig user;
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
    ensure();
    if (probing) controls.clear();
    ImGui.textWrapped(
        "Your text factor uses global scale multiplied by the window multiplier. Display scaling is"
            + " applied automatically. Changes take effect after Apply UI Scales saves them.");
    control("blur");
    scale("Global UI scale", global, UiScaleEditSession.GLOBAL, raw -> session.editGlobal(raw));
    ImGui.inputTextWithHint("##scale-filter", "Filter window names...", filter);
    control("filter");
    UiScale.beginChild("scale-targets", 0, 150, true);
    String query = filter.get().toLowerCase(Locale.ROOT);
    for (var name : UiScalePreferences.WINDOWS)
      if (name.toLowerCase(Locale.ROOT).contains(query)) {
        if (ImGui.selectable(name + "###scale-target-" + name, name.equals(selected)))
          selected = name;
        control("target-" + name);
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
    ImGui.sameLine();
    ImGui.textWrapped("Use a window multiplier");
    ImGui.beginDisabled(!enabled.contains(selected));
    scale(
        "Window multiplier",
        windows.get(selected),
        selected,
        raw -> session.editWindow(selected, raw));
    ImGui.endDisabled();
    if (!enabled.contains(selected)) ImGui.textDisabled("This window inherits the global scale");
    var feedback = session.feedback();
    feedback.problems().forEach((name, problem) -> ImGui.textWrapped("! " + name + ": " + problem));
    String displayedProblem = "";
    try {
      session.requireDisplayed(displayed());
    } catch (RuntimeException mismatch) {
      displayedProblem = mismatch.getMessage();
    }
    if (!displayedProblem.isEmpty()) ImGui.textWrapped("! " + displayedProblem);
    boolean current = session.current(user);
    if (!current)
      ImGui.textWrapped(
          "Active scales changed. Revert reloads them; pending inputs have been retained.");
    applyEnabled =
        current && session.changed() && feedback.value() != null && displayedProblem.isEmpty();
    ImGui.beginDisabled(!applyEnabled);
    if (ImGui.button("Apply UI Scales"))
      act(
          () -> {
            session.apply(user, file, displayed());
            sync();
            message = "UI scales saved";
          });
    control("apply");
    ImGui.endDisabled();
    if (ImGui.button("Revert UI Scales"))
      act(
          () -> {
            session.revert(user);
            sync();
            message = "Active UI scales reloaded";
          });
    control("revert");
    if (session.changed())
      ImGui.textDisabled("Pending UI scales are excluded from Save Preferences");
    if (!message.isEmpty()) ImGui.textWrapped(message);
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
    float available = ImGui.getContentRegionAvailX();
    float sliderWidth = Math.max(30, available * .55f);
    ImGui.beginDisabled(!valid);
    ImGui.setNextItemWidth(sliderWidth);
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
    ImGui.sameLine();
    ImGui.setNextItemWidth(Math.max(30, available - sliderWidth - 12));
    if (ImGui.inputText("##scale-text-" + key, text))
      act(
          () -> {
            edit.accept(text.get());
            message = "";
          });
    control(key);
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

  long validations() {
    return session.validations();
  }
}
