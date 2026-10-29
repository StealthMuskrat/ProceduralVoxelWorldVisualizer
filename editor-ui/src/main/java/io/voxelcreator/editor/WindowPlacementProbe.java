package io.voxelcreator.editor;

import static io.voxelcreator.editor.WindowPlacement.*;
import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deferred actual normal GLFW geometry/recreation plus production Center control in isolated ImGui.
 */
public final class WindowPlacementProbe {
  private static WorkspacePanel panel;
  private static EditorState state;

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    require(glfwInit(), "GLFW initialization");
    long window = 0;
    ImGui.createContext();
    try {
      glfwDefaultWindowHints();
      glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
      glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
      window = create();
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1100, 850);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      var user = new UserConfig();
      user.workspace.x = Integer.MAX_VALUE - 1000;
      user.workspace.y = Integer.MIN_VALUE + 1000;
      user.workspace.width = 1234;
      user.workspace.height = 789;
      user.workspace.docking = "placement fixture layout";
      user.workspace.visibleWindows.add("Help");
      var host = new GlfwMainWindow(window);
      var areas = host.workAreas();
      require(!areas.isEmpty(), "Native monitor work areas missing");
      var owner = new MainWindowPlacement(user.workspace, host);
      owner.restore();
      await(host, rect(user.workspace));
      owner.capture();
      var recovered = rect(user.workspace);
      user.save(out.resolve("recovered-user-config.json"));
      // Explicit normal move/resize fixture; no iconify/maximize or monitor-removal certification.
      var request = new UserConfig.Workspace();
      request.x = Integer.MIN_VALUE + 1000;
      request.y = Integer.MIN_VALUE + 1000;
      request.width = 640;
      request.height = 480;
      var normal = WindowPlacement.restore(request, areas, host.frame(), false).normal();
      host.bounds(normal);
      await(host, normal);
      owner.capture();
      require(rect(user.workspace).equals(normal), "Actual normal geometry was not captured");
      user.save(out.resolve("captured-user-config.json"));
      var centerCalls = new AtomicInteger();
      try (var editor = new EditorState(user, false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var noise = new Noise();
                  noise.id = "field";
                  noise.algorithm.key = "CONSTANT";
                  noise.algorithm.parameters.clear();
                  noise.algorithm.parameters.put("value", .125);
                  p.noise2D.add(noise);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var privateDraft = (Noise) state.draft;
        privateDraft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var tools = new LinkedHashMap<String, ImBoolean>();
        WorkspaceVisibility.restore(user.workspace)
            .forEach((name, visible) -> tools.put(name, new ImBoolean(visible)));
        panel =
            new WorkspacePanel(
                user,
                tools,
                () -> {
                  throw new AssertionError("Dock reset invoked by Center");
                },
                () -> WorkspaceVisibility.capture(user.workspace, flags(tools)),
                () -> {
                  centerCalls.incrementAndGet();
                  owner.center();
                });
        panel.probeControls();
        frames(3);
        var centered = WindowPlacement.restore(user.workspace, areas, host.frame(), true).normal();
        click("center");
        await(host, centered);
        owner.capture();
        require(
            centerCalls.get() == 1
                && rect(user.workspace).equals(centered)
                && !user.workspace.maximized,
            "Center did not restore a fitted normal window");
        require(
            user.workspace.docking.equals("placement fixture layout")
                && user.workspace.visibleWindows.equals(List.of("Help")),
            "Center changed docking or tool visibility");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.draft == privateDraft
                && privateDraft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset"))
                && state.engine.profiler().current().isEmpty(),
            "Center changed project, private input or profiling");
        user.save(out.resolve("user-config.json"));
      }
      panel = null;
      var saved = UserConfig.read(out.resolve("user-config.json"));
      glfwDestroyWindow(window);
      window = 0;
      window = create();
      var secondHost = new GlfwMainWindow(window);
      var second = new MainWindowPlacement(saved.workspace, secondHost);
      second.restore();
      await(secondHost, rect(saved.workspace));
      second.capture();
      require(
          rect(saved.workspace).equals(rect(user.workspace)),
          "Fresh native window lost saved normal geometry");
      saved.save(out.resolve("restarted-user-config.json"));
      Files.writeString(
          out.resolve("window-placement-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.ofEntries(
                  Map.entry("formatVersion", 1), Map.entry("normalNativeCapture", true),
                  Map.entry("centerControl", true), Map.entry("nativeWindowRecreation", true),
                  Map.entry("projectPreserved", true), Map.entry("dockingPreserved", true),
                  Map.entry("maximizedNativeVerified", false),
                      Map.entry("minimizedNativeVerified", false),
                  Map.entry("monitorRemovalNativeVerified", false),
                      Map.entry("recovered", recovered),
                  Map.entry("captured", normal), Map.entry("centered", rect(saved.workspace)),
                  Map.entry("workAreas", secondHost.workAreas()),
                      Map.entry("frameInsets", secondHost.frame()))));
      System.out.println(
          "WINDOW_PLACEMENT_NATIVE_PASS normal move/resize / Center control / fresh GLFW window /"
              + " private project retained; maximize and physical topology acceptance separate");
    } finally {
      ImGui.destroyContext();
      if (window != 0) glfwDestroyWindow(window);
      glfwTerminate();
    }
  }

  private static long create() {
    long handle = glfwCreateWindow(640, 480, "Window placement reference", 0, 0);
    require(handle != 0, "Native window creation");
    return handle;
  }

  private static Map<String, Boolean> flags(Map<String, ImBoolean> tools) {
    var flags = new LinkedHashMap<String, Boolean>();
    tools.forEach((name, value) -> flags.put(name, value.get()));
    return flags;
  }

  private static Rect rect(UserConfig.Workspace workspace) {
    require(workspace.x != null && workspace.y != null, "Normal position missing");
    return new Rect(workspace.x, workspace.y, workspace.width, workspace.height);
  }

  private static void await(GlfwMainWindow host, Rect expected) {
    long deadline = System.nanoTime() + 2_000_000_000L;
    do {
      glfwPollEvents();
      var value = host.sample();
      if (value != null
          && !value.iconified()
          && !value.maximized()
          && value.x() == expected.x()
          && value.y() == expected.y()
          && value.width() == expected.width()
          && value.height() == expected.height()) return;
      glfwWaitEventsTimeout(.01);
    } while (System.nanoTime() < deadline);
    throw new AssertionError(
        "Native geometry did not reach " + expected + "; actual=" + host.sample());
  }

  private static void click(String key) {
    var center = panel.probeControl(key);
    require(center != null, "Missing workspace control " + key);
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(2);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1000, 750, ImGuiCond.Always);
    if (ImGui.begin("Window placement settings fixture")) panel.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
