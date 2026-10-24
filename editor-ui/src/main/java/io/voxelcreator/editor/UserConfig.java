package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Independent preference document. Never included in ProjectData or project dirty tracking. */
public final class UserConfig {
  public int formatVersion = 1;
  public Appearance appearance = new Appearance();
  public Workspace workspace = new Workspace();
  public Input input = new Input();
  public Editor editor = new Editor();
  public Visualization visualization = new Visualization();
  public Performance performance = new Performance();

  public static final class Appearance {
    public String theme = "Dark";
    public float globalScale = 1;
    public Map<String, Float> windowScales = new HashMap<>();
    public Map<Integer, float[]> colors = new HashMap<>();
  }

  public static final class Workspace {
    public boolean multiViewport = true;
    public int width = 1440, height = 900;
    public String docking = "";
  }

  public static final class Input {
    public Map<String, String> shortcuts =
        new LinkedHashMap<>(
            Map.of("Save", "Ctrl+S", "Undo", "Ctrl+Z", "Redo", "Ctrl+Y", "Find", "Ctrl+F"));
  }

  public static final class Editor {
    public boolean autoRegenerate = true;
    public int opportunitiesPerSecond = 10;
  }

  public static final class Visualization {
    public boolean grid = true, unlit = false;
    public String noise2DMode = "Grayscale";
    public double threshold = 0;
    public int previewSize = 32;
  }

  public static final class Performance {
    public int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    public int maxPreviewDimension = 96;
    public long previewCellBudget = 96L * 96 * 96;
    public int cacheMiB = 128;
    public boolean profiler = false;
    public String updateEndpoint = "";
    public List<String> trustedOrigins = new ArrayList<>();
  }

  public static Path directory() {
    return Path.of(
        System.getProperty(
            "voxel.configDir",
            Path.of(System.getProperty("user.home"), ".procedural-voxel-creator").toString()));
  }

  public static UserConfig load() {
    try {
      var u =
          ProjectIO.JSON.readValue(
              directory().resolve("user-config.json").toFile(), UserConfig.class);
      u.validate();
      return u;
    } catch (Exception e) {
      return new UserConfig();
    }
  }

  public void validate() {
    if (formatVersion != 1
        || appearance.globalScale < .5
        || appearance.globalScale > 2.5
        || editor.opportunitiesPerSecond < 1
        || editor.opportunitiesPerSecond > 20
        || performance.workers < 1
        || performance.workers > 64
        || visualization.previewSize < 2
        || visualization.previewSize > performance.maxPreviewDimension)
      throw new IllegalArgumentException("Invalid user preferences");
    var seen = new HashSet<String>();
    for (String chord : input.shortcuts.values())
      if (chord.isBlank() || !seen.add(chord))
        throw new IllegalArgumentException("Shortcut conflict: " + chord);
  }

  public void save() {
    validate();
    try {
      Files.createDirectories(directory());
      Path temp = directory().resolve("user-config.tmp");
      Files.writeString(temp, ProjectIO.JSON.writeValueAsString(this));
      Files.move(
          temp, directory().resolve("user-config.json"), StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      throw new IllegalStateException("Could not save preferences", e);
    }
  }
}
