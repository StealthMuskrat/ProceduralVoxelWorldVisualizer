package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.BlockPos;
import java.util.Objects;

/** A new current-document trace request at one immutable historical coordinate. */
record TraceCoordinateIntent(
    Object document, ProjectData project, long revision, TraceSnapshot snapshot) {
  TraceCoordinateIntent {
    Objects.requireNonNull(document);
    Objects.requireNonNull(project);
    Objects.requireNonNull(snapshot);
  }

  static TraceCoordinateIntent capture(EditorState state, TraceSnapshot snapshot) {
    return new TraceCoordinateIntent(
        state.document.sessionToken(), state.project(), state.revision, snapshot);
  }

  BlockPos position() {
    return snapshot.trace().position();
  }

  boolean current(EditorState state) {
    return document == state.document.sessionToken()
        && project == state.project()
        && revision == state.revision
        && snapshot.project().equals(state.project().project.uuid);
  }
}
