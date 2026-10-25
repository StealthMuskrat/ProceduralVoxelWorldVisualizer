package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import java.util.*;

/** Owned bookmark metadata and cached filtering; neither selection nor navigation edits the model. */
public final class BookmarkIndex {
  public record Row(UUID uuid, String name, Coordinates coordinates, String search) {
    public String label() { return name + "  (" + coordinates.label() + ")"; }
  }

  private final List<Row> rows;
  private final Map<UUID, Row> byUuid;
  private String query;
  private List<Row> filtered;
  private int projections;

  public BookmarkIndex(ProjectData project, Tab tab) {
    var values = new ArrayList<Row>();
    var ids = new HashMap<UUID, Row>();
    for (var bookmark : BookmarkTransactions.list(project, tab)) {
      var coordinates = BookmarkTransactions.coordinates(bookmark);
      var row = new Row(bookmark.uuid, bookmark.name, coordinates,
          (bookmark.name + " " + coordinates.label()).toLowerCase(Locale.ROOT));
      values.add(row);
      ids.put(row.uuid(), row);
    }
    rows = List.copyOf(values);
    byUuid = Map.copyOf(ids);
  }

  public Row get(UUID uuid) { return uuid == null ? null : byUuid.get(uuid); }
  public int size() { return rows.size(); }
  public int projections() { return projections; }

  public List<Row> rows(String text) {
    String next = Objects.requireNonNullElse(text, "").strip().toLowerCase(Locale.ROOT);
    if (!next.equals(query)) {
      filtered = next.isEmpty() ? rows : rows.stream().filter(row -> row.search().contains(next)).toList();
      query = next;
      projections++;
    }
    return filtered;
  }

  public static final class Cache {
    private ProjectData project;
    private long revision;
    private final Map<Tab, BookmarkIndex> indexes = new EnumMap<>(Tab.class);
    private int builds;

    public BookmarkIndex get(ProjectData current, long version, Tab tab) {
      if (project != current || revision != version) {
        indexes.clear();
        project = current;
        revision = version;
      }
      return indexes.computeIfAbsent(tab, key -> { builds++; return new BookmarkIndex(current, key); });
    }

    public int builds() { return builds; }
  }
}
