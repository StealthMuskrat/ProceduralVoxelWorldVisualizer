package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.core.*;
import io.voxelcreator.model.ProjectIO;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Presentation metadata has no authority over computation, signatures or existing items. */
public final class AppMetadata {
  public int formatVersion = 1;
  public Map<String, Control> controls = new LinkedHashMap<>();
  public Map<String, JsonNode> creationDefaults = new LinkedHashMap<>();

  public static final class Control {
    public String label = "", tooltip = "", description = "", group = "";
    public int order = 0;
    public Map<String, String> options = new LinkedHashMap<>();

    public Control() {}

    Control(String l, String t, String d) {
      label = l;
      tooltip = t;
      description = d;
    }
  }

  private static AppMetadata builtIns() {
    var m = new AppMetadata();
    m.controls.put(
        "themes",
        new Control(
            "Themes and contrast",
            "Preview, measure and save UI colors",
            "View > Themes previews grouped colors independently of saved preferences. Save Theme"
                + " persists palette values only; Revert or closing restores the active theme."
                + " Reset stages documented Dark defaults. Contrast feedback measures"
                + " alpha-composited text and selected control pairs, while the inspector keeps a"
                + " readable palette. Status messages retain text indicators. Themes never change"
                + " project values or evaluation."));
    m.controls.put(
        "noise",
        new Control(
            "Noise Editor",
            "Author a reusable scalar field",
            "Create a 2D or 3D resource. Edit source, seed, transforms and ordered processing."
                + " Apply commits only valid definitions; shared resources retain their own"
                + " settings when used in a layer."));
    m.controls.put(
        "layers",
        new Control(
            "World Generator",
            "Ordered layers and independent placements",
            "Generation starts EMPTY. Layers run from top to bottom. Each placement inherits absent"
                + " properties live; Override stores only that placement's property, and Reset"
                + " removes it. SET, CLEAR and NO_CHANGE evaluate previous committed voxel state."
                + " Ordered numeric parameter drivers can replace, add to or multiply thresholds,"
                + " base height and height scale before interpretation."));
    m.controls.put(
        "navigation",
        new Control(
            "Navigation",
            "Orbit, pan and free-fly",
            "3D: left drag orbits, right drag pans, middle drag moves X/Z, Ctrl+middle moves Y,"
                + " wheel zooms. Ctrl+click enters free-fly. WASD, Space and Shift move; Escape"
                + " releases capture. Teleport changes coordinates only."));
    m.controls.put(
        "save",
        new Control(
            "Projects",
            "Explicit saving only",
            "New, Open and Exit ask Save / Don't Save / Cancel when dirty. Save validates then"
                + " writes a temporary sibling and atomically replaces the destination where"
                + " supported. No autosave or recent project list."));
    m.controls.put(
        "processing",
        new Control(
            "Processing and expressions",
            "One canonical ordered definition",
            "Inputs explicitly select constants, shared parameters, ambient variables, source,"
                + " earlier results or noise references. Scalar/Boolean expressions are typed,"
                + " bounded and stateless. Power is right associative; &&, || and ?: short circuit."
                + " Invalid numeric domains fail the sample."));
    m.controls.put(
        "archives",
        new Control(
            "Voxel archives",
            "Exact semantic voxel results",
            "Archives store compressed adaptive chunks and a semantic voxel ID dictionary. Coverage"
                + " distinguishes omitted EMPTY payloads from unarchived coordinates. Rendering"
                + " quality never changes exact data."));
    m.controls.put(
        "bookmarks",
        new Control(
            "Bookmarks",
            "Separate coordinate lists for each tab",
            "Bookmarks store only coordinates and a name. Adding, renaming, updating or deleting is"
                + " an undoable project edit; teleporting never dirties the document."));
    m.controls.put(
        "diagnostics",
        new Control(
            "Validation, logs and notifications",
            "Inspect grouped events and navigate to their source",
            "View > Validate Project checks the committed document without changing it. Logs"
                + " support severity, subsystem and text filters. Go To Problem opens the stored"
                + " UUID resource or control when available in the current project or draft."
                + " Reading a notification does not resolve an action. Mark Action Resolved"
                + " acknowledges the action; it does not repair project data. Dismiss hides a"
                + " resolved notification while retaining its grouped log history."));
    m.controls.put(
        "profiler",
        new Control(
            "Profiler captures",
            "Opt-in bounded CPU timings and metrics",
            "View > Profiler starts an explicit capture with duration, sample, series and"
                + " operation-sampling limits. Inspect counts, mean and P50/P95/P99, filter"
                + " source/context series and navigate to existing resources. JSON/CSV export"
                + " writes all retained measurements only when requested. CPU-side mesh/upload"
                + " timings do not measure GPU completion. GPU arenas compact within their existing"
                + " vertex budget and share upload write limits. Captures and navigation never"
                + " dirty the project."));
    m.controls.put(
        "shortcuts",
        new Control(
            "Keyboard shortcuts",
            "Context-aware remapping with conflict detection",
            "User Settings > Input keeps a private binding draft. Type or record"
                + " Ctrl/Shift/Alt/Super chords with letters, digits, F1-F12 or named keys. Apply"
                + " validates conflicts and saves atomically before activation; Revert discards the"
                + " draft, and Reset stages defaults. Resource clipboard keys require resource"
                + " focus, curve undo affects its private history, and text/popups/free-fly retain"
                + " their own input. Escape cancels recording. Preferences never dirty the"
                + " project."));
    m.controls.put(
        "creation-defaults",
        new Control(
            "Creation defaults",
            "Future items only",
            "Application Settings edits labels, help, grouping, order and validated future-item"
                + " defaults. Apply saves a private metadata draft atomically. New items store"
                + " concrete values in the project; existing resources, copied/imported"
                + " definitions, loading and evaluation never consult these defaults. Immutable"
                + " registry keys, signatures, options and creation ranges cannot be edited"
                + " here."));
    m.controls.put(
        "noise.algorithm",
        new Control(
            "Algorithm",
            "Choose the source algorithm",
            "Editing a source chooses a fixed registry algorithm key; display labels do not change"
                + " that key."));
    m.controls.put(
        "noise.seedOffset",
        new Control(
            "Seed offset",
            "Local resource seed offset",
            "The authored signed 64-bit offset is independent of presentation and future-item"
                + " defaults."));
    int order = 0;
    for (var control : m.controls.values()) {
      control.group = "Overview";
      control.order = order++;
    }
    for (String key : NoiseAlgorithms.KEYS) {
      var control =
          new Control(
              key,
              "Source algorithm " + key,
              "Algorithm identity and valid parameters remain defined by the engine registry.");
      control.group = "Algorithms";
      m.controls.put("algorithm." + key, control);
    }
    for (var entry : ProcessingRegistry.ITEMS.entrySet()) {
      var control =
          new Control(
              entry.getKey(),
              entry.getValue().category(),
              "Result "
                  + entry.getValue().result()
                  + "; registry inputs "
                  + entry.getValue().inputs()
                  + ". Labels do not alter this signature.");
      if (ProcessingExtensions.DETAILS.containsKey(entry.getKey()))
        control.description = ProcessingExtensions.DETAILS.get(entry.getKey()).description();
      control.group = "Processing";
      m.controls.put("processing." + entry.getKey(), control);
    }
    for (String key : CreationRegistry.TRANSFORMS) {
      var control =
          new Control(
              key,
              "Coordinate transform " + key,
              "Ordered transforms use the authored coordinates and registry semantics.");
      control.group = "Transforms";
      m.controls.put("transform." + key, control);
    }
    for (var spec : CreationRegistry.CONTROLS.values()) {
      var control =
          new Control(
              spec.label(),
              "Creation control: " + spec.id(),
              "Future-item value; immutable type "
                  + spec.kind()
                  + ". Existing project fields retain their authored values.");
      control.group = spec.group();
      m.controls.put(spec.id(), control);
    }
    return m;
  }

  private static final class BuiltIns {
    static final AppMetadata VALUE = builtIns();
  }

  public static AppMetadata defaults() {
    return copy(BuiltIns.VALUE);
  }

  public static AppMetadata copy(AppMetadata metadata) {
    return ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(metadata), AppMetadata.class);
  }

  public Control control(String id) {
    var control = controls.get(id);
    if (control != null) return control;
    var fallback = BuiltIns.VALUE.controls.get(id);
    return fallback == null
        ? null
        : ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(fallback), Control.class);
  }

  public String label(String id, String fallback) {
    var control = control(id);
    return uiText(control == null ? fallback : control.label);
  }

  public String option(String id, String value) {
    var control = control(id);
    return uiText(control == null ? value : control.options.getOrDefault(value, value));
  }

  public static String uiText(String value) {
    var text = new StringBuilder();
    char previous = 0;
    for (char c : value.toCharArray()) {
      if (c == '#' && previous == '#') text.append(' ');
      text.append(c);
      previous = c;
    }
    return text.toString();
  }

  public List<Map.Entry<String, Control>> orderedControls() {
    var merged = new LinkedHashMap<String, Control>();
    for (var entry : BuiltIns.VALUE.controls.entrySet())
      merged.put(
          entry.getKey(),
          controls.containsKey(entry.getKey())
              ? controls.get(entry.getKey())
              : ProjectIO.JSON.convertValue(
                  ProjectIO.JSON.valueToTree(entry.getValue()), Control.class));
    return merged.entrySet().stream()
        .sorted(
            Comparator.comparing((Map.Entry<String, Control> e) -> e.getValue().group)
                .thenComparingInt(e -> e.getValue().order)
                .thenComparing(Map.Entry::getKey))
        .toList();
  }

  public static AppMetadata load() {
    try {
      return read(UserConfig.directory().resolve("app-config.json"));
    } catch (Exception e) {
      return defaults();
    }
  }

  public static AppMetadata read(Path path) throws IOException {
    if (Files.size(path) > 8L * 1024 * 1024)
      throw new IllegalArgumentException("Application metadata exceeds 8 MiB");
    JsonNode tree;
    try (var input = Files.newInputStream(path)) {
      tree =
          ProjectIO.JSON
              .reader()
              .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(input);
    }
    if (tree == null) throw new IllegalArgumentException("Application metadata object required");
    if (!tree.path("formatVersion").isIntegralNumber()
        || tree.path("formatVersion").intValue() != 1)
      throw new IllegalArgumentException("Unsupported application metadata version");
    var parsed = ProjectIO.JSON.treeToValue(tree, AppMetadata.class);
    var result = defaults();
    result.creationDefaults = parsed.creationDefaults;
    if (parsed.controls == null) throw new IllegalArgumentException("Missing controls object");
    for (var entry : parsed.controls.entrySet()) {
      var base = result.controls.get(entry.getKey());
      if (base == null)
        throw new IllegalArgumentException("Unknown metadata control " + entry.getKey());
      var node = (ObjectNode) ProjectIO.JSON.valueToTree(base);
      var authored = tree.path("controls").path(entry.getKey());
      if (!authored.isObject()) throw new IllegalArgumentException("Control object required");
      authored.fields().forEachRemaining(field -> node.set(field.getKey(), field.getValue()));
      result.controls.put(entry.getKey(), ProjectIO.JSON.treeToValue(node, Control.class));
    }
    result.validate();
    return result;
  }

  public void validate() {
    if (formatVersion != 1
        || controls == null
        || creationDefaults == null
        || controls.size() > BuiltIns.VALUE.controls.size()
        || creationDefaults.size() > CreationRegistry.CONTROLS.size())
      throw new IllegalArgumentException("Invalid application metadata structure");
    for (var entry : controls.entrySet()) {
      var canonical = BuiltIns.VALUE.controls.get(entry.getKey());
      var value = entry.getValue();
      if (canonical == null || value == null)
        throw new IllegalArgumentException("Unknown/null metadata control " + entry.getKey());
      line(value.label, 160, true);
      text(value.tooltip, 1024, false);
      text(value.description, 8192, false);
      line(value.group, 80, false);
      if (value.order < -10000 || value.order > 10000 || value.options == null)
        throw new IllegalArgumentException("Invalid presentation order/options");
      var spec = CreationRegistry.CONTROLS.get(entry.getKey());
      var options = spec == null ? List.<String>of() : spec.options();
      if (!options.containsAll(value.options.keySet()))
        throw new IllegalArgumentException("Unknown option key for " + entry.getKey());
      for (String label : value.options.values()) line(label, 160, true);
    }
    creationDefaults.forEach((id, value) -> CreationRegistry.get(id).validate(value));
    EditorCreations.validateDefaults(this);
  }

  private static void line(String value, int limit, boolean required) {
    text(value, limit, required);
    if (value.chars().anyMatch(c -> c < 32))
      throw new IllegalArgumentException("Presentation labels/groups must be single-line text");
  }

  private static void text(String value, int limit, boolean required) {
    if (value == null
        || value.length() > limit
        || value.indexOf(0) >= 0
        || required && value.isBlank())
      throw new IllegalArgumentException("Invalid presentation text (limit " + limit + ")");
  }

  public void save() {
    save(UserConfig.directory().resolve("app-config.json"));
  }

  public void save(Path path) {
    validate();
    var snapshot = copy(this);
    Path destination = path.toAbsolutePath(), temp = null;
    try {
      Files.createDirectories(destination.getParent());
      temp = Files.createTempFile(destination.getParent(), ".app-config-", ".tmp");
      Files.writeString(temp, ProjectIO.JSON.writeValueAsString(snapshot), StandardCharsets.UTF_8);
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
      throw new IllegalStateException("Could not save application metadata", e);
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
        }
    }
  }

  public static String settingLabel(String key) {
    return switch (key) {
      case "baseHeight" -> "Base Height";
      case "heightScale" -> "Height Scale";
      case "minY" -> "Minimum Y";
      case "maxY" -> "Maximum Y";
      case "seedOffset" -> "Seed Offset";
      case "voxelTypeUuid" -> "Voxel Type";
      case "voxelStatePredicate" -> "Existing Voxel State";
      case "fieldGates" -> "Field Selection Gates";
      case "axisX" -> "Sample X Axis";
      case "axisY" -> "Sample Y Axis";
      case "axisZ" -> "Sample Z Axis";
      case "samplingTransforms" -> "Ordered Sampling Transforms";
      case "typeSource" -> "Type Source";
      case "typeIntervals" -> "Voxel Type Intervals";
      case "probabilityDriver" -> "Probability Field Driver";
      case "strengthDriver" -> "Strength Field Driver";
      case "parameterDrivers" -> "Numeric Parameter Drivers";
      case "scaleX" -> "Scale X";
      case "scaleY" -> "Scale Y";
      case "scaleZ" -> "Scale Z";
      case "offsetX" -> "Offset X";
      case "offsetY" -> "Offset Y";
      case "offsetZ" -> "Offset Z";
      default -> Character.toUpperCase(key.charAt(0)) + key.substring(1);
    };
  }
}
