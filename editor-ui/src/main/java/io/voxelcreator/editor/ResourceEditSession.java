package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.*;

/** One stale-safe browser edit; failed candidates never dispose of private Properties input. */
final class ResourceEditSession {
  enum Result {
    APPLIED,
    NEEDS_DECISION,
    REJECTED,
    UNAVAILABLE,
    CANCELLED
  }

  private final EditorState state;
  private ResourceBrowserEdits.Edit edit;
  private String label = "";
  private ProjectData project;
  private long revision, seedVersion;
  private UUID selected;
  private Object draft;
  private JsonNode draftValue;
  private List<UUID> selection;

  ResourceEditSession(EditorState state) {
    this.state = state;
  }

  boolean pending() {
    return edit != null;
  }

  String label() {
    return label;
  }

  Result request(String label, ResourceBrowserEdits.Edit edit) {
    if (pending()) return Result.UNAVAILABLE;
    Objects.requireNonNull(label);
    this.label = label.length() > 240 ? label.substring(0, 239) + "…" : label;
    this.edit = Objects.requireNonNull(edit);
    project = state.project();
    revision = state.revision;
    selected = state.selected;
    draft = state.draft;
    draftValue = ProjectIO.JSON.valueToTree(draft);
    selection = List.copyOf(state.multiSelection);
    seedVersion = state.seedInputs.version(state);
    if (modified()) return Result.NEEDS_DECISION;
    var result = execute(false);
    // No modal was opened for an unmodified draft; a failed command is requested afresh.
    if (result != Result.APPLIED) clear();
    return result;
  }

  private boolean modified() {
    if (state.seedInputs.pending(state)) return true;
    if (state.draft == null) return false;
    var source = ResourceGraph.find(state.project(), state.selected);
    return source == null || !ProjectIO.JSON.valueToTree(source.resource()).equals(draftValue);
  }

  Result decide(SearchNavigation.Decision decision) {
    if (!pending()) return Result.UNAVAILABLE;
    if (decision == SearchNavigation.Decision.CANCEL) {
      clear();
      return Result.CANCELLED;
    }
    if (state.project() != project
        || state.revision != revision
        || !Objects.equals(selected, state.selected)
        || state.draft != draft
        || !selection.equals(List.copyOf(state.multiSelection))
        || !draftValue.equals(ProjectIO.JSON.valueToTree(state.draft))
        || seedVersion != state.seedInputs.version(state)) {
      clear();
      return Result.UNAVAILABLE;
    }
    return execute(decision == SearchNavigation.Decision.APPLY);
  }

  private Result execute(boolean applyDraft) {
    ResourceBrowserEdits.Selection[] destination = {null};
    java.util.function.Consumer<ProjectData> change = p -> destination[0] = edit.apply(p);
    boolean accepted;
    try {
      accepted = applyDraft ? state.changeWithDraft(change) : state.change(change);
    } catch (RuntimeException failure) {
      state.error(
          "RESOURCE_EDIT_FAILED",
          EditorEvents.Subsystem.PROJECT,
          Objects.requireNonNullElse(failure.getMessage(), failure.toString()));
      return Result.REJECTED;
    }
    if (!accepted) return Result.REJECTED;
    var requested = destination[0];
    clear();
    reconcile(requested);
    return Result.APPLIED;
  }

  private void reconcile(ResourceBrowserEdits.Selection requested) {
    var entries = new LinkedHashMap<UUID, ResourceGraph.Entry>();
    ResourceGraph.entries(state.project()).forEach(e -> entries.put(e.uuid(), e));
    var ids =
        new ArrayList<>(
            new LinkedHashSet<>(requested == null ? state.multiSelection : requested.ids()));
    ids.removeIf(id -> !entries.containsKey(id));
    UUID active = requested == null ? state.selected : requested.active();
    if (!ids.contains(active)) active = ids.isEmpty() ? null : ids.getLast();
    String scope = active == null ? "" : entries.get(active).scope();
    ids.removeIf(id -> !entries.get(id).scope().equals(scope));
    state.selectResources(ids, active, scope, true);
  }

  void clear() {
    edit = null;
    label = "";
    project = null;
    draft = null;
    draftValue = null;
    selection = null;
    selected = null;
  }
}
