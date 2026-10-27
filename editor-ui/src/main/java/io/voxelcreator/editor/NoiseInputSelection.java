package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Draft-local, bounded input selections. Navigation selects once; later user choices win. */
final class NoiseInputSelection {
  private Noise owner;
  private final LinkedHashMap<UUID, String> selected = new LinkedHashMap<>(16, .75f, true);

  private void synchronize(Noise noise) {
    if (owner != noise) {
      owner = noise;
      selected.clear();
    }
  }

  boolean select(Noise noise, Item item, String name) {
    synchronize(noise);
    if (!owns(noise, item) || !item.inputs.containsKey(name)) return false;
    remember(item.uuid, name);
    return true;
  }

  String current(Noise noise, Item item) {
    synchronize(noise);
    if (!owns(noise, item) || item.inputs.isEmpty()) {
      selected.remove(item.uuid);
      return null;
    }
    String name = selected.get(item.uuid);
    if (!item.inputs.containsKey(name)) name = item.inputs.keySet().iterator().next();
    remember(item.uuid, name);
    return name;
  }

  private static boolean owns(Noise noise, Item item) {
    return noise.processing.contains(item) || noise.coordinateTransforms.contains(item);
  }

  private void remember(UUID item, String name) {
    selected.put(item, name);
    if (selected.size() > 128) selected.remove(selected.keySet().iterator().next());
  }

  int retained() {
    return selected.size();
  }
}
