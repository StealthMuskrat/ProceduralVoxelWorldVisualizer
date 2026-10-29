package io.voxelcreator.editor;

import static io.voxelcreator.editor.PerformancePreferences.*;

import imgui.ImGui;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Production explicit performance preferences, including exact 64-bit cell-budget input. */
final class PerformancePanel {
  private final UserConfig user;
  private final Path file;
  private final Consumer<String> errors;
  private final Values launched;
  private PerformanceEditSession session;
  private final Map<Field, ImString> inputs = new EnumMap<>(Field.class);
  private final Map<String, float[]> controls = new HashMap<>();
  private String message = "";
  private boolean probing, applyEnabled;

  PerformancePanel(UserConfig user, Path file, Consumer<String> errors) {
    this.user = Objects.requireNonNull(user);
    this.file = Objects.requireNonNull(file);
    this.errors = Objects.requireNonNull(errors);
    launched = PerformancePreferences.from(user);
  }

  private void ensure() {
    if (session == null) {
      session = new PerformanceEditSession(user);
      sync();
    }
  }

  private void sync() {
    for (var field : Field.values())
      inputs.put(
          field, new ImString(session.text().get(field), 4 * PerformanceEditSession.MAX_TEXT + 1));
  }

  void draw() {
    ensure();
    if (probing) controls.clear();
    ImGui.textWrapped(
        "Performance fields are private until Apply Performance. Revert reloads active values.");
    control("blur");
    for (var field : Field.values()) {
      if (ImGui.inputText(field.label() + "###performance-" + field.key(), inputs.get(field)))
        act(
            () -> {
              session.edit(field, inputs.get(field).get());
              message = "";
            });
      control(field.key());
      if (ImGui.isItemHovered())
        ImGui.setTooltip("Whole decimal integer: " + field.minimum() + ".." + field.maximum());
    }
    var feedback = session.feedback();
    feedback
        .problems()
        .forEach((field, problem) -> ImGui.textWrapped("! " + field.label() + ": " + problem));
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
          "Active performance settings changed. Revert reloads them; private input is retained.");
    applyEnabled =
        current && session.changed() && feedback.value() != null && displayedProblem.isEmpty();
    ImGui.beginDisabled(!applyEnabled);
    if (ImGui.button("Apply Performance"))
      act(
          () -> {
            session.apply(user, file, displayed());
            sync();
            message = "Performance preferences saved";
          });
    control("apply");
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Revert Performance"))
      act(
          () -> {
            session.revert(user);
            sync();
            message = "Active performance settings reloaded";
          });
    control("revert");
    if (session.changed())
      ImGui.textDisabled("Pending performance fields are excluded from Save Preferences");
    var activeValues = PerformancePreferences.from(user);
    for (var field : List.of(Field.WORKERS, Field.ENGINE_MIB, Field.DEFAULT_EXTENT)) {
      long active = activeValues.get(field);
      if (active != launched.get(field))
        ImGui.textWrapped(
            field.label()
                + ": "
                + launched.get(field)
                + " running; "
                + active
                + " saved for restart.");
    }
    ImGui.textWrapped(
        "Limits restrict new work; they do not reserve memory. Default extent takes effect at next"
            + " launch. Existing fixed previews keep their requested extent; use Regenerate after"
            + " correcting a rejected request.");
    if (feedback.value() != null) {
      long extent = feedback.value().get(Field.DEFAULT_EXTENT);
      long cells = feedback.value().get(Field.PREVIEW_CELLS);
      if (cells < extent * extent * extent || cells < 65536)
        ImGui.textWrapped(
            "The cell budget can reject the default 3D extent or the fixed 256 x 256 2D image."
                + " Raise the budget if those previews are needed.");
    }
    if (!message.isEmpty()) ImGui.textWrapped(message);
  }

  private Map<Field, String> displayed() {
    var result = new EnumMap<Field, String>(Field.class);
    inputs.forEach((field, value) -> result.put(field, value.get()));
    return result;
  }

  private void act(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException error) {
      String detail =
          Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
      if (error.getCause() != null)
        detail +=
            ": "
                + Objects.requireNonNullElse(
                    error.getCause().getMessage(), error.getCause().getClass().getSimpleName());
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

  boolean restartRequired() {
    var active = PerformancePreferences.from(user);
    return active.get(Field.WORKERS) != launched.get(Field.WORKERS)
        || active.get(Field.ENGINE_MIB) != launched.get(Field.ENGINE_MIB)
        || active.get(Field.DEFAULT_EXTENT) != launched.get(Field.DEFAULT_EXTENT);
  }

  void probeControls() {
    probing = true;
  }

  float[] probeControl(String key) {
    return controls.get(key);
  }

  Map<Field, String> probeText() {
    return session.text();
  }

  boolean probeApplyEnabled() {
    return applyEnabled;
  }

  long validations() {
    return session.validations();
  }
}
