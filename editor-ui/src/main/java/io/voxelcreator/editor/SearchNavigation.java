package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Search-specific draft protection. A target always resolves UUIDs in the current project. */
public final class SearchNavigation {
  public enum Result {
    NAVIGATED,
    NEEDS_DECISION,
    UNAVAILABLE,
    CANCELLED
  }

  public enum Decision {
    APPLY,
    DISCARD,
    CANCEL
  }

  private final EditorState state;
  private EditorEvents.Target pending;
  private UUID source;
  private JsonNode draftBase, activeBase;
  private long seedVersion;
  private ProjectData project;
  private Object draftOwner;
  private List<UUID> selection;
  private long version;

  public SearchNavigation(EditorState state) {
    this.state = state;
  }

  public boolean pending() {
    return pending != null;
  }

  public EditorEvents.Target target() {
    return pending;
  }

  public Result request(EditorEvents.Target target) {
    if (pending != null) return Result.UNAVAILABLE;
    if (!state.canNavigate(target)) return Result.UNAVAILABLE;
    version++;
    if (!Objects.equals(state.selected, target.resource()) && modified()) {
      pending = target;
      source = state.selected;
      project = state.project();
      draftOwner = state.draft;
      selection = List.copyOf(state.multiSelection);
      draftBase = ProjectIO.JSON.valueToTree(state.draft);
      activeBase = active();
      seedVersion = state.seedInputs.version(state);
      return Result.NEEDS_DECISION;
    }
    return state.navigate(target) ? Result.NAVIGATED : Result.UNAVAILABLE;
  }

  private boolean modified() {
    if (state.seedInputs.pending(state)) return true;
    if (state.draft == null) return false;
    var active = ResourceGraph.find(state.project(), state.selected);
    return active == null
        || !ProjectIO.JSON
            .valueToTree(active.resource())
            .equals(ProjectIO.JSON.valueToTree(state.draft));
  }

  private JsonNode active() {
    var entry = ResourceGraph.find(state.project(), state.selected);
    return ProjectIO.JSON.valueToTree(entry == null ? null : entry.resource());
  }

  public Result decide(Decision decision) {
    return decide(decision, () -> true);
  }

  Result decide(Decision decision, BooleanSupplier afterApply) {
    if (pending == null) return Result.UNAVAILABLE;
    if (decision == Decision.CANCEL) {
      clear();
      return Result.CANCELLED;
    }
    var target = pending;
    if (state.project() != project
        || state.draft != draftOwner
        || !selection.equals(List.copyOf(state.multiSelection))
        || !Objects.equals(state.selected, source)
        || seedVersion != state.seedInputs.version(state)
        || !draftBase.equals(ProjectIO.JSON.valueToTree(state.draft))
        || !activeBase.equals(active())
        || !state.canNavigate(target)) {
      clear();
      return Result.UNAVAILABLE;
    }
    if (decision == Decision.APPLY && !state.applyDraft()) return Result.NEEDS_DECISION;
    // A post-Apply source check may open a successor; retire this intent before calling it.
    clear();
    if (decision == Decision.APPLY) {
      long completed = version, revision = state.revision;
      var document = state.document.sessionToken();
      var applied = state.project();
      var selected = state.selected;
      var draft = state.draft;
      var values = ProjectIO.JSON.valueToTree(draft);
      var selectedResources = List.copyOf(state.multiSelection);
      long inputs = state.seedInputs.version(state);
      if (!afterApply.getAsBoolean()
          || version != completed
          || state.document.sessionToken() != document
          || state.project() != applied
          || state.revision != revision
          || state.draft != draft
          || !Objects.equals(state.selected, selected)
          || !selectedResources.equals(List.copyOf(state.multiSelection))
          || state.seedInputs.version(state) != inputs
          || !values.equals(ProjectIO.JSON.valueToTree(state.draft))) return Result.UNAVAILABLE;
    }
    return state.navigate(target) ? Result.NAVIGATED : Result.UNAVAILABLE;
  }

  long version() {
    return version;
  }

  public void clear() {
    version++;
    pending = null;
    source = null;
    draftBase = null;
    activeBase = null;
    project = null;
    draftOwner = null;
    selection = null;
  }
}
