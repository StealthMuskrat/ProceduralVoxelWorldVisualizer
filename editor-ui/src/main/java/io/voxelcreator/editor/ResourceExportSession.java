package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.util.*;

/**
 * Dependency payloads are prepared only when the committed project or explicit selection changes.
 */
public final class ResourceExportSession {
  private ProjectData project, payload;
  private long revision, preparations;
  private Set<UUID> selected = Set.of();
  private List<ResourceTreeIndex.Node> included = List.of();

  public void prepare(ProjectData current, long currentRevision, Set<UUID> selection) {
    if (current == project && revision == currentRevision && selected.equals(selection)) return;
    var ids = Set.copyOf(selection);
    var source = new ResourceTreeIndex(current);
    for (UUID id : ids)
      if (source.get(id) == null)
        throw new IllegalArgumentException("Selected export resource no longer exists");
    var next = ResourceGraph.selection(current, ids, true);
    var rows = new ResourceTreeIndex(next).all();
    project = current;
    revision = currentRevision;
    selected = ids;
    payload = next;
    included = rows;
    preparations++;
  }

  public List<ResourceTreeIndex.Node> included() {
    return included;
  }

  public Set<UUID> selected() {
    return selected;
  }

  public long preparations() {
    return preparations;
  }

  public ProjectData resources() {
    if (payload == null) throw new IllegalStateException("Prepare the export selection first");
    return ProjectIO.copy(payload);
  }

  public void clear() {
    project = null;
    payload = null;
    selected = Set.of();
    included = List.of();
  }
}
