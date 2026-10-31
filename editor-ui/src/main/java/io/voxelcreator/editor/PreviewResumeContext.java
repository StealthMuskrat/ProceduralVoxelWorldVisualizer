package io.voxelcreator.editor;

import io.voxelcreator.render.RenderPressure;
import java.util.*;

/** Frozen pause/source/limit review; volatile allocation, progress and clocks are not owners. */
record PreviewResumeContext(
    ViewportActionContext view,
    Kind kind,
    RenderPressure pressure,
    long pauseVersion,
    int level,
    boolean paused,
    Object source,
    String sourceKey,
    int extent,
    boolean rejected,
    PerformancePreferences.Values limits) {
  enum Kind {
    VOLUME("Resume Volume"),
    STREAMING("Resume Streaming");
    final String label;

    Kind(String label) {
      this.label = label;
    }
  }

  static PreviewResumeContext capture(
      EditorState state,
      ViewportActionContext view,
      Kind kind,
      RenderPressure pressure,
      Object source,
      String sourceKey,
      int extent,
      boolean rejected) {
    return new PreviewResumeContext(
        view,
        kind,
        pressure,
        pressure.pauseVersion(),
        pressure.level(),
        pressure.paused(),
        source,
        sourceKey,
        extent,
        rejected,
        PerformancePreferences.from(state.user));
  }

  boolean same(PreviewResumeContext other) {
    return other != null
        && view.same(other.view)
        && kind == other.kind
        && pressure == other.pressure
        && pauseVersion == other.pauseVersion
        && level == other.level
        && paused == other.paused
        && source == other.source
        && Objects.equals(sourceKey, other.sourceKey)
        && extent == other.extent
        && rejected == other.rejected
        && limits.equals(other.limits);
  }
}
