package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

public final class CurvePointProbe {
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
                  for (String name : List.of("aligned", "free", "auto", "insert")) {
                    var n = new Noise();
                    n.id = name;
                    var item = ProcessingRegistry.create("CURVE", "curve");
                    var input = Binding.constant(0);
                    input.sourceKind = "Ambient Value";
                    input.ambientKey = "x";
                    item.inputs.put("a", input);
                    var c = new Curve();
                    if (!name.equals("insert")) {
                      c.points.add(1, new Point(.5, .5));
                      for (var point : c.points) {
                        point.segmentInterpolation = "BEZIER";
                        point.handleMode = name.toUpperCase(Locale.ROOT);
                        point.inTangent = point.outTangent = 1;
                      }
                    }
                    item.parameters.put("curve", ProjectIO.JSON.valueToTree(c));
                    n.processing.add(item);
                    n.outputBinding = Binding.earlier(item.uuid);
                    p.noise2D.add(n);
                  }
                }),
            "Fixture rejected");
        var slopes = new LinkedHashMap<String, Double>();
        for (String name : List.of("aligned", "free", "auto")) {
          var n =
              state.project().noise2D.stream()
                  .filter(v -> v.id.equals(name))
                  .findFirst()
                  .orElseThrow();
          state.select("noise2D", n.uuid);
          panel = new PropertyPanel(state);
          panel.probeOpenCurve();
          frames(5);
          UUID middle = dialog().probeCurve().points.get(1).uuid;
          var parent = ProjectIO.JSON.valueToTree(state.draft);
          long revision = state.revision, history = state.document.historyBytes();
          click(dialog().probePoint(middle));
          click(dialog().probeHandle(middle, 1));
          frames(2);
          require(!dialog().probeCanUndo(), "Clicking a tangent created history");
          if (name.equals("auto")) {
            dragHandle(middle, .15, false);
            require(!dialog().probeCanUndo(), "Auto handle was editable");
            click(dialog().probeControl("cancel"));
            frames(2);
            require(parent.equals(ProjectIO.JSON.valueToTree(state.draft)), "Auto changed parent");
            continue;
          }
          double expected = 1 + dragHandle(middle, .2, false) / (1. / 6);
          near(expected, dialog().probeCurve().points.get(1).outTangent, 2e-6);
          var point = dialog().probeCurve().points.get(1);
          if (name.equals("aligned")) near(point.outTangent, point.inTangent, 0);
          else near(1, point.inTangent, 0);
          double slope = point.outTangent;
          slopes.put(name, slope);
          click(dialog().probeControl("undo"));
          near(1, dialog().probeCurve().points.get(1).outTangent, 0);
          require(!dialog().probeCanUndo(), "One tangent gesture made extra history");
          click(dialog().probeControl("redo"));
          near(slope, dialog().probeCurve().points.get(1).outTangent, 0);
          dragHandle(middle, -.1, true);
          near(slope, dialog().probeCurve().points.get(1).outTangent, 0);
          require(parent.equals(ProjectIO.JSON.valueToTree(state.draft)), "Local tangent leaked");
          click(dialog().probeControl("apply"));
          frames(2);
          require(
              state.revision == revision && state.document.historyBytes() == history,
              "Curve staged history");
          require(state.applyDraft(), "Tangent Apply rejected");
          double expectedSample = .75 + .0625 * (slope - 1);
          near(expectedSample, sample(state.project(), n.uuid, .75), 2e-12);
          state.document.undo();
          state.revision++;
          near(.75, sample(state.project(), n.uuid, .75), 2e-12);
          state.document.redo();
          state.revision++;
          near(expectedSample, sample(state.project(), n.uuid, .75), 2e-12);
        }
        var n =
            state.project().noise2D.stream()
                .filter(v -> v.id.equals("insert"))
                .findFirst()
                .orElseThrow();
        state.select("noise2D", n.uuid);
        panel = new PropertyPanel(state);
        panel.probeOpenCurve();
        frames(5);
        var original = dialog().probeCurve();
        var plot = dialog().probeControl("plot");
        float px = plot[0] + .4f * plot[2];
        float py =
            plot[1]
                + (float)
                    ((original.displayMax - .4)
                        / (original.displayMax - original.displayMin)
                        * plot[3]);
        click(new float[] {px, py});
        click(new float[] {px, py});
        frames(2);
        require(dialog().probeCurve().points.size() == 3, "Double-click insertion failed");
        var inserted = dialog().probeCurve().points.get(1);
        UUID identity = inserted.uuid;
        near(inserted.x, inserted.y, 2e-14);
        require(dialog().selectedPoints().equals(Set.of(identity)), "Inserted point not selected");
        click(dialog().probeControl("delete"));
        require(dialog().probeCurve().points.size() == 2, "Interior delete failed");
        click(dialog().probeControl("undo"));
        require(
            dialog().probeCurve().points.get(1).uuid.equals(identity), "Undo lost point identity");
        click(dialog().probeControl("redo"));
        require(dialog().probeCurve().points.size() == 2, "Delete Redo failed");
        click(dialog().probeControl("undo"));
        var finalCurve = dialog().probeCurve();
        require(
            finalCurve.points.getFirst().uuid.equals(original.points.getFirst().uuid)
                && finalCurve.points.getLast().uuid.equals(original.points.getLast().uuid),
            "Endpoint identities changed");
        click(dialog().probeControl("apply"));
        frames(2);
        require(state.applyDraft(), "Insertion Apply rejected");
        state.document.undo();
        state.revision++;
        require(
            curve(state.project().noise2D.get(3)).points.size() == 2,
            "Insertion document Undo failed");
        state.document.redo();
        state.revision++;
        require(
            curve(state.project().noise2D.get(3)).points.get(1).uuid.equals(identity),
            "Insertion document Redo lost identity");
        state.document.save(out.resolve("curve-point-project.json"));
        Files.writeString(
            out.resolve("curve-point-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "alignedSlope",
                    slopes.get("aligned"),
                    "freeSlope",
                    slopes.get("free"),
                    "autoProtected",
                    true,
                    "clickNoHistory",
                    true,
                    "insertedCanonicalValue",
                    true,
                    "deleteRestoresUuid",
                    true,
                    "privateStage",
                    true,
                    "oneUndoRedo",
                    true)));
        System.out.println(
            "CURVE_POINT_NATIVE_PASS Auto/Aligned/Free / zero-jump click / cumulative drag / Escape"
                + " / insertion / deletion / UUID Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Curve curve(Noise n) {
    return ProjectIO.JSON.convertValue(
        n.processing.getFirst().parameters.get("curve"), Curve.class);
  }

  private static CurveDialog dialog() {
    return panel.probeCurveDialog();
  }

  private static double sample(ProjectData p, UUID id, double x) {
    return new NoiseCompiler(p, 0).compile(id).sample(x, 0, 0);
  }

  private static double dragHandle(UUID id, double dy, boolean cancel) {
    var io = ImGui.getIO();
    var h = dialog().probeHandle(id, 1);
    var c = dialog().probeCurve();
    double pixels = dialog().probeControl("plot")[3] / (c.displayMax - c.displayMin);
    io.addMousePosEvent(h[0], h[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    double start = io.getMousePosY();
    io.addMousePosEvent(h[0], h[1] - (float) (dy * pixels / 2));
    frame();
    io.addMousePosEvent(h[0], h[1] - (float) (dy * pixels));
    frame();
    double delivered = (start - io.getMousePosY()) / pixels;
    if (cancel) {
      io.addKeyEvent(ImGuiKey.Escape, true);
      frame();
      io.addKeyEvent(ImGuiKey.Escape, false);
      frame();
    }
    io.addMouseButtonEvent(0, false);
    frames(2);
    return delivered;
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

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(30, 30, ImGuiCond.Always);
    panel.probeCurvePanel();
    ImGui.render();
  }

  private static void near(double a, double b, double epsilon) {
    require(Double.isFinite(b) && Math.abs(a - b) <= epsilon, a + " != " + b);
  }

  private static void require(boolean ok, String error) {
    if (!ok) throw new AssertionError(error);
  }
}
