package io.voxelcreator.storage;

import io.voxelcreator.model.ProjectData;
import java.util.*;

/** Context-local runtime IDs; archives use semantic strings. */
public final class VoxelRegistry {
  private final List<String> ids;
  private final Map<String, Integer> semantic;
  private final Map<UUID, Integer> uuids;

  public VoxelRegistry(List<ProjectData.VoxelType> types) {
    var names = new ArrayList<String>();
    names.add("EMPTY");
    var byName = new HashMap<String, Integer>();
    var byUuid = new HashMap<UUID, Integer>();
    for (var t : types) {
      int id = names.size();
      if (t.id.equals("EMPTY") || byName.put(t.id, id) != null)
        throw new IllegalArgumentException("Duplicate/reserved voxel ID");
      byUuid.put(t.uuid, id);
      names.add(t.id);
    }
    ids = List.copyOf(names);
    semantic = Map.copyOf(byName);
    uuids = Map.copyOf(byUuid);
  }

  public static VoxelRegistry semantic(String... names) {
    var types = new ArrayList<ProjectData.VoxelType>();
    for (String n : names) {
      var t = new ProjectData.VoxelType();
      t.id = n;
      types.add(t);
    }
    return new VoxelRegistry(types);
  }

  public int runtimeId(String id) {
    if (id.equals("EMPTY")) return 0;
    var n = semantic.get(id);
    if (n == null) throw new IllegalArgumentException("Unknown semantic voxel ID " + id);
    return n;
  }

  public int runtimeId(UUID uuid) {
    var id = uuids.get(uuid);
    if (id == null) throw new IllegalArgumentException("Unknown voxel UUID " + uuid);
    return id;
  }

  public String semanticId(int runtime) {
    if (runtime < 0 || runtime >= ids.size())
      throw new IllegalArgumentException("Runtime voxel ID out of bounds");
    return ids.get(runtime);
  }

  public List<String> semanticIds() {
    return ids;
  }

  public int size() {
    return ids.size();
  }
}
