package io.voxelcreator.editor;

import java.util.Objects;

/** UI refresh is consumed before invocation; failure/reentry cannot replay an accepted edit. */
final class ResourceEditCompletion {
  private final EditorState state;
  private Runnable callback;

  ResourceEditCompletion(EditorState state, Runnable callback) {
    this.state = Objects.requireNonNull(state);
    this.callback = Objects.requireNonNull(callback);
  }

  void complete() {
    var accepted = callback;
    callback = null;
    if (accepted == null) return;
    try {
      accepted.run();
    } catch (RuntimeException failure) {
      state.error(
          "RESOURCE_EDIT_REFRESH_FAILED",
          EditorEvents.Subsystem.APPLICATION,
          "Resource edit committed; UI refresh failed: "
              + Objects.requireNonNullElse(failure.getMessage(), failure.toString()));
    }
  }

  void clear() {
    callback = null;
  }

  boolean pending() {
    return callback != null;
  }
}
