package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.ProjectData;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Production orbit image events, native focus and context/pick arbitration. */
final class OrbitInputProbe {
  private static EditorState state;
  private static Viewports views;
  private static long window;
  private static String focus;

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    window = handle;
    glfwShowWindow(window);
    glfwFocusWindow(window);
    glfwPollEvents();
    require(glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0, "Orbit window focus");
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      state.user.visualization.streaming = false;
      state.user.editor.opportunitiesPerSecond = 20;
      require(state.change(OrbitInputProbe::fixture), "Orbit fixture validation");
      var authored = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      views = new Viewports(state, new PropertyPanel(state), window);
      try {
        var observations = new ArrayList<Map<String, Object>>();
        reset();
        var initial = pose();
        var p = point();
        position(p[0], p[1]);
        button(0, true);
        position(p[0] + 40, p[1] - 20);
        var partial = pose();
        require(
            Math.abs(partial[3] - (initial[3] - .32)) < 1e-10 && Math.abs(partial[4] + .16) < 1e-10,
            "Orbit initial displacement");
        float outsideX = 5, outsideY = p[1] + 35;
        position(outsideX, outsideY);
        var rotated = pose();
        double dx = outsideX - p[0], dy = outsideY - p[1];
        require(
            Math.abs(rotated[3] - (initial[3] - dx * .008)) < 1e-10
                && Math.abs(rotated[4] - Math.max(-1.5, Math.min(1.5, initial[4] + dy * .008)))
                    < 1e-10,
            "Owned orbit stopped outside image");
        button(0, false);
        require(
            views.probeInspectorTrace() == null && !views.probeInspectorOpen(),
            "Orbit release submitted a pick");
        observations.add(observation("orbit", initial, rotated, dx, dy));

        reset();
        initial = pose();
        p = point();
        position(p[0], p[1]);
        button(1, true);
        position(p[0] + 20, p[1] + 10);
        double distance = pose()[5];
        ImGui.getIO().addMouseWheelEvent(0, 2);
        frame();
        require(pose()[5] == distance, "Wheel changed an owned pan");
        outsideY = p[1] - 25;
        position(5, outsideY);
        var panned = pose();
        dx = 5 - p[0];
        dy = outsideY - p[1];
        require(
            Math.abs(panned[0] - (initial[0] - dy * .03)) < 1e-10
                && Math.abs(panned[2] - (initial[2] + dx * .03)) < 1e-10,
            "Owned right pan stopped outside image");
        button(1, false);
        require(!views.probeOrbitContextVisible(), "Right pan opened context menu");
        observations.add(observation("pan", initial, panned, dx, dy));

        reset();
        p = point();
        position(p[0], p[1]);
        button(1, true);
        button(1, false);
        frames(2);
        require(views.probeOrbitContextVisible(), "Short right click did not open context menu");
        key(ImGuiKey.Escape, true);
        key(ImGuiKey.Escape, false);
        frames(3);
        require(!views.probeOrbitContextVisible(), "Context menu did not close");
        position(p[0], p[1]);
        button(1, true);
        frames(24);
        button(1, false);
        require(!views.probeOrbitContextVisible(), "Long right press opened context menu");

        for (int held : new int[] {0, 1}) {
          reset();
          initial = pose();
          p = point();
          position(p[0], p[1]);
          button(held, true);
          position(p[0] + 24, p[1] - 10);
          position(p[0], p[1]);
          button(held, false);
          same(initial, "Out-and-back drag did not restore pose");
          require(
              !views.probeOrbitContextVisible() && !views.probeInspectorOpen(),
              "Out-and-back drag became a click");
        }

        reset();
        initial = pose();
        p = point();
        position(1100, 800);
        button(0, true);
        position(p[0], p[1]);
        position(p[0] + 30, p[1] + 20);
        button(0, false);
        same(initial, "Foreign drag was adopted");
        require(!views.probeInspectorOpen(), "Foreign release submitted a pick");

        position(p[0], p[1]);
        button(0, true);
        position(p[0] + 30, p[1] + 20);
        key(ImGuiKey.Escape, true);
        key(ImGuiKey.Escape, false);
        position(p[0] + 60, p[1] + 30);
        button(0, false);
        same(initial, "Escape did not restore/cancel owned drag");
        require(!views.probeInspectorOpen(), "Canceled orbit release submitted a pick");

        position(p[0], p[1]);
        button(1, true);
        position(p[0] + 20, p[1] + 10);
        var retained = pose();
        focus = "Orbit Probe Other";
        frame();
        position(p[0] + 50, p[1] + 30);
        focus = "Visualizers";
        frame();
        position(p[0] + 80, p[1] + 50);
        button(1, false);
        same(retained, "Focus return resumed held pan");
        require(!views.probeOrbitContextVisible(), "Canceled pan release opened context menu");

        reset();
        p = point();
        position(p[0], p[1]);
        button(0, true);
        position(p[0] + 20, p[1] + 10);
        retained = pose();
        views.probeNoiseTab(0);
        frames(3);
        position(p[0] + 60, p[1] + 30);
        views.probeNoiseTab(2);
        frames(3);
        button(0, false);
        same(retained, "Tab return resumed held orbit");
        require(!views.probeInspectorOpen(), "Tab-canceled drag submitted a pick");

        for (boolean vertical : new boolean[] {false, true}) {
          reset();
          initial = pose();
          p = point();
          position(p[0], p[1]);
          if (vertical) key(ImGuiKey.ModCtrl, true);
          button(2, true);
          if (vertical) key(ImGuiKey.ModCtrl, false);
          position(p[0] + 15, p[1] + 25);
          button(2, false);
          var middle = pose();
          require(
              Math.abs(middle[0] - (initial[0] - (vertical ? 0 : .6))) < 1e-10
                  && Math.abs(middle[1] - (initial[1] - (vertical ? 1 : 0))) < 1e-10
                  && Math.abs(middle[2] - (initial[2] - (vertical ? 0 : 1))) < 1e-10,
              "Middle drag did not retain its press-time world plane");
          observations.add(observation(vertical ? "middleY" : "middleXZ", initial, middle, 15, 25));
        }

        reset();
        p = point();
        position(p[0], p[1]);
        button(0, true);
        button(0, false);
        until(() -> views.probeInspectorTrace() != null);
        require(
            views.probeInspectorTrace().trace().position().equals(new BlockPos(3, 0, 0))
                && views.probeInspectorTrace().trace().finalType() == 1,
            "Fresh click did not recover picking");
        require(
            authored.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Orbit gestures changed project/history");
        new ProjectIO().save(out.resolve("orbit-project.json"), state.project());
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("observations", observations);
        report.put("hit", List.of(3, 0, 0, 1));
        for (String flag :
            List.of(
                "outsideCaptured",
                "panWithoutMenu",
                "shortContext",
                "longPressIgnored",
                "stickyDrag",
                "foreignIgnored",
                "escapeRollback",
                "focusCanceled",
                "tabCanceled",
                "middleAxisLocked",
                "wheelSuppressed",
                "freshPickRecovered",
                "viewOnly")) report.put(flag, true);
        Files.writeString(
            out.resolve("orbit-input-native.json"),
            ProjectIO.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println(
            "ORBIT_INPUT_NATIVE_PASS owned outside drag / pan vs context / long press / sticky drag"
                + " / foreign input / Escape / focus-tab / locked middle axes / wheel / pick /"
                + " view-only");
      } finally {
        views.close();
      }
    } finally {
      glfwHideWindow(window);
    }
  }

  private static Map<String, Object> observation(
      String kind, double[] before, double[] after, double dx, double dy) {
    return Map.of("kind", kind, "before", before, "after", after, "dx", dx, "dy", dy);
  }

  private static void fixture(ProjectData p) {
    p.project.minY = 0;
    p.project.maxY = 31;
    var type = new VoxelType();
    type.id = "orbit-solid";
    p.voxelTypes.add(type);
    var field = new Noise();
    field.id = "orbit-constant";
    field.dimension = 3;
    field.algorithm.key = "CONSTANT";
    field.algorithm.parameters.put("value", 1.);
    p.noise3D.add(field);
    var layer = new Layer();
    layer.id = "orbit-world";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    var op = new Operation();
    op.id = "orbit-fill";
    op.noiseGeneratorUuid = field.uuid;
    op.interpretation = "DENSITY";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
  }

  private static double[] pose() {
    return views.probeOrbitPose(2);
  }

  private static void same(double[] expected, String message) {
    var actual = pose();
    for (int i = 0; i < actual.length; i++)
      require(
          Math.abs(actual[i] - expected[i]) < 1e-10,
          message + ": " + Arrays.toString(actual) + " != " + Arrays.toString(expected));
  }

  private static void reset() {
    views.probeWorld(null, "Final", 8, .25);
    until(views::probeWorldReady);
    frames(2);
  }

  private static float[] point() {
    frames(2);
    return views.probeAdmissionControl("image");
  }

  private static void position(float x, float y) {
    ImGui.getIO().addMousePosEvent(x, y);
    frame();
  }

  private static void button(int button, boolean down) {
    ImGui.getIO().addMouseButtonEvent(button, down);
    frame();
  }

  private static void key(int key, boolean down) {
    ImGui.getIO().addKeyEvent(key, down);
    frame();
  }

  private static void until(BooleanSupplier condition) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!condition.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end) throw new AssertionError("Orbit timeout: " + state.status);
      Thread.yield();
    }
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    glfwPollEvents();
    state.drain();
    ImGui.newFrame();
    if (focus != null) {
      ImGui.setWindowFocus(focus);
      focus = null;
    }
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(950, 750, ImGuiCond.Always);
    views.draw();
    ImGui.setNextWindowPos(990, 30, ImGuiCond.Always);
    ImGui.setNextWindowSize(190, 110, ImGuiCond.Always);
    ImGui.begin("Orbit Probe Other");
    ImGui.text("Other focus target");
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
