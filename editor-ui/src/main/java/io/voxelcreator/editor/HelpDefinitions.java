package io.voxelcreator.editor;

import java.util.*;

/** Central help-only guidance and semantic links; authored AppMetadata supplies control text. */
final class HelpDefinitions {
  record Guide(String id, String name, String guidance) {}

  static final List<Guide> GUIDES =
      List.of(
          new Guide(
              "help.manual",
              "Using Help",
              "Search names, guidance, canonical options and related topics. Search words all need"
                  + " to match. Select a topic, then choose Guidance; Copy copies the complete"
                  + " selected topic. Right-click supported menu items or Inspector controls and"
                  + " choose Open Help. Help reads active application metadata; private Application"
                  + " Settings text appears only after Apply. Help never applies authoring input."),
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
                  + " actions. Save Preferences persists active preferences only and never applies"
                  + " private scale, theme or resource input."),
          new Guide(
              "help.visualization",
              "Visualization preferences",
              "User Settings > Visualization controls grid, unlit, streaming, ambient occlusion,"
                  + " fog and directional shadows. These display choices do not change exact"
                  + " generated voxel values. Shadow resolution and volume sample choices are"
                  + " explicit. Preview limits open the separate Performance draft; saving active"
                  + " visualization choices does not apply it. Disabled shadows retain their"
                  + " configured resolution."),
          new Guide(
              "help.trace",
              "Generation traces",
              "Trace a coordinate to inspect the immutable recorded generation result and ordered"
                  + " steps. Copy Trace copies the complete retained trace; Copy Value copies the"
                  + " selected complete observation, including values displayed over multiple"
                  + " pages. Source links retain the exact trace observation through any Apply /"
                  + " Discard / Cancel decision. Filters and paging do not generate new voxel"
                  + " values."),
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
          Map.entry(
              "noise", List.of("noise.algorithm", "processing", "layers", "creation-defaults")),
          Map.entry("layers", List.of("noise", "processing", "archives", "help.trace")),
          Map.entry("navigation", List.of("bookmarks", "help.workspace", "shortcuts")),
          Map.entry("save", List.of("help.project-settings", "archives", "creation-defaults")),
          Map.entry("processing", List.of("noise", "layers", "help.trace")),
          Map.entry("archives", List.of("save", "layers", "help.visualization")),
          Map.entry("bookmarks", List.of("navigation", "save")),
          Map.entry("diagnostics", List.of("help.find", "help.trace", "profiler")),
          Map.entry("profiler", List.of("diagnostics", "help.visualization")),
          Map.entry("shortcuts", List.of("navigation", "help.manual")),
          Map.entry("creation-defaults", List.of("noise", "processing", "help.appearance")),
          Map.entry("themes", List.of("help.appearance", "creation-defaults")),
          Map.entry("noise.algorithm", List.of("noise", "creation-defaults")),
          Map.entry("noise.seedOffset", List.of("noise", "help.project-settings")),
          Map.entry("help.find", List.of("diagnostics", "noise", "help.trace")),
          Map.entry("help.workspace", List.of("navigation", "help.appearance")),
          Map.entry("help.updates", List.of("help.about", "creation-defaults")),
          Map.entry("help.project-settings", List.of("save", "noise.seedOffset", "layers")),
          Map.entry("help.appearance", List.of("themes", "help.workspace", "shortcuts")),
          Map.entry("help.visualization", List.of("archives", "profiler", "help.workspace")),
          Map.entry("help.trace", List.of("layers", "diagnostics", "help.find")),
          Map.entry("help.about", List.of("help.updates", "help.manual")));

  static List<String> related(String id) {
    if (LINKS.containsKey(id)) return LINKS.get(id);
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
      case "New", "Open", "Save", "SaveAs", "Exit", "Undo", "Redo" -> "save";
      case "Import", "Export" -> "archives";
      case "Find", "FindReferences" -> "help.find";
      case "Validate", "Logs" -> "diagnostics";
      case "Profiler", "Debug & Profiling" -> "profiler";
      case "Settings", "User Settings" -> "help.appearance";
      case "Voxel Manager" -> "layers";
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

  private HelpDefinitions() {}
}
