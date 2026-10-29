package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Complete editor-owned performance limits and the coupled default preview extent. */
public final class PerformancePreferences {
  public enum Field {
    WORKERS("Performance", "workers", "Worker threads (restart)", 1, 64),
    MAX_DIMENSION("Performance", "maxPreviewDimension", "Maximum preview dimension", 2, 256),
    DEFAULT_EXTENT("Visualization", "previewSize", "Default preview extent (restart)", 2, 256),
    PREVIEW_CELLS("Performance", "previewCellBudget", "Preview cell budget", 1, Long.MAX_VALUE),
    CACHE_MIB("Performance", "cacheMiB", "Cache MiB", 0, Integer.MAX_VALUE),
    ENGINE_MIB("Performance", "engineMiB", "Engine memory MiB (restart)", 64, 4096),
    SCENE_CPU_MIB("Performance", "sceneCpuMiB", "Streaming CPU MiB per view", 16, 4096),
    SCENE_GPU_MIB("Performance", "sceneGpuMiB", "Streaming GPU MiB per view", 8, 256),
    UPLOAD_KIB("Performance", "uploadKiB", "Upload KiB per frame", 64, 16384),
    RENDER_DISTANCE("Performance", "renderDistance", "Render distance in chunks", 0, 4),
    VERTICAL_DISTANCE("Performance", "verticalDistance", "Vertical distance in chunks", 0, 2),
    LOD_START("Performance", "lodStart", "LOD start distance in chunks", 1, 16),
    LOD_QUALITY("Performance", "lodQuality", "LOD quality (0 low, 2 high)", 0, 2),
    STREAM_CHUNKS("Performance", "streamChunks", "Resident streaming chunk limit", 27, 2048);
    private final String section, key, label;
    private final long minimum, maximum;

    Field(String section, String key, String label, long minimum, long maximum) {
      this.section = section;
      this.key = key;
      this.label = label;
      this.minimum = minimum;
      this.maximum = maximum;
    }

    public String section() {
      return section;
    }

    public String key() {
      return key;
    }

    public String label() {
      return label;
    }

    public long minimum() {
      return minimum;
    }

    public long maximum() {
      return maximum;
    }

    void validate(long value) {
      if (value < minimum || value > maximum)
        throw new IllegalArgumentException(label + " must be " + minimum + ".." + maximum);
    }
  }

  public record Values(Map<Field, Long> fields) {
    public Values {
      Objects.requireNonNull(fields);
      if (!fields.keySet().equals(EnumSet.allOf(Field.class)))
        throw new IllegalArgumentException("Complete performance settings are required");
      var copy = new EnumMap<Field, Long>(Field.class);
      fields.forEach((key, value) -> copy.put(key, Objects.requireNonNull(value)));
      fields = Collections.unmodifiableMap(copy);
    }

    public long get(Field field) {
      return fields.get(field);
    }

    public void validate() {
      fields.forEach(Field::validate);
      if (get(Field.DEFAULT_EXTENT) > get(Field.MAX_DIMENSION))
        throw new IllegalArgumentException(
            "Default preview extent exceeds Maximum preview dimension");
    }

    void writeTo(UserConfig user) {
      validate();
      var p = user.performance;
      p.workers = (int) get(Field.WORKERS);
      p.maxPreviewDimension = (int) get(Field.MAX_DIMENSION);
      user.visualization.previewSize = (int) get(Field.DEFAULT_EXTENT);
      p.previewCellBudget = get(Field.PREVIEW_CELLS);
      p.cacheMiB = (int) get(Field.CACHE_MIB);
      p.engineMiB = (int) get(Field.ENGINE_MIB);
      p.sceneCpuMiB = (int) get(Field.SCENE_CPU_MIB);
      p.sceneGpuMiB = (int) get(Field.SCENE_GPU_MIB);
      p.uploadKiB = (int) get(Field.UPLOAD_KIB);
      p.renderDistance = (int) get(Field.RENDER_DISTANCE);
      p.verticalDistance = (int) get(Field.VERTICAL_DISTANCE);
      p.lodStart = (int) get(Field.LOD_START);
      p.lodQuality = (int) get(Field.LOD_QUALITY);
      p.streamChunks = (int) get(Field.STREAM_CHUNKS);
    }
  }

  private PerformancePreferences() {}

  public static Values from(UserConfig user) {
    var p = Objects.requireNonNull(user.performance);
    var values = new EnumMap<Field, Long>(Field.class);
    values.put(Field.WORKERS, (long) p.workers);
    values.put(Field.MAX_DIMENSION, (long) p.maxPreviewDimension);
    values.put(Field.DEFAULT_EXTENT, (long) user.visualization.previewSize);
    values.put(Field.PREVIEW_CELLS, p.previewCellBudget);
    values.put(Field.CACHE_MIB, (long) p.cacheMiB);
    values.put(Field.ENGINE_MIB, (long) p.engineMiB);
    values.put(Field.SCENE_CPU_MIB, (long) p.sceneCpuMiB);
    values.put(Field.SCENE_GPU_MIB, (long) p.sceneGpuMiB);
    values.put(Field.UPLOAD_KIB, (long) p.uploadKiB);
    values.put(Field.RENDER_DISTANCE, (long) p.renderDistance);
    values.put(Field.VERTICAL_DISTANCE, (long) p.verticalDistance);
    values.put(Field.LOD_START, (long) p.lodStart);
    values.put(Field.LOD_QUALITY, (long) p.lodQuality);
    values.put(Field.STREAM_CHUNKS, (long) p.streamChunks);
    return new Values(values);
  }

  /**
   * Reject JSON coercion/narrowing before Jackson binds preference fields; missing values inherit.
   */
  static void validateStored(JsonNode tree) {
    for (var field : Field.values()) {
      var section =
          tree.path(field.section().equals("Performance") ? "performance" : "visualization");
      if (!section.has(field.key())) continue;
      var value = section.get(field.key());
      if (!value.isIntegralNumber() || !value.canConvertToLong())
        throw new IllegalArgumentException("Invalid integer preference: " + field.label());
      field.validate(value.longValue());
    }
  }
}
