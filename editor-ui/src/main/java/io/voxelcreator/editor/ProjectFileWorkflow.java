package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One owned project transition; a failed or canceled save cannot retain a destructive action. */
final class ProjectFileWorkflow {
  enum Intent {
    NEW,
    OPEN,
    EXIT
  }

  enum Phase {
    IDLE,
    DRAFT,
    GUARD,
    OPEN,
    SAVE
  }

  enum Decision {
    SAVE,
    DISCARD,
    CANCEL
  }

  enum DraftDecision {
    APPLY,
    DISCARD,
    CANCEL
  }

  private final EditorState state;
  private final Runnable replaced, exit;
  private final BooleanSupplier blocked;
  private ProjectFileOwner owner;
  private boolean transition;
  private Phase phase = Phase.IDLE;
  private Intent pending;
  private String error = "";

  ProjectFileWorkflow(EditorState state, Runnable replaced, Runnable exit) {
    this(state, replaced, exit, () -> false);
  }

  ProjectFileWorkflow(
      EditorState state, Runnable replaced, Runnable exit, BooleanSupplier blocked) {
    this.state = Objects.requireNonNull(state);
    this.replaced = Objects.requireNonNull(replaced);
    this.exit = Objects.requireNonNull(exit);
    this.blocked = Objects.requireNonNull(blocked);
  }

  Phase phase() {
    return phase;
  }

  String error() {
    return error;
  }

  String action() {
    return pending == null
        ? "Save Project"
        : switch (pending) {
          case NEW -> "New Project";
          case OPEN -> "Open Project";
          case EXIT -> "Exit";
        };
  }

  void request(Intent intent) {
    if (phase != Phase.IDLE) return;
    if (blocked.getAsBoolean()) {
      error = "Finish or cancel the open editor or resource dialog before continuing.";
      state.error("PROJECT_TRANSITION_BLOCKED", EditorEvents.Subsystem.PROJECT, error);
      return;
    }
    error = "";
    pending = Objects.requireNonNull(intent);
    transition = true;
    owner = new ProjectFileOwner(state);
    if (modifiedDraft()) phase = Phase.DRAFT;
    else afterDraft();
  }

  private boolean modifiedDraft() {
    if (state.seedInputs.pending(state)) return true;
    if (state.draft == null) return false;
    var entry = ResourceGraph.find(state.project(), state.selected);
    return entry == null
        || !ProjectIO.JSON
            .valueToTree(entry.resource())
            .equals(ProjectIO.JSON.valueToTree(state.draft));
  }

  void decideDraft(DraftDecision decision) {
    if (phase != Phase.DRAFT) return;
    if (decision == DraftDecision.CANCEL) {
      cancel();
      return;
    }
    if (!current()) return;
    if (decision == DraftDecision.APPLY) {
      if (!state.applyDraft()) {
        error = "Properties Apply failed. Cancel to correct the draft, or choose Discard Draft.";
        return;
      }
      owner = new ProjectFileOwner(state);
    }
    error = "";
    // Discard grants permission for the transition; preserve raw text until it actually succeeds.
    afterDraft();
  }

  private void afterDraft() {
    if (state.document.dirty()) phase = Phase.GUARD;
    else complete();
  }

  void save(boolean as) {
    if (phase != Phase.IDLE) return;
    error = "";
    pending = null;
    transition = false;
    owner = new ProjectFileOwner(state);
    beginSave(as);
  }

  void decide(Decision decision) {
    if (phase != Phase.GUARD) return;
    if (decision == Decision.CANCEL) {
      cancel();
      return;
    }
    if (!current()) return;
    switch (decision) {
      case CANCEL -> cancel();
      case DISCARD -> complete();
      case SAVE -> beginSave(false);
    }
  }

  private void beginSave(boolean as) {
    if (as || state.document.path() == null) phase = Phase.SAVE;
    else {
      phase = Phase.IDLE;
      write(state.document.path().toString());
    }
  }

  void submit(String location) {
    if (phase == Phase.SAVE) write(location);
    else if (phase == Phase.OPEN) {
      if (!current()) return;
      try {
        var issues = state.document.open(path(location));
        if (!issues.isEmpty()) throw new IOException(issues.toString());
        state.revision++;
        state.selected = null;
        state.selectedKind = "";
        state.draft = null;
        state.seedInputs.synchronize(state);
        state.multiSelection.clear();
        state.issues = java.util.List.of();
        state.validationScope = "Committed project";
        phase = Phase.IDLE;
        error = "";
        owner = null;
        transition = false;
        replaced.run();
        state.notify("Information", "Project opened");
      } catch (Exception failure) {
        fail("OPEN", failure);
      }
    }
  }

  private static Path path(String location) throws IOException {
    if (location == null || location.isBlank()) throw new IOException("Enter a project file path");
    return Path.of(location);
  }

  private void write(String location) {
    if (!current()) return;
    try {
      state.document.save(path(location));
    } catch (Exception failure) {
      // A retry is a standalone save. Never perform an action whose required save failed.
      pending = null;
      transition = false;
      owner = phase == Phase.IDLE ? null : new ProjectFileOwner(state);
      fail("SAVE", failure);
      return;
    }
    state.notify("Information", "Project saved");
    error = "";
    owner = new ProjectFileOwner(state);
    complete();
  }

  private boolean current() {
    if (owner != null && owner.current(state) && (!transition || !blocked.getAsBoolean()))
      return true;
    pending = null;
    phase = Phase.IDLE;
    owner = null;
    transition = false;
    error = "The project, draft or open editor changed. Request the file action again.";
    state.error("STALE_PROJECT_FILE_ACTION", EditorEvents.Subsystem.PROJECT, error);
    return false;
  }

  private void fail(String operation, Exception failure) {
    error =
        operation
            + " failed: "
            + Objects.requireNonNullElse(failure.getMessage(), failure.toString());
    state.error("PROJECT_" + operation + "_FAILED", EditorEvents.Subsystem.PROJECT, error);
  }

  private void complete() {
    var intent = pending;
    pending = null;
    phase = Phase.IDLE;
    if (intent == null) {
      owner = null;
      transition = false;
      return;
    }
    switch (intent) {
      case NEW -> {
        state.newSession();
        replaced.run();
        owner = null;
        transition = false;
      }
      case OPEN -> phase = Phase.OPEN;
      case EXIT -> {
        owner = null;
        transition = false;
        exit.run();
      }
    }
  }

  void cancel() {
    pending = null;
    phase = Phase.IDLE;
    error = "";
    owner = null;
    transition = false;
  }
}
