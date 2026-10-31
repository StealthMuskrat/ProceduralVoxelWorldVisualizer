package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.util.*;

/** Immutable colors in the runtime/ghost order of one accepted World source. */
final class WorldPreviewPalette {
  private final UUID project;
  private final List<UUID> types;
  private final int[] colors;

  private WorldPreviewPalette(UUID project, List<UUID> types, int[] ownedColors) {
    this.project = project;
    this.types = types;
    this.colors = ownedColors;
  }

  static WorldPreviewPalette capture(ProjectData source, int[] colors) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(colors);
    var types = source.voxelTypes.stream().map(type -> Objects.requireNonNull(type.uuid)).toList();
    if (colors.length < 2L * types.size() + 1)
      throw new IllegalArgumentException("Palette does not cover runtime and removal-ghost IDs");
    return new WorldPreviewPalette(
        Objects.requireNonNull(source.project.uuid), List.copyOf(types), colors.clone());
  }

  WorldPreviewPalette refresh(ProjectData current) {
    if (!project.equals(current.project.uuid)) return this;
    var live = new HashMap<UUID, ProjectData.Color>();
    for (var type : current.voxelTypes) live.put(type.uuid, type.color);
    var next = colors.clone();
    for (int i = 0; i < types.size(); i++) {
      var color = live.get(types.get(i));
      if (color == null) continue;
      next[i + 1] = rgba(color.r, color.g, color.b);
      next[types.size() + i + 1] = rgba(235, Math.max(30, color.g / 3), Math.max(45, color.b / 3));
    }
    return Arrays.equals(colors, next) ? this : new WorldPreviewPalette(project, types, next);
  }

  List<UUID> types() {
    return types;
  }

  int[] colors() {
    return colors.clone();
  }

  private static int rgba(int r, int g, int b) {
    return r | (g << 8) | (b << 16) | 0xff000000;
  }
}
