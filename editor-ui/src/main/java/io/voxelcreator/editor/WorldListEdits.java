package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectIO;
import java.util.List;
import java.util.Objects;

/** Frame-local structural intents; the exact list and row identities must still own delivery. */
final class WorldListEdits {
  enum Action {
    UP,
    DOWN,
    DUPLICATE,
    REMOVE
  }

  static final class Request<T> {
    private final List<T> owner, rows;
    private final int index;
    private final Action action;
    private boolean consumed;

    private Request(List<T> owner, int index, Action action) {
      this.owner = owner;
      this.rows = List.copyOf(owner);
      this.index = index;
      this.action = Objects.requireNonNull(action);
    }
  }

  private WorldListEdits() {}

  static <T> Request<T> request(List<T> rows, int index, Action action) {
    return new Request<>(rows, index, action);
  }

  static <T> boolean apply(List<T> rows, Request<T> request, int limit, Class<T> type) {
    if (request == null || request.consumed) return false;
    request.consumed = true;
    if (rows != request.owner || rows.size() != request.rows.size()) return false;
    for (int i = 0; i < rows.size(); i++) if (rows.get(i) != request.rows.get(i)) return false;
    int index = request.index;
    if (index < 0 || index >= rows.size()) return false;
    switch (request.action) {
      case UP -> {
        if (index == 0) return false;
        java.util.Collections.swap(rows, index, index - 1);
      }
      case DOWN -> {
        if (index + 1 >= rows.size()) return false;
        java.util.Collections.swap(rows, index, index + 1);
      }
      case DUPLICATE -> {
        if (rows.size() >= limit) return false;
        T copy = ProjectIO.JSON.convertValue(rows.get(index), type);
        rows.add(index + 1, copy);
      }
      case REMOVE -> rows.remove(index);
    }
    return true;
  }

  static <T> boolean add(List<T> rows, T value, int limit) {
    if (rows.size() >= limit) return false;
    rows.add(Objects.requireNonNull(value));
    return true;
  }
}
