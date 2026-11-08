package io.voxelcreator.editor;

import java.util.Objects;
import java.util.concurrent.Future;

/** UI-owned source compilation; callbacks never retain authority over a replacement opening. */
final class SourceCompilerTask<T extends AutoCloseable> implements AutoCloseable {
  static final class Ticket {
    final String key;
    final long version, generation;

    private Ticket(String key, long version, long generation) {
      this.key = key;
      this.version = version;
      this.generation = generation;
    }
  }

  record Opening(Ticket ticket, Throwable cancellation) {}
  record Retirement(long generation, Throwable cancellation) {}

  private final OwnedPreviewMailbox<T> handoff = new OwnedPreviewMailbox<>();
  private Ticket ticket;
  private Future<?> future;
  private long generation;
  private boolean closed, awaitingAttachment;

  Opening begin(String key) {
    Objects.requireNonNull(key);
    if (closed) throw new IllegalStateException("Source compilation closed");
    var previous = future;
    ticket = null;
    future = null;
    awaitingAttachment = false;
    long opening = ++generation;
    long version = handoff.begin();
    Ticket admitted = null;
    if (!closed && generation == opening && handoff.current(version)) {
      ticket = admitted = new Ticket(key, version, opening);
      awaitingAttachment = true;
    }
    var failure = cancel(previous);
    return new Opening(admitted, failure);
  }

  Ticket ticket() { return ticket; }
  Future<?> future() { return future; }
  String key() { return ticket == null ? "" : ticket.key; }
  boolean pending() { return awaitingAttachment || future != null; }
  boolean pendingResult() { return handoff.pending(); }
  boolean current(long stamp) { return !closed && generation == stamp; }
  boolean current(Ticket owner) {
    return owner != null && ticket == owner && current(owner.generation) && handoff.current(owner.version);
  }

  /** Attach only the submitted Future; a stale return cancels that Future, never a live lookup. */
  Throwable attach(Ticket owner, Future<?> submitted) {
    Objects.requireNonNull(submitted);
    if (current(owner) && (future == null || future == submitted)) {
      future = submitted;
      awaitingAttachment = false;
      return null;
    }
    return cancel(submitted);
  }

  boolean complete(Ticket owner, Future<?> observed) {
    if (!current(owner) || awaitingAttachment || observed == null || future != observed) return false;
    future = null;
    return true;
  }

  /** A caller-side admission failure has no Future to await. */
  boolean noSubmission(Ticket owner) {
    if (!current(owner) || !awaitingAttachment || future != null) return false;
    awaitingAttachment = false;
    return true;
  }

  /** Worker publication is gated by the mailbox, without reading UI-owned ticket fields. */
  boolean eligible(Ticket owner) { return owner != null && handoff.current(owner.version); }

  boolean publish(Ticket owner, T value) {
    Objects.requireNonNull(owner);
    return handoff.publish(owner.version, value);
  }

  T take() { return current(ticket) && !pending() ? handoff.take() : null; }

  void finish(Ticket owner) {
    if (current(owner) && !pending()) retire(false);
  }

  Retirement stop() { return retire(false); }

  private Retirement retire(boolean closing) {
    var previous = future;
    ticket = null;
    future = null;
    awaitingAttachment = false;
    long retired = ++generation;
    if (closing) handoff.close();
    else handoff.invalidate();
    return new Retirement(retired, cancel(previous));
  }

  private static Throwable cancel(Future<?> previous) {
    return PreviewNotifications.attempt(() -> { if (previous != null) previous.cancel(true); });
  }

  public void close() {
    if (closed) return;
    closed = true;
    var failure = retire(true).cancellation();
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
  }
}
