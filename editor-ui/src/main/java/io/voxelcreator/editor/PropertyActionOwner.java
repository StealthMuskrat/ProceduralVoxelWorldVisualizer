package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.model.ProjectData.Identity;
import java.util.*;

/** Frame-local footer ownership; mutable draft contents do not create a replacement owner. */
final class PropertyActionOwner {
  private ProjectData project;
  private Object lifetime, draft;
  private UUID selected;
  private String kind;
  private long revision, version;
  private int count;
  private boolean blocked, sole;

  long synchronize(EditorState state, boolean blocked) {
    if (!current(state, blocked)) {
      project = state.project();
      lifetime = state.document.sessionToken();
      revision = state.revision;
      draft = state.draft;
      selected = state.selected;
      kind = state.selectedKind;
      count = state.multiSelection.size();
      sole = count == 1 && state.multiSelection.contains(selected);
      this.blocked = blocked;
      version++;
    }
    return version;
  }

  boolean current(EditorState state, boolean blocked) {
    return version != 0
        && project == state.project()
        && lifetime == state.document.sessionToken()
        && revision == state.revision
        && draft == state.draft
        && Objects.equals(selected, state.selected)
        && Objects.equals(kind, state.selectedKind)
        && count == state.multiSelection.size()
        && sole
            == (state.multiSelection.size() == 1 && state.multiSelection.contains(state.selected))
        && this.blocked == blocked;
  }

  boolean editable(EditorState state, boolean blocked) {
    return !blocked
        && current(state, false)
        && sole
        && state.draft instanceof Identity resource
        && Objects.equals(resource.uuid, state.selected);
  }

  long version() {
    return version;
  }
}
