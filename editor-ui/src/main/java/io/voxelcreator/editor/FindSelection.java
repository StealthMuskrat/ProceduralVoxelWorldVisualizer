package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.util.*;

/** A Find activation owns an actual committed hit and its opening document/index context. */
final class FindSelection {
  private EditorState editor;
  private Object lifetime;
  private ProjectData project;
  private UUID projectId;
  private long revision;
  private ResourceSearchIndex.Hit selected;

  void synchronize(EditorState state) {
    if (current(state)) return;
    editor = state;
    lifetime = state.document.sessionToken();
    project = state.project();
    projectId = project.project.uuid;
    revision = state.revision;
    selected = null;
  }

  boolean current(EditorState state) {
    return editor == state
        && lifetime == state.document.sessionToken()
        && project == state.project()
        && Objects.equals(projectId, state.project().project.uuid)
        && revision == state.revision;
  }

  void select(EditorState state, ResourceSearchIndex.Hit hit, List<ResourceSearchIndex.Hit> shown) {
    if (!current(state) || shown.stream().noneMatch(row -> row == hit))
      throw new IllegalStateException("Select a current displayed Find result");
    selected = Objects.requireNonNull(hit);
  }

  ResourceSearchIndex.Hit hit() {
    return selected;
  }

  boolean owns(EditorState state, ResourceSearchIndex.Hit hit) {
    return current(state) && selected == hit && hit != null;
  }

  void clear() {
    editor = null;
    lifetime = null;
    project = null;
    selected = null;
  }
}
