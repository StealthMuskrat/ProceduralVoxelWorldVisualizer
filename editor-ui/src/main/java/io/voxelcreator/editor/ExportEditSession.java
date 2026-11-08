package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Editor export owns a frozen source and requires its exact displayed preview before file I/O. */
final class ExportEditSession {
  private final EditorState state;
  private final UUID projectId;
  private final Object document;
  private ProjectData source, frozen;
  private long revision, preparations;
  private JsonNode sourceValue;
  private ResourceTreeIndex catalog;
  private ResourceExportSession model;
  private ResourceExportSession.Preview preview, shown;
  private Set<UUID> selected = Set.of();
  private final ExportSelectionHistory history = new ExportSelectionHistory();
  private long selectionVersion;
  private boolean closed, forcedStale, writing;
  private int unavailable;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final BooleanSupplier blocked;
  private ProjectData catalogSource;
  private boolean planReady;
  private Object request = new Object();
  private ProjectFileOwner planningOwner;
  private FutureTask<Void> task;
  private record Completion(Prepared value, Throwable failure) {}
  private final PreviewMailbox<Completion> handoff = new PreviewMailbox<>();
  private long delivery;
  private boolean preparedReady, admitted;
  private String planningMessage = "";

  private record Prepared(
      ResourceTreeIndex catalog,
      ResourceExportSession model,
      ResourceExportSession.Preview preview,
      int unavailable) {}

  ExportEditSession(EditorState state, Set<UUID> selected) {
    this(state, selected, null, null, () -> false);
  }

  ExportEditSession(
      EditorState state,
      Set<UUID> selected,
      Executor executor,
      Consumer<Runnable> ui,
      BooleanSupplier blocked) {
    this.state = Objects.requireNonNull(state);
    this.executor = executor;
    this.ui = ui;
    this.blocked = Objects.requireNonNull(blocked);
    if (executor != null) Objects.requireNonNull(ui);
    projectId = state.project().project.uuid;
    document = state.document.sessionToken();
    reload(selected);
  }

  boolean current() {
    return !closed
        && !writing
        && document == state.document.sessionToken()
        && !forcedStale
        && source == state.project()
        && revision == state.revision
        && Objects.equals(projectId, state.project().project.uuid);
  }

  boolean canReload() {
    return !closed
        && !writing
        && document == state.document.sessionToken()
        && Objects.equals(projectId, state.project().project.uuid);
  }

  boolean busy() {
    return writing;
  }

  boolean closed() {
    return closed;
  }

  Set<UUID> selected() {
    return selected;
  }

  ResourceTreeIndex catalog() {
    refresh();
    return catalog;
  }

  ResourceExportSession.Preview preview() {
    refresh();
    return preview;
  }

  long preparations() {
    return preparations;
  }

  int unavailableCount() {
    refresh();
    return unavailable;
  }

  void select(Set<UUID> selection) {
    if (!current()) throw new IllegalStateException("Reload the changed export source first");
    if (selected.equals(selection)) return;
    var ids = Set.copyOf(selection);
    history.record(selected, ids);
    selected = ids;
    selectionVersion++;
    selectionChanged();
  }

  private void selectionChanged() {
    shown = null;
    if (executor == null) {
      preview = null;
      prepare();
    } else schedule();
  }

  boolean canUndo() {
    return current() && history.canUndo();
  }

  boolean canRedo() {
    return current() && history.canRedo();
  }

  boolean undo() {
    return moveHistory(false);
  }

  boolean redo() {
    return moveHistory(true);
  }

  private boolean moveHistory(boolean forward) {
    if (!current()) throw new IllegalStateException("Reload the changed export source first");
    var next = history.move(selected, forward);
    if (next == null) return false;
    selected = next;
    selectionVersion++;
    selectionChanged();
    return true;
  }

  long selectionVersion() {
    return selectionVersion;
  }

  private void clearHistory() {
    history.clear();
    selectionVersion++;
  }

  private void prepare() {
    planReady = true;
    unavailable = (int) selected.stream().filter(id -> catalog.get(id) == null).count();
    if (unavailableCount() != 0) return;
    model.prepare(frozen, 0, selected);
    preview = model.preview();
    preparations++;
  }

  void reload() {
    reload(selected);
  }

  private void reload(Set<UUID> selection) {
    if (!canReload()) throw new IllegalStateException("The project was replaced; reopen Export");
    var value = ProjectIO.JSON.valueToTree(state.project());
    var snapshot = ProjectIO.JSON.convertValue(value, ProjectData.class);
    var ids = Set.copyOf(selection);
    source = state.project();
    revision = state.revision;
    sourceValue = value;
    frozen = snapshot;
    selected = ids;
    clearHistory();
    shown = null;
    forcedStale = false;
    if (executor == null) {
      catalog = new ResourceTreeIndex(snapshot);
      catalogSource = frozen;
      model = new ResourceExportSession();
      preview = null;
      prepare();
    } else schedule();
  }

  boolean choicesReady() {
    refresh();
    return current() && catalog != null && catalogSource == frozen;
  }

  Object planningToken() {
    return request;
  }

  boolean planning() {
    refresh();
    return task != null;
  }

  String planningMessage() {
    refresh();
    return planningMessage;
  }

  boolean canRetryPlan() {
    refresh();
    return current() && !planReady && task == null;
  }

  void retryPlan() {
    if (canRetryPlan()) schedule();
  }

  private void schedule() {
    Object ticket = retirePlanning();
    if (closed || ticket != request || task != null) return;
    planReady = false;
    shown = null;
    try {
      planningOwner = new ProjectFileOwner(state);
      var snapshot = frozen;
      var ids = selected;
      var preparedCatalog = catalogSource == snapshot ? catalog : null;
      planningMessage =
          "Preparing the resource catalog and dependency preview in the background. Any visible"
              + " Included rows belong to the last completed plan; review the new plan before"
              + " Export.";
      long handed = ++delivery;
      handoff.advance(handed);
      task =
          new FutureTask<>(
              () -> {
                Prepared result = null;
                Throwable failure = null;
                try {
                  var index =
                      preparedCatalog == null ? new ResourceTreeIndex(snapshot) : preparedCatalog;
                  int missing = (int) ids.stream().filter(id -> index.get(id) == null).count();
                  var next = new ResourceExportSession();
                  ResourceExportSession.Preview preview = null;
                  if (missing == 0) {
                    next.prepare(snapshot, 0, ids);
                    preview = next.preview();
                  }
                  result = new Prepared(index, next, preview, missing);
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                if (!handoff.publish(handed, new Completion(result, failure))) return null;
                ui.accept(() -> delivered(ticket, handed));
                return null;
              });
      executor.execute(task);
      if (ownsPlanning(ticket)) admitted = true;
    } catch (Throwable failure) {
      failPlanning(ticket, failure);
    }
  }

  private boolean ownsPlanning(Object ticket) {
    return ticket == request && task != null && !closed;
  }

  private void delivered(Object ticket, long handed) {
    if (!ownsPlanning(ticket) || delivery != handed || preparedReady) return;
    // A receipt cannot prove normal worker return or successful executor admission.
    preparedReady = true;
  }

  void acceptPlanning() {
    acceptPlanning(() -> true);
  }

  /** Current selection and host controls decide before installing this exact catalog/closure. */
  void acceptPlanning(BooleanSupplier shown) {
    Object ticket = request;
    refresh();
    if (!ownsPlanning(ticket) || !preparedReady || !admitted) return;
    Objects.requireNonNull(shown);
    var completed = BackgroundFailure.completed(task);
    if (!ownsPlanning(ticket) || completed == null) return;
    if (completed.failure() != null) {
      failPlanning(ticket, completed.failure());
      return;
    }
    var value = handoff.take();
    if (value == null) {
      failPlanning(ticket, new IllegalStateException("Export preview preparation unavailable"));
      return;
    }
    publish(ticket, value.value(), value.failure(), shown);
  }

  private boolean planningCurrent(Object ticket, BooleanSupplier shown) {
    if (!ownsPlanning(ticket)
        || !planningOwner.valuesCurrent(state)
        || !current()
        || !sourceValue.equals(ProjectIO.JSON.valueToTree(state.project()))) return false;
    boolean visible = shown.getAsBoolean();
    return visible
        && ownsPlanning(ticket)
        && planningOwner.valuesCurrent(state)
        && current()
        && sourceValue.equals(ProjectIO.JSON.valueToTree(state.project()));
  }

  private void publish(Object ticket, Prepared result, Throwable failure, BooleanSupplier shown) {
    if (!ownsPlanning(ticket)) return;
    refresh();
    if (!ownsPlanning(ticket)) return;
    try {
      if (!planningCurrent(ticket, shown)) {
        if (!ownsPlanning(ticket)) return;
        stalePlanning();
        return;
      }
      if (failure != null) {
        failPlanning(ticket, failure);
        return;
      }
      boolean unavailable = blocked.getAsBoolean();
      if (!ownsPlanning(ticket)) return;
      if (unavailable || !planningCurrent(ticket, shown)) {
        if (!ownsPlanning(ticket)) return;
        stalePlanning();
        return;
      }
      Objects.requireNonNull(result);
      Object completed = retirePlanning();
      if (closed || completed != request) return;
      catalog = result.catalog();
      catalogSource = frozen;
      model = result.model();
      preview = result.preview();
      this.unavailable = result.unavailable();
      planReady = true;
      this.shown = null;
      planningMessage = "";
      if (preview != null) preparations++;
    } catch (Throwable rejected) {
      failPlanning(ticket, rejected);
    }
  }

  private void refresh() {
    if (task == null || closed) return;
    Object ticket = request;
    try {
      if (!planningOwner.current(state)) {
        stalePlanning();
        return;
      }
      var completion = BackgroundFailure.completed(task);
      if (ownsPlanning(ticket) && completion != null && completion.failure() != null)
        failPlanning(ticket, completion.failure());
    } catch (Throwable failure) {
      failPlanning(ticket, failure);
    }
  }

  private void stalePlanning() {
    Object invalidated = retirePlanning();
    if (closed || invalidated != request) return;
    if (!sourceValue.equals(ProjectIO.JSON.valueToTree(state.project()))) forcedStale = true;
    planningMessage =
        "The source, private input or pending action changed. Reload or Retry preview before"
            + " Export.";
  }

  private void failPlanning(Object ticket, Throwable failure) {
    if (ticket != request || closed) return;
    Object failed = retirePlanning();
    if (closed || failed != request) return;
    String feedback =
        "Export preview preparation failed: "
            + BackgroundFailure.describe(failure)
            + ". Retry preview or Cancel.";
    if (closed || failed != request) return;
    planningMessage = feedback;
    try {
      state.error("EXPORT_PREVIEW_FAILED", EditorEvents.Subsystem.IMPORT_EXPORT, planningMessage);
    } catch (Throwable ignored) {
      // Feedback cannot strand the workspace or acknowledge an older preview.
    }
  }

  /** Keep the identity reserved before task cancellation can enter another planning request. */
  private Object retirePlanning() {
    var previous = task;
    Object retired = new Object();
    request = retired;
    task = null;
    planningOwner = null;
    preparedReady = admitted = false;
    handoff.advance(++delivery);
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
    return retired;
  }

  ResourceExportSession preparedModel() {
    refresh();
    if (!current() || !planReady || preview == null)
      throw new IllegalStateException("Review a current export selection first");
    if (executor != null) {
      ResourceExportSession.Preview actual = null;
      try {
        actual = model.preview();
      } catch (IllegalStateException cleared) {
        /* Rebuild explicitly. */
      }
      if (actual != preview) {
        schedule();
        throw new IllegalStateException(
            "The export preview changed. Review its background replacement first");
      }
      return model;
    }
    model.prepare(frozen, 0, selected);
    if (model.preview() != preview) {
      preview = model.preview();
      shown = null;
      preparations++;
    }
    return model;
  }

  void markShown(ResourceExportSession.Preview value) {
    refresh();
    if (current() && planReady && value != null && value == preview) shown = value;
  }

  boolean canExport() {
    refresh();
    return current()
        && planReady
        && !selected.isEmpty()
        && preview != null
        && preview.selected().equals(selected)
        && shown == preview;
  }

  ProjectData exportSnapshot(ResourceExportSession.Preview expected) {
    requireExportable(expected);
    return expected.resources();
  }

  void publishPrepared(
      ResourceExportSession.Preview expected, ResourcePackageFiles.Prepared prepared)
      throws IOException {
    requireExportable(expected);
    writing = true;
    try {
      prepared.publish(state.document::path);
      closed = true;
      clearHistory();
      handoff.close();
      shown = null;
    } finally {
      writing = false;
    }
  }

  private void requireExportable(ResourceExportSession.Preview expected) {
    refresh();
    if (writing) throw new IllegalStateException("Export publication is already in progress");
    if (!current() || !sourceValue.equals(ProjectIO.JSON.valueToTree(state.project()))) {
      if (!closed) forcedStale = true;
      throw new IllegalStateException("Export source changed; Reload Preview before exporting");
    }
    if (selected.isEmpty()) throw new IllegalStateException("Select resources to export");
    if (!planReady
        || expected == null
        || preview != expected
        || shown != expected
        || !preview.selected().equals(selected))
      throw new IllegalStateException("Display the current export preview before exporting");
  }

  void write(Path destination, ResourcePackageFiles files) throws IOException {
    requireExportable(preview);
    writing = true;
    try {
      files.writeGuarded(destination, preview.resources(), state.document::path);
      closed = true;
      clearHistory();
      handoff.close();
      shown = null;
    } finally {
      writing = false;
    }
  }

  void cancel() {
    if (writing) return;
    closed = true;
    clearHistory();
    retirePlanning();
    handoff.close();
    shown = null;
  }
}
