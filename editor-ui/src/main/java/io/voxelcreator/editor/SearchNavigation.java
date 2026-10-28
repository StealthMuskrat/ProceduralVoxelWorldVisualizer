package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.*;

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
    clear();
    if (!state.canNavigate(target)) return Result.UNAVAILABLE;
    if (!Objects.equals(state.selected, target.resource()) && modified()) {
      pending = target;
      source = state.selected;
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
    if (pending == null) return Result.UNAVAILABLE;
    if (decision == Decision.CANCEL) {
      clear();
      return Result.CANCELLED;
    }
    var target = pending;
    if (!Objects.equals(state.selected, source)
        || seedVersion != state.seedInputs.version(state)
        || !draftBase.equals(ProjectIO.JSON.valueToTree(state.draft))
        || !activeBase.equals(active())
        || !state.canNavigate(target)) {
      clear();
      return Result.UNAVAILABLE;
    }
    if (decision == Decision.APPLY && !state.applyDraft()) return Result.NEEDS_DECISION;
    clear();
    return state.navigate(target) ? Result.NAVIGATED : Result.UNAVAILABLE;
  }

  public void clear() {
    pending = null;
    source = null;
    draftBase = null;
    activeBase = null;
  }
}
