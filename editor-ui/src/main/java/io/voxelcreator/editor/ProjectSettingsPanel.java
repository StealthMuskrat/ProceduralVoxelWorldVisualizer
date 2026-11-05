package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Private Project Settings buffer shared by the menu and diagnostic entry points. */
public final class ProjectSettingsPanel implements AutoCloseable {
  private final EditorState state;
  private final SettingsGuidance guidance;
  private ProjectSettingsSession session;
  final SettingsActionScope actionScope = new SettingsActionScope();
  private boolean revealFeedback;
  private final Map<String, ImString> buffers = new LinkedHashMap<>();
  private final Map<String, CoordinateInput> boundInputs = new LinkedHashMap<>();
  private final Map<String, CoordinateInput.Row> boundRows = new LinkedHashMap<>();
  private ExactLongInput seedInput = new ExactLongInput();
  private ExactLongInput.Row seedRow;
  private final Map<String, float[]> controls = new HashMap<>();
  private final PropertyFocus focus = new PropertyFocus();
  final SettingsPageChrome layout = new SettingsPageChrome(4);
  private final Map<String, Integer> fieldIds = new LinkedHashMap<>();
  private final Map<String, DialogBounds.Rect> inputBounds = new LinkedHashMap<>();
  private boolean focusWindow;
  private String message = "";
  private int diagnosticRows;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final BooleanSupplier blocked;
  private long shownWorkVersion = -1;
  private boolean disposed;

  public ProjectSettingsPanel(EditorState state) {
    this(state, null, null, () -> false);
  }

  static ProjectSettingsPanel background(EditorState state, BooleanSupplier blocked) {
    return new ProjectSettingsPanel(state, state.background, state.uiQueue::offer, blocked);
  }

  ProjectSettingsPanel(
      EditorState state, Executor executor, Consumer<Runnable> ui, BooleanSupplier blocked) {
    this.state = state;
    this.executor = executor;
    this.ui = ui;
    this.blocked = Objects.requireNonNull(blocked);
    if (executor != null) Objects.requireNonNull(ui);
    guidance = new SettingsGuidance(state);
  }

  public boolean open(EditorEvents.Target target) {
    if (disposed) return false;
    if (target != null
        && (!target.settings()
            || target.resource() != null
            || target.control() != null
            || !state.canNavigate(target))) return false;
    if (session == null || session.closed()) reload();
    focusWindow = true;
    String field = ProjectSettingsSession.field(target);
    if (field != null && session.current(state))
      focus.request(
          new EditorEvents.Target(state.project().project.uuid, null, null, field, "", true),
          session);
    else focus.cancel();
    return true;
  }

  private void reload() {
    actionScope.release();
    if (session != null) session.cancel();
    session = new ProjectSettingsSession(state, executor, ui, blocked);
    shownWorkVersion = -1;
    buffers.clear();
    boundInputs.clear();
    boundRows.clear();
    seedInput = new ExactLongInput();
    seedRow = null;
    for (var field : ProjectSettingsSession.FIELDS)
      buffers.put(
          field, new ImString(session.text(field), ProjectSettingsSession.MAX_CHARACTERS + 1));
    focus.cancel();
    message = "";
    revealFeedback = false;
  }

  public void cancel() {
    actionScope.release();
    revealFeedback = false;
    if (session != null) session.cancel();
    session = null;
    buffers.clear();
    focus.cancel();
    boundInputs.clear();
    boundRows.clear();
    seedRow = null;
    message = "";
    focusWindow = false;
    diagnosticRows = 0;
    controls.clear();
    fieldIds.clear();
    inputBounds.clear();
  }

  boolean pendingChanges() {
    return session != null && (session.modified() || session.applying());
  }

  /** Called inside the shared Settings window; true closes that window after Apply/Cancel. */
  public boolean draw() {
    controls.clear();
    fieldIds.clear();
    inputBounds.clear();
    diagnosticRows = 0;
    if (session == null || session.closed()) return true;
    session.refresh();
    if (session.closed()) return true;
    var owner = session;
    long work = owner.workVersion();
    if (shownWorkVersion != work) {
      actionScope.release();
      shownWorkVersion = work;
    }
    if (focusWindow) {
      ImGui.setWindowFocus("Project Settings");
      focusWindow = false;
    }
    long request = actionScope.synchronize(state.project(), session, state.revision);
    boolean current = session.current(state);
    focus.synchronize(state.project().project.uuid, null, session);
    layout.begin("project-settings-body");
    if (!session.progress().isEmpty()) ImGui.textWrapped(bounded(session.progress(), 3072));
    if (!session.applyFailure().isEmpty())
      ImGui.textWrapped("! " + bounded(session.applyFailure(), 2048));
    if (!current) {
      focus.cancel();
      ImGui.textWrapped(
          "The project changed while these settings were open. This private text is preserved;"
              + " discard and reload before applying.");
    }
    ImGui.textWrapped(
        "Apply validates these values against the complete current project. Changes remain private"
            + " until Apply; saving is explicit.");
    for (var field : ProjectSettingsSession.FIELDS) {
      boolean targeted = focus.matches(null, field);
      boolean submitted = targeted && focus.submit();
      String label =
          switch (field) {
            case "seed" -> "Project seed";
            case "minY" -> "Minimum Y (inclusive)";
            default -> "Maximum Y (inclusive)";
          };
      ImGui.textWrapped(label);
      String id = "###project-setting-" + field;
      fieldIds.put(field, ImGui.getID(id));
      Runnable activate =
          () -> {
            if (submitted) ImGui.setKeyboardFocusHere();
          };
      float width = Math.max(1, ImGui.getContentRegionAvailX());
      boolean[] inputFocused = {false};
      java.util.function.IntConsumer describe =
          nativeId -> {
            if (nativeId == fieldIds.get(field))
              inputFocused[0] = ImGui.isItemActive() || ImGui.isItemFocused();
            guidance.integer(
                SettingsControlMetadata.project(field),
                nativeId,
                buffers.get(field).get(),
                field.equals("seed") ? Long.MIN_VALUE : Integer.MIN_VALUE,
                field.equals("seed") ? Long.MAX_VALUE : Integer.MAX_VALUE,
                current && buffers.get(field).get().equals(session.text(field)));
          };
      if (field.equals("seed"))
        seedRow =
            seedInput.draw(
                id,
                width,
                buffers.get(field),
                session.text(field),
                null,
                current,
                value -> edit(field, value),
                activate,
                describe);
      else
        boundRows.put(
            field,
            boundInputs
                .computeIfAbsent(field, ignored -> new CoordinateInput())
                .draw(
                    field,
                    id,
                    width,
                    buffers.get(field),
                    session.text(field),
                    current,
                    value -> edit(field, value),
                    activate,
                    describe));
      var bounds = field.equals("seed") ? seedRow.input() : boundRows.get(field).input();
      control(field, bounds);
      inputBounds.put(field, bounds);
      if (targeted) {
        ImGui.setScrollHereY(.25f);
        if (inputFocused[0]) focus.acknowledge(field);
        else if (!submitted && ImGui.isMouseClicked(0)
            || ImGui.isKeyPressed(ImGuiKey.Escape, false)) focus.cancel();
      }
    }
    var problems = session.validate();
    for (var problem : problems) ImGui.textWrapped("! " + problem.message());
    if (problems.isEmpty() && session.issueCount() > 0) {
      UiScale.beginChild(
          "project-settings-diagnostics",
          0,
          SettingsPageLayout.multiline(
              layout.body().height(), ImGui.getTextLineHeightWithSpacing()),
          true);
      for (var problem : session.applyIssues().stream().limit(8).toList()) {
        diagnosticRows++;
        ImGui.textWrapped("! " + bounded(problem.jsonPointer() + ": " + problem.message(), 1024));
      }
      ImGui.endChild();
      ImGui.textDisabled(session.issueCount() + " candidate diagnostic(s); showing up to eight.");
    }
    if (!message.isEmpty()) ImGui.textWrapped("! " + bounded(message, 512));
    if (revealFeedback) {
      ImGui.setScrollHereY(.6f);
      revealFeedback = false;
    }
    var owned = new LinkedHashMap<String, String>();
    for (var field : ProjectSettingsSession.FIELDS) owned.put(field, session.text(field));
    String firstProblem =
        ProjectSettingsProblemNavigation.first(owned, displayed(), problems, session.applyIssues());
    layout.end();
    ImGui.pushID("project-settings-request:" + request);
    ImGui.beginDisabled(!current || session.applying());
    boolean apply = layout.identifiedAction("apply", session.applying() ? "Applying..." : "Apply");
    control("apply");
    ImGui.endDisabled();
    boolean cancel = layout.identifiedAction("cancel", "Cancel");
    control("cancel");
    ImGui.beginDisabled(current);
    boolean reload = layout.identifiedAction("reload", "Discard and Reload");
    control("reload");
    ImGui.endDisabled();
    ImGui.beginDisabled(firstProblem == null || !current);
    boolean problem = layout.identifiedAction("problem", "Show Problem");
    control("problem");
    ImGui.endDisabled();
    ImGui.popID();
    boolean done = false;
    if (!actionScope.current(state.project(), session, state.revision)) return false;
    boolean escape =
        ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)
            && ImGui.isKeyPressed(ImGuiKey.Escape, false);
    if (cancel || escape) done = true;
    else if (work != session.workVersion()) return false;
    else if (reload && !current) reload();
    else if (problem && firstProblem != null && current)
      focus.request(
          new EditorEvents.Target(state.project().project.uuid, null, null, firstProblem, "", true),
          session);
    else if (apply && current) {
      try {
        done = session.apply(state, displayed());
        if (session.applying()) message = "";
        else if (!done)
          message =
              session.applyFailure().isEmpty()
                  ? "Project Settings were rejected. Correct the values or inspect Validation"
                      + " Results."
                  : "Project Settings could not be applied. Your text is preserved; retry or"
                      + " inspect Logs.";
      } catch (RuntimeException invalid) {
        message =
            Objects.requireNonNullElse(
                invalid.getMessage(), "Project Settings could not be applied.");
      }
      if (!done && !session.applying()) revealFeedback = true;
    }
    if (!done && session == owner && work == owner.workVersion())
      done = owner.acceptPrepared(displayed());
    if (done) cancel();
    return done;
  }

  void suspendActions() {
    actionScope.release();
    if (session != null) session.suspendApply();
  }

  private void edit(String field, String value) {
    if (value.length() > ProjectSettingsSession.MAX_CHARACTERS) {
      buffers.get(field).set(session.text(field));
      message = "Project setting text limit " + ProjectSettingsSession.MAX_CHARACTERS;
    } else {
      session.edit(field, value);
      message = "";
    }
  }

  ExactLongInput.Row seedRow() {
    return seedRow;
  }

  Map<String, CoordinateInput.Row> boundRows() {
    return Map.copyOf(boundRows);
  }

  private Map<String, String> displayed() {
    var result = new LinkedHashMap<String, String>();
    buffers.forEach((key, value) -> result.put(key, value.get()));
    return result;
  }

  Map<String, Integer> fieldIds() {
    return Map.copyOf(fieldIds);
  }

  Map<String, DialogBounds.Rect> inputBounds() {
    return Map.copyOf(inputBounds);
  }

  List<Diagnostic> feedback() {
    return session.validate();
  }

  private static String bounded(String text, int limit) {
    return StatusBarText.clip(Objects.requireNonNullElse(text, ""), limit);
  }

  ContextHelp contextHelp() {
    return guidance.contextHelp();
  }

  private void control(String key) {
    control(key, PopupLayout.itemBounds());
  }

  private void control(String key, DialogBounds.Rect bounds) {
    controls.put(
        key,
        new float[] {
          bounds.x() + Math.min(80, bounds.width() * .25f), bounds.y() + bounds.height() / 2
        });
  }

  float[] controlAt(String key) {
    return controls.get(key);
  }

  String focusedField() {
    return focus.acknowledged();
  }

  String text(String field) {
    return session.text(field);
  }

  int diagnosticRows() {
    return diagnosticRows;
  }

  int candidateIssueCount() {
    return session == null ? 0 : session.issueCount();
  }

  void probeText(String field, String text) {
    session.edit(field, text);
    buffers.get(field).set(text);
  }

  String applyFailure() {
    return session == null ? "" : session.applyFailure();
  }

  ProjectSettingsSession probeSession() {
    return session;
  }

  @Override
  public void close() {
    disposed = true;
    cancel();
  }
}
