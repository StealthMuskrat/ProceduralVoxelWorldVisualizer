package io.voxelcreator.editor;

import io.voxelcreator.storage.BlockPos;
import java.util.Objects;
import java.util.concurrent.*;

/** UI-owned, replaceable Inspector work. Results still use the shared publication gate. */
final class InspectorTask implements AutoCloseable {
  private InspectorRequests.Request request;
  private BlockPos coordinate;
  private Future<?> future;
  private boolean closed;

  void begin(InspectorRequests.Request value, BlockPos target) {
    Objects.requireNonNull(value);
    if (!value.viewBound()) Objects.requireNonNull(target);
    if (closed) throw new IllegalStateException("Inspector work is closed");
    cancel();
    request = value;
    coordinate = target;
  }

  void attach(InspectorRequests.Request owner, Future<?> value) {
    Objects.requireNonNull(value);
    if (!matches(owner)) value.cancel(true);
    else {
      if (future != null && future != value) future.cancel(true);
      future = value;
    }
  }

  /** A successful worker still waits for its UI callback; only terminal failure is observed. */
  Throwable failure() {
    if (closed || request == null || future == null) return null;
    try {
      if (!future.isDone()) return null;
      future.get();
      return null;
    } catch (ExecutionException failure) {
      return failure.getCause() == null ? failure : failure.getCause();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return interrupted;
    } catch (Throwable failure) {
      return failure;
    }
  }

  static String describe(Throwable failure) {
    String detail;
    try {
      detail = failure.getMessage();
    } catch (Throwable unreadable) {
      detail = null;
    }
    if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
    if (detail.isBlank()) detail = failure.getClass().getName();
    return StatusBarText.clip(detail, 2048);
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
    request = null;
    coordinate = null;
    future = null;
    return true;
  }

  void cancel() {
    if (future != null) future.cancel(true);
    future = null;
    request = null;
    coordinate = null;
  }

  public void close() {
    closed = true;
    cancel();
  }
}
