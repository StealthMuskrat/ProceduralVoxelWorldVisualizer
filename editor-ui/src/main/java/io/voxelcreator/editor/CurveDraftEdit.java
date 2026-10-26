package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** A curve dialog may stage only into the exact draft/source that opened it. */
public final class CurveDraftEdit {
  private final ProjectData project;
  private final long revision;
  private final Noise owner;
  private final Item item;
  private final JsonNode base;
  private final boolean present;

  public CurveDraftEdit(EditorState state, Noise owner, Item item) {
    if (state.draft != owner
        || !Objects.equals(state.selected, owner.uuid)
        || !owner.processing.contains(item)
        || !"CURVE".equals(item.kind))
      throw new IllegalArgumentException("Select the curve's current Properties draft");
    project = state.project();
    revision = state.revision;
    this.owner = owner;
    this.item = item;
    present = item.parameters.containsKey("curve");
    var value = item.parameters.get("curve");
    base = value == null ? null : value.deepCopy();
  }

  public void stage(EditorState state, Curve curve) {
    if (state.project() != project
        || state.revision != revision
        || state.draft != owner
        || !Objects.equals(state.selected, owner.uuid)
        || !owner.processing.contains(item)
        || !"CURVE".equals(item.kind)
        || present != item.parameters.containsKey("curve")
        || !Objects.equals(base, item.parameters.get("curve")))
      throw new IllegalStateException("Curve source changed; cancel and reopen the editor");
    new ScalarCurve(curve);
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
  }
}
