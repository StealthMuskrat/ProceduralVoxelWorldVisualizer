package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.util.*;

/** Owned editor import: changed destinations require explicit reload and a displayed preview. */
final class ImportEditSession {
  enum Result {
    APPLIED,
    REJECTED,
    STALE,
    NOT_REVIEWED,
    CLOSED
  }

  private final EditorState state;
  private final ProjectData incoming;
  private final UUID projectId;
  private ProjectData destination;
  private long revision;
  private ResourceImport.Session model;
  private ResourceImport.Plan shown;
  private Map<UUID, ResourceImport.Choice> choices = Map.of();
  private boolean closed, forcedStale;
  private List<Diagnostic> applyIssues = List.of();
  private int issueCount;

  ImportEditSession(EditorState state, ProjectData incoming) {
    this.state = Objects.requireNonNull(state);
    this.incoming = ProjectIO.copy(Objects.requireNonNull(incoming));
    projectId = state.project().project.uuid;
    reload();
  }

  boolean current() {
    return !closed && !forcedStale && state.project() == destination && state.revision == revision;
  }

  boolean canReload() {
    return !closed && Objects.equals(projectId, state.project().project.uuid);
  }

  boolean closed() {
    return closed;
  }

  ResourceImport.Plan preview() {
    return model.preview();
  }

  Map<UUID, ResourceImport.Choice> choices() {
    return choices;
  }

  ResourceImport.Choice choice(UUID id) {
    return choices().getOrDefault(id, ResourceImport.Choice.COPY);
  }

  void choose(UUID id, ResourceImport.Choice choice) {
    if (!current()) throw new IllegalStateException("Reload the changed import destination first");
    var previous = choice(id);
    model.choose(id, choice);
    if (previous != choice) {
      choices = model.choices();
      shown = null;
      applyIssues = List.of();
      issueCount = 0;
    }
  }

  void reload() {
    if (!canReload())
      throw new IllegalStateException("The project was replaced; reopen Import for this project");
    var previous = choices;
    var next = new ResourceImport.Session(state.project(), incoming);
    previous.forEach(next::choose);
    model = next;
    choices = next.choices();
    destination = state.project();
    revision = state.revision;
    forcedStale = false;
    shown = null;
    applyIssues = List.of();
    issueCount = 0;
  }

  void markShown(ResourceImport.Plan preview) {
    if (current() && preview == model.preview()) shown = preview;
  }

  boolean canApply() {
    return current() && shown == model.preview() && shown != null && shown.valid();
  }

  List<Diagnostic> applyIssues() {
    return applyIssues;
  }

  int issueCount() {
    return issueCount;
  }

  Result apply() {
    if (closed) return Result.CLOSED;
    if (!current() || !model.current(state.project())) {
      forcedStale = true;
      return Result.STALE;
    }
    if (shown != model.preview()) return Result.NOT_REVIEWED;
    if (!shown.valid()) return Result.REJECTED;
    // Import only appends/reuses resources. Preserve the exact separate Properties draft/selection.
    var result = state.changeResult(model::applyTo);
    if (!result.applied()) {
      issueCount = result.issueCount();
      applyIssues = result.issues();
      return Result.REJECTED;
    }
    closed = true;
    shown = null;
    return Result.APPLIED;
  }

  void cancel() {
    closed = true;
    shown = null;
    applyIssues = List.of();
    issueCount = 0;
  }
}
