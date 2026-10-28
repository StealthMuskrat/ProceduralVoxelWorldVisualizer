package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.util.*;

/** Dependency payloads are prepared only when the source or explicit selection changes. */
public final class ResourceExportSession {
  /** Immutable prepared metadata and privately owned payload, stable across later preparations. */
  public static final class Preview {
    private final ProjectData payload;
    private final Set<UUID> selected;
    private final List<ResourceTreeIndex.Node> included;

    private Preview(
        ProjectData payload, Set<UUID> selected, List<ResourceTreeIndex.Node> included) {
      this.payload = payload;
      this.selected = Set.copyOf(selected);
      this.included = List.copyOf(included);
    }

    public Set<UUID> selected() {
      return selected;
    }

    public List<ResourceTreeIndex.Node> included() {
      return included;
    }

    public ProjectData resources() {
      return ProjectIO.copy(payload);
    }
  }

  private ProjectData project;
  private long revision, preparations;
  private Preview preview;

  public void prepare(ProjectData current, long currentRevision, Set<UUID> selection) {
    if (preview != null
        && current == project
        && revision == currentRevision
        && selected().equals(selection)) return;
    var ids = Set.copyOf(selection);
    var source = new ResourceTreeIndex(current);
    for (UUID id : ids)
      if (source.get(id) == null)
        throw new IllegalArgumentException("Selected export resource no longer exists");
    var next = ResourceGraph.selection(current, ids, true);
    var rows = new ResourceTreeIndex(next).all();
    var prepared = new Preview(next, ids, rows);
    project = current;
    revision = currentRevision;
    preview = prepared;
    preparations++;
  }

  public Preview preview() {
    if (preview == null) throw new IllegalStateException("Prepare the export selection first");
    return preview;
  }

  public List<ResourceTreeIndex.Node> included() {
    return preview == null ? List.of() : preview.included();
  }

  public Set<UUID> selected() {
    return preview == null ? Set.of() : preview.selected();
  }

  public long preparations() {
    return preparations;
  }

  public ProjectData resources() {
    return preview().resources();
  }

  public void clear() {
    project = null;
    preview = null;
  }
}
