package io.voxelcreator.editor;

import java.util.*;

/**
 * A footer review owns exact view inputs and task/snapshot identity, without parsing a fallback.
 */
record InspectorActionContext(
    Object lifetime,
    UUID project,
    long revision,
    TraceSnapshot trace,
    InspectorRequests.Request request,
    long inputVersion,
    Map<String, String> raw,
    Map<String, String> displayed,
    boolean tracePage) {
  InspectorActionContext {
    raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    displayed = Collections.unmodifiableMap(new LinkedHashMap<>(displayed));
  }

  static InspectorActionContext capture(
      EditorState state,
      InspectorCoordinates coordinates,
      Map<String, String> displayed,
      InspectorPanel.Status status,
      boolean tracePage) {
    var raw = new LinkedHashMap<String, String>();
    for (var axis : coordinates.axes()) raw.put(axis, coordinates.text(axis));
    return new InspectorActionContext(
        state.document.sessionToken(),
        state.project().project.uuid,
        state.revision,
        status.trace(),
        status.request(),
        coordinates.version(),
        raw,
        displayed,
        tracePage);
  }

  boolean same(InspectorActionContext other) {
    return other != null
        && lifetime == other.lifetime
        && Objects.equals(project, other.project)
        && revision == other.revision
        && trace == other.trace
        && request == other.request
        && inputVersion == other.inputVersion
        && raw.equals(other.raw)
        && displayed.equals(other.displayed)
        && tracePage == other.tracePage;
  }
}
