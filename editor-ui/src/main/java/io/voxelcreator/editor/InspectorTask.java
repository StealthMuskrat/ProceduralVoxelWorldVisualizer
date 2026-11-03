package io.voxelcreator.editor;

import io.voxelcreator.storage.BlockPos;
import java.util.Objects;
import java.util.concurrent.Future;

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
