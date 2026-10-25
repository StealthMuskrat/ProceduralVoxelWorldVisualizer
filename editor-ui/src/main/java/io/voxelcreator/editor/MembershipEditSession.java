package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Membership;
import java.util.*;

/** Temporary membership selection; Apply changes only members in one document transaction. */
public final class MembershipEditSession {
  private final UUID owner;
  private final JsonNode activeBase, draftBase;
  private final List<UUID> values;
  private final Set<UUID> selected;
  private long revision;

  public MembershipEditSession(EditorState state, Membership draft) {
    var active = ResourceGraph.find(state.project(), draft.uuid);
    if (active == null || !(active.resource() instanceof Membership))
      throw new IllegalArgumentException("Membership resource no longer exists");
    owner = draft.uuid;
    activeBase = ProjectIO.JSON.valueToTree(active.resource());
    draftBase = ProjectIO.JSON.valueToTree(draft);
    values = new ArrayList<>(draft.voxelTypeUuids);
    selected = new HashSet<>(values);
  }

  public UUID owner() {
    return owner;
  }

  public long revision() {
    return revision;
  }

  public int count() {
    return selected.size();
  }

  public boolean contains(UUID id) {
    return selected.contains(id);
  }

  public List<UUID> values() {
    return List.copyOf(values);
  }

  public void set(UUID id, boolean include) {
    Objects.requireNonNull(id);
    if (include) {
      if (selected.add(id)) {
        values.add(id);
        revision++;
      }
    } else if (selected.remove(id)) {
      values.removeIf(id::equals);
      revision++;
    }
  }

  public void setVisible(List<ResourceTreeIndex.Node> visible, boolean include) {
    for (var node : visible) {
      if (!node.kind().equals("voxel"))
        throw new IllegalArgumentException("Membership accepts voxel types only");
    }
    for (var node : visible) set(node.uuid(), include);
  }

  public boolean apply(EditorState state) {
    var active = ResourceGraph.find(state.project(), owner);
    if (active == null
        || !activeBase.equals(ProjectIO.JSON.valueToTree(active.resource()))
        || !(state.draft instanceof Membership draft)
        || !draft.uuid.equals(owner)
        || !draftBase.equals(ProjectIO.JSON.valueToTree(draft)))
      throw new IllegalStateException("Membership changed elsewhere; Cancel and reopen the editor");
    var valid = new HashSet<UUID>();
    state.project().voxelTypes.forEach(t -> valid.add(t.uuid));
    if (!valid.containsAll(values))
      throw new IllegalArgumentException("A selected voxel type no longer exists");
    var candidate = values();
    boolean applied =
        state.change(
            p -> {
              var target = ResourceGraph.find(p, owner);
              if (target == null || !(target.resource() instanceof Membership m))
                throw new IllegalArgumentException("Membership resource no longer exists");
              m.voxelTypeUuids = new ArrayList<>(candidate);
            });
    if (applied) draft.voxelTypeUuids = new ArrayList<>(candidate);
    return applied;
  }
}
