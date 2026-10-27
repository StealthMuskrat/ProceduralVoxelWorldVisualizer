package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.SlicePlane;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Production orthogonal image input and accepted geometry against an analytical affine field. */
final class SliceInputProbe {
  private static Viewports views;
  private static EditorState state;
  private static long window;
  private static UUID noise;
  private static String focus;

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    window = handle;
    glfwShowWindow(window);
    glfwFocusWindow(window);
    glfwPollEvents();
    require(glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0, "Slice window focus");
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      state.user.visualization.streaming = false;
      state.user.editor.opportunitiesPerSecond = 20;
      require(state.change(SliceInputProbe::fixture), "Slice fixture validation");
      noise = state.project().noise3D.getFirst().uuid;
      var authored = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      views = new Viewports(state, new PropertyPanel(state), window);
      try {
        var samples = new ArrayList<Map<String, Object>>();
        for (var plane : SlicePlane.values()) {
          reset(plane);
          var accepted = views.probeSliceView();
          var raw = views.probeSliceRaw();
          near(value(accepted.at(0, 0)), raw[0]);
          near(value(accepted.at(7. / 8, 7. / 8)), raw[63]);
          positionAt(.25, .75);
          var hover = views.probeSliceHover();
          require(hover != null, "Slice hover missing");
          near(value(hover.point()), hover.raw());
          samples.add(sample(accepted, hover, pointer(), raw[0], raw[63]));
        }

        reset(SlicePlane.X);
        positionAt(.25, .75);
        var anchor = pointer();
        var before = views.probeSliceNavigation();
        wheel(2);
        var zoomed = views.probeSliceNavigation();
        near(
            before.u() + (anchor[0] * 8 - 4) * before.step(),
            zoomed.u() + (anchor[0] * 8 - 4) * zoomed.step());
        near(
            before.v() + (anchor[1] * 8 - 4) * before.step(),
            zoomed.v() + (anchor[1] * 8 - 4) * zoomed.step());
        wheel(-2);
        same(before, "Inverse slice zoom changed center");

        var pans = new ArrayList<Map<String, Object>>();
        for (int held : new int[] {0, 2}) {
          reset(SlicePlane.Z);
          var bounds = bounds();
          positionAt(.5, .5);
          float startX = ImGui.getIO().getMousePosX(), startY = ImGui.getIO().getMousePosY();
          before = views.probeSliceNavigation();
          button(held, true);
          position(5, startY + 30);
          var panned = views.probeSliceNavigation();
          double du = (ImGui.getIO().getMousePosX() - startX) / (double) bounds[2],
              dv = (ImGui.getIO().getMousePosY() - startY) / (double) bounds[3];
          near(before.u() - du * 8 * .75, panned.u());
          near(before.v() - dv * 8 * .75, panned.v());
          wheel(2);
          same(panned, "Wheel changed captured slice pan");
          key(ImGuiKey.Escape, true);
          key(ImGuiKey.Escape, false);
          positionAt(.6, .6);
          button(held, false);
          same(before, "Escape failed to restore/cancel slice pan");
          pans.add(
              Map.of(
                  "button",
                  held,
                  "du",
                  du,
                  "dv",
                  dv,
                  "before",
                  pose(before),
                  "after",
                  pose(panned)));
        }

        reset(SlicePlane.Y);
        before = views.probeSliceNavigation();
        position(1100, 800);
        button(0, true);
        positionAt(.5, .5);
        positionAt(.6, .6);
        button(0, false);
        same(before, "Foreign slice drag was adopted");
        positionAt(.5, .5);
        button(0, true);
        positionAt(.55, .55);
        var retained = views.probeSliceNavigation();
        focus = "Slice Probe Other";
        frame();
        positionAt(.65, .65);
        focus = "Visualizers";
        frame();
        positionAt(.75, .75);
        button(0, false);
        same(retained, "Focus return resumed held slice pan");

        reset(SlicePlane.Y);
        positionAt(.5, .5);
        button(2, true);
        positionAt(.55, .55);
        retained = views.probeSliceNavigation();
        views.probeNoiseTab(0);
        frames(3);
        position(100, 100);
        views.probeSliceTab();
        frames(3);
        positionAt(.7, .7);
        button(2, false);
        same(retained, "Tab return resumed held slice pan");

        reset(SlicePlane.X);
        positionAt(.5, .5);
        button(0, true);
        positionAt(.55, .55);
        views.probeSliceRequest(SlicePlane.Z, 16, 9);
        frames(3);
        var superseded = views.probeSliceNavigation();
        positionAt(.7, .7);
        button(0, false);
        same(superseded, "Held slice pan overwrote a newer plane/extent request");

        reset(SlicePlane.X);
        state.user.editor.autoRegenerate = false;
        var displayed = views.probeSliceView();
        long stamp = views.probeSubmissionStamp(1);
        views.probeSliceRequest(SlicePlane.Z, 16, 9);
        frames(3);
        require(
            views.probeSliceView() == displayed && stamp == views.probeSubmissionStamp(1),
            "Manual request replaced accepted slice or submitted work");
        positionAt(.25, .75);
        var pendingPointer = pointer();
        var expected = displayed.at(pendingPointer[0], pendingPointer[1]);
        require(
            expected.equals(views.probeSliceHover().point()),
            "Pending request mixed hover geometry");
        near(value(expected), views.probeSliceHover().raw());
        button(0, true);
        button(0, false);
        var picked = views.probeSlicePicked();
        require(
            picked != null
                && picked.x() == (int) Math.floor(expected.x())
                && picked.y() == (int) Math.floor(expected.y())
                && picked.z() == (int) Math.floor(expected.z()),
            "Pending request mixed bookmark geometry");
        var pending = sample(displayed, views.probeSliceHover(), pendingPointer, 0, 0);
        control("toolbar");
        until(() -> views.probeNoiseReady(1) && views.probeSliceView() != displayed);
        var refreshed = views.probeSliceView();
        require(
            refreshed.plane() == SlicePlane.Z
                && refreshed.side() == 16
                && refreshed.slice() == 9
                && displayed.plane() == SlicePlane.X
                && displayed.side() == 8
                && displayed.slice() == -3,
            "Accepted slice metadata was not immutable");
        positionAt(.25, .75);
        var current =
            sample(
                refreshed,
                views.probeSliceHover(),
                pointer(),
                views.probeSliceRaw()[0],
                views.probeSliceRaw()[255]);
        require(
            authored.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Slice input changed project/history");
        new ProjectIO().save(out.resolve("slice-project.json"), state.project());
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("samples", samples);
        report.put("anchor", anchor);
        report.put("zoomBefore", pose(new SliceNavigation(SlicePlane.X, -3, -7.25, 12.5, .75, 8)));
        report.put("zoomAfter", pose(zoomed));
        report.put("pans", pans);
        report.put("pending", pending);
        report.put("refreshed", current);
        report.put("picked", List.of(picked.x(), picked.y(), picked.z()));
        for (String flag :
            List.of(
                "allPlanes",
                "anchoredZoom",
                "outsidePan",
                "wheelSuppressed",
                "escapeRollback",
                "foreignIgnored",
                "focusCanceled",
                "tabCanceled",
                "requestSuperseded",
                "pendingGeometry",
                "bookmarkGeometry",
                "manualPreview",
                "immutableAccepted",
                "viewOnly")) report.put(flag, true);
        Files.writeString(
            out.resolve("slice-input-native.json"),
            ProjectIO.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println(
            "SLICE_INPUT_NATIVE_PASS XYZ planes / affine samples / anchored wheel / captured"
                + " left-middle / Escape / foreign-focus-tab / accepted pending hover-bookmark /"
                + " manual / view-only");
      } finally {
        views.close();
      }
    } finally {
      glfwHideWindow(window);
    }
  }

  private static Map<String, Object> sample(
      SliceImageView view, SliceImageView.Sample sample, double[] uv, double first, double last) {
    var result = new LinkedHashMap<String, Object>();
    result.put("plane", view.plane().name());
    result.put("slice", view.slice());
    result.put("side", view.side());
    result.put("origin", List.of(view.originU(), view.originV()));
    result.put("step", view.step());
    result.put("pointer", uv);
    var p = sample.point();
    result.put("point", List.of(p.x(), p.y(), p.z()));
    result.put("raw", sample.raw());
    result.put("first", first);
    result.put("last", last);
    return result;
  }

  private static void fixture(ProjectData p) {
    var field = new Noise();
    field.id = "slice-affine";
    field.dimension = 3;
    field.algorithm.key = "CONSTANT";
    field.algorithm.parameters.put("value", 0.);
    var expression = ProcessingRegistry.create("EXPRESSION", "slice-expression", 3);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x + 10*y + 100*z"));
    field.processing.add(expression);
    field.outputBinding = Binding.earlier(expression.uuid);
    p.noise3D.add(field);
  }

  private static double value(SliceImageView.Point p) {
    return p.x() + 10 * p.y() + 100 * p.z();
  }

  private static double[] pose(SliceNavigation n) {
    return new double[] {n.u(), n.v(), n.step(), n.side()};
  }

  private static void near(double expected, double actual) {
    require(Math.abs(expected - actual) < 1e-9, "Slice arithmetic: " + expected + " != " + actual);
  }

  private static void same(SliceNavigation n, String message) {
    var actual = views.probeSliceNavigation();
    require(
        n.plane() == actual.plane() && n.slice() == actual.slice() && n.side() == actual.side(),
        message);
    near(n.u(), actual.u());
    near(n.v(), actual.v());
    near(n.step(), actual.step());
  }

  private static void reset(SlicePlane plane) {
    state.user.editor.autoRegenerate = true;
    views.probeSlice(noise, plane, 8, -3, -7.25, 12.5, .75);
    until(() -> views.probeNoiseReady(1));
    frames(3);
    focus = "Visualizers";
    frame();
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

  private static void wheel(float amount) {
    ImGui.getIO().addMouseWheelEvent(0, amount);
    frame();
  }

  private static void key(int code, boolean down) {
    ImGui.getIO().addKeyEvent(code, down);
    frame();
  }

  private static void control(String key) {
    frames(2);
    var p = views.probeAdmissionControl(key);
    require(p != null, "Missing slice control " + key);
    position(p[0], p[1]);
    button(0, true);
    button(0, false);
  }

  private static void until(BooleanSupplier condition) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!condition.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end) throw new AssertionError("Slice timeout: " + state.status);
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
    ImGui.begin("Slice Probe Other");
    ImGui.text("Other focus target");
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
