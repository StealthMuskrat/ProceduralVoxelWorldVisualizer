package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectIO;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
    public Map<String, float[]> statusColors = new HashMap<>();
  }

  public static final class Workspace {
    public boolean multiViewport = true;
    public int width = 1440, height = 900;
    public String docking = "";
  }

  public static final class Input {
    public Map<String, String> shortcuts = Shortcuts.defaults();
  }

  public static final class Editor {
    public boolean autoRegenerate = true;
    public int opportunitiesPerSecond = 10;
  }

  public static final class Visualization {
    public boolean grid = true,
        unlit = false,
        streaming = true,
        ambientOcclusion = true,
        fog = false,
        indirectDrawing = true;
    public String noise2DMode = "Grayscale";
    public double threshold = 0;
    public int previewSize = 32;
  }

  public static final class Performance {
    public int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    public int maxPreviewDimension = 96;
    public long previewCellBudget = 96L * 96 * 96;
    public int cacheMiB = 128,
        sceneCpuMiB = 128,
        sceneGpuMiB = 32,
        uploadKiB = 2048,
        renderDistance = 1,
        verticalDistance = 1,
        lodStart = 2,
        lodQuality = 1,
        streamChunks = 343,
        engineMiB = 512;
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
      return read(directory().resolve("user-config.json"));
    } catch (Exception e) {
      return new UserConfig();
    }
  }

  /**
   * Strict reader for validation/import/restart checks; normal startup retains its default
   * fallback.
   */
  public static UserConfig read(Path path) throws IOException {
    if (Files.size(path) > 8L * 1024 * 1024)
      throw new IllegalArgumentException("Preference file exceeds 8 MiB");
    com.fasterxml.jackson.databind.JsonNode tree;
    try (var input = Files.newInputStream(path)) {
      tree =
          ProjectIO.JSON
              .reader()
              .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(input);
    }
    if (tree == null
        || !tree.isObject()
        || !tree.path("formatVersion").isIntegralNumber()
        || !tree.path("formatVersion").canConvertToInt()
        || tree.path("formatVersion").intValue() != 1)
      throw new IllegalArgumentException("Invalid preference format version");
    var appearance = tree.path("appearance");
    for (String field : List.of("colors", "statusColors")) {
      if (!appearance.has(field)) continue;
      var map = appearance.get(field);
      if (!map.isObject()) throw new IllegalArgumentException("Invalid theme color map");
      for (var entries = map.fields(); entries.hasNext(); ) {
        var entry = entries.next();
        if (field.equals("colors") && !entry.getKey().matches("0|[1-9][0-9]*"))
          throw new IllegalArgumentException("Noncanonical theme color key");
        var value = entry.getValue();
        if (!value.isArray() || value.size() != 4)
          throw new IllegalArgumentException("Invalid RGBA theme color");
        for (var component : value)
          if (!component.isNumber()
              || !Double.isFinite(component.doubleValue())
              || component.doubleValue() < 0
              || component.doubleValue() > 1)
            throw new IllegalArgumentException("Invalid theme component");
      }
    }
    var user = ProjectIO.JSON.treeToValue(tree, UserConfig.class);
    if (user.input == null) throw new IllegalArgumentException("Missing input preferences");
    user.input.shortcuts = Shortcuts.upgrade(user.input.shortcuts);
    user.validate();
    return user;
  }

  public void validate() {
    if (appearance == null
        || workspace == null
        || input == null
        || editor == null
        || visualization == null
        || performance == null
        || appearance.windowScales == null)
      throw new IllegalArgumentException("Missing preference section");
    ThemePalette.from(appearance);
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
    if (!Float.isFinite(appearance.globalScale)
        || workspace.width < 400
        || workspace.height < 300
        || performance.maxPreviewDimension < 2
        || performance.maxPreviewDimension > 256
        || performance.previewCellBudget < 1
        || performance.cacheMiB < 0)
      throw new IllegalArgumentException("Invalid workspace/performance preferences");
    if (performance.sceneCpuMiB < 16
        || performance.sceneCpuMiB > 4096
        || performance.sceneGpuMiB < 8
        || performance.sceneGpuMiB > 256
        || performance.uploadKiB < 64
        || performance.uploadKiB > 16384
        || performance.renderDistance < 0
        || performance.renderDistance > 4
        || performance.verticalDistance < 0
        || performance.verticalDistance > 2
        || performance.lodStart < 1
        || performance.lodStart > 16
        || performance.lodQuality < 0
        || performance.lodQuality > 2
        || performance.streamChunks < 27
        || performance.streamChunks > 2048
        || performance.engineMiB < 64
        || performance.engineMiB > 4096)
      throw new IllegalArgumentException("Invalid streaming/render budgets");
    for (float value : appearance.windowScales.values())
      if (!Float.isFinite(value) || value < .5f || value > 2.5f)
        throw new IllegalArgumentException("Window scale outside 50..250%");
    if (input == null) throw new IllegalArgumentException("Missing input preferences");
    Shortcuts.canonical(input.shortcuts);
  }

  public void save() {
    save(directory().resolve("user-config.json"));
  }

  public void save(Path path) {
    validate();
    var snapshot = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(this), UserConfig.class);
    snapshot.input.shortcuts = Shortcuts.canonical(snapshot.input.shortcuts);
    snapshot.writeSnapshot(path);
  }

  /** Publish new live bindings only after validation and the entire preference write succeed. */
  public void applyShortcuts(Map<String, String> candidate, Path path) {
    var canonical = Shortcuts.canonical(candidate);
    var snapshot = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(this), UserConfig.class);
    snapshot.input.shortcuts = canonical;
    snapshot.validate();
    snapshot.writeSnapshot(path);
    input.shortcuts = new LinkedHashMap<>(canonical);
  }

  /** Merge only theme values into the latest preferences, persist, then publish owned copies. */
  public void applyTheme(ThemePalette candidate, Path path) {
    var snapshot = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(this), UserConfig.class);
    candidate.writeTo(snapshot.appearance);
    snapshot.validate();
    snapshot.writeSnapshot(path);
    candidate.writeTo(appearance);
  }

  private void writeSnapshot(Path path) {
    Path destination = path.toAbsolutePath(), temp = null;
    try {
      Files.createDirectories(destination.getParent());
      temp = Files.createTempFile(destination.getParent(), ".user-config-", ".tmp");
      Files.writeString(temp, ProjectIO.JSON.writeValueAsString(this), StandardCharsets.UTF_8);
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try {
        Files.move(
            temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Could not save preferences", e);
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
        }
    }
  }
}
