package io.voxelcreator.model;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

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
    if (!delta.empty()) {
      for (var removed : redo) historyBytes -= removed.bytes();
      redo.clear();
      undo.push(delta);
      historyBytes += delta.bytes();
      while (undo.size() > 100 || historyBytes > (32L << 20) && undo.size() > 1)
        historyBytes -= undo.removeLast().bytes();
      active = candidate;
    }
    return List.of();
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

  /** Owner-thread optimistic guard; this is not a lock or cross-thread document access policy. */
  private final class TransactionOwner {
    final ProjectData source = active;
    final Object lifetime = sessionToken;
    final Path sourcePath = path;
    final String checkpoint = saved;
    final java.util.function.Function<ProjectData, List<Diagnostic>> validation = validator;
    final ProjectDelta undoHead = undo.peek(), redoHead = redo.peek();
    final long bytes = historyBytes;
    final com.fasterxml.jackson.databind.JsonNode values = ProjectIO.JSON.valueToTree(source);

    void requireManaged() {
      if (source != active
          || lifetime != sessionToken
          || validation != validator
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
    active = p;
    path = f;
    saved = nextSaved;
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
      var issues = List.copyOf(validation.apply(candidate));
      if (!issues.isEmpty()) throw new IOException(issues.toString());
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

  /** Checks precede atomic replacement; after replacement the exact written checkpoint is final. */
  public void applySave(PreparedSave prepared) throws IOException {
    Objects.requireNonNull(prepared);
    var request = prepared.request;
    if (request.owner() != this
        || request.validation != validator
        || request.source != active
        || request.lifetime != sessionToken
        || !Objects.equals(request.sourcePath, path)
        || !Objects.equals(request.sourceSaved, saved)
        || !request.values.equals(ProjectIO.JSON.valueToTree(active)))
      throw new IllegalStateException("Prepared Save owner, document or validation policy changed");
    String checkpoint = prepared.staged.publish();
    path = request.file;
    saved = checkpoint;
  }

  public void save(Path file) throws IOException {
    var issues = validator.apply(active);
    if (!issues.isEmpty()) throw new IOException(issues.toString());
    String nextSaved = persistence.saveAndGetCheckpoint(file, active);
    path = file;
    saved = nextSaved;
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
