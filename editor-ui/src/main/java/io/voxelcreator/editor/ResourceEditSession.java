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
  private ResourceReviewContext context;
  private long seedVersion;
  private boolean executing;
  private final Runnable afterReconcile;
  private UUID selected;
  private Object draft;
  private JsonNode draftValue;
  private List<UUID> selection;

  ResourceEditSession(EditorState state) {
    this(state, () -> {});
  }

  ResourceEditSession(EditorState state, Runnable afterReconcile) {
    this.state = Objects.requireNonNull(state);
    this.afterReconcile = Objects.requireNonNull(afterReconcile);
  }

  boolean busy() {
    return executing;
  }

  boolean pending() {
    return edit != null;
  }

  String label() {
    return label;
  }

  Result request(String label, ResourceBrowserEdits.Edit edit) {
    if (executing || pending()) return Result.UNAVAILABLE;
    Objects.requireNonNull(label);
    this.label = label.length() > 240 ? label.substring(0, 239) + "…" : label;
    this.edit = Objects.requireNonNull(edit);
    context = ResourceReviewContext.capture(state);
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
    if (executing || !pending()) return Result.UNAVAILABLE;
    if (decision == SearchNavigation.Decision.CANCEL) {
      clear();
      return Result.CANCELLED;
    }
    if (!context.current(state)
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
    executing = true;
    try {
      var acceptedEdit = edit;
      ResourceBrowserEdits.Selection[] destination = {null};
      java.util.function.Consumer<ProjectData> change = p -> destination[0] = acceptedEdit.apply(p);
      boolean accepted;
      try {
        accepted = applyDraft ? state.changeWithDraft(change) : state.change(change);
      } catch (RuntimeException failure) {
        report("RESOURCE_EDIT_FAILED", "Resource edit rejected: ", failure);
        return Result.REJECTED;
      }
      if (!accepted) return Result.REJECTED;
      var requested = destination[0];
      retire();
      try {
        reconcile(requested);
        afterReconcile.run();
      } catch (RuntimeException failure) {
        report(
            "RESOURCE_EDIT_RECONCILE_FAILED",
            "Resource edit committed; selection refresh failed: ",
            failure);
      }
      return Result.APPLIED;
    } finally {
      executing = false;
    }
  }

  private void report(String code, String prefix, RuntimeException failure) {
    state.error(
        code,
        EditorEvents.Subsystem.PROJECT,
        StatusBarText.clip(
            prefix + Objects.requireNonNullElse(failure.getMessage(), failure.toString()), 1024));
  }

  private void reconcile(ResourceBrowserEdits.Selection requested) {
    state.reconcileSelection(
        requested == null ? state.multiSelection : requested.ids(),
        requested == null ? state.selected : requested.active());
  }

  void clear() {
    if (executing) return;
    retire();
  }

  private void retire() {
    edit = null;
    label = "";
    context = null;
    draft = null;
    draftValue = null;
    selection = null;
    selected = null;
  }
}
