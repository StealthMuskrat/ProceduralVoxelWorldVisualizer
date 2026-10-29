package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectIO;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Independent preference document. Never included in ProjectData or project dirty tracking. */
public final class UserConfig {
  private final PreferenceFiles files;

  public UserConfig() {
    this(new PreferenceFiles());
  }

  UserConfig(PreferenceFiles files) {
    this.files = Objects.requireNonNull(files);
  }

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
    public Integer x, y;
    public boolean maximized = false;
    public String docking = "";
    public List<String> visibleWindows = new ArrayList<>();
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
        shadows = false,
        volumeShadows = false,
        indirectDrawing = true;
    public String noise2DMode = "Grayscale";
    public double threshold = 0;
    public int previewSize = 32;
    public int shadowResolution = 1024;
    public int volumeShadowSteps = 16;
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
    com.fasterxml.jackson.databind.JsonNode tree =
        ProjectIO.JSON
            .reader()
            .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readTree(PreferenceFiles.read(path));
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
    var visualization = tree.path("visualization");
    if (visualization.has("shadows") && !visualization.path("shadows").isBoolean())
      throw new IllegalArgumentException("Invalid shadows preference");
    if (visualization.has("shadowResolution")
        && (!visualization.path("shadowResolution").isIntegralNumber()
            || !visualization.path("shadowResolution").canConvertToInt()))
      throw new IllegalArgumentException("Invalid shadow resolution");
    if (visualization.has("volumeShadows") && !visualization.path("volumeShadows").isBoolean())
      throw new IllegalArgumentException("Invalid volume shadow preference");
    if (visualization.has("volumeShadowSteps")
        && (!visualization.path("volumeShadowSteps").isIntegralNumber()
            || !visualization.path("volumeShadowSteps").canConvertToInt()))
      throw new IllegalArgumentException("Invalid volume shadow steps");
    PerformancePreferences.validateStored(tree);
    WindowPlacement.validateStored(tree);
    UiScalePreferences.validateStored(tree);
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
    UiScalePreferences.validate(appearance);
    if (workspace.docking == null)
      throw new IllegalArgumentException("Missing docking preferences");
    WorkspaceVisibility.validate(workspace.visibleWindows);
    WindowPlacement.validate(workspace);
    if (performance.updateEndpoint == null
        || performance.trustedOrigins == null
        || performance.trustedOrigins.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Missing manual update preferences");
    if (formatVersion != 1
        || appearance.globalScale < .5
        || appearance.globalScale > 2.5
        || editor.opportunitiesPerSecond < 1
        || editor.opportunitiesPerSecond > 20
        || !io.voxelcreator.render.DirectionalShadow.validResolution(visualization.shadowResolution)
        || !io.voxelcreator.render.VolumeLight.validSteps(visualization.volumeShadowSteps))
      throw new IllegalArgumentException("Invalid user preferences");
    if (!Float.isFinite(appearance.globalScale) || workspace.width < 400 || workspace.height < 300)
      throw new IllegalArgumentException("Invalid workspace preferences");
    PerformancePreferences.from(this).validate();
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
    snapshot.writeSnapshot(path, files);
  }

  /** Publish new live bindings only after validation and the entire preference write succeed. */
  public void applyShortcuts(Map<String, String> candidate, Path path) {
    var canonical = Shortcuts.canonical(candidate);
    var snapshot = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(this), UserConfig.class);
    snapshot.input.shortcuts = canonical;
    snapshot.validate();
    snapshot.writeSnapshot(path, files);
    input.shortcuts = new LinkedHashMap<>(canonical);
  }

  /** Merge only theme values into the latest preferences, persist, then publish owned copies. */
  public void applyTheme(ThemePalette candidate, Path path) {
    var snapshot = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(this), UserConfig.class);
    candidate.writeTo(snapshot.appearance);
    snapshot.validate();
    snapshot.writeSnapshot(path, files);
    candidate.writeTo(appearance);
  }

  /** Publish manual-update configuration only after the complete preference file is written. */
  public void applyUpdateTrust(String endpoint, List<String> origins, Path path) {
    var candidate = UpdateTrust.parse(endpoint, String.join("\n", Objects.requireNonNull(origins)));
    var snapshot = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(this), UserConfig.class);
    snapshot.performance.updateEndpoint = candidate.endpoint();
    snapshot.performance.trustedOrigins = new ArrayList<>(candidate.origins());
    snapshot.validate();
    snapshot.writeSnapshot(path, files);
    performance.updateEndpoint = candidate.endpoint();
    performance.trustedOrigins = new ArrayList<>(candidate.origins());
  }

  /** Merge only the complete owned limits/default extent, persist, then publish together. */
  public void applyPerformance(PerformancePreferences.Values candidate, Path path) {
    Objects.requireNonNull(candidate).validate();
    var snapshot = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(this), UserConfig.class);
    candidate.writeTo(snapshot);
    snapshot.validate();
    snapshot.writeSnapshot(path, files);
    candidate.writeTo(this);
  }

  /** Persist owned global/known window scales while retaining latest unrelated/extension values. */
  public void applyScales(UiScalePreferences.Values candidate, Path path) {
    Objects.requireNonNull(candidate);
    var snapshot = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(this), UserConfig.class);
    candidate.writeTo(snapshot.appearance);
    snapshot.validate();
    snapshot.writeSnapshot(path, files);
    candidate.writeTo(appearance);
  }

  private void writeSnapshot(Path path, PreferenceFiles writer) {
    try {
      writer.write(path, this, ".user-config-");
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException("Could not save preferences", failure);
    }
  }
}
