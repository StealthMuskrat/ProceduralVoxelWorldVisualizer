package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.lang.ref.WeakReference;
import java.util.*;

/** Bounded committed-definition checks; private control availability always stays live. */
final class TraceSourceCache {
  static final int CAPACITY = 32;

  enum State {
    AVAILABLE(""),
    NO_TARGET("This observation has no source control."),
    OTHER_DOCUMENT(
        "This trace belongs to an earlier or different document. Request a new trace before"
            + " navigating."),
    MISSING_SOURCE("The source resource was removed. Restore it or request a new trace."),
    SOURCE_CHANGED(
        "The source definition changed after this trace. Restore it or request a new trace."),
    CONTROL_UNAVAILABLE(
        "The referenced control is unavailable in the current resource or Properties draft.");
    final String explanation;

    State(String explanation) {
      this.explanation = explanation;
    }

    boolean available() {
      return this == AVAILABLE;
    }
  }

  private Object document;
  private WeakReference<ProjectData> project = new WeakReference<>(null);
  private long revision = -1, checks;
  private TraceSnapshot snapshot;
  private final LinkedHashMap<UUID, TraceSnapshot.Definition> definitions =
      new LinkedHashMap<>(16, .75f, true);

  State evaluate(EditorState state, TraceSnapshot trace, EditorEvents.Target target) {
    if (document != state.document.sessionToken()
        || project.get() != state.project()
        || revision != state.revision
        || snapshot != trace) {
      clear();
      document = state.document.sessionToken();
      project = new WeakReference<>(state.project());
      revision = state.revision;
      snapshot = trace;
    }
    if (target == null || target.resource() == null) return State.NO_TARGET;
    if (!trace.sameDocument(state)) return State.OTHER_DOCUMENT;
    var definition = definitions.get(target.resource());
    if (definition == null) {
      definition = trace.definition(state.project(), target.resource());
      checks++;
      if (definitions.size() == CAPACITY)
        definitions.remove(definitions.keySet().iterator().next());
      definitions.put(target.resource(), definition);
    }
    return switch (definition) {
      case MISSING -> State.MISSING_SOURCE;
      case CHANGED -> State.SOURCE_CHANGED;
      case MATCH -> state.canNavigate(target) ? State.AVAILABLE : State.CONTROL_UNAVAILABLE;
    };
  }

  void clear() {
    document = null;
    project.clear();
    revision = -1;
    snapshot = null;
    definitions.clear();
  }

  long checks() {
    return checks;
  }

  int retained() {
    return definitions.size();
  }
}
