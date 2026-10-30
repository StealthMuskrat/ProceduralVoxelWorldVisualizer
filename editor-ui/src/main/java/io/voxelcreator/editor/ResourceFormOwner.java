package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.util.Objects;
import java.util.UUID;

/** Transient browser form identity; a form never adopts a replacement project or later revision. */
final class ResourceFormOwner {
  private final ProjectData project;
  private final long revision;
  private final String nativeId = UUID.randomUUID().toString();
  private boolean open = true;

  ResourceFormOwner(EditorState state) {
    Objects.requireNonNull(state);
    project = state.project();
    revision = state.revision;
  }

  boolean current(EditorState state) {
    return open && state.project() == project && state.revision == revision;
  }

  void close() {
    open = false;
  }

  String nativeId() {
    return nativeId;
  }
}
