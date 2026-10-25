package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.util.*;

/** One committed index and one query result; replacement is detected independently of revision. */
public final class ResourceSearchSession {
  private ProjectData project;
  private long revision, builds, queries;
  private ResourceSearchIndex index;
  private ResourceSearchIndex.Filter filter;
  private List<ResourceSearchIndex.Hit> results = List.of();

  public List<ResourceSearchIndex.Hit> search(
      ProjectData current, long currentRevision, ResourceSearchIndex.Filter request) {
    if (project != current || revision != currentRevision || index == null) {
      index = new ResourceSearchIndex(current);
      project = current;
      revision = currentRevision;
      filter = null;
      builds++;
    }
    if (!request.equals(filter)) {
      results = index.search(request);
      filter = request;
      queries++;
    }
    return results;
  }

  public long builds() {
    return builds;
  }

  public long queries() {
    return queries;
  }

  public int indexed() {
    return index == null ? 0 : index.size();
  }

  public void clear() {
    project = null;
    index = null;
    filter = null;
    results = List.of();
  }
}
