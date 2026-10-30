package io.voxelcreator.editor;

import io.voxelcreator.api.Profiler;
import java.util.*;

/** Full logical identity, independent of projection position or display-label sanitization. */
record ProfileSeriesKey(
    Profiler.Kind kind, Profiler.Unit unit, long context, UUID project, UUID source, String label) {
  static ProfileSeriesKey of(Profiler.Series row) {
    return new ProfileSeriesKey(
        row.kind(), row.unit(), row.contextId(), row.projectUuid(), row.sourceUuid(), row.label());
  }

  String widgetId() {
    var key =
        new StringBuilder(kind.name())
            .append('/')
            .append(unit.name())
            .append('/')
            .append(context)
            .append('/')
            .append(project)
            .append('/')
            .append(source)
            .append('/');
    // Encode UTF-16 code units exactly, including unmatched surrogates, without ImGui ### markers.
    for (int i = 0; i < label.length(); i++) {
      int c = label.charAt(i);
      key.append(Character.forDigit((c >>> 12) & 15, 16))
          .append(Character.forDigit((c >>> 8) & 15, 16))
          .append(Character.forDigit((c >>> 4) & 15, 16))
          .append(Character.forDigit(c & 15, 16));
    }
    return key.toString();
  }
}
