package io.voxelcreator.editor;

import java.util.*;

/** Cached selector results use committed UUID/kind metadata, never displayed labels as identity. */
public final class ResourceChoices {
  private ResourceTreeIndex index;
  private String kind, query;
  private List<ResourceTreeIndex.Node> rows = List.of();
  private long evaluations;

  public List<ResourceTreeIndex.Node> rows(
      ResourceTreeIndex current, String resourceKind, String filter) {
    String text = filter.strip().toLowerCase(Locale.ROOT);
    if (index != current || !Objects.equals(kind, resourceKind) || !Objects.equals(query, text)) {
      rows = resourceKind.equals("noise") ? current.noises(text) : current.kind(resourceKind, text);
      index = current;
      kind = resourceKind;
      query = text;
      evaluations++;
    }
    return rows;
  }

  public long evaluations() {
    return evaluations;
  }

  public void clear() {
    index = null;
    kind = query = null;
    rows = List.of();
  }

  public static boolean accepts(ResourceTreeIndex.Node node, String kind) {
    return node != null
        && (node.kind().equals(kind) || kind.equals("noise") && node.kind().startsWith("noise"));
  }

  public static String label(ResourceTreeIndex.Node node) {
    return node.id()
        + (node.kind().equals("noise2D") ? " [2D]" : node.kind().equals("noise3D") ? " [3D]" : "");
  }

  public static UUID choose(ResourceTreeIndex index, String kind, UUID id, boolean allowNull) {
    if (id == null && allowNull) return null;
    if (!accepts(index.get(id), kind))
      throw new IllegalArgumentException("Choose an existing compatible resource");
    return id;
  }
}
