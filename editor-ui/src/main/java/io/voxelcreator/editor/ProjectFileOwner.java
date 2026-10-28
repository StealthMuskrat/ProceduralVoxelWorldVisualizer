package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.nio.file.Path;
import java.util.*;

/** Exact UI/document ownership for a pending project decision or file path. */
final class ProjectFileOwner {
  private final ProjectData project;
  private final long revision, seedVersion;
  private final UUID selected;
  private final Object draft;
  private final JsonNode value;
  private final List<UUID> selection;
  private final Path path;
  private final boolean dirty;

  ProjectFileOwner(EditorState state) {
    project = state.project();
    revision = state.revision;
    selected = state.selected;
    draft = state.draft;
    value = ProjectIO.JSON.valueToTree(draft);
    selection = List.copyOf(state.multiSelection);
    seedVersion = state.seedInputs.version(state);
    path = state.document.path();
    dirty = state.document.dirty();
  }

  boolean current(EditorState state) {
    return project == state.project()
        && revision == state.revision
        && Objects.equals(selected, state.selected)
        && draft == state.draft
        && selection.equals(List.copyOf(state.multiSelection))
        && value.equals(ProjectIO.JSON.valueToTree(state.draft))
        && seedVersion == state.seedInputs.version(state)
        && Objects.equals(path, state.document.path())
        && dirty == state.document.dirty();
  }
}
