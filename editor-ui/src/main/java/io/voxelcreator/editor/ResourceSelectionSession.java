package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.*;

/** Private browser selection intent. No gesture replaces a modified Properties draft implicitly. */
final class ResourceSelectionSession {
  enum Result {
    SELECTED,
    NEEDS_DECISION,
    UNAVAILABLE,
    CANCELLED
  }

  private record Plan(List<UUID> ids, UUID active, String scope) {}

  private final EditorState state;
  private Plan pending;
  private ProjectData project;
  private long revision, seedVersion;
  private UUID source;
  private Object draft;
  private JsonNode draftValue;
  private List<UUID> sourceSelection;

  ResourceSelectionSession(EditorState state) {
    this.state = state;
  }

  boolean pending() {
    return pending != null;
  }

  List<UUID> destinations() {
    return pending == null ? List.of() : pending.ids;
  }

  Result click(UUID id, boolean toggle) {
    var target = ResourceGraph.find(state.project(), id);
    if (target == null) return Result.UNAVAILABLE;
    var ids = new LinkedHashSet<UUID>();
    if (toggle && sameScope(state.multiSelection, target.scope())) ids.addAll(state.multiSelection);
    if (!toggle || !ids.remove(id)) ids.add(id);
    UUID active = ids.contains(id) ? id : ids.stream().reduce((a, b) -> b).orElse(null);
    return request(List.copyOf(ids), active);
  }

  Result request(List<UUID> ids, UUID active) {
    if (pending()) return Result.UNAVAILABLE;
    var plan = resolve(ids, active);
    if (plan == null) return Result.UNAVAILABLE;
    if (Objects.equals(active, state.selected) && ids.equals(List.copyOf(state.multiSelection)))
      return Result.SELECTED;
    if (modified()) {
      pending = plan;
      project = state.project();
      revision = state.revision;
      source = state.selected;
      draft = state.draft;
      draftValue = ProjectIO.JSON.valueToTree(draft);
      sourceSelection = List.copyOf(state.multiSelection);
      seedVersion = state.seedInputs.version(state);
      return Result.NEEDS_DECISION;
    }
    install(plan, false);
    return Result.SELECTED;
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

  private boolean sameScope(Collection<UUID> ids, String scope) {
    var remaining = new HashSet<>(ids);
    for (var entry : ResourceGraph.entries(state.project()))
      if (remaining.remove(entry.uuid()) && !entry.scope().equals(scope)) return false;
    return remaining.isEmpty();
  }

  private Plan resolve(List<UUID> ids, UUID active) {
    if (ids == null || new HashSet<>(ids).size() != ids.size()) return null;
    if (ids.isEmpty()) return active == null ? new Plan(List.of(), null, "") : null;
    if (active == null || !ids.contains(active)) return null;
    var entry = ResourceGraph.find(state.project(), active);
    if (entry == null || !sameScope(ids, entry.scope())) return null;
    return new Plan(List.copyOf(ids), active, entry.scope());
  }

  Result decide(SearchNavigation.Decision decision) {
    if (!pending()) return Result.UNAVAILABLE;
    if (decision == SearchNavigation.Decision.CANCEL) {
      clear();
      return Result.CANCELLED;
    }
    var plan = pending;
    if (state.project() != project
        || state.revision != revision
        || !Objects.equals(state.selected, source)
        || state.draft != draft
        || !sourceSelection.equals(List.copyOf(state.multiSelection))
        || !draftValue.equals(ProjectIO.JSON.valueToTree(state.draft))
        || seedVersion != state.seedInputs.version(state)
        || !plan.equals(resolve(plan.ids, plan.active))) {
      clear();
      return Result.UNAVAILABLE;
    }
    if (decision == SearchNavigation.Decision.APPLY && !state.applyDraft())
      return Result.NEEDS_DECISION;
    // Apply can change resource membership. Never install an obsolete destination afterwards.
    if (!plan.equals(resolve(plan.ids, plan.active))) {
      clear();
      return Result.UNAVAILABLE;
    }
    clear();
    install(plan, decision == SearchNavigation.Decision.DISCARD);
    return Result.SELECTED;
  }

  private void install(Plan plan, boolean discard) {
    state.selectResources(plan.ids, plan.active, plan.scope, discard);
  }

  void clear() {
    pending = null;
    project = null;
    source = null;
    draft = null;
    draftValue = null;
    sourceSelection = null;
  }
}
