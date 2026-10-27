package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.Coordinates;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.SlicePlane;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Production 2D resize/release input and shared image context-menu ownership. */
final class ImageGestureProbe {
  private static Viewports views;
  private static EditorState state;
  private static long window;
  private static float width = 950, height = 750;
  private static boolean collapsed;
  private static String focus;

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    window = handle;
    glfwShowWindow(window);
    glfwFocusWindow(window);
    glfwPollEvents();
    require(glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0, "Image gesture window focus");
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      state.user.visualization.streaming = false;
      state.user.editor.opportunitiesPerSecond = 20;
      require(
          state.change(
              p -> {
                p.noise2D.add(field("image-affine", 2, "x + 100*z"));
                p.noise3D.add(field("context-affine", 3, "x + 10*y + 100*z"));
              }),
          "Image fixture validation");
      var authored = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      views = new Viewports(state, new PropertyPanel(state), window);
      try {
        views.probeAdmissionSource(state.project().noise2D.getFirst().uuid);
        until(views::probeAdmissionReady);
        frames(3);
        state.user.editor.autoRegenerate = false;
        var accepted = views.probeAcceptedImage();
        long stamp = views.probeSubmissionStamp(0);
        positionAt(.25, .75);
        button(0, true);
        button(0, false);
        var selected = views.probeImagePicked();
        var uv = pointer();
        var point = accepted.at(uv[0], uv[1]);
        require(
            selected != null
                && selected.x() == (int) Math.floor(point.x())
                && selected.z() == (int) Math.floor(point.z()),
            "Owned 2D click did not use accepted geometry");
        var hover = views.probeImageHover();
        require(
            hover != null && hover.x() == point.x() && hover.z() == point.z(),
            "2D hover pointer precision disagreed with accepted geometry");
        near(point.x() + 100 * point.z(), hover.raw());
        var samples = new ArrayList<Map<String, Object>>();
        samples.add(
            sample("2D-left", uv, List.of(point.x(), 0., point.z()), hover.raw(), selected));

        positionAt(.5, .5);
        var bounds = bounds();
        float startX = ImGui.getIO().getMousePosX(), startY = ImGui.getIO().getMousePosY();
        var before = views.probeImageNavigation();
        button(0, true);
        position(startX + 20, startY + 10);
        var partial = views.probeImageNavigation();
        width = 1100;
        height = 800;
        frame();
        same(partial, "Resizing jumped an owned 2D pan");
        var resizedBounds = bounds();
        require(
            bounds[2] != resizedBounds[2] && bounds[3] != resizedBounds[3],
            "Image resize fixture failed");
        ImGui.getIO().addMousePosEvent(startX + 80, startY + 40);
        ImGui.getIO().addMouseButtonEvent(0, false);
        frame();
        var released = views.probeImageNavigation();
        var expected = before.pan(80. / bounds[2], 40. / bounds[3]);
        same(expected, "Release-frame displacement lost or used resized dimensions");
        require(
            selected.equals(views.probeImagePicked()), "Resize drag replaced picked coordinate");
        width = 950;
        height = 750;
        frame();

        position(1150, 850);
        button(0, true);
        positionAt(.6, .6);
        button(0, false);
        same(released, "Foreign 2D drag was adopted");
        require(
            selected.equals(views.probeImagePicked()),
            "Foreign release replaced picked coordinate");
        positionAt(.5, .5);
        button(0, true);
        var b = bounds();
        position(b[0] + b[2] * .6f, b[1] + b[3] * .6f);
        key(ImGuiKey.Escape, true);
        key(ImGuiKey.Escape, false);
        positionAt(.7, .7);
        button(0, false);
        same(released, "Escape failed to restore/cancel 2D pan");
        require(
            selected.equals(views.probeImagePicked()),
            "Canceled release replaced picked coordinate");

        positionAt(.5, .5);
        button(0, true);
        positionAt(.55, .55);
        var retained = views.probeImageNavigation();
        collapsed = true;
        frame();
        position(100, 100);
        collapsed = false;
        frame();
        positionAt(.7, .7);
        button(0, false);
        same(retained, "Collapsed viewport resumed held 2D pan");
        require(
            selected.equals(views.probeImagePicked()),
            "Collapsed release replaced picked coordinate");

        positionAt(.5, .5);
        button(2, true);
        wheel(2);
        button(2, false);
        same(retained, "Middle-held wheel changed 2D navigation");
        positionAt(.4, .6);
        button(1, true);
        button(1, false);
        frames(2);
        require(views.probeOrbitContextVisible(), "Short 2D context click did not open menu");
        uv = pointer();
        point = accepted.at(uv[0], uv[1]);
        selected = views.probeImagePicked();
        require(
            selected.x() == (int) Math.floor(point.x())
                && selected.z() == (int) Math.floor(point.z()),
            "2D context selection used requested instead of accepted geometry");
        samples.add(
            sample(
                "2D-context",
                uv,
                List.of(point.x(), 0., point.z()),
                accepted.sample(uv[0], uv[1]).raw(),
                selected));
        closeMenu();
        contextRejections(false, selected);
        require(
            stamp == views.probeSubmissionStamp(0) && views.probeAcceptedImage() == accepted,
            "Manual 2D navigation regenerated/replaced accepted image");

        state.user.editor.autoRegenerate = true;
        views.probeSlice(
            state.project().noise3D.getFirst().uuid, SlicePlane.Y, 8, -3, -7.25, 12.5, .75);
        until(() -> views.probeNoiseReady(1));
        frames(3);
        state.user.editor.autoRegenerate = false;
        positionAt(.25, .75);
        button(1, true);
        button(1, false);
        frames(2);
        require(views.probeOrbitContextVisible(), "Short slice context click did not open menu");
        uv = pointer();
        var slice = views.probeSliceView();
        var sp = slice.at(uv[0], uv[1]);
        selected = views.probeSlicePicked();
        require(
            selected != null
                && selected.x() == (int) Math.floor(sp.x())
                && selected.y() == (int) Math.floor(sp.y())
                && selected.z() == (int) Math.floor(sp.z()),
            "Slice context selection lost accepted geometry");
        samples.add(
            sample(
                "slice-context",
                uv,
                List.of(sp.x(), sp.y(), sp.z()),
                slice.sample(uv[0], uv[1]).raw(),
                selected));
        closeMenu();
        contextRejections(true, selected);
        require(
            authored.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Image gestures changed project/history");
        new ProjectIO().save(out.resolve("image-gestures-project.json"), state.project());
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("samples", samples);
        report.put("before", pose(before));
        report.put("released", pose(released));
        report.put("pressDimensions", List.of(bounds[2], bounds[3]));
        report.put("resizedDimensions", List.of(resizedBounds[2], resizedBounds[3]));
        report.put("releaseDelta", List.of(80, 40));
        for (String flag :
            List.of(
                "resizeStable",
                "finalReleaseApplied",
                "foreignBookmarkRetained",
                "escapeBookmarkRetained",
                "collapseCanceled",
                "heldWheelSuppressed",
                "shortContexts",
                "longContextsIgnored",
                "stickyContextsIgnored",
                "foreignContextsIgnored",
                "focusContextsCanceled",
                "buttonArbitration",
                "acceptedGeometry",
                "manualUnchanged",
                "viewOnly")) report.put(flag, true);
        Files.writeString(
            out.resolve("image-gestures-native.json"),
            ProjectIO.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println(
            "IMAGE_GESTURES_NATIVE_PASS fixed press dimensions / final release / owned bookmark"
                + " clicks / collapse / double hover / shared short context / guards / view-only");
      } finally {
        views.close();
      }
    } finally {
      glfwHideWindow(window);
    }
  }

  private static void contextRejections(boolean slice, Coordinates selected) {
    positionAt(.4, .6);
    button(1, true);
    frames(24);
    button(1, false);
    noContext(slice, selected, "Long context press");
    positionAt(.4, .6);
    float x = ImGui.getIO().getMousePosX(), y = ImGui.getIO().getMousePosY();
    button(1, true);
    position(x + 20, y - 10);
    position(x, y);
    button(1, false);
    noContext(slice, selected, "Out-and-back context drag");
    position(1150, 850);
    button(1, true);
    positionAt(.4, .6);
    button(1, false);
    noContext(slice, selected, "Foreign context release");
    positionAt(.4, .6);
    button(1, true);
    focus = "Image Gesture Other";
    frame();
    focus = "Visualizers";
    frame();
    button(1, false);
    noContext(slice, selected, "Focus-canceled context release");
    positionAt(.4, .6);
    button(1, true);
    button(0, true);
    button(0, false);
    button(1, false);
    noContext(slice, selected, "Competing button release");
  }

  private static void noContext(boolean slice, Coordinates selected, String message) {
    require(
        !views.probeOrbitContextVisible()
            && selected.equals(slice ? views.probeSlicePicked() : views.probeImagePicked()),
        message);
  }

  private static Map<String, Object> sample(
      String kind, double[] uv, List<Double> point, double raw, Coordinates picked) {
    return Map.of(
        "kind",
        kind,
        "pointer",
        uv,
        "point",
        point,
        "raw",
        raw,
        "picked",
        picked.y() == null
            ? List.of(picked.x(), picked.z())
            : List.of(picked.x(), picked.y(), picked.z()));
  }

  private static Noise field(String id, int dimension, String expression) {
    var n = new Noise();
    n.id = id;
    n.dimension = dimension;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", 0.);
    var item = ProcessingRegistry.create("EXPRESSION", id + "-expression", dimension);
    item.parameters.put("expression", ProjectIO.JSON.valueToTree(expression));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    return n;
  }

  private static double[] pose(NoiseImageNavigation n) {
    return new double[] {n.x(), n.z(), n.step()};
  }

  private static void same(NoiseImageNavigation expected, String message) {
    var actual = views.probeImageNavigation();
    near(expected.x(), actual.x());
    near(expected.z(), actual.z());
    require(expected.step() == actual.step(), message);
  }

  private static void near(double expected, double actual) {
    require(Math.abs(expected - actual) < 1e-9, "Image arithmetic: " + expected + " != " + actual);
  }

  private static void closeMenu() {
    key(ImGuiKey.Escape, true);
    key(ImGuiKey.Escape, false);
    frames(3);
    require(!views.probeOrbitContextVisible(), "Image context menu did not close");
  }

  private static float[] bounds() {
    frames(2);
    return views.probeAdmissionControl("image-bounds");
  }

  private static double[] pointer() {
    var b = views.probeAdmissionControl("image-bounds");
    return new double[] {
      (ImGui.getIO().getMousePosX() - b[0]) / (double) b[2],
      (ImGui.getIO().getMousePosY() - b[1]) / (double) b[3]
    };
  }

  private static void positionAt(double u, double v) {
    var b = bounds();
    position((float) (b[0] + u * b[2]), (float) (b[1] + v * b[3]));
  }

  private static void position(float x, float y) {
    ImGui.getIO().addMousePosEvent(x, y);
    frame();
  }

  private static void button(int b, boolean down) {
    ImGui.getIO().addMouseButtonEvent(b, down);
    frame();
  }

  private static void key(int key, boolean down) {
    ImGui.getIO().addKeyEvent(key, down);
    frame();
  }

  private static void wheel(float amount) {
    ImGui.getIO().addMouseWheelEvent(0, amount);
    frame();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void until(BooleanSupplier condition) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!condition.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end)
        throw new AssertionError("Image gesture timeout: " + state.status);
      Thread.yield();
    }
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
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
    views.draw();
    ImGui.setNextWindowPos(1120, 30, ImGuiCond.Always);
    ImGui.setNextWindowSize(150, 110, ImGuiCond.Always);
    ImGui.begin("Image Gesture Other");
    ImGui.text("Other focus target");
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
