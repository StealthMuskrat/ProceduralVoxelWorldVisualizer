package io.voxelcreator.editor;

import java.util.*;

/** One opened selector, exact displayed rows and consumed callback ownership; no native state. */
final class SelectorSession {
  record Context(
      Object document,
      Object project,
      long revision,
      Object owner,
      long ownerVersion,
      Object current,
      String kind,
      String none,
      Object source,
      String query) {
    Context {
      if (source instanceof List<?> rows) source = List.copyOf(rows);
    }

    boolean sameOwner(Context other) {
      return other != null
          && document == other.document
          && project == other.project
          && revision == other.revision
          && owner == other.owner
          && ownerVersion == other.ownerVersion
          && Objects.equals(current, other.current)
          && Objects.equals(kind, other.kind)
          && (none != null) == (other.none != null);
    }

    boolean same(Context other) {
      return sameOwner(other)
          && Objects.equals(none, other.none)
          && Objects.equals(source, other.source)
          && Objects.equals(query, other.query);
    }
  }

  record Ticket(long version, Context context) {}

  private long version;
  private int frame = -1;
  private boolean active;
  private Context opening, shown, retry;
  private String feedback = "";

  void open() {
    version++;
    active = true;
    frame = -1;
    opening = shown = retry = null;
    feedback = "";
  }

  Ticket observe(int currentFrame, Context context) {
    if (!active
        || frame >= 0 && (currentFrame < frame || currentFrame - frame > 1)
        || opening != null && !opening.sameOwner(context)) {
      retire();
      return null;
    }
    frame = currentFrame;
    if (opening == null) opening = context;
    if (shown == null) shown = context;
    else if (!shown.same(context)) {
      version++;
      shown = context;
      feedback = "";
    }
    return new Ticket(version, shown);
  }

  boolean consume(Ticket ticket, int currentFrame, Context context) {
    if (!active
        || frame != currentFrame
        || version != ticket.version()
        || shown != ticket.context()
        || !shown.same(context)) return false;
    active = false;
    return true;
  }

  boolean failed(Ticket ticket, String message) {
    if (active || version != ticket.version() || shown != ticket.context()) return false;
    retry = shown;
    feedback = Objects.requireNonNull(message);
    return true;
  }

  void complete(Ticket ticket) {
    if (!active && version == ticket.version() && shown == ticket.context()) retire();
  }

  boolean retry(Context context, int currentFrame) {
    if (retry == null) return false;
    if (!retry.same(context) || currentFrame < frame || currentFrame - frame > 1) {
      retire();
      return false;
    }
    String message = feedback;
    open();
    feedback = message;
    return true;
  }

  boolean active() {
    return active;
  }

  String feedback() {
    return feedback;
  }

  void retire() {
    version++;
    active = false;
    frame = -1;
    opening = shown = retry = null;
    feedback = "";
  }
}
