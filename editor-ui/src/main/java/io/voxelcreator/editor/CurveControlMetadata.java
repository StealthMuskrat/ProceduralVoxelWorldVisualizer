package io.voxelcreator.editor;

import java.util.*;

/** Curve-dialog guidance has no creation-default or evaluation authority. */
final class CurveControlMetadata {
  record Definition(String key, String label, String tooltip, String description) {
    String id() {
      return "curve.editor." + key;
    }
  }

  static final List<Definition> DEFINITIONS =
      List.of(
          definition(
              "min",
              "Curve Domain Minimum",
              "Set the input domain's lower endpoint and its locked endpoint X. Interior points"
                  + " must remain strictly ordered.",
              "Domain edits change the curve, not just its view. The first endpoint follows this"
                  + " value; validation rejects an invalid domain or point order."),
          definition(
              "max",
              "Curve Domain Maximum",
              "Set the input domain's upper endpoint and its locked endpoint X. Interior points"
                  + " must remain strictly ordered.",
              "Domain edits change the curve, not just its view. The last endpoint follows this"
                  + " value; validation rejects an invalid domain or point order."),
          definition(
              "displayMin",
              "Curve Display Minimum",
              "Set the lower output display range. Curve outputs are not clamped to this range.",
              "The display range frames the plot and suggests the Y slider range. Negative outputs"
                  + " and outputs above one remain valid; exact text is not silently clamped."),
          definition(
              "displayMax",
              "Curve Display Maximum",
              "Set the upper output display range. Curve outputs are not clamped to this range.",
              "The display range frames the plot and suggests the Y slider range. This range is"
                  + " curve data; changing it remains private until Curve Apply and the parent"
                  + " Properties Apply."),
          definition(
              "stepX",
              "Curve X Snap Increment",
              "Choose a positive finite X snap increment. Snap X controls whether plot edits use"
                  + " it; Alt temporarily bypasses snapping.",
              "The increment is local to this dialog. It does not alter existing curve points until"
                  + " an eligible plot edit uses snapping, and it is not saved as a project or user"
                  + " preference."),
          definition(
              "stepY",
              "Curve Y Snap Increment",
              "Choose a positive finite Y snap increment. Snap Y controls whether plot edits use"
                  + " it; Alt temporarily bypasses snapping.",
              "The increment is local to this dialog. It does not alter existing curve points until"
                  + " an eligible plot edit uses snapping, and it is not saved as a project or user"
                  + " preference."),
          definition(
              "x",
              "Curve Point X",
              "Edit an interior point's input coordinate without crossing its neighbors. Endpoint X"
                  + " follows the domain minimum or maximum.",
              "Point UUIDs retain identity through sorting and editing. Endpoint X is read-only"
                  + " here; edit the matching domain endpoint. Exact input is validated before"
                  + " changing the private curve."),
          definition(
              "y",
              "Curve Point Y",
              "Edit the selected point's output, including negative values and values above one."
                  + " The display range only suggests the slider range.",
              "Endpoint and interior Y values are editable. Exact text retains the full double"
                  + " value. A held slider move forms one local undo step; no edit publishes the"
                  + " parent project by itself."),
          definition(
              "inTangent",
              "Curve Incoming Slope",
              "Edit the incoming Bezier slope in Aligned or Free mode. Aligned also updates the"
                  + " outgoing slope.",
              "Slopes require an adjacent Bezier segment. Auto derives monotone slopes and hides"
                  + " direct slope editing; Aligned couples the two slopes and Free edits each"
                  + " independently."),
          definition(
              "outTangent",
              "Curve Outgoing Slope",
              "Edit the outgoing Bezier slope in Aligned or Free mode. Aligned also updates the"
                  + " incoming slope.",
              "Slopes require an adjacent Bezier segment. Values remain curve-space slopes,"
                  + " independent of plot zoom, resolution and pixel coordinates."),
          definition(
              "outsideDomain",
              "Curve Outside-Domain Policy",
              "Choose Clamp, Extend, Repeat or Mirror for inputs outside the configured domain.",
              "The canonical mode list and its deterministic endpoint/seam rules remain defined by"
                  + " the curve model. Presentation metadata cannot add, remove or rename stored"
                  + " mode IDs."),
          definition(
              "segmentInterpolation",
              "Curve Next-Segment Interpolation",
              "Choose Step, Linear, Smooth or Bezier for the segment starting at the selected"
                  + " point.",
              "Interpolation belongs to each segment's left point. The final point has no outgoing"
                  + " segment. Mixing modes does not make evaluation depend on the preview's"
                  + " sampling resolution."),
          definition(
              "handleMode",
              "Curve Bezier Handle Mode",
              "Auto derives monotone slopes; Aligned couples incoming/outgoing slopes; Free edits"
                  + " them independently.",
              "Choose a handle mode on a point adjacent to a Bezier segment. Switching from Auto"
                  + " starts explicit slopes from the current compiled curve, preserving the"
                  + " existing edit validation."),
          definition(
              "grid",
              "Curve Grid",
              "Show or hide this dialog's plot grid without changing curve evaluation.",
              "Grid visibility is local display state. It is separate from Snap X and Snap Y and"
                  + " does not dirty the project or user preferences."),
          definition(
              "snapX",
              "Curve Snap X",
              "Snap eligible plot edits to the local X increment. Alt temporarily bypasses"
                  + " snapping.",
              "Snapping is independent on each axis. Endpoint X remains domain-locked and group"
                  + " movement still respects neighbor ordering. Exact numeric entry keeps its"
                  + " existing unsnapped behavior."),
          definition(
              "snapY",
              "Curve Snap Y",
              "Snap eligible plot edits to the local Y increment. Alt temporarily bypasses"
                  + " snapping.",
              "Snapping is independent on each axis. The Y increment does not clamp curve output;"
                  + " exact numeric entry keeps its existing unsnapped behavior."),
          definition(
              "undo",
              "Curve Local Undo",
              "Undo one private curve edit in this dialog.",
              "Local curve history is separate from project history. Undo ends an owned gesture and"
                  + " restores its prior curve values. Curve Apply is required to stage a result in"
                  + " Properties."),
          definition(
              "redo",
              "Curve Local Redo",
              "Redo one private curve edit in this dialog.",
              "Local curve history never publishes a project transaction. Cancel discards the"
                  + " entire dialog edit context."),
          definition(
              "fit",
              "Curve Fit View",
              "Fit the plot to its configured input domain and output display range.",
              "Fit changes local navigation only. Middle drag pans and the wheel zooms; no view"
                  + " navigation changes stored point coordinates or deterministic evaluation."),
          definition(
              "delete",
              "Curve Delete Interior Points",
              "Delete selected interior points while retaining the mandatory endpoints.",
              "The curve always requires at least two points. Deletion is one private local edit;"
                  + " mandatory endpoints remain and Curve Apply validates the complete result."),
          definition(
              "apply",
              "Curve Apply",
              "Validate and stage the private curve in its original Properties draft. Properties"
                  + " Apply commits the project change.",
              "A changed source or selection prevents stale publication. Curve Apply does not save"
                  + " the project or apply other Properties input. The parent Properties"
                  + " transaction provides the project undo boundary. Desktop prepares Apply in"
                  + " the background. Cancel Apply or Escape preserves the private curve; changing"
                  + " curve data cancels pending Apply. Retry Apply is explicit after failure."),
          definition(
              "cancel",
              "Curve Cancel",
              "Discard this dialog's private edits and local history.",
              "Cancel leaves the original Properties draft and committed project unchanged. Escape"
                  + " cancels an owned gesture and pending background Apply; use Cancel to discard"
                  + " the whole dialog edit"
                  + " context."),
          definition(
              "plot",
              "Curve Plot Readout",
              "Read exact input/output coordinates without changing the curve. The plot supports"
                  + " point selection, movement, insertion, pan and zoom.",
              "Click selects; Shift-click changes multi-selection; drag moves; double-click"
                  + " inserts; Delete removes selected interior points. Right-click the plot opens"
                  + " its existing actions. Right-click the readout opens Help. Preview sampling"
                  + " and drawing never define stored curve evaluation."));

  private static Definition definition(
      String key, String label, String tooltip, String description) {
    return new Definition(key, label, tooltip, description);
  }

  static void contribute(Map<String, AppMetadata.Control> controls) {
    int order = 0;
    for (var definition : DEFINITIONS) {
      var control =
          new AppMetadata.Control(
              definition.label(), definition.tooltip(), definition.description());
      control.group = "Curve editor guidance";
      control.order = order++;
      controls.put(definition.id(), control);
    }
  }

  static String topic(String key) {
    if (key == null) return null;
    return DEFINITIONS.stream()
        .filter(definition -> definition.key().equals(key))
        .map(Definition::id)
        .findFirst()
        .orElse(null);
  }

  static String tooltip(AppMetadata metadata, String key, String detail) {
    var id = Objects.requireNonNull(topic(key), "Unknown curve guidance control");
    var control = Objects.requireNonNull(metadata).control(id);
    String configured = control == null ? "" : Objects.requireNonNullElse(control.tooltip, "");
    String text = configured.isBlank() ? "" : configured + "\n";
    if (detail != null && !detail.isBlank()) text += detail + "\n";
    return text + "Right-click for Help.";
  }

  private CurveControlMetadata() {}
}
