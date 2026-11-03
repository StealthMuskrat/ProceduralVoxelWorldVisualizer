package io.voxelcreator.editor;

import io.voxelcreator.model.SettingsResolver;
import java.util.*;

/** Presentation-only World families; nested indices never become metadata or navigation IDs. */
final class WorldControlMetadata {
  record Definition(String id, String label, String tooltip, String description) {}

  static final List<Definition> DEFINITIONS =
      List.of(
          new Definition(
              "world.enabled",
              "World Enable State",
              "A disabled layer skips all its placements; a disabled placement skips that"
                  + " operation. Toggling Enabled stays private until Apply.",
              "Enable state changes execution eligibility without deleting definitions or"
                  + " overrides."),
          new Definition(
              "world.source",
              "Placement Noise Source",
              "Select an ordinary 2D or 3D noise resource by UUID. The placement samples its"
                  + " canonical shared definition under the resolved World settings.",
              "A placement stores a reference, not a copied noise pipeline. Source algorithm"
                  + " parameters, transforms and processing remain editable on the referenced"
                  + " resource."),
          new Definition(
              "world.interpretation",
              "Placement Interpretation",
              "Choose how the source scalar selects cells: height below/above/boundary, selection,"
                  + " density, type selection or ALL.",
              "Height modes use rounded Base Height plus field times Height Scale. Selection and"
                  + " density use field >= Threshold. TYPE_SELECTION and ALL omit the threshold"
                  + " comparison; type choice follows FIXED or INTERVALS. Gates, previous-state"
                  + " predicates and deterministic acceptance still apply."),
          new Definition(
              "world.inheritance",
              "Live Layer Inheritance",
              "Unchecked properties inherit current layer defaults. Enabling an override copies a"
                  + " starting value into this private placement; Reset removes the override.",
              "Layer defaults stay live until an explicit placement override is enabled. Zero is"
                  + " still an override. Reset removes the override rather than copying a resolved"
                  + " value. Properties edits remain private until Apply; Save persists the"
                  + " committed project."),
          new Definition(
              "world.setting.baseHeight",
              "Base Height",
              "Height interpretations start at this world-space height before adding the sampled"
                  + " field times Height Scale.",
              "Height interpretations start at this world-space height before adding the sampled"
                  + " field times Height Scale. Layer defaults stay live until an explicit"
                  + " placement override is enabled. Zero is still an override. Reset removes the"
                  + " override rather than copying a resolved value. Properties edits remain"
                  + " private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.heightScale",
              "Height Scale",
              "Multiply the placement field by this factor before adding Base Height and applying"
                  + " the selected height rounding.",
              "Multiply the placement field by this factor before adding Base Height and applying"
                  + " the selected height rounding. Layer defaults stay live until an explicit"
                  + " placement override is enabled. Zero is still an override. Reset removes the"
                  + " override rather than copying a resolved value. Properties edits remain"
                  + " private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.threshold",
              "Threshold",
              "Selection and density use sampled field >= Threshold. Height interpretations use"
                  + " Base Height and Height Scale.",
              "Selection and density use sampled field >= Threshold. Height interpretations use"
                  + " Base Height and Height Scale. Layer defaults stay live until an explicit"
                  + " placement override is enabled. Zero is still an override. Reset removes the"
                  + " override rather than copying a resolved value. Properties edits remain"
                  + " private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.strength",
              "Strength",
              "Constant acceptance factor in [0,1]. It combines with probability and enabled mapped"
                  + " field factors before deterministic acceptance.",
              "Constant acceptance factor in [0,1]. It combines with probability and enabled mapped"
                  + " field factors before deterministic acceptance. Layer defaults stay live until"
                  + " an explicit placement override is enabled. Zero is still an override. Reset"
                  + " removes the override rather than copying a resolved value. Properties edits"
                  + " remain private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.probability",
              "Probability",
              "Placement acceptance factor in [0,1]. The operation and voxel coordinates keep its"
                  + " acceptance draw deterministic.",
              "Placement acceptance factor in [0,1]. The operation and voxel coordinates keep its"
                  + " acceptance draw deterministic. Layer defaults stay live until an explicit"
                  + " placement override is enabled. Zero is still an override. Reset removes the"
                  + " override rather than copying a resolved value. Properties edits remain"
                  + " private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.minY",
              "Minimum Y",
              "Inclusive lower world Y limit for this operation, further restricted by the project"
                  + " vertical bounds.",
              "Inclusive lower world Y limit for this operation, further restricted by the project"
                  + " vertical bounds. Layer defaults stay live until an explicit placement"
                  + " override is enabled. Zero is still an override. Reset removes the override"
                  + " rather than copying a resolved value. Properties edits remain private until"
                  + " Apply; Save persists the committed project."),
          new Definition(
              "world.setting.maxY",
              "Maximum Y",
              "Inclusive upper world Y limit for this operation, further restricted by the project"
                  + " vertical bounds.",
              "Inclusive upper world Y limit for this operation, further restricted by the project"
                  + " vertical bounds. Layer defaults stay live until an explicit placement"
                  + " override is enabled. Zero is still an override. Reset removes the override"
                  + " rather than copying a resolved value. Properties edits remain private until"
                  + " Apply; Save persists the committed project."),
          new Definition(
              "world.setting.axisX",
              "Sample X Axis",
              "Choose the signed world-coordinate axis supplying sample X before its scale, offset"
                  + " and ordered sampling transforms.",
              "Choose the signed world-coordinate axis supplying sample X before its scale, offset"
                  + " and ordered sampling transforms. Layer defaults stay live until an explicit"
                  + " placement override is enabled. Zero is still an override. Reset removes the"
                  + " override rather than copying a resolved value. Properties edits remain"
                  + " private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.axisY",
              "Sample Y Axis",
              "Choose the signed world-coordinate axis supplying sample Y before its scale, offset"
                  + " and ordered sampling transforms.",
              "Choose the signed world-coordinate axis supplying sample Y before its scale, offset"
                  + " and ordered sampling transforms. Layer defaults stay live until an explicit"
                  + " placement override is enabled. Zero is still an override. Reset removes the"
                  + " override rather than copying a resolved value. Properties edits remain"
                  + " private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.axisZ",
              "Sample Z Axis",
              "Choose the signed world-coordinate axis supplying sample Z before its scale, offset"
                  + " and ordered sampling transforms.",
              "Choose the signed world-coordinate axis supplying sample Z before its scale, offset"
                  + " and ordered sampling transforms. Layer defaults stay live until an explicit"
                  + " placement override is enabled. Zero is still an override. Reset removes the"
                  + " override rather than copying a resolved value. Properties edits remain"
                  + " private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.scaleX",
              "Scale X",
              "Scale the chosen sample X axis before adding Offset X and applying ordered sampling"
                  + " transforms. This does not edit the source noise definition.",
              "Scale the chosen sample X axis before adding Offset X and applying ordered sampling"
                  + " transforms. This does not edit the source noise definition. Layer defaults"
                  + " stay live until an explicit placement override is enabled. Zero is still an"
                  + " override. Reset removes the override rather than copying a resolved value."
                  + " Properties edits remain private until Apply; Save persists the committed"
                  + " project."),
          new Definition(
              "world.setting.scaleY",
              "Scale Y",
              "Scale the chosen sample Y axis before adding Offset Y and applying ordered sampling"
                  + " transforms. This does not edit the source noise definition.",
              "Scale the chosen sample Y axis before adding Offset Y and applying ordered sampling"
                  + " transforms. This does not edit the source noise definition. Layer defaults"
                  + " stay live until an explicit placement override is enabled. Zero is still an"
                  + " override. Reset removes the override rather than copying a resolved value."
                  + " Properties edits remain private until Apply; Save persists the committed"
                  + " project."),
          new Definition(
              "world.setting.scaleZ",
              "Scale Z",
              "Scale the chosen sample Z axis before adding Offset Z and applying ordered sampling"
                  + " transforms. This does not edit the source noise definition.",
              "Scale the chosen sample Z axis before adding Offset Z and applying ordered sampling"
                  + " transforms. This does not edit the source noise definition. Layer defaults"
                  + " stay live until an explicit placement override is enabled. Zero is still an"
                  + " override. Reset removes the override rather than copying a resolved value."
                  + " Properties edits remain private until Apply; Save persists the committed"
                  + " project."),
          new Definition(
              "world.setting.offsetX",
              "Offset X",
              "Add to the scaled sample X coordinate before ordered sampling transforms. Source"
                  + " noise transforms remain part of the shared source definition.",
              "Add to the scaled sample X coordinate before ordered sampling transforms. Source"
                  + " noise transforms remain part of the shared source definition. Layer defaults"
                  + " stay live until an explicit placement override is enabled. Zero is still an"
                  + " override. Reset removes the override rather than copying a resolved value."
                  + " Properties edits remain private until Apply; Save persists the committed"
                  + " project."),
          new Definition(
              "world.setting.offsetY",
              "Offset Y",
              "Add to the scaled sample Y coordinate before ordered sampling transforms. Source"
                  + " noise transforms remain part of the shared source definition.",
              "Add to the scaled sample Y coordinate before ordered sampling transforms. Source"
                  + " noise transforms remain part of the shared source definition. Layer defaults"
                  + " stay live until an explicit placement override is enabled. Zero is still an"
                  + " override. Reset removes the override rather than copying a resolved value."
                  + " Properties edits remain private until Apply; Save persists the committed"
                  + " project."),
          new Definition(
              "world.setting.offsetZ",
              "Offset Z",
              "Add to the scaled sample Z coordinate before ordered sampling transforms. Source"
                  + " noise transforms remain part of the shared source definition.",
              "Add to the scaled sample Z coordinate before ordered sampling transforms. Source"
                  + " noise transforms remain part of the shared source definition. Layer defaults"
                  + " stay live until an explicit placement override is enabled. Zero is still an"
                  + " override. Reset removes the override rather than copying a resolved value."
                  + " Properties edits remain private until Apply; Save persists the committed"
                  + " project."),
          new Definition(
              "world.setting.samplingTransforms",
              "Ordered Sampling Transforms",
              "Apply enabled Translate, Scale and Rotate rows in list order after the axis"
                  + " scale/offset mapping. Rotation uses degrees and an explicit axis order; Scale"
                  + " and Rotate use their pivots.",
              "Apply enabled Translate, Scale and Rotate rows in list order after the axis"
                  + " scale/offset mapping. Rotation uses degrees and an explicit axis order; Scale"
                  + " and Rotate use their pivots. Layer defaults stay live until an explicit"
                  + " placement override is enabled. Zero is still an override. Reset removes the"
                  + " override rather than copying a resolved value. Properties edits remain"
                  + " private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.seedOffset",
              "Seed Offset",
              "An exact signed 64-bit placement seed offset. It participates in deterministic"
                  + " sampling and acceptance without changing the referenced noise definition.",
              "An exact signed 64-bit placement seed offset. It participates in deterministic"
                  + " sampling and acceptance without changing the referenced noise definition."
                  + " Layer defaults stay live until an explicit placement override is enabled."
                  + " Zero is still an override. Reset removes the override rather than copying a"
                  + " resolved value. Properties edits remain private until Apply; Save persists"
                  + " the committed project."),
          new Definition(
              "world.setting.action",
              "Action",
              "SET writes a selected occupied voxel type, CLEAR makes selected cells EMPTY, and"
                  + " NO_CHANGE preserves their previously committed state.",
              "SET writes a selected occupied voxel type, CLEAR makes selected cells EMPTY, and"
                  + " NO_CHANGE preserves their previously committed state. Layer defaults stay"
                  + " live until an explicit placement override is enabled. Zero is still an"
                  + " override. Reset removes the override rather than copying a resolved value."
                  + " Properties edits remain private until Apply; Save persists the committed"
                  + " project."),
          new Definition(
              "world.setting.rounding",
              "Rounding",
              "Height interpretations round Base Height plus field times Height Scale. FLOOR rounds"
                  + " down, CEIL rounds up, and ROUND uses floor(height + 0.5), including negative"
                  + " heights.",
              "Height interpretations round Base Height plus field times Height Scale. FLOOR rounds"
                  + " down, CEIL rounds up, and ROUND uses floor(height + 0.5), including negative"
                  + " heights. Layer defaults stay live until an explicit placement override is"
                  + " enabled. Zero is still an override. Reset removes the override rather than"
                  + " copying a resolved value. Properties edits remain private until Apply; Save"
                  + " persists the committed project."),
          new Definition(
              "world.setting.typeSource",
              "Type Source",
              "FIXED uses the selected voxel type for SET. INTERVALS chooses an occupied type from"
                  + " non-overlapping sampled-value intervals; no matching interval produces"
                  + " NO_CHANGE.",
              "FIXED uses the selected voxel type for SET. INTERVALS chooses an occupied type from"
                  + " non-overlapping sampled-value intervals; no matching interval produces"
                  + " NO_CHANGE. Layer defaults stay live until an explicit placement override is"
                  + " enabled. Zero is still an override. Reset removes the override rather than"
                  + " copying a resolved value. Properties edits remain private until Apply; Save"
                  + " persists the committed project."),
          new Definition(
              "world.setting.voxelTypeUuid",
              "Voxel Type",
              "Select the occupied voxel type used by FIXED type selection and SET. The stable"
                  + " reference follows a type rename; CLEAR does not write an occupied type.",
              "Select the occupied voxel type used by FIXED type selection and SET. The stable"
                  + " reference follows a type rename; CLEAR does not write an occupied type. Layer"
                  + " defaults stay live until an explicit placement override is enabled. Zero is"
                  + " still an override. Reset removes the override rather than copying a resolved"
                  + " value. Properties edits remain private until Apply; Save persists the"
                  + " committed project."),
          new Definition(
              "world.setting.typeIntervals",
              "Voxel Type Intervals",
              "Each interval declares minimum/maximum values, endpoint inclusion and an occupied"
                  + " voxel type. Intervals cannot overlap, shared endpoints belong to at most one"
                  + " interval, and no match produces NO_CHANGE.",
              "Each interval declares minimum/maximum values, endpoint inclusion and an occupied"
                  + " voxel type. Intervals cannot overlap, shared endpoints belong to at most one"
                  + " interval, and no match produces NO_CHANGE. Layer defaults stay live until an"
                  + " explicit placement override is enabled. Zero is still an override. Reset"
                  + " removes the override rather than copying a resolved value. Properties edits"
                  + " remain private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.probabilityDriver",
              "Probability Field Driver",
              "An enabled field mapping clamps input to its declared interval and maps to [0,1]"
                  + " output endpoints before multiplying constant probability. Disabled factor ="
                  + " 1; output endpoints may descend.",
              "An enabled field mapping clamps input to its declared interval and maps to [0,1]"
                  + " output endpoints before multiplying constant probability. Disabled factor ="
                  + " 1; output endpoints may descend. Layer defaults stay live until an explicit"
                  + " placement override is enabled. Zero is still an override. Reset removes the"
                  + " override rather than copying a resolved value. Properties edits remain"
                  + " private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.strengthDriver",
              "Strength Field Driver",
              "An enabled field mapping clamps input to its declared interval and maps to [0,1]"
                  + " output endpoints before multiplying constant strength. Disabled factor = 1;"
                  + " output endpoints may descend.",
              "An enabled field mapping clamps input to its declared interval and maps to [0,1]"
                  + " output endpoints before multiplying constant strength. Disabled factor = 1;"
                  + " output endpoints may descend. Layer defaults stay live until an explicit"
                  + " placement override is enabled. Zero is still an override. Reset removes the"
                  + " override rather than copying a resolved value. Properties edits remain"
                  + " private until Apply; Save persists the committed project."),
          new Definition(
              "world.setting.parameterDrivers",
              "Numeric Parameter Drivers",
              "Enabled rows clamp and map a field to a destination in sampling, interpretation or"
                  + " acceptance phases, then combine by REPLACE, ADD or MULTIPLY. Output endpoints"
                  + " may descend. Sampling targets require an explicit field and fixed baseline"
                  + " coordinates. Effective strength/probability must remain in [0,1]. Transform"
                  + " row numbers are one-based and stay positional after list edits.",
              "Enabled rows clamp and map a field to a destination in sampling, interpretation or"
                  + " acceptance phases, then combine by REPLACE, ADD or MULTIPLY. Output endpoints"
                  + " may descend. Sampling targets require an explicit field and fixed baseline"
                  + " coordinates. Effective strength/probability must remain in [0,1]. Transform"
                  + " row numbers are one-based and stay positional after list edits. Layer"
                  + " defaults stay live until an explicit placement override is enabled. Zero is"
                  + " still an override. Reset removes the override rather than copying a resolved"
                  + " value. Properties edits remain private until Apply; Save persists the"
                  + " committed project."),
          new Definition(
              "world.setting.voxelStatePredicate",
              "Previous Voxel State",
              "Test previously committed voxel state before this operation. EMPTY matches neither"
                  + " positive nor negative occupied-type/set tests. AND/OR groups keep their exact"
                  + " grouping and cannot be empty; this operation never reads its own partial"
                  + " writes.",
              "Test previously committed voxel state before this operation. EMPTY matches neither"
                  + " positive nor negative occupied-type/set tests. AND/OR groups keep their exact"
                  + " grouping and cannot be empty; this operation never reads its own partial"
                  + " writes. Layer defaults stay live until an explicit placement override is"
                  + " enabled. Zero is still an override. Reset removes the override rather than"
                  + " copying a resolved value. Properties edits remain private until Apply; Save"
                  + " persists the committed project."),
          new Definition(
              "world.setting.fieldGates",
              "Field Selection Gates",
              "Field comparisons, inclusive/exclusive intervals and deterministic probability gates"
                  + " compose through AND, OR and NOT. Preserve the authored grouping. Gate"
                  + " probability uses its own stable path draw, independently of placement"
                  + " probability.",
              "Field comparisons, inclusive/exclusive intervals and deterministic probability gates"
                  + " compose through AND, OR and NOT. Preserve the authored grouping. Gate"
                  + " probability uses its own stable path draw, independently of placement"
                  + " probability. Layer defaults stay live until an explicit placement override is"
                  + " enabled. Zero is still an override. Reset removes the override rather than"
                  + " copying a resolved value. Properties edits remain private until Apply; Save"
                  + " persists the committed project."));

  static void contribute(Map<String, AppMetadata.Control> controls) {
    int order = 0;
    for (var definition : DEFINITIONS) {
      var control =
          new AppMetadata.Control(
              definition.label(), definition.tooltip(), definition.description());
      control.group = "World settings";
      control.order = order++;
      controls.put(definition.id(), control);
    }
  }

  static String setting(String path, String leaf) {
    if (path == null) return null;
    return topic("settings/" + path + (leaf.isEmpty() ? "" : "/" + leaf));
  }

  static String integerTooltip(String configured, String raw, boolean invalid) {
    return Objects.requireNonNull(configured)
        + "\nText: "
        + Objects.requireNonNull(raw)
        + "\n"
        + HelpDefinitions.LONG_SLIDER_TOOLTIP
        + (invalid ? "\nCorrect the complete integer text before using its slider." : "")
        + "\nRight-click for Help.";
  }

  static String topic(String control) {
    if (control == null) return null;
    return switch (control) {
      case "enabled" -> "world.enabled";
      case "noiseGeneratorUuid" -> "world.source";
      case "interpretation" -> "world.interpretation";
      default -> {
        String prefix =
            control.startsWith("settings/")
                ? "settings/"
                : control.startsWith("override/")
                    ? "override/"
                    : control.startsWith("reset/") ? "reset/" : null;
        if (prefix == null) yield null;
        String path = control.substring(prefix.length());
        int end = path.indexOf('/');
        String property = end < 0 ? path : path.substring(0, end);
        if (!SettingsResolver.PROPERTIES.contains(property)) yield null;
        yield prefix.equals("settings/") ? "world.setting." + property : "world.inheritance";
      }
    };
  }

  private WorldControlMetadata() {}
}
