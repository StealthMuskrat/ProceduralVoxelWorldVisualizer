package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Three private raw fields, bound to one exact document revision until Apply or Cancel. */
final class ProjectSettingsSession {
  static final List<String> FIELDS = List.of("seed", "minY", "maxY");
  static final int MAX_CHARACTERS = 128;
  private final EditorState editor;
  private final ProjectData project;
  private final UUID projectId;
  private final JsonNode projectBase;
  private final long revision;
  private final long baseSeed;
  private final int baseMinY, baseMaxY;
  private final Map<String, String> text = new LinkedHashMap<>();
  private List<Diagnostic> cached, applyIssues = List.of();
  private int issueCount;
  private String applyFailure = "";
  private boolean closed, forcedStale;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final BooleanSupplier blocked;
  private Object workToken = new Object();
  private FutureTask<Void> task;
  private record Prepared(DocumentSession.PreparedEdit value, Throwable failure) {}
  private final PreviewMailbox<Prepared> handoff = new PreviewMailbox<>();
  private long delivery;
  private boolean ready, admitted;
  private Map<String, String> workValues;
  private boolean admitting;
  private long workVersion;
  private String progress = "";
  private List<Diagnostic> sharedIssues, sharedValues;
  private String sharedScope;

  ProjectSettingsSession(EditorState state) {
    this(state, null, null, () -> false);
  }

  ProjectSettingsSession(
      EditorState state, Executor executor, Consumer<Runnable> ui, BooleanSupplier blocked) {
    editor = state;
    this.executor = executor;
    this.ui = ui;
    this.blocked = Objects.requireNonNull(blocked);
    if (executor != null) Objects.requireNonNull(ui);
    project = state.project();
    projectId = project.project.uuid;
    projectBase = ProjectIO.JSON.valueToTree(project);
    revision = state.revision;
    baseSeed = project.project.seed;
    baseMinY = project.project.minY;
    baseMaxY = project.project.maxY;
    text.put("seed", Long.toString(project.project.seed));
    text.put("minY", Integer.toString(project.project.minY));
    text.put("maxY", Integer.toString(project.project.maxY));
  }

  String text(String field) {
    if (!FIELDS.contains(field))
      throw new IllegalArgumentException("Unknown project setting " + field);
    return text.get(field);
  }

  void edit(String field, String value) {
    if (closed) throw new IllegalStateException("Project Settings is closed");
    text(field);
    if (Objects.requireNonNull(value).length() > MAX_CHARACTERS)
      throw new IllegalArgumentException("Project setting text limit " + MAX_CHARACTERS);
    if (text.get(field).equals(value)) return;
    if (task != null || ready || admitting) {
      retireWork();
      progress =
          "Pending Apply cancelled because the settings were edited. Review the new text and Apply"
              + " again.";
    }
    text.put(field, value);
    cached = null;
    applyIssues = List.of();
    issueCount = 0;
    applyFailure = "";
  }

  boolean current(EditorState state) {
    return !closed
        && !forcedStale
        && state == editor
        && state.project() == project
        && Objects.equals(projectId, project.project.uuid)
        && state.revision == revision
        && project.project.seed == baseSeed
        && project.project.minY == baseMinY
        && project.project.maxY == baseMaxY;
  }

  boolean closed() {
    refresh();
    return closed;
  }

  boolean applying() {
    refresh();
    return task != null || ready || admitting;
  }

  long workVersion() {
    return workVersion;
  }

  String progress() {
    refresh();
    return progress;
  }

  boolean modified() {
    return !closed
        && (!text.get("seed").equals(Long.toString(baseSeed))
            || !text.get("minY").equals(Integer.toString(baseMinY))
            || !text.get("maxY").equals(Integer.toString(baseMaxY)));
  }

  private static Diagnostic problem(String field, String message) {
    return new Diagnostic(
        "INVALID_PROJECT_SETTING",
        Diagnostic.Severity.ERROR,
        message,
        "/project/" + field,
        null,
        null,
        field);
  }

  List<Diagnostic> validate() {
    if (cached != null) return cached;
    var problems = new ArrayList<Diagnostic>();
    var values = new HashMap<String, Long>();
    for (var field : FIELDS) {
      String raw = text.get(field);
      try {
        if (raw.length() > MAX_CHARACTERS || !raw.matches("[+-]?[0-9]+"))
          throw new NumberFormatException();
        long value = field.equals("seed") ? Long.parseLong(raw) : Integer.parseInt(raw);
        values.put(field, value);
      } catch (NumberFormatException invalid) {
        problems.add(
            problem(
                field,
                field.equals("seed")
                    ? "Project seed must be a signed 64-bit decimal integer."
                    : "Project Y bound must be a signed 32-bit decimal integer."));
      }
    }
    if (values.containsKey("minY")
        && values.containsKey("maxY")
        && values.get("minY") > values.get("maxY"))
      problems.add(
          new Diagnostic(
              "INVALID_BOUNDS",
              Diagnostic.Severity.ERROR,
              "Minimum Y must not exceed maximum Y.",
              "/project/maxY",
              null,
              null,
              "maxY"));
    cached = List.copyOf(problems);
    return cached;
  }

  List<Diagnostic> applyIssues() {
    return applyIssues;
  }

  int issueCount() {
    return issueCount;
  }

  String applyFailure() {
    return applyFailure;
  }

  boolean apply(EditorState state) {
    return apply(state, Map.copyOf(text));
  }

  boolean apply(EditorState state, Map<String, String> displayed) {
    if (!current(state))
      throw new IllegalStateException(
          closed
              ? "Project Settings is closed"
              : "The project changed. Discard and reload Project Settings before applying.");
    if (!displayed.keySet().equals(Set.copyOf(FIELDS)))
      throw new IllegalStateException("All three Project Settings fields are required");
    for (var field : FIELDS)
      if (!Objects.equals(text.get(field), displayed.get(field)))
        throw new IllegalStateException(
            "Displayed " + field + " differs from the private input; correct it before applying.");
    if (task != null || ready || admitting) return false;
    if (!projectBase.equals(ProjectIO.JSON.valueToTree(state.project()))) {
      forcedStale = true;
      throw new IllegalStateException(
          "The project changed. Discard and reload Project Settings before applying.");
    }
    var problems = validate();
    if (!problems.isEmpty()) {
      applyIssues = problems;
      issueCount = problems.size();
      applyFailure = "";
      state.issues = problems;
      state.validationScope = "Rejected Project Settings";
      for (var problem : problems)
        state.reportDiagnostic(problem, EditorEvents.Subsystem.PROJECT, true);
      return false;
    }
    long seed = Long.parseLong(text.get("seed"));
    int minY = Integer.parseInt(text.get("minY")), maxY = Integer.parseInt(text.get("maxY"));
    if (executor != null) {
      prepare(seed, minY, maxY);
      return false;
    }
    var result =
        state.changeResult(
            p -> {
              p.project.seed = seed;
              p.project.minY = minY;
              p.project.maxY = maxY;
            });
    applyIssues = result.issues();
    issueCount = result.issueCount();
    applyFailure = result.failure();
    if (!result.applied()) {
      return false;
    }
    closed = true;
    return true;
  }

  private void prepare(long seed, int minY, int maxY) {
    retireWork();
    Object token = workToken;
    admitting = true;
    applyIssues = List.of();
    issueCount = 0;
    applyFailure = "";
    try {
      var values = Map.copyOf(text);
      var request =
          editor.document.requestEdit(
              p -> {
                p.project.seed = seed;
                p.project.minY = minY;
                p.project.maxY = maxY;
              });
      sharedIssues = editor.issues;
      sharedValues = List.copyOf(sharedIssues);
      sharedScope = editor.validationScope;
      boolean denied = blocked.getAsBoolean();
      if (token != workToken || closed) return;
      if (denied || !current(editor) || !values.equals(text) || !baseCurrent()) {
        staleWork();
        return;
      }
      progress =
          "Preparing and validating Project Settings in the background. Current committed settings"
              + " remain active. Cancel or Escape closes this private settings draft without"
              + " applying.";
      workValues = values;
      long handed = ++delivery;
      handoff.advance(handed);
      task =
          new FutureTask<>(
              () -> {
                DocumentSession.PreparedEdit result = null;
                Throwable failure = null;
                try {
                  result = request.prepare();
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                if (!handoff.publish(handed, new Prepared(result, failure))) return null;
                ui.accept(() -> delivered(token, handed));
                return null;
              });
      executor.execute(task);
      if (owns(token)) admitted = true;
    } catch (Throwable failure) {
      failWork(token, failure);
    } finally {
      if (token == workToken) admitting = false;
    }
  }

  private boolean owns(Object token) {
    return token == workToken && (task != null || ready) && !closed;
  }

  private void delivered(Object token, long handed) {
    if (!owns(token) || delivery != handed || ready) return;
    // Readiness is only a receipt; retain full worker and executor observation until acceptance.
    ready = true;
  }

  /** The panel decides Cancel/Reload and verifies exact displayed fields before publication. */
  boolean acceptPrepared(Map<String, String> displayed) {
    Object token = workToken;
    refresh();
    if (!owns(token) || !ready || !admitted || admitting) return false;
    try {
      var values = workValues;
      if (!values.equals(text) || !values.equals(displayed))
        throw new IllegalStateException("Displayed Project Settings changed; review the fields and Apply again");
      if (!owns(token) || workValues != values) return false;
      var completion = BackgroundFailure.completed(task);
      if (!owns(token) || workValues != values || completion == null) return false;
      if (completion.failure() != null) {
        failWork(token, completion.failure());
        return false;
      }
      var value = handoff.take();
      if (value == null) throw new IllegalStateException("Project Settings preparation unavailable");
      publish(token, values, value.value(), value.failure());
      return closed;
    } catch (Throwable failure) {
      failWork(token, failure);
      return false;
    }
  }

  void suspendApply() {
    if (closed || (task == null && !ready && !admitting)) return;
    retireWork();
    progress = "Project Settings Apply cancelled. Private text is preserved; Apply again explicitly.";
  }

  private boolean feedbackCurrent() {
    return sharedIssues == editor.issues
        && sharedValues.equals(editor.issues)
        && Objects.equals(sharedScope, editor.validationScope);
  }

  private void publish(
      Object token,
      Map<String, String> values,
      DocumentSession.PreparedEdit prepared,
      Throwable failure) {
    if (!owns(token)) return;
    try {
      if (!current(editor) || !values.equals(text) || !feedbackCurrent()) {
        staleWork();
        return;
      }
      if (!baseCurrent()) {
        staleWork();
        return;
      }
      if (failure != null) {
        failWork(token, failure);
        return;
      }
      boolean denied = blocked.getAsBoolean();
      if (!owns(token)) return;
      if (denied
          || !current(editor)
          || !values.equals(text)
          || !feedbackCurrent()
          || !baseCurrent()
          || !editor.document.currentEdit(prepared)) {
        staleWork();
        return;
      }
      if (!prepared.success()) {
        var diagnostics = prepared.diagnostics();
        var result = ProjectChangeResult.rejected(diagnostics);
        retireWork();
        applyIssues = result.issues();
        issueCount = result.issueCount();
        progress =
            "Project Settings were rejected. Correct the text or review the candidate diagnostics,"
                + " then Apply again.";
        editor.rejectDraftApply(diagnostics);
        return;
      }
      editor.document.applyEdit(prepared);
      retireWork();
      closed = true;
      handoff.close();
      progress = "Project Settings applied.";
      editor.revision++;
      editor.issues = List.of();
      editor.validationScope = "Committed project";
      editor.status = "Project Settings applied";
      // Settings publication never refreshes or overwrites separate private Properties input.
    } catch (Throwable rejected) {
      failWork(token, rejected);
    }
  }

  void refresh() {
    if ((task == null && !ready) || closed) return;
    Object token = workToken;
    try {
      if (!current(editor) || !feedbackCurrent()) {
        staleWork();
        return;
      }
      var completion = BackgroundFailure.completed(task);
      if (owns(token) && completion != null && completion.failure() != null)
        failWork(token, completion.failure());
    } catch (Throwable failure) {
      failWork(token, failure);
    }
  }

  private boolean baseCurrent() {
    if (projectBase.equals(ProjectIO.JSON.valueToTree(editor.project()))) return true;
    forcedStale = true;
    return false;
  }

  private void staleWork() {
    retireWork();
    progress =
        "Project Settings Apply became stale. Review the text and Apply again, or discard and"
            + " reload if the project changed.";
  }

  private void failWork(Object token, Throwable failure) {
    if (token != workToken || closed) return;
    retireWork();
    Object failed = workToken;
    String detail = BackgroundFailure.describe(failure);
    if (closed || failed != workToken) return;
    applyIssues = List.of();
    issueCount = 0;
    applyFailure = detail;
    progress =
        "Project Settings Apply failed. The text is preserved; Apply again to retry or Cancel.";
    try {
      editor.error("PROJECT_SETTINGS_APPLY_FAILED", EditorEvents.Subsystem.PROJECT, applyFailure);
    } catch (Throwable ignored) {
      /* Keep correction and cancellation available. */
    }
  }

  private void retireWork() {
    var previous = task;
    workToken = new Object();
    workVersion++;
    task = null;
    ready = admitted = false;
    workValues = null;
    handoff.advance(++delivery);
    admitting = false;
    sharedIssues = sharedValues = null;
    sharedScope = null;
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  void cancel() {
    closed = true;
    retireWork();
    handoff.close();
    applyIssues = List.of();
    issueCount = 0;
    applyFailure = "";
  }

  static String field(EditorEvents.Target target) {
    if (target == null
        || !target.settings()
        || target.resource() != null
        || target.control() != null) return null;
    String parameter = Objects.requireNonNullElse(target.parameter(), "");
    if (parameter.startsWith("project/")) parameter = parameter.substring(8);
    if (FIELDS.contains(parameter)) return parameter;
    if (!Set.of("", "project").contains(parameter)) return null;
    String pointer = Objects.requireNonNullElse(target.pointer(), "");
    for (var field : FIELDS) if (pointer.equals("/project/" + field)) return field;
    return pointer.equals("/project") ? "maxY" : null;
  }
}
