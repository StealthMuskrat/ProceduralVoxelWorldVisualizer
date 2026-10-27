package io.voxelcreator.editor;

/** UI-thread-owned publication gate for asynchronous inspector work. */
final class InspectorRequests {
  record Request(long serial, long revision, long viewVersion, boolean viewBound) {}

  private long serial, viewVersion;
  private boolean closed;

  Request begin(long revision, boolean viewBound) {
    if (closed) throw new IllegalStateException("Inspector is closed");
    return new Request(++serial, revision, viewVersion, viewBound);
  }

  void viewChanged() {
    viewVersion++;
  }

  boolean current(Request request, long revision) {
    return !closed
        && request.serial == serial
        && request.revision == revision
        && (!request.viewBound || request.viewVersion == viewVersion);
  }

  void close() {
    closed = true;
  }
}
