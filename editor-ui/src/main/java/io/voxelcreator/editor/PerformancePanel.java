package io.voxelcreator.editor;

import static io.voxelcreator.editor.PerformancePreferences.*;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Production explicit performance preferences, including exact 64-bit cell-budget input. */
final class PerformancePanel implements AutoCloseable {
  private final UserConfig user;
  private final PreferenceApplySession applyWork;
  private boolean closed;
  private final SettingsGuidance guidance;
  final PreferenceActionScope actionScope = new PreferenceActionScope();
  private final PreferenceSaveAction saveActions = new PreferenceSaveAction();
  private int saveId;
  private boolean revealSave;
  final SettingsPageChrome layout = new SettingsPageChrome(3);
  private Field reveal;
  private final Map<Field, Integer> fieldIds = new EnumMap<>(Field.class);
  private final Path file;
  private final Consumer<String> errors;
  private final Values launched;
  private PerformanceEditSession session;
  private final Map<Field, ImString> inputs = new EnumMap<>(Field.class);
  private final Map<Field, ExactLongInput> numeric = new EnumMap<>(Field.class);
  private final Map<Field, ExactLongInput.Row> numericRows = new EnumMap<>(Field.class);
  private final Map<String, float[]> controls = new HashMap<>();
  private String message = "";
  private boolean probing, applyEnabled;

  PerformancePanel(UserConfig user, Path file, Consumer<String> errors) {
    this(user, file, errors, null, null, null);
  }

  PerformancePanel(EditorState state, Path file, Consumer<String> errors) {
    this(state.user, file, errors, state, null, null);
  }

  static PerformancePanel background(EditorState state, Path file, Consumer<String> errors) {
    return new PerformancePanel(state, file, errors, state.background, state::postUi);
  }

  PerformancePanel(EditorState state, Path file, Consumer<String> errors,
      Executor executor, Consumer<Runnable> ui) {
    this(state.user, file, errors, state, executor, ui);
  }

  private PerformancePanel(UserConfig user, Path file, Consumer<String> errors, EditorState state,
      Executor executor, Consumer<Runnable> ui) {
    this.user = Objects.requireNonNull(user);
    guidance = new SettingsGuidance(state);
    this.file = Objects.requireNonNull(file);
    this.errors = Objects.requireNonNull(errors);
    launched = PerformancePreferences.from(user);
    applyWork =
        executor == null ? null : new PreferenceApplySession(user, file, executor, ui, errors);
  }

  private void ensure() {
    if (session == null) {
      session = new PerformanceEditSession(user);
      sync();
    }
  }

  private void sync() {
    actionScope.resetDraft();
    numeric.clear();
    numericRows.clear();
    for (var field : Field.values())
      inputs.put(
          field, new ImString(session.text().get(field), 4 * PerformanceEditSession.MAX_TEXT + 1));
  }

  void draw() {
    draw(null);
  }

  void draw(Runnable savePreferences) {
    if (closed) return;
    ensure();
    if (applyWork != null) applyWork.refresh();
    long work = applyWork == null ? 0 : applyWork.version();
    long request = actionScope.synchronize(PerformancePreferences.from(user));
    long saveRequest = saveActions.synchronize(actionScope, savePreferences != null);
    boolean save = false;
    saveId = 0;
    if (probing) {
      controls.clear();
      fieldIds.clear();
    }
    layout.begin("performance-settings-body");
    ImGui.textWrapped(
        "Performance fields are private until Apply Performance. Revert reloads active values.");
    control("blur");
    numericRows.clear();
    boolean current = session.current(user);
    for (var field : Field.values()) {
      ImGui.textWrapped(field.label());
      String id = "###performance-" + field.key();
      if (probing) fieldIds.put(field, ImGui.getID(id));
      boolean focus = field == reveal;
      var row =
          numeric
              .computeIfAbsent(field, ignored -> new ExactLongInput())
              .draw(
                  id,
                  Math.max(1, ImGui.getContentRegionAvailX()),
                  inputs.get(field),
                  session.text().get(field),
                  null,
                  current,
                  field.minimum(),
                  field.maximum(),
                  field.sliderRadius(),
                  HelpDefinitions.BOUNDED_INTEGER_TOOLTIP,
                  text ->
                      act(
                          () -> {
                            session.edit(field, text);
                            message = "";
                          }),
                  () -> {
                    if (focus) ImGui.setKeyboardFocusHere();
                  },
                  nativeId ->
                      guidance.integer(
                          SettingsControlMetadata.performance(field),
                          nativeId,
                          inputs.get(field).get(),
                          field.minimum(),
                          field.maximum(),
                          current && inputs.get(field).get().equals(session.text().get(field))));
      numericRows.put(field, row);
      control(field.key(), row.input());
      if (focus) {
        ImGui.setScrollHereY(.3f);
        reveal = null;
      }
    }
    var feedback = session.feedback();
    feedback
        .problems()
        .forEach((field, problem) -> ImGui.textWrapped("! " + field.label() + ": " + problem));
    String displayedProblem = "";
    var shown = displayed();
    try {
      session.requireDisplayed(shown);
    } catch (RuntimeException mismatch) {
      displayedProblem = Objects.requireNonNullElse(mismatch.getMessage(), mismatch.toString());
    }
    if (!displayedProblem.isEmpty()) ImGui.textWrapped("! " + displayedProblem);
    if (!current)
      ImGui.textWrapped(
          "Active performance settings changed. Revert reloads them; private input is retained.");
    applyEnabled =
        current && session.changed() && feedback.value() != null && displayedProblem.isEmpty();
    var firstProblem =
        PerformanceProblemNavigation.first(session.text(), shown, feedback.problems());
    if (session.changed())
      ImGui.textWrapped("Pending performance fields are excluded from Save Preferences");
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
    if (applyWork != null && !applyWork.message().isEmpty())
      ImGui.textWrapped(StatusBarText.bounded(applyWork.message(), 3072));
    if (savePreferences != null) {
      ImGui.pushID("performance-save:" + saveActions.nativeId() + ":" + saveRequest);
      saveId = ImGui.getID("Save Preferences");
      save = DialogLayout.button("Save Preferences");
      control("save-preferences");
      if (revealSave) {
        ImGui.setScrollHereY(.5f);
        revealSave = false;
      }
      ImGui.popID();
    }
    layout.end();
    ImGui.pushID("performance-request:" + actionScope.nativeId() + ":" + request);
    boolean running = applyWork != null && applyWork.running();
    ImGui.pushID("private-preference-work:" + (applyWork == null ? 0 : applyWork.version()));
    ImGui.beginDisabled(!applyEnabled && !running);
    boolean apply = layout.identifiedAction("apply", running ? "Cancel Apply" : "Apply Performance");
    control("apply");
    ImGui.endDisabled();
    ImGui.popID();
    boolean revert = layout.identifiedAction("revert", "Revert Performance");
    control("revert");
    ImGui.beginDisabled(firstProblem == null);
    boolean problem = layout.identifiedAction("problem", "Show Invalid Limit");
    control("problem");
    ImGui.endDisabled();
    ImGui.popID();
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      if (applyWork != null) applyWork.cancel();
      return;
    }
    if (!actionScope.current(PerformancePreferences.from(user))) return;
    if (revert)
      act(
          () -> {
            if (applyWork != null) applyWork.cancel();
            session.revert(user);
            sync();
            message = "Active performance settings reloaded";
          });
    else if (apply && (applyEnabled || running)
        && (applyWork == null || work == applyWork.version())) {
      if (running) applyWork.cancel();
      else act(() -> {
        if (applyWork == null) {
          session.apply(user, file, displayed());
          sync();
          message = "Performance preferences saved";
        } else {
          var owner = session;
          long version = owner.version();
          var requestValue = owner.request(user, displayed());
          applyWork.start(requestValue,
              () -> session == owner && owner.ownsInput(user, version, displayed()));
          message = "";
        }
      });
    } else if (problem && firstProblem != null) reveal = firstProblem;
    else if (save && savePreferences != null && saveActions.current(actionScope, true))
      savePreferences.run();
    if (applyWork != null && applyWork.accept(work))
      act(
          () -> {
            session.revert(user);
            sync();
            message = "";
          });
  }

  PreferenceApplySession probeApplyWork() {
    return applyWork;
  }

  @Override
  public void close() {
    if (closed) return;
    suspendActions();
    if (applyWork != null) applyWork.close();
    closed = true;
  }

  void suspendActions() {
    if (applyWork != null) applyWork.cancel();
    actionScope.resetDraft();
    saveActions.release();
  }

  void probeRevealSave() {
    revealSave = true;
  }

  int probeSaveId() {
    return saveId;
  }

  void probeEdit(Field field, String value) {
    ensure();
    session.edit(field, value);
    inputs.get(field).set(value);
  }

  void probeRevert() {
    ensure();
    if (applyWork != null) applyWork.cancel();
    session.revert(user);
    sync();
  }

  Map<Field, Integer> fieldIds() {
    return Map.copyOf(fieldIds);
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

  ContextHelp contextHelp() {
    return guidance.contextHelp();
  }

  private void control(String key) {
    control(key, PopupLayout.itemBounds());
  }

  private void control(String key, DialogBounds.Rect bounds) {
    if (probing)
      controls.put(
          key, new float[] {bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2});
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

  Map<Field, ExactLongInput.Row> numericRows() {
    return Map.copyOf(numericRows);
  }
}
