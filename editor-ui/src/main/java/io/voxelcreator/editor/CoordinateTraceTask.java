package io.voxelcreator.editor;

import io.voxelcreator.storage.BlockPos;
import java.util.Objects;
import java.util.concurrent.Future;

/**
 * UI-owned, replaceable coordinate work. Results still use the shared Inspector publication gate.
 */
final class CoordinateTraceTask implements AutoCloseable {
  private InspectorRequests.Request request;
  private BlockPos coordinate;
  private Future<?> future;
  private boolean closed;

  void begin(InspectorRequests.Request value, BlockPos target) {
    Objects.requireNonNull(value);
    Objects.requireNonNull(target);
    if (closed) throw new IllegalStateException("Coordinate tracing is closed");
    if (value.viewBound()) throw new IllegalArgumentException("Coordinate request required");
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
    return !closed && request != null && request.equals(value);
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
