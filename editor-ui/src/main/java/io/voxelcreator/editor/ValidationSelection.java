package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.util.*;

/** A selected row belongs to one exact diagnostic publication and document lifetime. */
final class ValidationSelection {
  private EditorState editor;
  private Object lifetime;
  private ProjectData project;
  private UUID projectId, publication = UUID.randomUUID();
  private long revision;
  private String scope;
  private List<Diagnostic> source, rows = List.of();
  private int selected = -1;

  void synchronize(EditorState state) {
    if (current(state)) return;
    editor = state;
    lifetime = state.document.sessionToken();
    project = state.project();
    projectId = project.project.uuid;
    revision = state.revision;
    scope = state.validationScope;
    source = state.issues;
    rows = List.copyOf(source);
    selected = -1;
    publication = UUID.randomUUID();
  }

  boolean current(EditorState state) {
    return editor == state
        && lifetime == state.document.sessionToken()
        && project == state.project()
        && Objects.equals(projectId, state.project().project.uuid)
        && revision == state.revision
        && Objects.equals(scope, state.validationScope)
        && source == state.issues
        && rows.equals(state.issues);
  }

  List<Diagnostic> rows() {
    return rows;
  }

  String scope() {
    return scope;
  }

  String publication() {
    return publication.toString();
  }

  int index() {
    return selected;
  }

  void select(int index) {
    if (index < 0 || index >= rows.size())
      throw new IllegalArgumentException("Unknown diagnostic row");
    selected = index;
  }

  Diagnostic selected() {
    return selected < 0 ? null : rows.get(selected);
  }

  EditorEvents.Target target(EditorState state) {
    return current(state) && selected() != null
        ? EditorEvents.Target.diagnostic(projectId, selected())
        : null;
  }
}
