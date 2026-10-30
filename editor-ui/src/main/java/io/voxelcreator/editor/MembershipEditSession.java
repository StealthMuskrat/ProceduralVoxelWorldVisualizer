package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Membership;
import java.util.*;

/** Temporary membership selection; Apply changes only members in one document transaction. */
public final class MembershipEditSession {
  private final EditorState editor;
  private final Membership parent;
  private final UUID project;
  private final Object projectSession;
  private final String kind;
  private final List<UUID> selection;
  private final UUID owner;
  private final JsonNode activeBase, draftBase;
  private final List<UUID> values;
  private final Set<UUID> selected;
  private long revision;
  private boolean closed;
  private List<Diagnostic> applyIssues = List.of();
  private int issueCount;

  public MembershipEditSession(EditorState state, Membership draft) {
    if (state.draft != draft || !Objects.equals(state.selected, draft.uuid))
      throw new IllegalArgumentException("Select the membership's current Properties draft");
    var active = ResourceGraph.find(state.project(), draft.uuid);
    if (active == null || !(active.resource() instanceof Membership))
      throw new IllegalArgumentException("Membership resource no longer exists");
    editor = state;
    parent = draft;
    project = state.project().project.uuid;
    projectSession = state.document.sessionToken();
    kind = state.selectedKind;
    selection = List.copyOf(state.multiSelection);
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
    requireOpen();
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
    requireOpen();
    for (var node : visible) {
      if (!node.kind().equals("voxel"))
        throw new IllegalArgumentException("Membership accepts voxel types only");
    }
    for (var node : visible) set(node.uuid(), include);
  }

  public boolean apply(EditorState state) {
    requireOpen();
    if (!currentOwner(state))
      throw new IllegalStateException("Membership owner changed; Cancel and reopen the editor");
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
    var result =
        state.changeResult(
            p -> {
              var target = ResourceGraph.find(p, owner);
              if (target == null || !(target.resource() instanceof Membership m))
                throw new IllegalArgumentException("Membership resource no longer exists");
              m.voxelTypeUuids = new ArrayList<>(candidate);
            });
    if (result.applied()) {
      draft.voxelTypeUuids = new ArrayList<>(candidate);
      closed = true;
      applyIssues = List.of();
      issueCount = 0;
    } else {
      issueCount = result.issueCount();
      applyIssues = result.issues();
    }
    return result.applied();
  }

  /** Cheap draw-time ownership check; Apply also compares complete opening resource values. */
  boolean currentOwner(EditorState state) {
    return !closed
        && state == editor
        && state.document.sessionToken() == projectSession
        && Objects.equals(project, state.project().project.uuid)
        && state.draft == parent
        && Objects.equals(owner, state.selected)
        && Objects.equals(kind, state.selectedKind)
        && selection.equals(List.copyOf(state.multiSelection));
  }

  List<Diagnostic> applyIssues() {
    return applyIssues;
  }

  int issueCount() {
    return issueCount;
  }

  public boolean closed() {
    return closed;
  }

  public void cancel() {
    closed = true;
    applyIssues = List.of();
    issueCount = 0;
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Membership editor is closed");
  }
}
