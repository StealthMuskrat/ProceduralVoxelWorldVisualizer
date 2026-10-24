package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Presentation metadata has no authority over computation, signatures or existing items. */
public final class AppMetadata {
  public int formatVersion = 1;
  public Map<String, Control> controls = new LinkedHashMap<>();

  public static final class Control {
    public String label = "", tooltip = "", description = "";

    public Control() {}

    Control(String l, String t, String d) {
      label = l;
      tooltip = t;
      description = d;
    }
  }

  public static AppMetadata defaults() {
    var m = new AppMetadata();
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
                + " removes it. SET, CLEAR and NO_CHANGE evaluate previous committed voxel"
                + " state."));
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
    return m;
  }

  public static AppMetadata load() {
    try {
      var m =
          ProjectIO.JSON.readValue(
              UserConfig.directory().resolve("app-config.json").toFile(), AppMetadata.class);
      if (m.formatVersion != 1 || !m.controls.keySet().equals(defaults().controls.keySet()))
        throw new IllegalArgumentException();
      return m;
    } catch (Exception e) {
      return defaults();
    }
  }

  public void save() {
    try {
      Files.createDirectories(UserConfig.directory());
      Files.writeString(
          UserConfig.directory().resolve("app-config.json"),
          ProjectIO.JSON.writeValueAsString(this));
    } catch (Exception e) {
      throw new IllegalStateException(e);
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
