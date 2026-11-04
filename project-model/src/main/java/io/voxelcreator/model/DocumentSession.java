package io.voxelcreator.model;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Atomic, bounded structural-delta transactions; view navigation never enters this history. */
public final class DocumentSession {
  private final ProjectIO persistence;

  public DocumentSession() {
    this(new ProjectIO());
  }

  DocumentSession(ProjectIO persistence) {
    this.persistence = Objects.requireNonNull(persistence);
  }

  private ProjectData active = new ProjectData();
  private Path path;
  private String saved = serialize(active);
  private Object checkpointReceipt = new Object();
  private final Deque<ProjectDelta> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private long historyBytes;
  private Object sessionToken = new Object();

  /** Opaque lifetime identity. Successful Open/New replaces it; edits/history/save retain it. */
  public Object sessionToken() {
    return sessionToken;
  }

  public long historyBytes() {
    return historyBytes;
  }

  private java.util.function.Function<ProjectData, List<Diagnostic>> validator =
      Validator::validate;

  public ProjectData data() {
    return active;
  }

  public Path path() {
    return path;
  }

  /** Candidate-only policy; edits/history retain its identity through publication checks. */
  public void setValidator(java.util.function.Function<ProjectData, List<Diagnostic>> v) {
    validator = v;
  }

  public boolean dirty() {
    return !saved.equals(serialize(active));
  }

  /** Candidate callbacks cannot publish across a changed source, checkpoint, history or policy. */
  public List<Diagnostic> edit(Consumer<ProjectData> change) {
    var owner = new TransactionOwner();
    var candidate = ProjectIO.JSON.convertValue(owner.values, ProjectData.class);
    change.accept(candidate);
    owner.requireManaged();
    var issues = owner.validation.apply(candidate);
    owner.requireCurrent();
    if (!issues.isEmpty()) return issues;
    var delta = ProjectDelta.between(owner.values, ProjectIO.JSON.valueToTree(candidate));
    publishEdit(candidate, delta);
    return List.of();
  }

  private void publishEdit(ProjectData candidate, ProjectDelta delta) {
    if (!delta.empty()) {
      for (var removed : redo) historyBytes -= removed.bytes();
      redo.clear();
      undo.push(delta);
      historyBytes += delta.bytes();
      while (undo.size() > 100 || historyBytes > (32L << 20) && undo.size() > 1)
        historyBytes -= undo.removeLast().bytes();
      active = candidate;
    }
  }

  /** Capture on the document owner thread; preparation may then run on a worker. */
  public EditRequest requestEdit(Consumer<ProjectData> change) {
    return new EditRequest(Objects.requireNonNull(change), null, null);
  }

  /**
   * Capture two candidate-only stages with an intermediate validation boundary. The final stage
   * runs only after that boundary accepts; the captured document policy then validates the result.
   */
  public EditRequest requestEdit(
      Consumer<ProjectData> initialChange,
      Function<ProjectData, List<Diagnostic>> intermediateValidation,
      Consumer<ProjectData> finalChange) {
    return new EditRequest(
        Objects.requireNonNull(initialChange),
        Objects.requireNonNull(intermediateValidation),
        Objects.requireNonNull(finalChange));
  }

  /**
   * Owned source values and captured validation policy. Change/validation run on the preparation
   * thread and must not read or mutate this live document. Publication is an owner-thread action.
   */
  public final class EditRequest {
    private final TransactionOwner source = new TransactionOwner();
    private final Consumer<ProjectData> change;
    private final Function<ProjectData, List<Diagnostic>> intermediateValidation;
    private final Consumer<ProjectData> finalChange;

    private EditRequest(
        Consumer<ProjectData> change,
        Function<ProjectData, List<Diagnostic>> intermediateValidation,
        Consumer<ProjectData> finalChange) {
      this.change = change;
      this.intermediateValidation = intermediateValidation;
      this.finalChange = finalChange;
    }

    private DocumentSession owner() {
      return DocumentSession.this;
    }

    public PreparedEdit prepare() {
      var candidate = ProjectIO.JSON.convertValue(source.values, ProjectData.class);
      change.accept(candidate);
      if (intermediateValidation != null) {
        var intermediate = List.copyOf(intermediateValidation.apply(candidate));
        if (!intermediate.isEmpty()) return new PreparedEdit(this, null, null, intermediate);
        finalChange.accept(candidate);
      }
      var issues = List.copyOf(source.validation.apply(candidate));
      if (!issues.isEmpty()) return new PreparedEdit(this, null, null, issues);
      // Freeze after validation: later changes to a retained callback input cannot alter the
      // candidate or undo delta after preparation returns. No mutable accessor escapes.
      var values = ProjectIO.JSON.valueToTree(candidate);
      var frozen = ProjectIO.JSON.convertValue(values, ProjectData.class);
      var delta = ProjectDelta.between(source.values, values);
      return new PreparedEdit(this, frozen, delta, List.of());
    }
  }

  /** Private validated candidate/delta; successful publication is single-use, including no-ops. */
  public static final class PreparedEdit {
    private final EditRequest request;
    private final ProjectData candidate;
    private final ProjectDelta delta;
    private final List<Diagnostic> diagnostics;
    private boolean consumed;

    private PreparedEdit(
        EditRequest request,
        ProjectData candidate,
        ProjectDelta delta,
        List<Diagnostic> diagnostics) {
      this.request = request;
      this.candidate = candidate;
      this.delta = delta;
      this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
      return diagnostics;
    }

    public boolean success() {
      return candidate != null && diagnostics.isEmpty();
    }
  }

  /** Owner-thread check for successful or rejected work; does not consume or validate again. */
  public boolean currentEdit(PreparedEdit prepared) {
    if (prepared == null || prepared.consumed || prepared.request.owner() != this) return false;
    try {
      prepared.request.source.requireCurrent();
      return true;
    } catch (IllegalStateException changed) {
      return false;
    }
  }

  /** Publish only against the exact captured source, checkpoint, history and validation policy. */
  public void applyEdit(PreparedEdit prepared) {
    Objects.requireNonNull(prepared);
    if (prepared.request.owner() != this)
      throw new IllegalStateException("Prepared edit belongs to another document");
    prepared.request.source.requireCurrent();
    if (prepared.consumed || !prepared.success())
      throw new IllegalStateException("Prepared edit is rejected or already consumed");
    prepared.consumed = true;
    publishEdit(prepared.candidate, prepared.delta);
  }

  public void undo() {
    step(undo, redo, false);
  }

  public void redo() {
    step(redo, undo, true);
  }

  private void step(Deque<ProjectDelta> source, Deque<ProjectDelta> destination, boolean forward) {
    if (source.isEmpty()) return;
    var owner = new TransactionOwner();
    var delta = source.peek();
    var candidate = delta.applySnapshot(owner.values, forward);
    var issues = owner.validation.apply(candidate);
    owner.requireCurrent();
    if (!issues.isEmpty()) throw new IllegalStateException("History candidate rejected: " + issues);
    destination.push(delta);
    source.pop();
    active = candidate;
  }

  /** Capture on the owner thread; rejects an empty Undo direction without preparing work. */
  public HistoryRequest requestUndo() {
    return new HistoryRequest(false);
  }

  /** Capture on the owner thread; rejects an empty Redo direction without preparing work. */
  public HistoryRequest requestRedo() {
    return new HistoryRequest(true);
  }

  /** Private captured history entry and validation policy; prepare may run on a worker. */
  public final class HistoryRequest {
    private final boolean forward;
    private final TransactionOwner source;
    private final ProjectDelta delta;

    private HistoryRequest(boolean forward) {
      this.forward = forward;
      delta = (forward ? redo : undo).peek();
      if (delta == null)
        throw new IllegalStateException("No " + (forward ? "Redo" : "Undo") + " entry");
      source = new TransactionOwner();
    }

    private DocumentSession owner() {
      return DocumentSession.this;
    }

    public PreparedHistory prepare() {
      var candidate = delta.applySnapshot(source.values, forward);
      var issues = List.copyOf(source.validation.apply(candidate));
      if (!issues.isEmpty()) return new PreparedHistory(this, null, issues);
      // Do not retain the mutable input a validation callback may have kept.
      return new PreparedHistory(this, ProjectIO.copy(candidate), List.of());
    }
  }

  /** Owned reconstructed/validated history candidate; successful publication is single-use. */
  public static final class PreparedHistory {
    private final HistoryRequest request;
    private final ProjectData candidate;
    private final List<Diagnostic> diagnostics;
    private boolean consumed;

    private PreparedHistory(
        HistoryRequest request, ProjectData candidate, List<Diagnostic> diagnostics) {
      this.request = request;
      this.candidate = candidate;
      this.diagnostics = List.copyOf(diagnostics);
    }

    public boolean success() {
      return candidate != null && diagnostics.isEmpty();
    }

    public List<Diagnostic> diagnostics() {
      return diagnostics;
    }
  }

  /**
   * Owner-thread check for accepted or rejected history work, without validation or consumption.
   */
  public boolean currentHistory(PreparedHistory prepared) {
    if (prepared == null || prepared.consumed || prepared.request.owner() != this) return false;
    try {
      prepared.request.source.requireCurrent();
      return (prepared.request.forward ? redo : undo).peek() == prepared.request.delta;
    } catch (IllegalStateException changed) {
      return false;
    }
  }

  /** Move the captured entry between stacks only after checking all live owner authority. */
  public void applyHistory(PreparedHistory prepared) {
    Objects.requireNonNull(prepared);
    if (prepared.request.owner() != this)
      throw new IllegalStateException("Prepared history belongs to another document");
    prepared.request.source.requireCurrent();
    var from = prepared.request.forward ? redo : undo;
    var to = prepared.request.forward ? undo : redo;
    if (prepared.consumed || !prepared.success() || from.peek() != prepared.request.delta)
      throw new IllegalStateException("Prepared history is rejected, consumed or superseded");
    prepared.consumed = true;
    to.push(prepared.request.delta);
    from.pop();
    active = prepared.candidate;
  }

  /** Owner-thread optimistic guard; this is not a lock or cross-thread document access policy. */
  private final class TransactionOwner {
    final ProjectData source = active;
    final Object lifetime = sessionToken;
    final Path sourcePath = path;
    final String checkpoint = saved;
    final Object receipt = checkpointReceipt;
    final java.util.function.Function<ProjectData, List<Diagnostic>> validation = validator;
    final ProjectDelta undoHead = undo.peek(), redoHead = redo.peek();
    final long bytes = historyBytes;
    final com.fasterxml.jackson.databind.JsonNode values = ProjectIO.JSON.valueToTree(source);

    void requireManaged() {
      if (source != active
          || lifetime != sessionToken
          || validation != validator
          || receipt != checkpointReceipt
          || !Objects.equals(sourcePath, path)
          || !Objects.equals(checkpoint, saved)
          || undoHead != undo.peek()
          || redoHead != redo.peek()
          || bytes != historyBytes)
        throw new IllegalStateException("Document ownership changed while preparing a transaction");
    }

    void requireCurrent() {
      requireManaged();
      if (!values.equals(ProjectIO.JSON.valueToTree(active)))
        throw new IllegalStateException("Document values changed while preparing a transaction");
    }
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }

  /** Captures the current validation policy without reading a file or changing this document. */
  public OpenRequest requestOpen(Path file) {
    return new OpenRequest(Objects.requireNonNull(file), validator);
  }

  /** Preparation may run on a worker; installation remains an explicit owner-thread action. */
  public final class OpenRequest {
    private final Path file;
    private final ProjectData source = active;
    private final Object lifetime = sessionToken;
    private final Path sourcePath = path;
    private final String sourceSaved = saved;
    private final Object sourceReceipt = checkpointReceipt;
    private final java.util.function.Function<ProjectData, List<Diagnostic>> validation;

    private OpenRequest(
        Path file, java.util.function.Function<ProjectData, List<Diagnostic>> validation) {
      this.file = file;
      this.validation = validation;
    }

    private DocumentSession owner() {
      return DocumentSession.this;
    }

    public PreparedOpen prepare() throws IOException {
      var load = persistence.load(file);
      if (!load.success()) return new PreparedOpen(this, null, null, load.diagnostics());
      var issues = List.copyOf(validation.apply(load.project()));
      if (!issues.isEmpty()) return new PreparedOpen(this, null, null, issues);
      return new PreparedOpen(this, load.project(), serialize(load.project()), List.of());
    }
  }

  /** Owned validated candidate with no mutable project accessor; publication is single-use. */
  public static final class PreparedOpen {
    private final OpenRequest request;
    private final ProjectData project;
    private final String checkpoint;
    private final List<Diagnostic> diagnostics;
    private boolean consumed;

    private PreparedOpen(
        OpenRequest request, ProjectData project, String checkpoint, List<Diagnostic> diagnostics) {
      this.request = request;
      this.project = project;
      this.checkpoint = checkpoint;
      this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
      return diagnostics;
    }

    public boolean success() {
      return project != null && diagnostics.isEmpty();
    }
  }

  /** Caller must first establish that its pending Open decision still owns the active document. */
  public void applyOpen(PreparedOpen prepared) {
    Objects.requireNonNull(prepared);
    var request = prepared.request;
    if (request.owner() != this
        || request.validation != validator
        || request.source != active
        || request.lifetime != sessionToken
        || request.sourceReceipt != checkpointReceipt
        || !Objects.equals(request.sourcePath, path)
        || !Objects.equals(request.sourceSaved, saved))
      throw new IllegalStateException("Prepared Open owner, document or validation policy changed");
    if (prepared.consumed || !prepared.success())
      throw new IllegalStateException("Prepared Open is rejected or already consumed");
    // All checks and fallible serialization precede replacing any document state.
    Object nextToken = new Object();
    prepared.consumed = true;
    replace(prepared.project, prepared.request.file, prepared.checkpoint, nextToken);
  }

  public List<Diagnostic> open(Path file) throws IOException {
    var prepared = requestOpen(file).prepare();
    if (!prepared.success()) return prepared.diagnostics();
    applyOpen(prepared);
    return List.of();
  }

  public void newProject() {
    replace(new ProjectData(), null);
  }

  private void replace(ProjectData p, Path f) {
    replace(p, f, serialize(p), new Object());
  }

  private void replace(ProjectData p, Path f, String nextSaved, Object nextToken) {
    Object nextReceipt = new Object();
    active = p;
    path = f;
    saved = nextSaved;
    checkpointReceipt = nextReceipt;
    sessionToken = nextToken;
    undo.clear();
    redo.clear();
    historyBytes = 0;
  }

  /** Captures an owned committed snapshot; only preparation may run on a worker. */
  public SaveRequest requestSave(Path file) {
    return new SaveRequest(Objects.requireNonNull(file));
  }

  public final class SaveRequest {
    private final Path file;
    private final ProjectData source = active;
    private final Object lifetime = sessionToken;
    private final Path sourcePath = path;
    private final String sourceSaved = saved;
    private final Object sourceReceipt = checkpointReceipt;
    private final java.util.function.Function<ProjectData, List<Diagnostic>> validation = validator;
    private final com.fasterxml.jackson.databind.JsonNode values =
        ProjectIO.JSON.valueToTree(active);
    private final ProjectData snapshot = ProjectIO.JSON.convertValue(values, ProjectData.class);

    private SaveRequest(Path file) {
      this.file = file;
    }

    private DocumentSession owner() {
      return DocumentSession.this;
    }

    public PreparedSave prepare() throws IOException {
      var candidate = ProjectIO.copy(snapshot);
      var validationValues = ProjectIO.JSON.valueToTree(candidate);
      var issues = List.copyOf(validation.apply(candidate));
      if (!issues.isEmpty()) throw new IOException(issues.toString());
      if (!validationValues.equals(ProjectIO.JSON.valueToTree(candidate)))
        throw new IOException(
            "Save validation changed the committed snapshot; validation must be read-only");
      return new PreparedSave(this, persistence.prepareSave(file, candidate));
    }
  }

  /** Close every unpublished preparation, including cancelled and stale results. */
  public static final class PreparedSave implements AutoCloseable {
    private final SaveRequest request;
    private final ProjectIO.StagedSave staged;

    private PreparedSave(SaveRequest request, ProjectIO.StagedSave staged) {
      this.request = request;
      this.staged = staged;
    }

    @Override
    public void close() throws IOException {
      staged.close();
    }
  }

  /** Checks precede atomic replacement; a newer completed checkpoint retains its own metadata. */
  public void applySave(PreparedSave prepared) throws IOException {
    Objects.requireNonNull(prepared);
    var request = prepared.request;
    if (request.owner() != this
        || request.validation != validator
        || request.source != active
        || request.lifetime != sessionToken
        || request.sourceReceipt != checkpointReceipt
        || !Objects.equals(request.sourcePath, path)
        || !Objects.equals(request.sourceSaved, saved)
        || !request.values.equals(ProjectIO.JSON.valueToTree(active)))
      throw new IllegalStateException("Prepared Save owner, document or validation policy changed");
    Object nextReceipt = new Object();
    String checkpoint = prepared.staged.publish();
    // Replacement already succeeded. Preserve newer New/Open/Save completion metadata rather than
    // relabelling it with this older path/checkpoint. Same-session later edits still remain dirty
    // against the exact bytes just written, without replacing their model or history.
    if (request.lifetime == sessionToken && request.sourceReceipt == checkpointReceipt) {
      path = request.file;
      saved = checkpoint;
      checkpointReceipt = nextReceipt;
    }
  }

  /**
   * Same owned snapshot, validation and atomic publication contract as prepared/background Save.
   */
  public void save(Path file) throws IOException {
    var prepared = requestSave(file).prepare();
    Throwable failure = null;
    try {
      applySave(prepared);
    } catch (IOException | RuntimeException | Error rejected) {
      failure = rejected;
      throw rejected;
    } finally {
      try {
        prepared.close();
      } catch (IOException | RuntimeException | Error cleanup) {
        if (failure == null) throw cleanup;
        if (cleanup != failure) failure.addSuppressed(cleanup);
      }
    }
  }

  private static String serialize(ProjectData p) {
    try {
      return ProjectIO.JSON.writeValueAsString(p);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ProjectData parse(String p) {
    try {
      return ProjectIO.JSON.readValue(p, ProjectData.class);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
