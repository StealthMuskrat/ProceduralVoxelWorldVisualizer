package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData.Gate;
import io.voxelcreator.model.ProjectData.Predicate;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.model.WorldSemantics;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Structural edits of private World drafts; persistence and history remain caller-owned. */
final class WorldConditionEdits {
  static final int MAX_DEPTH = 32;
  static final List<String> PREDICATES =
      List.of(
          "ANY",
          "EMPTY",
          "OCCUPIED",
          "TYPE_IS",
          "TYPE_IS_NOT",
          "IN_SET",
          "NOT_IN_SET",
          "AND",
          "OR");

  private WorldConditionEdits() {}

  static boolean group(String kind) {
    return kind.equals("AND") || kind.equals("OR");
  }

  static boolean gateGroup(String kind) {
    return group(kind) || kind.equals("NOT");
  }

  static boolean kind(Predicate target, String next, int depth) {
    if (!PREDICATES.contains(next)) throw new IllegalArgumentException("Unknown condition kind");
    if (target.kind.equals(next)) return false;
    if (group(next) && !group(target.kind)) {
      room(target, depth, p -> group(p.kind) ? p.children : List.of());
      var wrapper = new Predicate();
      wrapper.kind = next;
      wrapper.children.add(ProjectIO.JSON.convertValue(target, Predicate.class));
      assign(target, wrapper);
    } else {
      if (group(next) && target.children.isEmpty()) childRoom(depth);
      if (!sameReferenceFamily(target.kind, next)) target.reference = null;
      target.kind = next;
      if (!group(next)) target.children.clear();
      else if (target.children.isEmpty()) target.children.add(new Predicate());
    }
    return true;
  }

  private static boolean sameReferenceFamily(String a, String b) {
    return a.startsWith("TYPE_") && b.startsWith("TYPE_")
        || List.of("IN_SET", "NOT_IN_SET").contains(a)
            && List.of("IN_SET", "NOT_IN_SET").contains(b);
  }

  static boolean kind(Gate target, String next, int depth) {
    if (!WorldSemantics.GATES.contains(next))
      throw new IllegalArgumentException("Unknown gate kind");
    if (target.kind.equals(next)) return false;
    if (gateGroup(next) && !(group(next) && group(target.kind))) {
      room(target, depth, g -> gateGroup(g.kind) ? g.children : List.of());
      var wrapper = new Gate();
      wrapper.kind = next;
      wrapper.children.add(ProjectIO.JSON.convertValue(target, Gate.class));
      assign(target, wrapper);
    } else {
      if (gateGroup(next) && target.children.isEmpty()) childRoom(depth);
      target.kind = next;
      if (!gateGroup(next)) target.children.clear();
      else if (target.children.isEmpty()) target.children.add(new Gate());
    }
    return true;
  }

  static boolean add(Predicate target, int depth) {
    if (!group(target.kind)) return false;
    childRoom(depth);
    target.children.add(new Predicate());
    return true;
  }

  static boolean add(Gate target, int depth) {
    if (!gateGroup(target.kind) || target.kind.equals("NOT") && !target.children.isEmpty())
      return false;
    childRoom(depth);
    target.children.add(new Gate());
    return true;
  }

  static boolean remove(Predicate target, int index) {
    return group(target.kind) && remove(target.children, index);
  }

  static boolean remove(Gate target, int index) {
    return gateGroup(target.kind) && remove(target.children, index);
  }

  private static boolean remove(List<?> children, int index) {
    if (children.size() <= 1 || index < 0 || index >= children.size()) return false;
    children.remove(index);
    return true;
  }

  static boolean unwrap(Predicate target) {
    if (!group(target.kind) || target.children.size() != 1) return false;
    assign(target, ProjectIO.JSON.convertValue(target.children.getFirst(), Predicate.class));
    return true;
  }

  static boolean unwrap(Gate target) {
    if (!gateGroup(target.kind) || target.children.size() != 1) return false;
    assign(target, ProjectIO.JSON.convertValue(target.children.getFirst(), Gate.class));
    return true;
  }

  private static void childRoom(int depth) {
    if (depth < 0 || depth >= MAX_DEPTH)
      throw new IllegalArgumentException("Conditions support at most 32 nested levels");
  }

  private record Node<T>(T value, int depth) {}

  private static <T> void room(T target, int depth, Function<T, List<T>> children) {
    var pending = new ArrayDeque<Node<T>>();
    pending.add(new Node<>(target, depth + 1));
    while (!pending.isEmpty()) {
      var node = pending.removeFirst();
      if (depth < 0 || node.depth() > MAX_DEPTH)
        throw new IllegalArgumentException("Wrapping would exceed 32 nested levels");
      for (var child : children.apply(node.value()))
        pending.addLast(new Node<>(child, node.depth() + 1));
    }
  }

  private static void assign(Predicate target, Predicate source) {
    target.kind = source.kind;
    target.reference = source.reference;
    target.children = new ArrayList<>(source.children);
  }

  private static void assign(Gate target, Gate source) {
    target.kind = source.kind;
    target.comparison = source.comparison;
    target.threshold = source.threshold;
    target.max = source.max;
    target.includeMin = source.includeMin;
    target.includeMax = source.includeMax;
    target.probability = source.probability;
    target.seedOffset = source.seedOffset;
    target.noiseGeneratorUuid = source.noiseGeneratorUuid;
    target.children = new ArrayList<>(source.children);
  }
}
