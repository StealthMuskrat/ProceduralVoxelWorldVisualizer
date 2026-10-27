package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;

/** One owned project transition; a failed or canceled save cannot retain a destructive action. */
final class ProjectFileWorkflow {
  enum Intent {
    NEW,
    OPEN,
    EXIT
  }

  enum Phase {
    IDLE,
    GUARD,
    OPEN,
    SAVE
  }

  enum Decision {
    SAVE,
    DISCARD,
    CANCEL
  }

  private final EditorState state;
  private final Runnable replaced, exit;
  private Phase phase = Phase.IDLE;
  private Intent pending;
  private String error = "";

  ProjectFileWorkflow(EditorState state, Runnable replaced, Runnable exit) {
    this.state = Objects.requireNonNull(state);
    this.replaced = Objects.requireNonNull(replaced);
    this.exit = Objects.requireNonNull(exit);
  }

  Phase phase() {
    return phase;
  }

  String error() {
    return error;
  }

  void request(Intent intent) {
    if (phase != Phase.IDLE) return;
    error = "";
    pending = Objects.requireNonNull(intent);
    if (state.document.dirty()) phase = Phase.GUARD;
    else complete();
  }

  void save(boolean as) {
    if (phase != Phase.IDLE) return;
    error = "";
    pending = null;
    beginSave(as);
  }

  void decide(Decision decision) {
    if (phase != Phase.GUARD) return;
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
      try {
        var issues = state.document.open(path(location));
        if (!issues.isEmpty()) throw new IOException(issues.toString());
        state.revision++;
        state.selected = null;
        state.selectedKind = "";
        state.draft = null;
        state.multiSelection.clear();
        state.issues = java.util.List.of();
        state.validationScope = "Committed project";
        phase = Phase.IDLE;
        error = "";
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
    try {
      state.document.save(path(location));
      state.notify("Information", "Project saved");
      error = "";
      complete();
    } catch (Exception failure) {
      // A retry is a standalone save. Never perform an action whose required save failed.
      pending = null;
      fail("SAVE", failure);
    }
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
    if (intent == null) return;
    switch (intent) {
      case NEW -> {
        state.newSession();
        replaced.run();
      }
      case OPEN -> phase = Phase.OPEN;
      case EXIT -> exit.run();
    }
  }

  void cancel() {
    pending = null;
    phase = Phase.IDLE;
    error = "";
  }
}
