package io.voxelcreator.editor;

import io.voxelcreator.storage.BlockPos;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/** UI-owned, replaceable Inspector work. Results still use the shared publication gate. */
final class InspectorTask implements AutoCloseable {
  private InspectorRequests.Request request;
  private BlockPos coordinate;
  private Future<?> future;
  private boolean closed, delivered;
  private long generation;
  private volatile Ticket ticket;
  private final PreviewMailbox<Result> handoff = new PreviewMailbox<>();

  /** One exact opening/attempt; equal request values do not reconstruct this capability. */
  static final class Ticket {
    private final InspectorRequests.Request request;
    private final long generation;

    private Ticket(InspectorRequests.Request request, long generation) {
      this.request = request;
      this.generation = generation;
    }
  }

  /** CPU-only prepared presentation; null trace without failure denotes a completed pick miss. */
  record Result(TraceSnapshot trace, BlockPos picked, boolean proxy, Throwable failure) {
    Result {
      if (failure != null && (trace != null || picked != null || proxy)
          || trace == null && (picked != null || proxy)
          || proxy && picked == null
          || picked != null && !picked.equals(trace.trace().position()))
        throw new IllegalArgumentException("Inconsistent Inspector completion");
    }

    static Result traced(TraceSnapshot trace, BlockPos picked, boolean proxy) {
      return new Result(Objects.requireNonNull(trace), picked, proxy, null);
    }

    static Result miss() { return new Result(null, null, false, null); }

    static Result failed(Throwable failure) {
      return new Result(null, null, false, Objects.requireNonNull(failure));
    }
  }

  Ticket ticket() { return ticket; }

  boolean current(Ticket shown) {
    return shown != null && ticket == shown && matches(shown.request);
  }

  /** Worker publication retains no native owner and cannot refill a retired slot. */
  boolean publish(Ticket owner, Result value) {
    return owner != null && ticket == owner && handoff.publish(owner.generation, value);
  }

  /** UI callbacks carry only the ticket; current controls retain publication authority. */
  void delivered(Ticket owner) {
    if (current(owner)) delivered = true;
  }

  boolean ready() { return pending() && delivered; }

  boolean completed() {
    return pending() && BackgroundFailure.completed(future) != null;
  }

  /** After current controls: consumed before any trace/log/failure callback or native update. */
  Result accept(Ticket shown, BooleanSupplier valid) {
    if (!current(shown)) return null;
    var completion = BackgroundFailure.completed(future);
    if (!current(shown) || completion == null
        || completion.failure() == null && !delivered) return null;
    try {
      boolean allowed = valid.getAsBoolean();
      if (!current(shown) || !allowed) return null;
      var value = completion.failure() == null ? handoff.take() : Result.failed(completion.failure());
      if (value == null) value = Result.failed(new IllegalStateException("Inspector result was not delivered"));
      if (value.failure() == null && (shown.request.viewBound()
          ? value.trace() != null && value.picked() == null : value.trace() == null || value.picked() != null))
        value = Result.failed(new IllegalStateException("Inspector result does not match its request"));
      return finish(shown.request) ? value : null;
    } catch (Throwable failure) {
      if (!current(shown)) return null;
      return finish(shown.request) ? Result.failed(failure) : null;
    }
  }

  void begin(InspectorRequests.Request value, BlockPos target) {
    Objects.requireNonNull(value);
    if (!value.viewBound()) Objects.requireNonNull(target);
    if (closed) throw new IllegalStateException("Inspector work is closed");
    cancel();
    if (closed || ticket != null) return; // Cancellation callbacks may have created a successor.
    request = value;
    coordinate = target;
    ticket = new Ticket(value, generation);
  }

  void attach(InspectorRequests.Request owner, Future<?> value) {
    Objects.requireNonNull(value);
    if (!matches(owner)) value.cancel(true);
    else {
      var previous = future;
      future = value;
      if (previous != null && previous != value) previous.cancel(true);
    }
  }

  /** A successful worker still waits for its UI callback; only terminal failure is observed. */
  Throwable failure() {
    if (closed || request == null || future == null) return null;
    var completion = BackgroundFailure.completed(future);
    return completion == null ? null : completion.failure();
  }

  static String describe(Throwable failure) {
    return BackgroundFailure.describe(failure);
  }

  boolean pending() {
    return request != null;
  }

  InspectorRequests.Request request() {
    return request;
  }

  BlockPos coordinate() {
    return coordinate;
  }

  private boolean matches(InspectorRequests.Request value) {
    return !closed && request != null && request == value;
  }

  boolean finishSuccessful(InspectorRequests.Request owner) {
    // An already known terminal failure takes precedence over an enqueued success callback.
    return matches(owner) && failure() == null && finish(owner);
  }

  boolean finish(InspectorRequests.Request owner) {
    if (!matches(owner)) return false;
    retire(false);
    return true;
  }

  void cancel() { retire(true); }

  private void retire(boolean interrupt) {
    var previous = future;
    ticket = null;
    future = null;
    request = null;
    coordinate = null;
    delivered = false;
    handoff.advance(++generation);
    if (interrupt && previous != null) previous.cancel(true);
  }

  public void close() {
    closed = true;
    cancel();
    handoff.close();
  }
}
