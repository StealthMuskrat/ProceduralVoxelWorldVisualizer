package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

public final class CurveNavigationProbe {
  private static PropertyPanel panel;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1200, 1000);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var state = new EditorState(new UserConfig(), false)) {
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "navigation";
                  var item = ProcessingRegistry.create("CURVE", "curve");
                  var input = Binding.constant(0);
                  input.sourceKind = "Ambient Value";
                  input.ambientKey = "x";
                  item.inputs.put("a", input);
                  var c = new Curve();
                  c.min = -2;
                  c.max = 2;
                  c.displayMin = -3;
                  c.displayMax = 3;
                  c.points.getFirst().x = -2;
                  c.points.getFirst().y = -2;
                  c.points.getLast().x = 2;
                  c.points.getLast().y = 2;
                  c.points.add(1, new Point(0, 0));
                  item.parameters.put("curve", ProjectIO.JSON.valueToTree(c));
                  n.processing.add(item);
                  n.outputBinding = Binding.earlier(item.uuid);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var committed = ProjectIO.JSON.valueToTree(state.project());
        var draft = ProjectIO.JSON.valueToTree(state.draft);
        long revision = state.revision, history = state.document.historyBytes();
        panel = new PropertyPanel(state);
        panel.probeOpenCurve();
        frames(5);
        var base = dialog().probeView();
        var plot = dialog().probeControl("plot");
        io.addMousePosEvent(plot[0] + plot[2] * .25f, plot[1] + plot[3] * .35f);
        frame();
        double fx = (io.getMousePosX() - plot[0]) / plot[2];
        double fy = (plot[1] + plot[3] - io.getMousePosY()) / plot[3];
        double anchorX = base.x(fx), anchorY = base.y(fy);
        io.addMouseWheelEvent(0, 2);
        frame();
        var zoomed = dialog().probeView();
        near(anchorX, zoomed.x(fx));
        near(anchorY, zoomed.y(fy));
        require(dialog().probeZoom() > 1, "Wheel did not zoom");
        double zoom = dialog().probeZoom();
        // Capture a middle drag inside the plot, then continue above/right of its boundary.
        centerMouse();
        io.addMouseButtonEvent(2, true);
        frame();
        double sx = io.getMousePosX(), sy = io.getMousePosY();
        var beforePan = dialog().probeView();
        io.addMousePosEvent(plot[0] + plot[2] + 60, plot[1] - 40);
        frame();
        double dx = (sx - io.getMousePosX()) / plot[2] * beforePan.width();
        double dy = (io.getMousePosY() - sy) / plot[3] * beforePan.height();
        near(beforePan.minX() + dx, dialog().probeView().minX());
        near(beforePan.minY() + dy, dialog().probeView().minY());
        io.addMouseButtonEvent(2, false);
        frame();
        var released = dialog().probeView();
        io.addMousePosEvent(plot[0] + plot[2] + 90, plot[1] - 80);
        frame();
        require(released.equals(dialog().probeView()), "Released pan kept moving");
        centerMouse();
        var beforeCancel = dialog().probeView();
        io.addMouseButtonEvent(2, true);
        frame();
        io.addMousePosEvent(plot[0] + plot[2] / 2 + 45, plot[1] + plot[3] / 2 + 35);
        frame();
        require(!beforeCancel.equals(dialog().probeView()), "Pan did not move");
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        frame();
        io.addMousePosEvent(plot[0] + plot[2] / 2 + 65, plot[1] + plot[3] / 2 + 55);
        frame();
        require(
            beforeCancel.equals(dialog().probeView()),
            "Escape failed to restore captured baseline");
        io.addMouseButtonEvent(2, false);
        frame();
        click(dialog().probeControl("fit"));
        require(base.equals(dialog().probeView()), "Fit did not restore configured ranges");
        var middle = dialog().probeCurve().points.get(1).uuid;
        var center = dialog().probePoint(middle);
        io.addMousePosEvent(center[0], center[1]);
        frame();
        io.addMouseButtonEvent(0, true);
        frame();
        var held = dialog().probeView();
        io.addMouseWheelEvent(0, 3);
        frame();
        require(held.equals(dialog().probeView()), "Wheel changed the view during a point drag");
        io.addMouseButtonEvent(0, false);
        frames(2);
        require(!dialog().probeCanUndo(), "Navigation created authoring history");
        click(dialog().probeControl("cancel"));
        frames(2);
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Navigation changed document");
        require(draft.equals(ProjectIO.JSON.valueToTree(state.draft)), "Navigation changed draft");
        require(
            revision == state.revision && history == state.document.historyBytes(),
            "Navigation changed history/revision");
        state.document.save(out.resolve("curve-navigation-project.json"));
        Files.writeString(
            out.resolve("curve-navigation-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "cursorAnchor",
                    true,
                    "zoom",
                    zoom,
                    "capturedOutsidePlot",
                    true,
                    "panEscape",
                    true,
                    "fitRestored",
                    true,
                    "wheelIgnoredDuringPointDrag",
                    true,
                    "viewOnly",
                    true)));
        System.out.println(
            "CURVE_NAVIGATION_NATIVE_PASS cursor anchor / captured outside pan / Escape / Fit / no"
                + " authored changes");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static CurveDialog dialog() {
    return panel.probeCurveDialog();
  }

  private static void centerMouse() {
    var p = dialog().probeControl("plot");
    ImGui.getIO().addMousePosEvent(p[0] + p[2] / 2, p[1] + p[3] / 2);
    frame();
  }

  private static void click(float[] p) {
    require(p != null, "Control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0], p[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(30, 30, ImGuiCond.Always);
    panel.probeCurvePanel();
    ImGui.render();
  }

  private static void near(double a, double b) {
    require(Double.isFinite(b) && Math.abs(a - b) < 1e-10, a + " != " + b);
  }

  private static void require(boolean ok, String error) {
    if (!ok) throw new AssertionError(error);
  }
}
