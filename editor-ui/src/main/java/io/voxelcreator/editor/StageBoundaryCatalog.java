package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.util.*;

/** Immutable committed pipeline labels; tree display order never changes generation order. */
final class StageBoundaryCatalog {
  record Row(UUID uuid, UUID layerUuid, String kind, String id, String layerId, boolean enabled) {
    String label() {
      return kind + " / " + id;
    }

    String detail() {
      return (kind.equals("operation") ? "Layer " + layerId + " / " : "")
          + (enabled ? "enabled" : "disabled");
    }
  }

  private final List<Row> rows;
  private final Map<UUID, Row> byUuid;
  private final List<DraftChoicePicker.Choice> choices;

  StageBoundaryCatalog(ProjectData project) {
    var values = new ArrayList<Row>();
    for (var layer : project.worldGenerator.layers) {
      values.add(new Row(layer.uuid, layer.uuid, "layer", layer.id, layer.id, layer.enabled));
      for (var op : layer.operations)
        values.add(
            new Row(
                op.uuid, layer.uuid, "operation", op.id, layer.id, layer.enabled && op.enabled));
    }
    rows = List.copyOf(values);
    var ids = new LinkedHashMap<UUID, Row>();
    for (var row : rows) ids.put(row.uuid(), row);
    byUuid = Map.copyOf(ids);
    choices =
        rows.stream()
            .map(
                row ->
                    new DraftChoicePicker.Choice(row.uuid().toString(), row.label(), row.detail()))
            .toList();
  }

  List<Row> rows() {
    return rows;
  }

  List<DraftChoicePicker.Choice> choices() {
    return choices;
  }

  Row get(UUID id) {
    return id == null ? null : byUuid.get(id);
  }

  /** Default only a never-selected boundary; a missing explicit UUID stays missing. */
  UUID initial(UUID current, UUID preferred) {
    if (current != null) return current;
    if (get(preferred) != null) return preferred;
    return rows.isEmpty() ? null : rows.getFirst().uuid();
  }

  boolean available(String mode, UUID current) {
    return mode.equals("Final") || get(current) != null;
  }

  static final class Cache {
    private ProjectData project;
    private long revision;
    private StageBoundaryCatalog catalog;
    private int builds;

    StageBoundaryCatalog get(ProjectData source, long version) {
      if (project != source || revision != version || catalog == null) {
        project = source;
        revision = version;
        catalog = new StageBoundaryCatalog(source);
        builds++;
      }
      return catalog;
    }

    int builds() {
      return builds;
    }
  }
}
