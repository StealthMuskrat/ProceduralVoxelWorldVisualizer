package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** A curve dialog may stage only into the exact draft/source that opened it. */
public final class CurveDraftEdit {
  private final EditorState state;
  private final ProjectData project;
  private final long revision;
  private final UUID projectId, ownerId, itemId;
  private final String kind;
  private final List<UUID> selection;
  private final Noise owner;
  private final Item item;
  private final JsonNode source, base, projectBase;
  private final boolean present;
  private boolean closed;

  public CurveDraftEdit(EditorState state, Noise owner, Item item) {
    if (state.draft != owner
        || !Objects.equals(state.selected, owner.uuid)
        || !(EditorState.find(state.project(), owner.uuid) instanceof Noise)
        || !owner.processing.contains(item)
        || !"CURVE".equals(item.kind))
      throw new IllegalArgumentException("Select the curve's current Properties draft");
    this.state = state;
    project = state.project();
    revision = state.revision;
    projectId = project.project.uuid;
    ownerId = owner.uuid;
    itemId = item.uuid;
    kind = state.selectedKind;
    selection = List.copyOf(state.multiSelection);
    this.owner = owner;
    this.item = item;
    present = item.parameters.containsKey("curve");
    source = item.parameters.get("curve");
    base = source == null ? null : source.deepCopy();
    projectBase = ProjectIO.JSON.valueToTree(project);
  }

  /** Cheap frame-time guard; complete value checks remain at the publication boundary. */
  public boolean current(EditorState state) {
    return !closed
        && state == this.state
        && state.project() == project
        && state.revision == revision
        && Objects.equals(projectId, project.project.uuid)
        && state.draft == owner
        && Objects.equals(ownerId, owner.uuid)
        && Objects.equals(state.selected, ownerId)
        && Objects.equals(kind, state.selectedKind)
        && selection.equals(List.copyOf(state.multiSelection))
        && owner.processing.contains(item)
        && Objects.equals(itemId, item.uuid)
        && "CURVE".equals(item.kind)
        && present == item.parameters.containsKey("curve")
        && source == item.parameters.get("curve");
  }

  public void stage(EditorState state, Curve curve) {
    if (!current(state)
        || !Objects.equals(base, item.parameters.get("curve"))
        || !projectBase.equals(ProjectIO.JSON.valueToTree(state.project())))
      throw new IllegalStateException("Curve source changed; cancel and reopen the editor");
    var value = ProjectIO.JSON.valueToTree(Objects.requireNonNull(curve));
    var candidate = ProjectIO.JSON.convertValue(value, Curve.class);
    new ScalarCurve(candidate);
    if (base != null && !Objects.equals(base.path("uuid").asText(), candidate.uuid.toString()))
      throw new IllegalArgumentException("Curve identity changed; reopen the source curve");
    item.parameters.put("curve", value);
    closed = true;
  }

  public void cancel() {
    closed = true;
  }

  public boolean closed() {
    return closed;
  }
}
