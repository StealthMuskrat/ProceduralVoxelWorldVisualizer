package io.voxelcreator.editor;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.io.IOException;
import java.util.*;

/** Owned editor import: changed destinations require explicit reload and a displayed preview. */
final class ImportEditSession {
  enum Result {
    APPLIED,
    REJECTED,
    STALE,
    NOT_REVIEWED,
    BUSY,
    CLOSED
  }

  /** Privately owned initial plan; worker construction never touches the live editor. */
  static final class Prepared {
    private final ProjectData incoming;
    private final ResourceImport.Session model;
    private boolean consumed;

    private Prepared(ProjectData incoming, ResourceImport.Session model) {
      this.incoming = incoming;
      this.model = model;
    }
  }

  static Prepared prepare(ProjectData destination, ProjectData incoming) throws IOException {
    var owned = ProjectIO.copy(Objects.requireNonNull(incoming));
    var issues = NoiseCompiler.validate(owned);
    if (!issues.isEmpty())
      throw new IOException("Invalid import resources: " + issues.stream().limit(8).toList());
    var model = new ResourceImport.Session(destination, owned);
    model.preview();
    return new Prepared(owned, model);
  }

  private final EditorState state;
  private final ProjectData incoming;
  private final UUID projectId;
  private final Object document;
  private ProjectData destination;
  private long revision;
  private ResourceImport.Session model;
  private ResourceImport.Plan shown;
  private Map<UUID, ResourceImport.Choice> choices = Map.of();
  private boolean closed, forcedStale, applying;
  private List<Diagnostic> applyIssues = List.of();
  private int issueCount;

  ImportEditSession(EditorState state, ProjectData incoming) {
    this.state = Objects.requireNonNull(state);
    this.incoming = ProjectIO.copy(Objects.requireNonNull(incoming));
    projectId = state.project().project.uuid;
    document = state.document.sessionToken();
    reload();
  }

  ImportEditSession(EditorState state, Prepared prepared) {
    this.state = Objects.requireNonNull(state);
    Objects.requireNonNull(prepared);
    if (prepared.consumed || !prepared.model.current(state.project()))
      throw new IllegalStateException("Import preparation was consumed or the destination changed");
    incoming = prepared.incoming;
    projectId = state.project().project.uuid;
    document = state.document.sessionToken();
    destination = state.project();
    revision = state.revision;
    model = prepared.model;
    choices = model.choices();
    prepared.consumed = true;
  }

  boolean current() {
    return !closed
        && !applying
        && !forcedStale
        && document == state.document.sessionToken()
        && Objects.equals(projectId, state.project().project.uuid)
        && state.project() == destination
        && state.revision == revision;
  }

  boolean canReload() {
    return !closed
        && !applying
        && document == state.document.sessionToken()
        && Objects.equals(projectId, state.project().project.uuid);
  }

  boolean busy() {
    return applying;
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
    if (applying) return Result.BUSY;
    if (!current() || !model.current(state.project())) {
      forcedStale = true;
      return Result.STALE;
    }
    if (shown != model.preview()) return Result.NOT_REVIEWED;
    if (!shown.valid()) return Result.REJECTED;
    // Import only appends/reuses resources. Preserve the exact separate Properties draft/selection.
    applying = true;
    try {
      var result = state.changeResult(model::applyTo);
      if (!result.applied()) {
        issueCount = result.issueCount();
        applyIssues = result.issues();
        return Result.REJECTED;
      }
      closed = true;
      shown = null;
      return Result.APPLIED;
    } finally {
      applying = false;
    }
  }

  void cancel() {
    if (applying) return;
    closed = true;
    shown = null;
    applyIssues = List.of();
    issueCount = 0;
  }
}
