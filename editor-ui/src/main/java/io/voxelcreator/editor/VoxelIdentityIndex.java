package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Immutable incoming voxel-type resource references; never reads private authoring drafts. */
final class VoxelIdentityIndex {
  record Dependent(UUID uuid, String kind, String id) {}

  record Type(UUID uuid, String id, List<Dependent> dependents) {
    Type {
      dependents = List.copyOf(dependents);
    }
  }

  private final Map<UUID, Type> types;

  VoxelIdentityIndex(ProjectData project) {
    if (project.voxelTypes.isEmpty()) {
      types = Map.of();
      return;
    }
    var uses = new HashMap<UUID, List<Dependent>>();
    project.voxelTypes.forEach(t -> uses.put(t.uuid, new ArrayList<>()));
    // Match the recognized UUID-reference contract used by deletion/clipboard/import. Layers
    // intentionally include their contained operations, just as ResourceGraph.dependents does.
    for (var entry : ResourceGraph.entries(project)) {
      if (entry.kind().equals("voxel")) continue;
      var dependent = new Dependent(entry.uuid(), entry.kind(), entry.resource().id);
      for (UUID reference : ResourceGraph.references(entry.resource())) {
        var list = uses.get(reference);
        if (list != null) list.add(dependent);
      }
    }
    var frozen = new HashMap<UUID, Type>();
    project.voxelTypes.forEach(t -> frozen.put(t.uuid, new Type(t.uuid, t.id, uses.get(t.uuid))));
    types = Map.copyOf(frozen);
  }

  Type type(UUID id) {
    return id == null ? null : types.get(id);
  }

  static final class Cache {
    private Object lifetime;
    private WeakReference<ProjectData> source = new WeakReference<>(null);
    private long revision, builds;
    private VoxelIdentityIndex index;

    VoxelIdentityIndex get(Object session, ProjectData project, long version) {
      Objects.requireNonNull(session);
      Objects.requireNonNull(project);
      if (index == null || lifetime != session || source.get() != project || revision != version) {
        var next = new VoxelIdentityIndex(project);
        index = next;
        lifetime = session;
        source = new WeakReference<>(project);
        revision = version;
        builds++;
      }
      return index;
    }

    long builds() {
      return builds;
    }

    void clear() {
      lifetime = null;
      source.clear();
      index = null;
    }
  }
}
