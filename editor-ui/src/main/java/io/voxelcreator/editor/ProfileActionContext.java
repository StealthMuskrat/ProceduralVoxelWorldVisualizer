package io.voxelcreator.editor;

import io.voxelcreator.api.Profiler;

/** Constant-size controller identity; samples and refreshed display snapshots are not its owner. */
record ProfileActionContext(Profiler profiler, Profiler.Capture capture, boolean recording) {
  static ProfileActionContext from(Profiler profiler) {
    var capture = profiler.current().orElse(null);
    return new ProfileActionContext(profiler, capture, capture != null && capture.recording());
  }
}
