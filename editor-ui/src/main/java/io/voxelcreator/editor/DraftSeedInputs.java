package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.util.*;

/** Invalid private seed text never becomes a fallback model value or crosses draft ownership. */
final class DraftSeedInputs {
  static final int MAX_FIELDS = 1024, MAX_CHARACTERS = 128;

  record Problem(String id, String path, String label, String text, boolean orphan) {
    Diagnostic diagnostic(UUID resource) {
      return new Diagnostic(
          orphan ? "STALE_SEED_TEXT" : "INVALID_SEED_TEXT",
          Diagnostic.Severity.ERROR,
          orphan
              ? "A pending seed belonged to a changed gate tree. Restore its obsolete input before"
                  + " Apply."
              : label + " requires a signed 64-bit decimal integer.",
          "/editor/draft/" + path,
          resource,
          null,
          orphan ? "settings/fieldGates" : path);
    }
  }

  private UUID project, resource;
  private Object draft;
  private Object ownerToken = new Object();
  private long version, nextOrphan;
  private final Map<String, Problem> problems = new LinkedHashMap<>();

  void synchronize(EditorState state) {
    if (!Objects.equals(project, state.project().project.uuid)
        || !Objects.equals(resource, state.selected)
        || draft != state.draft) {
      problems.clear();
      version++;
      ownerToken = new Object();
      project = state.project().project.uuid;
      resource = state.selected;
      draft = state.draft;
    }
  }

  Object owner() {
    return ownerToken;
  }

  long version(EditorState state) {
    synchronize(state);
    return version;
  }

  boolean pending(EditorState state) {
    synchronize(state);
    return !problems.isEmpty();
  }

  List<Problem> problems(EditorState state) {
    synchronize(state);
    return List.copyOf(problems.values());
  }

  String text(String path) {
    var value = problems.get(path);
    return value == null ? null : value.text;
  }

  OptionalLong edit(EditorState state, String path, String label, String text) {
    synchronize(state);
    if (draft == null)
      throw new IllegalStateException("Select a Properties draft before editing a seed");
    if (path.length() > 512 || text.length() > MAX_CHARACTERS)
      throw new IllegalArgumentException("Seed text or control path exceeds the editor limit");
    if (label.length() > 160) label = label.substring(0, 160);
    try {
      if (!text.matches("[+-]?[0-9]+")) throw new NumberFormatException();
      long value = Long.parseLong(text);
      if (problems.remove(path) != null) version++;
      return OptionalLong.of(value);
    } catch (NumberFormatException invalid) {
      if (!problems.containsKey(path) && problems.size() >= MAX_FIELDS)
        throw new IllegalStateException(
            "Restore invalid seed inputs before editing another seed field");
      problems.put(path, new Problem(path, path, label, text, false));
      version++;
      return OptionalLong.empty();
    }
  }

  void orphan(EditorState state, String prefix) {
    synchronize(state);
    var affected =
        problems.values().stream().filter(p -> !p.orphan && within(p.path, prefix)).toList();
    for (var old : affected) {
      problems.remove(old.id);
      String id = "orphan:" + nextOrphan++;
      problems.put(id, new Problem(id, old.path, old.label, old.text, true));
    }
    if (!affected.isEmpty()) version++;
  }

  void discard(EditorState state, String id) {
    synchronize(state);
    if (problems.remove(id) != null) version++;
  }

  void discardPrefix(EditorState state, String prefix) {
    synchronize(state);
    if (problems.values().removeIf(p -> within(p.path, prefix))) version++;
  }

  void clear(EditorState state) {
    synchronize(state);
    if (!problems.isEmpty()) {
      problems.clear();
      version++;
    }
  }

  List<Diagnostic> diagnostics(EditorState state) {
    return problems(state).stream().map(p -> p.diagnostic(state.selected)).toList();
  }

  static boolean within(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }
}
