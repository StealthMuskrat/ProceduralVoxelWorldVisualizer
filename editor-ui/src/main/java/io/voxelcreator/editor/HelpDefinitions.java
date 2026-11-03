package io.voxelcreator.editor;

import io.voxelcreator.model.CurveModes;
import java.util.*;

/** Central help-only guidance and semantic links; authored AppMetadata supplies control text. */
final class HelpDefinitions {
  static final String BOUNDED_INTEGER_TOOLTIP =
      "Adjust this private integer input. Exact text accepts the complete documented range; the"
          + " existing Apply or Start Capture action remains explicit.";
  static final String LONG_SLIDER_TOOLTIP =
      "Adjust by up to 256 exact integer steps from the current seed. Exact text keeps every digit"
          + " of a signed 64-bit seed. Slider edits stay private until the existing Apply action.";
  static final String COORDINATE_TOOLTIP =
      "Text accepts the complete signed 32-bit coordinate range. Moving the slider edits input"
          + " only.";
  static final String COORDINATE_INVALID_TOOLTIP =
      "Correct the complete coordinate text before using its slider.";

  record Guide(String id, String name, String guidance) {}

  static final List<Guide> GUIDES =
      List.of(
          new Guide(
              "help.project-open",
              "Opening a project",
              "Open Project reads and validates a separate candidate in the background. Cancel Open"
                  + " or Escape retires that request and preserves the current project and private"
                  + " draft. A changed project, draft or validation policy prevents late"
                  + " replacement. Failed reads or validation retain the entered path for explicit"
                  + " retry. Successful Open replaces the document once, resets history and starts"
                  + " a clean saved checkpoint. Save and Save As remain explicit committed-project"
                  + " writes."),
          new Guide(
              "help.manual",
              "Using Help",
              "Search names, guidance, canonical options and related topics. Search words all need"
                  + " to match. Select a topic, then choose Guidance; Copy copies the complete"
                  + " selected topic. Right-click supported menu items or Inspector controls and"
                  + " choose Open Help. Help reads active application metadata; private Application"
                  + " Settings text appears only after Apply. Help never applies authoring input."),
          new Guide(
              "help.validation",
              "Validate committed project in the background",
              "View > Validate Project checks an owned snapshot of committed data in the"
                  + " background. Previous diagnostic results remain available while Running;"
                  + " Cancel Validation retires the request without changing the project or private"
                  + " Properties input. Closing only the results window leaves the requested check"
                  + " running. A result publishes on the UI thread only if its document and"
                  + " diagnostic publication still match. A project edit, New/Open or newer"
                  + " rejected-edit result makes an older check stale. Choose Validate Committed"
                  + " Project again to retry cancelled, stale or failed checks. Copy Details and Go"
                  + " To Problem retain their exact displayed-result ownership. Validation does not"
                  + " Apply drafts, save projects or change preferences."),
          new Guide(
              "help.find",
              "Find resources and references",
              "View > Find searches committed resources by ID, UUID, authored value or breadcrumb."
                  + " Results and Filters are separate pages. Go To Selected retains the selected"
                  + " committed hit even outside filters. When source navigation would replace"
                  + " private Properties edits, Apply / Discard / Cancel retains ownership of that"
                  + " exact request. Find References limits results to references to the selected"
                  + " UUID."),
          new Guide(
              "help.pins",
              "Pinned controls and live sources",
              "In a Noise item's Properties, use Pin Control to choose one parameter or named"
                  + " input, or Pin Item for a link to the whole item. Shared parameters also offer"
                  + " Pin Control. Pinned Controls lets you search pins, filter groups, edit"
                  + " labels, reorder and unpin. A literal pin edits its original value in the"
                  + " private Properties draft; it does not create a second value. Bound inputs"
                  + " show their current source and Go To Control instead of a misleading constant."
                  + " A parameter pin follows Bind and returns to its literal when that binding is"
                  + " removed. Go To Control opens the original exact field. View > Find includes a"
                  + " pinned control kind and searches committed labels, groups and control names."
                  + " Find References shows each pin referring to a shared parameter or item. A"
                  + " unique control link reveals that pin even after reorder; duplicate identical"
                  + " references or removed pins reveal the picker without guessing. Used By links"
                  + " in a private draft follow its current pin identities. Apply publishes the"
                  + " complete resource in one Undo transaction; Save persists it. Filtering,"
                  + " navigation and Help do not apply or save edits. Right-click the Pinned"
                  + " Controls header or an item's Pin Control picker for this guidance."),
          new Guide(
              "help.workspace",
              "Workspace and window placement",
              "View > Reset Dock Layout restores the three-panel default arrangement. User Settings"
                  + " > Workspace controls saved utility visibility, default docking and"
                  + " main-window centering. Undock and Redock retain usable dock identities; a"
                  + " missing dock offers default-layout recovery. User Config retains normal"
                  + " screen-coordinate geometry and maximized intent. Workspace actions do not"
                  + " save the project or apply private resource edits."),
          new Guide(
              "help.updates",
              "Manual update checks",
              "Help > Check for Updates performs a manual check only. With no endpoint configured"
                  + " it opens User Settings > Performance > Update trust. Configure an HTTPS"
                  + " metadata endpoint and explicit trusted metadata/release origins, then Apply"
                  + " Trust. Failed configuration writes retain the private draft. Checks validate"
                  + " certificate trust, schema, response limits and origins; results offer a"
                  + " verified release-information link. There is no autonomous download, install"
                  + " or replacement."),
          new Guide(
              "help.project-settings",
              "Project Settings",
              "Project Settings edits seed and vertical bounds in a private draft. Apply validates"
                  + " and publishes one project transaction. Cancel discards that settings draft."
                  + " Reload handles a changed committed document explicitly; Show Problem reveals"
                  + " a supported invalid or mismatched input. Saving User Config does not apply"
                  + " Project Settings."),
          new Guide(
              "help.appearance",
              "Appearance and UI scale",
              "User Settings > Appearance composes global and per-window scale. Scale fields retain"
                  + " private text until their own Apply; invalid text can be revealed without"
                  + " repair. Theme editing has its own preview and Save Theme / Revert / Reset"
                  + " actions. Each RGBA channel pairs a 0–1 slider with exact decimal text;"
                  + " invalid text stays visible and blocks Save Theme until corrected or reset."
                  + " Scale sliders retain the entered precision until an intentional edit."
                  + " Save Preferences persists active preferences only and never applies"
                  + " private scale, theme or resource input."),
          new Guide(
              "help.voxel-identities",
              "Voxel identities and rename impact",
              "Voxel Manager offers Voxel Types, Voxel Sets and Groups. Select a type to review"
                  + " incoming committed UUID references; Properties also compares its committed"
                  + " semantic ID with private ID input. Renaming keeps UUID references linked."
                  + " Apply in Properties or Enter in inline rename commits the validated ID;"
                  + " Properties Revert or inline Escape discards that proposal. Find All"
                  + " References opens unified Find without applying private edits. Existing exact"
                  + " voxel archives retain their original semantic ID strings and are not"
                  + " rewritten; a compatible registry must still provide those names. Groups are"
                  + " organizational, while voxel sets can participate in generation selection."),
          new Guide(
              "help.visualization",
              "Visualization preferences",
              "User Settings > Visualization controls grid, unlit, streaming, ambient occlusion,"
                  + " fog and directional shadows. These display choices do not change exact"
                  + " generated voxel values. Shadow resolution and volume sample choices are"
                  + " explicit. Preview limits open the separate Performance draft; saving active"
                  + " visualization choices does not apply it. Disabled shadows retain their"
                  + " configured resolution. In 3D Noise and World, right-drag pans the camera's"
                  + " screen plane, including vertical movement when level. Middle-drag moves in"
                  + " world X/Z; Ctrl+middle moves world Y. Left-drag orbits and wheel zooms."
                  + " Navigation preserves procedural values and private resource edits. Fixed"
                  + " Threshold Voxels, Point Cloud and Isosurface analysis offers independent"
                  + " Extent X/Y/Z cell counts, bounded by each axis and the total preview budget."
                  + " Orthogonal Slices use the same XYZ extents projected onto their active plane,"
                  + " with separate width/height sampling and navigation. Volume also uses"
                  + " independent fixed XYZ counts through scalar textures and ray-box rendering."
                  + " Camera-relative streaming retains its uniform chunk-based window control. A"
                  + " failed background preview keeps the accepted display and offers Regenerate to"
                  + " retry. Worker completion alone does not accept a result; source and request"
                  + " ownership still apply. Numerical/source worker failures do not retry"
                  + " automatically or apply private authoring input."),
          new Guide(
              "help.scalar-readouts",
              "Scalar value readouts",
              "Idle scalar fields, sliders and read-only labels use up to five fractional digits."
                  + " Small nonzero values and large magnitudes use scientific notation;"
                  + " world-coordinate summaries retain their whole signed coordinate digits. Hover"
                  + " a scalar slider, inherited World value, curve readout or preview sample"
                  + " status for its full value. Focus a numeric text field to edit its"
                  + " full-precision value, including scientific notation. Focusing or leaving a"
                  + " field never rounds its value. Valid profile, theme and scale text keeps its"
                  + " entered spelling for exact editing; rejected raw text stays visible. Readout"
                  + " rounding never changes authored, sampled or stored values. Slider ranges"
                  + " remain suggestions; exact input and existing validation determine allowed"
                  + " values. Seed offsets and integer inputs retain their exact formats."
                  + " Registry-backed source, transform, processing and warp parameters share their"
                  + " configured tooltip on both slider and text; right-click either part for the"
                  + " same Help topic. Metadata changes guidance without changing computation or"
                  + " existing parameter values."),
          new Guide(
              "help.coordinates",
              "Coordinate sliders and exact input",
              "Inspector XYZ and Add Bookmark/Teleport coordinates pair a slider with exact"
                  + " signed decimal text. "
                  + COORDINATE_TOOLTIP
                  + " Suggested sliders span up to 256 blocks on each side of the current value,"
                  + " bounded at the signed integer limits. The range stays fixed during a drag"
                  + " and follows the new coordinate after release. Whole-number proposals use"
                  + " nearest-integer ties-to-even rounding. Text accepts leading signs/zeros"
                  + " and both integer endpoints without rewriting them on redraw. Invalid or"
                  + " disagreeing text disables the slider until corrected. "
                  + COORDINATE_INVALID_TOOLTIP
                  + " Narrow layouts stack the slider and input while keeping the exact field"
                  + " available. Slider movement never starts Trace, Teleport or Apply: choose"
                  + " that action explicitly. Bookmark edits remain private until Apply; Inspector"
                  + " input remains local navigation/inspection state."),
          new Guide(
              "help.long-inputs",
              "Exact seed sliders and project bounds",
              "Project Settings, Properties seed offsets and Application Settings creation seeds"
                  + " pair a suggested slider with exact signed decimal input. "
                  + LONG_SLIDER_TOOLTIP
                  + " Suggested adjustments preserve every seed digit, including adjacent large"
                  + " seeds and both signed limits. The range stays anchored during a drag."
                  + " Invalid, stale or disagreeing text disables the slider and remains available"
                  + " for correction or restoration. Native temporary slider text is disabled; use"
                  + " the adjacent exact field. Project Y bounds use signed 32-bit coordinate"
                  + " pairs; crossing the bounds is reported at Apply instead of silently changing"
                  + " the other field. Properties and Project Settings Apply are explicit project"
                  + " transactions. Creation defaults affect future resources only after"
                  + " Application Settings Apply; existing resources and user preferences are"
                  + " unchanged by private input."),
          new Guide(
              "help.settings-guidance",
              "Project, performance and capture input guidance",
              "Project Settings, Performance and Profiler next-capture inputs share configured"
                  + " guidance on both slider and exact-text parts. Right-click either part for the"
                  + " same topic, including rejected input and disabled sliders. Hover preserves"
                  + " every typed integer digit; suggested adjustments do not replace exact input."
                  + " Project Apply validates one project transaction and explicit Save persists"
                  + " it. Apply Performance publishes only its validated user preferences; private"
                  + " performance fields stay out of Save Preferences. Next-capture fields and work"
                  + " totals configure only the next explicit Start Capture, leaving active"
                  + " captures and export reviews unchanged. Application Settings customizes these"
                  + " Help names, tooltips and descriptions through settings.* entries. Fixed form"
                  + " labels, ranges and publication actions remain authoritative; these subjects"
                  + " do not supply creation defaults or edit computation."),
          new Guide(
              "help.runtime-limits",
              "Performance and capture numeric limits",
              "Performance settings and Profiler next-capture limits pair suggested sliders with"
                  + " exact whole-decimal input. "
                  + BOUNDED_INTEGER_TOOLTIP
                  + " Small fields expose their complete allowed slider range. Larger budgets"
                  + " use a nearby range; exact text still accepts every documented integer,"
                  + " including long cell budgets. A held range stays fixed until the interaction"
                  + " ends. Invalid, out-of-range or disagreeing text disables that slider and"
                  + " stays available for correction. Default preview extent must not exceed"
                  + " Maximum preview dimension; adjust either field, then Apply Performance."
                  + " Private performance input is excluded from Save Preferences. Active changes"
                  + " elsewhere require Revert before Apply. Worker, engine-memory and default"
                  + " extent changes retain their restart notices. Capture fields configure only"
                  + " the next Start Capture; current captures and prepared exports retain their"
                  + " immutable options and samples. Editing limits never starts/stops a capture"
                  + " or publishes preferences automatically."),
          new Guide(
              "help.curve-guidance",
              "Curve editor controls and private edits",
              "Curve numeric slider/text pairs share configured guidance and context Help,"
                  + " including domain, display range, snap increments, point coordinates and"
                  + " explicit Bezier slopes. Right-click either part for the same subject. Mode"
                  + " selectors retain canonical CurveModes semantics; their tooltip combines the"
                  + " configured guide with the selected mode's rules. Endpoint X is domain-locked;"
                  + " its readout explains which domain field to edit. Grid, snap toggles,"
                  + " increments and Fit are local controls; the display range itself is stored"
                  + " curve data. Right-click dialog actions for Help without invoking them. The"
                  + " plot keeps its existing right-click actions; the coordinate readout offers"
                  + " Help and exact values. Curve Apply validates into the original Properties"
                  + " draft, Properties Apply commits one project transaction, and explicit project"
                  + " Save persists it. Cancel discards only this dialog's edits. Application"
                  + " Settings > curve.editor entries customize Help names and guidance, separately"
                  + " from future-curve creation defaults. Fixed dialog labels, mode IDs and"
                  + " evaluation remain authoritative. "
                  + CurveModes.ARITHMETIC_DESCRIPTION),
          new Guide(
              "help.world-guidance",
              "World controls and live inheritance",
              "World Properties offers configured guidance on layer defaults, placement controls,"
                  + " nested transforms, drivers, type intervals and condition trees. Right-click"
                  + " either part of a numeric pair, a supported control/header, an inherited value"
                  + " or Override/Reset for Help. Nested rows use their setting family's guide."
                  + " Application Settings can edit these World Help names, tooltips and guidance;"
                  + " its Apply publishes presentation only. World field labels and ordered"
                  + " controls keep their existing layout. Unchecked placement settings inherit"
                  + " live layer defaults; an explicit override, including zero, remains private"
                  + " until Properties Apply. Reset removes an override. Reading Help does not"
                  + " enable, reset, apply or save any World setting."),
          new Guide(
              "help.world-lists",
              "World ordered settings",
              "Sampling transforms, numeric parameter drivers and type intervals are ordered"
                  + " private settings lists. Up/Down move a row; Remove deletes that row."
                  + " Transform and parameter-driver Duplicate creates an independent copy"
                  + " immediately after it. Lists show their row count and existing limits."
                  + " Transform order changes sampling; driver order changes value composition"
                  + " inside each phase. Numeric transform drivers target row numbers, which stay"
                  + " fixed after moves, copies or removal. Review their targets explicitly;"
                  + " invalid targets block Apply. Add Type Interval previews an unused finite"
                  + " range using the first existing Voxel Type; reordering does not change it."
                  + " Correct invalid ranges first. Missing types, exhausted finite values or the"
                  + " row limit disable Add. Non-overlapping type intervals retain their"
                  + " endpoint behavior when reordered. Placement lists inherit live until Override"
                  + " and replace one whole property. Reset removes that override. Apply commits"
                  + " the complete private resource as one undoable project edit; saving remains"
                  + " explicit."),
          new Guide(
              "help.world-conditions",
              "World logical conditions",
              "Previous Voxel State predicates inspect the existing voxel; EMPTY is distinct from"
                  + " negative type/set tests, which require an occupied cell. Field Selection"
                  + " Gates filter the sampled field. Changing a basic condition to AND/OR retains"
                  + " it as a clause; switching AND/OR retains all clauses. NOT retains the whole"
                  + " existing gate as its single child. Groups cannot lose their last clause. Use"
                  + " Only Child removes a one-child group, including NOT negation. Choosing a"
                  + " basic condition replaces its subclauses. The editor supports the model's 32"
                  + " nested levels. Regrouping gates can change path-based probability draws;"
                  + " exact seed offsets remain intact. Invalid raw seed text remains detached for"
                  + " explicit repair or restoration. Placement settings remain inherited until"
                  + " Override; Apply publishes the private resource as one undoable transaction"
                  + " and Save remains explicit."),
          new Guide(
              "help.trace",
              "Generation traces",
              "Trace a coordinate to inspect the immutable recorded generation result and ordered"
                  + " steps. Copy Trace copies the complete retained trace; Copy Value copies the"
                  + " selected complete observation, including values displayed over multiple"
                  + " pages. Source links retain the exact trace observation through any Apply /"
                  + " Discard / Cancel decision. Filters and paging do not generate new voxel"
                  + " values. Pick and Trace run in background workers. Cancel retires their"
                  + " publication authority; an unexpected worker failure also releases pending"
                  + " state and preserves the last valid trace. A failed coordinate attempt offers"
                  + " Retry Trace for that exact target, independently of entered XYZ or an older"
                  + " displayed result. A failed pick offers a new pick or Trace XYZ instead of"
                  + " inventing a coordinate target. Nothing retries automatically."),
          new Guide(
              "help.about",
              "About and dependency licenses",
              "Help > About separates runtime details and dependency license summaries. Runtime"
                  + " details report application, engine, Java and current OpenGL context strings."
                  + " Copy copies the displayed section. Full dependency notices ship under"
                  + " docs/third-party and bundled runtime legal directories; docs/LICENSES.md"
                  + " explains ownership. No open-source license grant has been supplied for"
                  + " project-owned source."));

  private static final Map<String, List<String>> LINKS =
      Map.ofEntries(
          Map.entry("help.manual", List.of("noise", "help.find", "shortcuts", "help.about")),
          Map.entry("help.project-open", List.of("save", "help.validation", "diagnostics")),
          Map.entry(
              "noise",
              List.of("noise.algorithm", "processing", "layers", "creation-defaults", "help.pins")),
          Map.entry(
              "layers",
              List.of(
                  "noise",
                  "processing",
                  "archives",
                  "help.trace",
                  "help.world-conditions",
                  "help.world-lists")),
          Map.entry("navigation", List.of("bookmarks", "help.workspace", "shortcuts")),
          Map.entry("save", List.of("help.project-settings", "archives", "creation-defaults")),
          Map.entry("processing", List.of("noise", "layers", "help.trace", "help.pins")),
          Map.entry("archives", List.of("save", "layers", "help.visualization")),
          Map.entry("bookmarks", List.of("navigation", "save", "help.coordinates")),
          Map.entry(
              "diagnostics", List.of("help.validation", "help.find", "help.trace", "profiler")),
          Map.entry("help.validation", List.of("diagnostics", "help.find")),
          Map.entry(
              "profiler", List.of("diagnostics", "help.visualization", "help.runtime-limits")),
          Map.entry(
              "help.runtime-limits", List.of("profiler", "help.appearance", "help.long-inputs")),
          Map.entry("shortcuts", List.of("navigation", "help.manual")),
          Map.entry("creation-defaults", List.of("noise", "processing", "help.appearance")),
          Map.entry("themes", List.of("help.appearance", "creation-defaults")),
          Map.entry("noise.algorithm", List.of("noise", "creation-defaults")),
          Map.entry("noise.seedOffset", List.of("noise", "help.project-settings")),
          Map.entry("help.find", List.of("diagnostics", "noise", "help.trace", "help.pins")),
          Map.entry(
              "help.pins",
              List.of("noise", "processing", "help.find", "save", "help.scalar-readouts")),
          Map.entry("help.workspace", List.of("navigation", "help.appearance")),
          Map.entry("help.updates", List.of("help.about", "creation-defaults")),
          Map.entry(
              "help.project-settings",
              List.of("save", "noise.seedOffset", "layers", "help.long-inputs")),
          Map.entry(
              "help.long-inputs",
              List.of(
                  "help.project-settings",
                  "noise.seedOffset",
                  "creation-defaults",
                  "help.coordinates")),
          Map.entry("help.appearance", List.of("themes", "help.workspace", "shortcuts")),
          Map.entry("help.voxel-identities", List.of("layers", "archives", "help.find")),
          Map.entry(
              "help.visualization",
              List.of("archives", "profiler", "help.workspace", "help.scalar-readouts")),
          Map.entry(
              "help.scalar-readouts",
              List.of("help.visualization", "layers", "creation-defaults", "help.coordinates")),
          Map.entry(
              "help.coordinates",
              List.of("bookmarks", "navigation", "help.trace", "help.scalar-readouts")),
          Map.entry(
              "help.settings-guidance",
              List.of(
                  "help.project-settings", "help.runtime-limits", "help.long-inputs", "profiler")),
          Map.entry(
              "help.curve-guidance",
              List.of(
                  "processing.CURVE",
                  "curve.editor.outsideDomain",
                  "curve.editor.x",
                  "help.scalar-readouts",
                  "noise")),
          Map.entry(
              "help.world-guidance",
              List.of(
                  "layers",
                  "world.inheritance",
                  "world.interpretation",
                  "help.world-lists",
                  "help.world-conditions")),
          Map.entry("help.world-lists", List.of("layers", "help.world-conditions", "help.trace")),
          Map.entry(
              "help.world-conditions", List.of("layers", "help.trace", "help.project-settings")),
          Map.entry(
              "help.trace", List.of("layers", "diagnostics", "help.find", "help.coordinates")),
          Map.entry("help.about", List.of("help.updates", "help.manual")));

  static List<String> related(String id) {
    if (LINKS.containsKey(id)) return LINKS.get(id);
    if (id.startsWith("settings.project."))
      return List.of("help.settings-guidance", "help.project-settings", "help.long-inputs");
    if (id.startsWith("settings.performance.") || id.startsWith("settings.capture."))
      return List.of("help.settings-guidance", "help.runtime-limits", "profiler");
    if (id.startsWith("curve.editor."))
      return List.of("help.curve-guidance", "processing.CURVE", "help.scalar-readouts", "noise");
    if (id.startsWith("world."))
      return List.of("layers", "help.world-guidance", "world.inheritance", "help.scalar-readouts")
          .stream()
          .filter(link -> !link.equals(id))
          .toList();
    if (id.startsWith("algorithm.")) return List.of("noise.algorithm", "noise");
    if (id.startsWith("processing.") || id.startsWith("transform."))
      return List.of("processing", "noise");
    if (id.startsWith("noise2D.") || id.startsWith("noise3D.")) {
      String[] parts = id.split("\\.");
      if (parts.length > 2 && parts[1].equals("algorithm"))
        return List.of("algorithm." + parts[2], "noise.algorithm", "creation-defaults");
      return List.of("noise", "creation-defaults");
    }
    return List.of("creation-defaults", "help.manual");
  }

  static String menuTopic(String action) {
    return switch (action) {
      case "Open" -> "help.project-open";
      case "New", "Save", "SaveAs", "Exit", "Undo", "Redo" -> "save";
      case "Import", "Export" -> "archives";
      case "Find", "FindReferences" -> "help.find";
      case "Validate" -> "help.validation";
      case "Logs" -> "diagnostics";
      case "Profiler", "Debug & Profiling" -> "profiler";
      case "Settings", "User Settings" -> "help.appearance";
      case "Voxel Manager" -> "help.voxel-identities";
      case "Themes" -> "themes";
      case "Center Main Window", "Reset Dock Layout" -> "help.workspace";
      case "Project Settings" -> "help.project-settings";
      case "Application Settings" -> "creation-defaults";
      case "Help" -> "help.manual";
      case "About" -> "help.about";
      case "Check for Updates" -> "help.updates";
      default -> throw new IllegalArgumentException("No context Help route for " + action);
    };
  }

  static final String THEME_CHANNEL_TOOLTIP =
      "Each RGBA channel has a 0–1 slider and decimal text. Focus text to see every entered digit."
          + " Invalid text remains visible and blocks this color's preview and Save Theme until"
          + " corrected or reset. Other valid edits in the same color are kept for that correction."
          + " Save Theme publishes the private palette; closing discards it.";

  private HelpDefinitions() {}
}
