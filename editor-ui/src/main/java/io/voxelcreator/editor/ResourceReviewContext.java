package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.util.Objects;
import java.util.UUID;

/** Cheap exact committed context for a resource dialog gesture across an external gate. */
record ResourceReviewContext(
    Object document, ProjectData canonical, UUID projectId, long revision) {
  static ResourceReviewContext capture(EditorState state) {
    return new ResourceReviewContext(
        state.document.sessionToken(),
        state.project(),
        state.project().project.uuid,
        state.revision);
  }

  boolean current(EditorState state) {
    return document == state.document.sessionToken()
        && canonical == state.project()
        && Objects.equals(projectId, state.project().project.uuid)
        && revision == state.revision;
  }
}
