package io.voxelcreator.model;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Atomic, bounded snapshot transactions; view navigation never enters this history. */
public final class DocumentSession {
  private ProjectData active = new ProjectData();
  private Path path;
  private String saved = serialize(active);
  private final Deque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
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
    String before = serialize(active);
    if (!before.equals(serialize(candidate))) {
      undo.push(before);
      while (undo.size() > 100) undo.removeLast();
      redo.clear();
      active = candidate;
    }
    return List.of();
  }

  public void undo() {
    if (!undo.isEmpty()) {
      redo.push(serialize(active));
      active = parse(undo.pop());
    }
  }

  public void redo() {
    if (!redo.isEmpty()) {
      undo.push(serialize(active));
      active = parse(redo.pop());
    }
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
