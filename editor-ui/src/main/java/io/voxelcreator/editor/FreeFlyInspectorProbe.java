package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Image/key input, native ImGui focus and GLFW cursor ownership through the Inspector handoff. */
final class FreeFlyInspectorProbe {
  private static Viewports views;
  private static EditorState state;
  private static long window;
  private static boolean focusOther;

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    window = handle;
    glfwShowWindow(window);
    glfwFocusWindow(window);
    glfwPollEvents();
    require(glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0, "Flight window not focused");
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      state.user.visualization.streaming = false;
      state.user.editor.opportunitiesPerSecond = 20;
      require(state.change(FreeFlyInspectorProbe::fixture), "Flight fixture validation");
      var authored = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var properties = new PropertyPanel(state);
      views = new Viewports(state, properties, window);
      boolean closed = false;
      try {
        views.probeWorld(null, "Final", 8, .25);
        until(views::probeWorldReady);
        double[] initial = views.probeCameraPose(2);
        enter();
        require(
            views.probeInspectorTrace() == null && !views.probeInspectorOpen(),
            "Entry click was reused as a World pick");
        same(initial, "Entry changed camera pose");
        key(ImGuiKey.W, true);
        frames(4);
        key(ImGuiKey.W, false);
        frame();
        double[] moved = views.probeCameraPose(2);
        require(
            Math.abs(moved[0] - (initial[0] - 2)) < 1e-6
                && Math.abs(moved[1] - initial[1]) < 1e-12
                && Math.abs(moved[2] - initial[2]) < 1e-12,
            "W movement mismatch");

        clickImage();
        until(() -> views.probeInspectorTrace() != null);
        require(
            views.probeInspectorTrace().trace().position().equals(new BlockPos(3, 0, 0))
                && views.probeInspectorTrace().trace().finalType() == 1,
            "Free-fly center-ray hit");
        require(
            views.probeFlightMode(2) == ViewportFlight.Mode.INSPECTOR && views.probeCameraFlying(2),
            "Inspector discarded flight intent");
        cursor(GLFW_CURSOR_NORMAL);
        same(moved, "Inspector handoff changed pose");
        key(ImGuiKey.W, true);
        frames(4);
        key(ImGuiKey.W, false);
        frame();
        same(moved, "Inspector allowed flight movement");
        editX(7);
        same(moved, "Inspector text editing moved camera");
        control("inspector-close");
        frames(3);
        paused(moved, "Inspector close stole cursor");

        enter();
        same(moved, "Resume changed pose");
        focusOther = true;
        frames(2);
        paused(moved, "Another ImGui window did not suspend flight");
        key(ImGuiKey.Escape, true);
        frame();
        key(ImGuiKey.Escape, false);
        frame();
        paused(moved, "Escape in another window exited the paused camera");
        enter();
        views.probeNoiseTab(0);
        frames(3);
        paused(moved, "Tab switch retained capture");
        views.probeNoiseTab(2);
        frames(3);
        paused(moved, "Returning to World stole capture");
        enter();
        key(ImGuiKey.Escape, true);
        frame();
        key(ImGuiKey.Escape, false);
        frame();
        require(
            views.probeFlightMode(2) == ViewportFlight.Mode.ORBIT && !views.probeCameraFlying(2),
            "Escape did not return to orbit");
        cursor(GLFW_CURSOR_NORMAL);
        same(moved, "Escape changed eye/orientation");
        enter();
        views.close();
        closed = true;
        cursor(GLFW_CURSOR_NORMAL);
        require(!views.flying() && !views.probeCameraFlying(2), "Closure retained flight");
        require(
            authored.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Flight/Inspector input authored a project transaction");
        new ProjectIO().save(out.resolve("free-fly-project.json"), state.project());
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("initial", initial);
        report.put("moved", moved);
        report.put("hit", List.of(3, 0, 0, 1));
        report.put("entered", List.of(7, 0, 0));
        for (String flag :
            List.of(
                "entryConsumed",
                "inspectorSuspended",
                "posePreserved",
                "textEditingPaused",
                "closeDidNotCapture",
                "otherWindowPaused",
                "otherEscapeIgnored",
                "tabPaused",
                "explicitResume",
                "escapeOrbit",
                "closeReleased",
                "viewOnly")) report.put(flag, true);
        Files.writeString(
            out.resolve("free-fly-native.json"),
            ProjectIO.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println(
            "FREE_FLY_INSPECTOR_NATIVE_PASS entry / movement / center pick / paused text /"
                + " focus-safe close / other window / tabs / resume / Escape / close / view-only");
      } finally {
        if (!closed) views.close();
      }
    } finally {
      glfwHideWindow(window);
    }
  }

  private static void fixture(io.voxelcreator.model.ProjectData p) {
    p.project.minY = 0;
    p.project.maxY = 31;
    var type = new VoxelType();
    type.id = "flight-solid";
    p.voxelTypes.add(type);
    var source = new Noise();
    source.id = "flight-constant";
    source.dimension = 3;
    source.algorithm.key = "CONSTANT";
    source.algorithm.parameters.put("value", 1d);
    p.noise3D.add(source);
    var layer = new Layer();
    layer.id = "flight-fill";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    var op = new Operation();
    op.id = "flight-operation";
    op.noiseGeneratorUuid = source.uuid;
    op.interpretation = "DENSITY";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
  }

  private static void enter() {
    frames(2);
    var p = views.probeAdmissionControl("image");
    ImGui.getIO().addMousePosEvent(p[0], p[1]);
    frame();
    key(ImGuiKey.ModCtrl, true);
    frame();
    ImGui.getIO().addMouseButtonEvent(0, true);
    frame();
    require(
        views.probeFlightMode(2) == ViewportFlight.Mode.CAPTURED,
        "Ctrl+click did not capture flight: " + views.probeFlightInput());
    cursor(GLFW_CURSOR_DISABLED);
    ImGui.getIO().addMouseButtonEvent(0, false);
    key(ImGuiKey.ModCtrl, false);
    frame();
    require(views.flying(), "Entry release lost flight capture");
  }

  private static void paused(double[] expected, String message) {
    require(
        views.probeFlightMode(2) == ViewportFlight.Mode.PAUSED
            && views.probeCameraFlying(2)
            && !views.flying(),
        message);
    cursor(GLFW_CURSOR_NORMAL);
    same(expected, message + " (pose)");
  }

  private static void same(double[] expected, String message) {
    var actual = views.probeCameraPose(2);
    for (int i = 0; i < actual.length; i++)
      require(
          Math.abs(actual[i] - expected[i]) < 1e-10,
          message + ": " + Arrays.toString(actual) + " != " + Arrays.toString(expected));
  }

  private static void cursor(int expected) {
    require(glfwGetInputMode(window, GLFW_CURSOR) == expected, "Native cursor mode mismatch");
  }

  private static void editX(int x) {
    control("trace-x");
    key(ImGuiKey.ModCtrl, true);
    key(ImGuiKey.A, true);
    frame();
    key(ImGuiKey.A, false);
    key(ImGuiKey.ModCtrl, false);
    frame();
    ImGui.getIO().addInputCharactersUTF8(Integer.toString(x));
    frame();
    key(ImGuiKey.Enter, true);
    frame();
    key(ImGuiKey.Enter, false);
    frames(2);
    require(views.probeInspectorCoordinate().x() == x, "Paused Inspector XYZ editing");
  }

  private static void key(int key, boolean down) {
    ImGui.getIO().addKeyEvent(key, down);
  }

  private static void control(String name) {
    if (Set.of("trace-x", "trace-y", "trace-z").contains(name))
      views.probeInspectorCoordinatesPage();
    frames(2);
    var p = views.probeAdmissionControl(name);
    require(p != null, "Missing flight control " + name);
    click(p[0], p[1]);
  }

  private static void clickImage() {
    control("image");
  }

  private static void click(float x, float y) {
    ImGui.getIO().addMousePosEvent(x, y);
    // Captured cursor cannot hover another window first: that delta is mouse look.
    // Submit the focus-changing position and press in the same native input frame.
    if (!views.flying()) frame();
    ImGui.getIO().addMouseButtonEvent(0, true);
    frame();
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static void until(BooleanSupplier condition) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!condition.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end) throw new AssertionError("Flight timeout: " + state.status);
      Thread.yield();
    }
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    glfwPollEvents();
    state.drain();
    ImGui.newFrame();
    if (focusOther) {
      ImGui.setWindowFocus("Flight Probe Other");
      focusOther = false;
    }
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(950, 750, ImGuiCond.Always);
    views.draw();
    ImGui.setNextWindowPos(990, 30, ImGuiCond.Always);
    ImGui.setNextWindowSize(190, 110, ImGuiCond.Always);
    ImGui.begin("Flight Probe Other");
    ImGui.text("Other focus target");
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
