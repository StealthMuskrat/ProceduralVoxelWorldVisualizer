package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.util.Objects;
import java.util.UUID;

/** Transient browser form ownership and bounded history; replacement/revision never retargets it. */
final class ResourceFormOwner {
  private final ProjectData project;
  private final long revision;
  private final String nativeId = UUID.randomUUID().toString();
  private boolean open = true;
  private final TextEditHistory history = new TextEditHistory();
  private long inputVersion, restoration;

  record Ticket(ResourceFormOwner owner, long version) {}

  ResourceFormOwner(EditorState state) {
    Objects.requireNonNull(state);
    project = state.project();
    revision = state.revision;
  }

  boolean current(EditorState state) {
    return open && state.project() == project && state.revision == revision;
  }

  void close() {
    open = false;
    history.clear();
  }

  boolean record(EditorState state, String field, String before, String after, String gesture) {
    if (!current(state)) throw new IllegalStateException("Resource form changed; Cancel and reopen");
    Objects.requireNonNull(field);
    Objects.requireNonNull(before);
    Objects.requireNonNull(after);
    if (before.equals(after)) return false;
    history.record(field, before, after, gesture);
    inputVersion++;
    return true;
  }

  void finishEdit() {
    history.finish();
  }

  boolean canUndo(EditorState state) {
    return current(state) && history.canUndo();
  }

  boolean canRedo(EditorState state) {
    return current(state) && history.canRedo();
  }

  Ticket capture() {
    return new Ticket(this, inputVersion);
  }

  TextEditHistory.Change move(EditorState state, Ticket ticket, boolean forward) {
    if (!owns(state, ticket)) return null;
    var change = history.move(forward);
    if (change != null) {
      inputVersion++;
      restoration++;
    }
    return change;
  }

  boolean owns(EditorState state, Ticket ticket) {
    return current(state) && ticket != null && ticket.owner() == this && ticket.version() == inputVersion;
  }

  long inputVersion() {
    return inputVersion;
  }

  String interactionId() {
    return nativeId + ":" + restoration;
  }

  String nativeId() {
    return nativeId;
  }
}
