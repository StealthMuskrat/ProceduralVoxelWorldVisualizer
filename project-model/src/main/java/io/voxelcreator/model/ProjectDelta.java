package io.voxelcreator.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Structural undo delta. Array insert/delete/reorder records only the changed contiguous range. */
final class ProjectDelta {
  private interface Change {
    void apply(JsonNode root, boolean forward);

    long bytes();
  }

  private record Value(List<String> parent, String key, JsonNode before, JsonNode after)
      implements Change {
    public void apply(JsonNode root, boolean forward) {
      JsonNode target = at(root, parent), value = forward ? after : before;
      if (target instanceof ObjectNode object) {
        if (value == null) object.remove(key);
        else object.set(key, value.deepCopy());
      } else ((ArrayNode) target).set(Integer.parseInt(key), value.deepCopy());
    }

    public long bytes() {
      return estimate(before) + estimate(after);
    }
  }

  private record Splice(List<String> path, int index, List<JsonNode> before, List<JsonNode> after)
      implements Change {
    public void apply(JsonNode root, boolean forward) {
      ArrayNode array = (ArrayNode) at(root, path);
      var removed = forward ? before : after;
      var added = forward ? after : before;
      var tail = new ArrayList<JsonNode>();
      for (int i = index + removed.size(); i < array.size(); i++) tail.add(array.get(i));
      while (array.size() > index) array.remove(array.size() - 1);
      for (var value : added) array.add(value.deepCopy());
      tail.forEach(array::add);
    }

    public long bytes() {
      return before.stream().mapToLong(ProjectDelta::estimate).sum()
          + after.stream().mapToLong(ProjectDelta::estimate).sum();
    }
  }

  private final List<Change> changes;

  private ProjectDelta(List<Change> changes) {
    this.changes = List.copyOf(changes);
  }

  static ProjectDelta between(JsonNode before, JsonNode after) {
    var changes = new ArrayList<Change>();
    diff(before, after, List.of(), changes);
    return new ProjectDelta(changes);
  }

  boolean empty() {
    return changes.isEmpty();
  }

  long bytes() {
    return changes.stream().mapToLong(Change::bytes).sum();
  }

  ProjectData apply(ProjectData project, boolean forward) {
    JsonNode root = ProjectIO.JSON.valueToTree(project);
    if (forward) for (var c : changes) c.apply(root, true);
    else for (int i = changes.size() - 1; i >= 0; i--) changes.get(i).apply(root, false);
    return ProjectIO.JSON.convertValue(root, ProjectData.class);
  }

  private static JsonNode at(JsonNode node, List<String> path) {
    for (String key : path) node = node.isArray() ? node.get(Integer.parseInt(key)) : node.get(key);
    return node;
  }

  private static List<String> child(List<String> path, String key) {
    var result = new ArrayList<>(path);
    result.add(key);
    return List.copyOf(result);
  }

  private static long estimate(JsonNode n) {
    return n == null ? 0 : 2L * n.toString().length();
  }

  private static JsonNode copy(JsonNode n) {
    return n == null ? null : n.deepCopy();
  }

  private static void diff(
      JsonNode before, JsonNode after, List<String> path, List<Change> changes) {
    if (Objects.equals(before, after)) return;
    if (before != null && after != null && before.isObject() && after.isObject()) {
      var keys = new LinkedHashSet<String>();
      before.fieldNames().forEachRemaining(keys::add);
      after.fieldNames().forEachRemaining(keys::add);
      for (String key : keys)
        if (before.has(key) && after.has(key))
          diff(before.get(key), after.get(key), child(path, key), changes);
        else changes.add(new Value(path, key, copy(before.get(key)), copy(after.get(key))));
      return;
    }
    if (before != null && after != null && before.isArray() && after.isArray()) {
      boolean sameOrder = before.size() == after.size();
      if (sameOrder)
        for (int i = 0; i < before.size(); i++)
          if (!Objects.equals(before.get(i).get("uuid"), after.get(i).get("uuid"))) {
            sameOrder = false;
            break;
          }
      if (sameOrder) {
        for (int i = 0; i < before.size(); i++)
          diff(before.get(i), after.get(i), child(path, Integer.toString(i)), changes);
        return;
      }
      int start = 0, endBefore = before.size(), endAfter = after.size();
      while (start < endBefore && start < endAfter && before.get(start).equals(after.get(start)))
        start++;
      while (endBefore > start
          && endAfter > start
          && before.get(endBefore - 1).equals(after.get(endAfter - 1))) {
        endBefore--;
        endAfter--;
      }
      var a = new ArrayList<JsonNode>();
      var b = new ArrayList<JsonNode>();
      for (int i = start; i < endBefore; i++) a.add(copy(before.get(i)));
      for (int i = start; i < endAfter; i++) b.add(copy(after.get(i)));
      changes.add(new Splice(path, start, List.copyOf(a), List.copyOf(b)));
      return;
    }
    changes.add(
        new Value(
            List.copyOf(path.subList(0, path.size() - 1)),
            path.getLast(),
            copy(before),
            copy(after)));
  }
}
