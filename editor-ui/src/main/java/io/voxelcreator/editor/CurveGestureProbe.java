package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Real pointer gestures against the production Curve Editor and Properties publication path. */
public final class CurveGestureProbe {
  private static PropertyPanel panel;
  private static UUID first, second;

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
                  n.id = "curve";
                  var item = ProcessingRegistry.create("CURVE", "curve");
                  var input = Binding.constant(0);
                  input.sourceKind = "Ambient Value";
                  input.ambientKey = "x";
                  item.inputs.put("a", input);
                  var curve = new Curve();
                  curve.points.add(1, new Point(.25, .25));
                  curve.points.add(2, new Point(.75, .75));
                  item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
                  n.processing.add(item);
                  n.outputBinding = Binding.earlier(item.uuid);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        var n = state.project().noise2D.getFirst();
        state.select("noise2D", n.uuid);
        var draft = (Noise) state.draft;
        var item = draft.processing.getFirst();
        var source = ProjectIO.JSON.convertValue(item.parameters.get("curve"), Curve.class);
        first = source.points.get(1).uuid;
        second = source.points.get(2).uuid;
        var committed = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        panel = new PropertyPanel(state);
        panel.probeOpenCurve();
        frames(5);
        click(dialog().probePoint(first));
        drag(.076, false);
        click(dialog().probeControl("cancel"));
        frames(2);
        require(panel.probeCurveDialog() == null, "Cancel did not close");
        require(
            item.parameters.get("curve").equals(ProjectIO.JSON.valueToTree(source)),
            "Cancel leaked curve");
        panel.probeOpenCurve();
        frames(4);
        click(dialog().probePoint(first));
        io.addKeyEvent(ImGuiKey.ModShift, true);
        frame();
        click(dialog().probePoint(second));
        io.addKeyEvent(ImGuiKey.ModShift, false);
        frame();
        require(dialog().selectedPoints().equals(Set.of(first, second)), "Shift group selection");
        click(dialog().probeControl("snapY"));
        drag(.076, false);
        check(.35, .85);
        click(dialog().probeControl("undo"));
        check(.25, .75);
        require(!dialog().probeCanUndo(), "One gesture made extra history");
        click(dialog().probeControl("redo"));
        check(.35, .85);
        // A local Undo while the pointer is held must stop the UI drag as well as the session.
        holdMove(.1);
        panel.curveShortcut("CurveUndo");
        frame();
        moveHeld(.2);
        check(.35, .85);
        release();
        holdMove(.15);
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        frame();
        moveHeld(.2);
        check(.35, .85);
        release();
        click(dialog().probeControl("undo"));
        check(.25, .75);
        click(dialog().probeControl("redo"));
        check(.35, .85);
        click(dialog().probeControl("undo"));
        drag(.073, true);
        check(.323, .823);
        click(dialog().probeControl("undo"));
        check(.25, .75);
        drag(.076, false);
        check(.35, .85);
        click(dialog().probeControl("apply"));
        frames(2);
        require(panel.probeCurveDialog() == null, "Curve Apply did not close");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())), "Premature publication");
        require(
            revision == state.revision && history == state.document.historyBytes(),
            "Curve staged document history");
        require(state.applyDraft(), "Properties Apply rejected");
        near(.35, sample(state.project(), n.uuid));
        state.document.undo();
        state.revision++;
        near(.25, sample(state.project(), n.uuid));
        state.document.redo();
        state.revision++;
        near(.35, sample(state.project(), n.uuid));
        state.document.save(out.resolve("curve-gesture-project.json"));
        Files.writeString(
            out.resolve("curve-gesture-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "before",
                    .25,
                    "after",
                    .35,
                    "points",
                    4,
                    "privateStage",
                    true,
                    "cancel",
                    true,
                    "escape",
                    true,
                    "altBypass",
                    true,
                    "oneGestureUndo",
                    true,
                    "oneDocumentUndoRedo",
                    true)));
        System.out.println(
            "CURVE_GESTURE_NATIVE_PASS group snap / Alt bypass / Escape / active Undo / private"
                + " Apply / document Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static float startX, startY, plotHeight;

  private static CurveDialog dialog() {
    return panel.probeCurveDialog();
  }

  private static void holdMove(double dy) {
    var io = ImGui.getIO();
    var p = dialog().probePoint(first);
    startX = p[0];
    startY = p[1];
    plotHeight = dialog().probeControl("plot")[3];
    io.addMousePosEvent(startX, startY);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    moveHeld(dy / 2);
    moveHeld(dy);
  }

  private static void moveHeld(double dy) {
    ImGui.getIO().addMousePosEvent(startX, startY - (float) (dy * plotHeight));
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frames(2);
  }

  private static void drag(double dy, boolean alt) {
    var io = ImGui.getIO();
    if (alt) {
      io.addKeyEvent(ImGuiKey.ModAlt, true);
      frame();
    }
    holdMove(dy);
    release();
    if (alt) {
      io.addKeyEvent(ImGuiKey.ModAlt, false);
      frame();
    }
  }

  private static void check(double a, double b) {
    var c = dialog().probeCurve();
    near(a, c.points.get(1).y);
    near(b, c.points.get(2).y);
    near(.25, c.points.get(1).x);
    near(.75, c.points.get(2).x);
  }

  private static double sample(ProjectData p, UUID id) {
    return new NoiseCompiler(p, 0).compile(id).sample(.25, 0, 0);
  }

  private static void near(double a, double b) {
    require(Math.abs(a - b) < 2e-6, a + " != " + b);
  }

  private static void click(float[] p) {
    require(p != null, "Missing control");
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0], p[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(30, 30, ImGuiCond.Always);
    panel.probeCurvePanel();
    ImGui.render();
  }

  private static void require(boolean value, String error) {
    if (!value) throw new AssertionError(error);
  }
}
