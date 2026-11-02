package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Deferred real rectangular uploads, affine hover, owned pan/zoom and pending accepted geometry.
 */
final class RectangularSliceProbe {
  private static EditorState state;
  private static Viewports views;
  private static long window;

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    window = handle;
    glfwShowWindow(window);
    glfwFocusWindow(window);
    glfwPollEvents();
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      state.user.visualization.streaming = false;
      state.user.performance.previewCellBudget = 35;
      state.user.performance.maxPreviewDimension = 8;
      state.user.editor.opportunitiesPerSecond = 20;
      require(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "affine";
                n.dimension = 3;
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", 1.);
                var item = ProcessingRegistry.create("EXPRESSION", "affine", 3);
                item.inputs.clear();
                item.parameters.put("expression", ProjectIO.JSON.valueToTree("x + 10*y + 100*z"));
                n.processing.add(item);
                n.outputBinding = Binding.earlier(item.uuid);
                p.noise3D.add(n);
              }),
          "Fixture rejected");
      var noise = state.project().noise3D.getFirst().uuid;
      state.document.save(out.resolve("before.json"));
      state.user.save(out.resolve("preferences-before.json"));
      state.select("noise3D", noise);
      ((Noise) state.draft).id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      Object draft = state.draft;
      var values = ProjectIO.JSON.valueToTree(draft);
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      try (var visualizers = new Viewports(state, new PropertyPanel(state), window)) {
        views = visualizers;
        var rows = new ArrayList<Map<String, Object>>();
        for (var plane : SlicePlane.values()) {
          views.probeRectangularSlice(noise, plane, new GridExtent(3, 5, 7));
          untilReady();
          positionAt(.25, .75);
          rows.add(receipt());
        }
        var navigation = views.probeSliceNavigation();
        var anchor = pointer();
        ImGui.getIO().addMouseWheelEvent(0, 2);
        frame();
        var zoomed = views.probeSliceNavigation();
        near(
            navigation.u()
                + (anchor[0] * navigation.width() - navigation.width() / 2.) * navigation.step(),
            zoomed.u() + (anchor[0] * zoomed.width() - zoomed.width() / 2.) * zoomed.step());
        near(
            navigation.v()
                + (anchor[1] * navigation.height() - navigation.height() / 2.) * navigation.step(),
            zoomed.v() + (anchor[1] * zoomed.height() - zoomed.height() / 2.) * zoomed.step());
        views.probeRectangularSlice(noise, SlicePlane.Z, new GridExtent(3, 5, 7));
        untilReady();
        positionAt(.5, .5);
        var panBefore = views.probeSliceNavigation();
        var image = views.probeAdmissionControl("image-bounds");
        float startX = ImGui.getIO().getMousePosX(), startY = ImGui.getIO().getMousePosY();
        ImGui.getIO().addMouseButtonEvent(2, true);
        frame();
        ImGui.getIO().addMousePosEvent(startX + 20, startY - 12);
        frame();
        var panned = views.probeSliceNavigation();
        double du = (ImGui.getIO().getMousePosX() - startX) / (double) image[2];
        double dv = (ImGui.getIO().getMousePosY() - startY) / (double) image[3];
        near(panBefore.u() - du * 3 * .75, panned.u());
        near(panBefore.v() - dv * 5 * .75, panned.v());
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
        frame();
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
        frame();
        ImGui.getIO().addMouseButtonEvent(2, false);
        frame();
        require(
            panBefore.equals(views.probeSliceNavigation()),
            "Escape did not restore rectangular pan");
        untilReady();
        positionAt(.25, .75);
        var accepted = views.probeSliceView();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(2);
        try {
          for (int i = 0; i < 2; i++)
            state.background.submit(
                () -> {
                  started.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
          require(started.await(5, TimeUnit.SECONDS), "Fixture workers did not reach barrier");
          views.probeRectangularSlice(noise, SlicePlane.X, new GridExtent(6, 4, 2));
          frames(4);
          positionAt(.25, .75);
          require(
              views.probeSliceView() == accepted, "Pending shape replaced accepted geometry early");
          var pending = receipt();
          release.countDown();
          untilReady();
          positionAt(.25, .75);
          var refreshed = receipt();
          require(
              views.probeSliceView().width() == 4 && views.probeSliceView().height() == 2,
              "New rectangular slice dimensions missing");
          Files.writeString(
              out.resolve("rectangular-slice-native.json"),
              ProjectIO.JSON.writeValueAsString(
                  Map.of(
                      "formatVersion",
                      1,
                      "rows",
                      rows,
                      "pending",
                      pending,
                      "refreshed",
                      refreshed,
                      "navigation",
                      navigation,
                      "zoomed",
                      zoomed,
                      "anchor",
                      anchor,
                      "panBefore",
                      panBefore,
                      "panned",
                      panned,
                      "panDelta",
                      List.of(du, dv))));
        } finally {
          release.countDown();
        }
      } finally {
        glfwHideWindow(window);
      }
      require(
          before.equals(ProjectIO.JSON.valueToTree(state.project()))
              && state.draft == draft
              && values.equals(ProjectIO.JSON.valueToTree(draft))
              && state.revision == revision
              && state.document.historyBytes() == history
              && !state.document.dirty()
              && state.seedInputs.text("seedOffset").equals("-"),
          "Slices changed private/project/history state");
      new ProjectIO().save(out.resolve("after.json"), state.project());
      state.user.save(out.resolve("preferences.json"));
      System.out.println(
          "RECTANGULAR_SLICE_NATIVE_PASS three plane uploads / affine hover / anchored zoom /"
              + " rectangular pan / pending geometry");
    }
  }

  private static Map<String, Object> receipt() {
    var view = views.probeSliceView();
    var hover = views.probeSliceHover();
    require(hover != null, "Native rectangular hover absent");
    int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
    int width, height;
    try {
      glBindTexture(GL_TEXTURE_2D, views.probe3DTexture());
      width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
      height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
    } finally {
      glBindTexture(GL_TEXTURE_2D, bound);
    }
    require(width == view.width() && height == view.height(), "GPU upload used square dimensions");
    var row = new LinkedHashMap<String, Object>();
    row.put("plane", view.plane());
    row.put("slice", view.slice());
    row.put("width", width);
    row.put("height", height);
    row.put("step", view.step());
    row.put("origin", List.of(view.originU(), view.originV()));
    row.put("values", views.probeSliceRaw());
    row.put("pointer", pointer());
    row.put("point", hover.point());
    row.put("raw", hover.raw());
    return row;
  }

  private static double[] pointer() {
    var b = views.probeAdmissionControl("image-bounds");
    return new double[] {
      (ImGui.getIO().getMousePosX() - b[0]) / (double) b[2],
      (ImGui.getIO().getMousePosY() - b[1]) / (double) b[3]
    };
  }

  private static void positionAt(double x, double y) {
    var b = views.probeAdmissionControl("image-bounds");
    ImGui.getIO().addMousePosEvent((float) (b[0] + b[2] * x), (float) (b[1] + b[3] * y));
    frame();
  }

  private static void untilReady() {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!views.probeNoiseReady(1)) {
      frame();
      if (System.nanoTime() > end) throw new AssertionError("Rectangular slice timeout");
      Thread.yield();
    }
    frames(2);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    glfwPollEvents();
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(1000, 800, ImGuiCond.Always);
    views.draw();
    ImGui.render();
  }

  private static void near(double a, double b) {
    require(Math.abs(a - b) < 1e-8, "Rectangular navigation mismatch");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
