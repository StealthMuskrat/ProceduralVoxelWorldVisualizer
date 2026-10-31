package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.BlockPos;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.UUID;

/** One failed coordinate attempt; never derives its target from typed XYZ or an older result. */
final class InspectorTraceRetry {
  private final InspectorRequests.Request request;
  private final WeakReference<ProjectData> project;
  private final UUID projectId;
  private final BlockPos position;
  private final long inputVersion;

  private InspectorTraceRetry(
      EditorState state, InspectorRequests.Request request, BlockPos position, long inputVersion) {
    this.request = request;
    this.project = new WeakReference<>(state.project());
    this.projectId = state.project().project.uuid;
    this.position = Objects.requireNonNull(position);
    this.inputVersion = inputVersion;
  }

  static InspectorTraceRetry capture(
      EditorState state, InspectorRequests.Request request, BlockPos position, long inputVersion) {
    Objects.requireNonNull(request);
    if (request.viewBound()
        || request.document() != state.document.sessionToken()
        || request.revision() != state.revision)
      throw new IllegalArgumentException(
          "Retry requires a current document-bound coordinate attempt");
    return new InspectorTraceRetry(state, request, position, inputVersion);
  }

  boolean current(EditorState state, long currentInputVersion) {
    return request.document() == state.document.sessionToken()
        && project.get() == state.project()
        && projectId.equals(state.project().project.uuid)
        && request.revision() == state.revision
        && inputVersion == currentInputVersion;
  }

  BlockPos position() {
    return position;
  }

  long serial() {
    return request.serial();
  }
}
