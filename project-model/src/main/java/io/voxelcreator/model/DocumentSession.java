package io.voxelcreator.model;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Atomic, bounded structural-delta transactions; view navigation never enters this history. */
public final class DocumentSession {
  private ProjectData active = new ProjectData();
  private Path path;
  private String saved = serialize(active);
  private final Deque<ProjectDelta> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private long historyBytes;

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

  public void setValidator(java.util.function.Function<ProjectData, List<Diagnostic>> v) {
    validator = v;
  }

  public boolean dirty() {
    return !saved.equals(serialize(active));
  }

  public List<Diagnostic> edit(Consumer<ProjectData> change) {
    var candidate = ProjectIO.copy(active);
    change.accept(candidate);
    var issues = validator.apply(candidate);
    if (!issues.isEmpty()) return issues;
    var delta =
        ProjectDelta.between(
            ProjectIO.JSON.valueToTree(active), ProjectIO.JSON.valueToTree(candidate));
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
    var delta = source.peek();
    var candidate = delta.apply(active, forward);
    var issues = validator.apply(candidate);
    if (!issues.isEmpty()) throw new IllegalStateException("History candidate rejected: " + issues);
    destination.push(delta);
    source.pop();
    active = candidate;
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }

  public List<Diagnostic> open(Path file) throws IOException {
    var load = new ProjectIO().load(file);
    if (!load.success()) return load.diagnostics();
    var issues = validator.apply(load.project());
    if (!issues.isEmpty()) return issues;
    replace(load.project(), file);
    return List.of();
  }

  public void newProject() {
    replace(new ProjectData(), null);
  }

  private void replace(ProjectData p, Path f) {
    active = p;
    path = f;
    saved = serialize(p);
    undo.clear();
    redo.clear();
    historyBytes = 0;
  }

  public void save(Path file) throws IOException {
    var issues = validator.apply(active);
    if (!issues.isEmpty()) throw new IOException(issues.toString());
    new ProjectIO().save(file, active);
    path = file;
    saved = serialize(active);
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
