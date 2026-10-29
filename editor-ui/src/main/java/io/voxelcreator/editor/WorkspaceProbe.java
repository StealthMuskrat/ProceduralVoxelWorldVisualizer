package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred actual side-panel docking, Workspace controls and fresh-context preference restore. */
public final class WorkspaceProbe {
  private static EditorState state;
  private static ResourcePanel resources;
  private static PropertyPanel properties;
  private static WorkspaceLayout layout;
  private static WorkspacePanel settings;
  private static ProfilePanel profiler;
  private static Map<String, ImBoolean> tools;
  private static int built, blockedCaptures;
  private static final Viewports.StreamingStatistics EMPTY =
      new Viewports.StreamingStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, false);

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    Path file = out.resolve("user-config.json");
    createContext();
    try (var editor = new EditorState(new UserConfig(), false)) {
      require(
          editor.change(
              p -> {
                var noise = new Noise();
                noise.id = "field";
                noise.algorithm.key = "CONSTANT";
                noise.algorithm.parameters.clear();
                noise.algorithm.parameters.put("value", .125);
                p.noise2D.add(noise);
              }),
          "Fixture rejected");
      editor.select("noise2D", editor.project().noise2D.getFirst().uuid);
      var draft = (Noise) editor.draft;
      draft.id = "private";
      editor.seedInputs.edit(editor, "seedOffset", "Seed", "-");
      var project = ProjectIO.JSON.valueToTree(editor.project());
      long revision = editor.revision, history = editor.document.historyBytes();
      boolean dirty = editor.document.dirty();
      setup(editor);
      frames(7);
      require(
          resources.docking.probeDock() != 0 && properties.docking.probeDock() != 0,
          "Default side panels were not docked");
      click(resources.docking.probeControl("undock"));
      frames(3);
      require(
          resources.docking.probeDock() == 0 && properties.docking.probeDock() != 0,
          "Actual Resources Undock did not isolate its host");
      redock(resources.docking);
      frames(4);
      require(resources.docking.probeDock() != 0, "Resources redock failed");
      click(properties.docking.probeControl("undock"));
      frames(3);
      require(properties.docking.probeDock() == 0, "Actual Properties Undock did not detach");
      redock(properties.docking);
      frames(4);
      require(properties.docking.probeDock() != 0, "Properties redock failed");
      click(settings.probeControl("Logs"));
      click(settings.probeControl("Help"));
      click(settings.probeControl("Profiler"));
      require(
          editor.user.workspace.visibleWindows.equals(List.of("Logs", "Help", "Profiler")),
          "Workspace checkboxes did not publish tool visibility");
      require(
          editor.engine.profiler().current().isEmpty(), "Showing Profiler started instrumentation");
      int previousBuilds = built, previousBlocked = blockedCaptures;
      click(settings.probeControl("defaults"));
      frames(3);
      require(
          built == previousBuilds + 1
              && blockedCaptures > previousBlocked
              && resources.docking.probeDock() != 0
              && properties.docking.probeDock() != 0,
          "Same-frame INI capture lost default reset request");
      // Explicit normal-size fixture exercises publication without creating an OS window here.
      WorkspaceVisibility.captureSize(editor.user.workspace, 1234, 789, false, false);
      WorkspaceVisibility.captureSize(editor.user.workspace, 0, 0, true, false);
      WorkspaceVisibility.captureSize(editor.user.workspace, 1920, 1080, false, true);
      editor.user.save(file);
      require(
          project.equals(ProjectIO.JSON.valueToTree(editor.project()))
              && revision == editor.revision
              && history == editor.document.historyBytes()
              && dirty == editor.document.dirty()
              && editor.draft == draft
              && draft.id.equals("private")
              && "-".equals(editor.seedInputs.text("seedOffset")),
          "Workspace actions changed authoring/private input");
    } finally {
      ImGui.destroyContext();
    }

    var saved = UserConfig.read(file);
    require(
        saved.workspace.width == 1234 && saved.workspace.height == 789,
        "Normal size was polluted by minimized/maximized fixture");
    createContext();
    try (var editor = new EditorState(saved, false)) {
      ImGui.loadIniSettingsFromMemory(saved.workspace.docking);
      setup(editor);
      frames(7);
      require(
          built == 0 && resources.docking.probeDock() != 0 && properties.docking.probeDock() != 0,
          "Saved INI was replaced or failed to restore side docks");
      require(
          tools.get("Logs").get()
              && tools.get("Help").get()
              && tools.get("Profiler").get()
              && !tools.get("Find").get()
              && !tools.get("Themes").get(),
          "Fresh-context visibility did not restore exact tool flags");
      require(
          editor.engine.profiler().current().isEmpty()
              && editor.project().noise2D.isEmpty()
              && editor.project().noise3D.isEmpty()
              && editor.project().worldGenerator.layers.isEmpty()
              && !editor.document.dirty(),
          "Workspace restore created resources, profiling or project edits");
      Files.writeString(
          out.resolve("workspace-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.ofEntries(
                  Map.entry("formatVersion", 1),
                  Map.entry("resourcesUndockRedock", true),
                  Map.entry("propertiesUndockRedock", true),
                  Map.entry("resetSurvivedCapture", true),
                  Map.entry("freshContextDockRestore", true),
                  Map.entry("toolVisibilityRestored", true),
                  Map.entry("profilerStayedDisabled", true),
                  Map.entry("projectPreserved", true),
                  Map.entry("normalSizeFixture", true),
                  Map.entry("freshProjectEmpty", true),
                  Map.entry("width", saved.workspace.width),
                  Map.entry("height", saved.workspace.height))));
      System.out.println(
          "WORKSPACE_NATIVE_PASS actual docking / settings visibility / reset ordering / fresh INI"
              + " restore / private authoring");
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void createContext() {
    ImGui.createContext();
    var io = ImGui.getIO();
    io.setIniFilename(null);
    io.setDisplaySize(1600, 1000);
    io.setDeltaTime(1f / 60);
    io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
    io.getFonts().addFontDefault();
    io.getFonts().build();
    ThemeStyle.apply(ThemePalette.starting("Dark"));
  }

  private static void setup(EditorState editor) {
    state = editor;
    built = blockedCaptures = 0;
    properties = new PropertyPanel(editor);
    resources = new ResourcePanel(editor, properties);
    resources.docking.defaults(WorkspaceProbe::reset);
    properties.docking.defaults(WorkspaceProbe::reset);
    resources.docking.probeControls();
    properties.docking.probeControls();
    layout = new WorkspaceLayout(editor.user.workspace);
    tools = new LinkedHashMap<>();
    WorkspaceVisibility.restore(editor.user.workspace)
        .forEach((name, value) -> tools.put(name, new ImBoolean(value)));
    settings =
        new WorkspacePanel(
            editor.user, tools, WorkspaceProbe::reset, WorkspaceProbe::captureVisibility);
    settings.probeControls();
    profiler = new ProfilePanel(editor, target -> {});
  }

  private static void reset() {
    layout.reset(state.user.workspace);
    resources.docking.reset();
    properties.docking.reset();
  }

  private static void captureVisibility() {
    var values = new LinkedHashMap<String, Boolean>();
    tools.forEach((name, value) -> values.put(name, value.get()));
    WorkspaceVisibility.capture(state.user.workspace, values);
  }

  private static void redock(PanelDockControls controls) {
    float[] point = controls.probeControl("redock");
    click(point != null ? point : controls.probeControl("defaults"));
  }

  private static void click(float[] point) {
    require(point != null, "Missing workspace control");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    frame();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    ImGui.newFrame();
    int dock = ImGui.dockSpaceOverViewport();
    layout.build(
        () -> {
          WorkspaceDocking.defaults(dock);
          resources.docking.reset();
          properties.docking.reset();
          built++;
        });
    resources.draw();
    properties.draw();
    ImGui.begin("Visualizers");
    ImGui.text("Workspace fixture / no GL renderer");
    ImGui.end();
    ImGui.setNextWindowPos(450, 550, ImGuiCond.Always);
    ImGui.setNextWindowSize(600, 390, ImGuiCond.Always);
    ImGui.begin("Workspace settings fixture");
    settings.draw();
    ImGui.end();
    if (tools.get("Profiler").get()) ImGui.setNextWindowPos(1200, 650, ImGuiCond.Always);
    profiler.draw(tools.get("Profiler"), EMPTY);
    captureVisibility();
    if (!layout.capture(state.user.workspace, ImGui::saveIniSettingsToMemory)) blockedCaptures++;
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
