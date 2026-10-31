package io.voxelcreator.editor;

/** UI-thread-owned publication gate for asynchronous inspector work. */
final class InspectorRequests {
  record Request(long serial, long revision, long viewVersion, boolean viewBound, Object document) {
    Request(long serial, long revision, long viewVersion, boolean viewBound) {
      this(serial, revision, viewVersion, viewBound, null);
    }
  }

  private long serial, viewVersion;
  private boolean closed;

  Request begin(long revision, boolean viewBound) {
    return begin(null, revision, viewBound);
  }

  Request begin(Object document, long revision, boolean viewBound) {
    if (closed) throw new IllegalStateException("Inspector is closed");
    return new Request(++serial, revision, viewVersion, viewBound, document);
  }

  void viewChanged() {
    viewVersion++;
  }

  void cancelCurrent() {
    serial++;
  }

  boolean current(Request request, long revision) {
    return current(request, null, revision);
  }

  boolean current(Request request, Object document, long revision) {
    return !closed
        && request.document == document
        && request.serial == serial
        && request.revision == revision
        && (!request.viewBound || request.viewVersion == viewVersion);
  }

  void close() {
    closed = true;
  }
}
