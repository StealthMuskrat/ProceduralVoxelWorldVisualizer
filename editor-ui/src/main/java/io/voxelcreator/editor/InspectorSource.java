package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.UUID;

/** Owner-only committed source guard; private Properties and entered coordinates stay separate. */
final class InspectorSource {
  private final ProjectData project;
  private final Object document;
  private final UUID id;
  private final long revision;
  private final JsonNode values;

  private InspectorSource(EditorState state, ProjectData snapshot) {
    project = state.project();
    document = state.document.sessionToken();
    id = project.project.uuid;
    revision = state.revision;
    values = ProjectIO.JSON.valueToTree(snapshot);
  }

  static InspectorSource capture(EditorState state, ProjectData snapshot) {
    return new InspectorSource(state, snapshot);
  }

  boolean current(EditorState state) {
    return project == state.project() && document == state.document.sessionToken()
        && id.equals(state.project().project.uuid) && revision == state.revision
        && values.equals(ProjectIO.JSON.valueToTree(state.project()));
  }
}
