package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

public final class CurveWrappingProbe {
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
                  for (String mode : List.of("REPEAT", "MIRROR")) {
                    var n = new Noise();
                    n.id = mode.toLowerCase(Locale.ROOT);
                    n.algorithm.key = "CONSTANT";
                    n.algorithm.parameters.put("value", ProjectIO.JSON.valueToTree(0));
                    var item = ProcessingRegistry.create("CURVE", "curve");
                    var input = Binding.constant(0);
                    input.sourceKind = "Ambient Value";
                    input.ambientKey = "x";
                    item.inputs.put("a", input);
                    var c = new Curve();
                    c.min = 1;
                    c.max = 2;
                    c.displayMin = 0;
                    c.displayMax = 1;
                    c.outsideDomain = mode;
                    c.points.getFirst().x = 1;
                    c.points.getLast().x = 2;
                    item.parameters.put("curve", ProjectIO.JSON.valueToTree(c));
                    n.processing.add(item);
                    n.outputBinding = Binding.earlier(item.uuid);
                    p.noise2D.add(n);
                  }
                }),
            "Fixture rejected");
        var committed = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var positions = new ArrayList<Double>();
        for (var n : state.project().noise2D) {
          state.select("noise2D", n.uuid);
          var draft = ProjectIO.JSON.valueToTree(state.draft);
          panel = new PropertyPanel(state);
          panel.probeOpenCurve();
          frames(5);
          var plot = dialog().probeControl("plot");
          io.addMousePosEvent(plot[0] + plot[2] / 2, plot[1] + plot[3] / 2);
          frame();
          io.addMouseWheelEvent(0, -25);
          frames(2);
          io.addMouseButtonEvent(2, true);
          frame();
          io.addMousePosEvent(1e18f, plot[1] + plot[3] / 2);
          frame();
          io.addMouseButtonEvent(2, false);
          frames(2);
          double far = dialog().probeView().minX();
          positions.add(far);
          require(far < -1e15, "Large finite pan was not reached");
          require(
              dialog().probePreviewFailures() == 0, "Wrapped preview still has invalid samples");
          require(!dialog().probeCanUndo(), "Wrapping preview created history");
          click(dialog().probeControl("fit"));
          frames(2);
          require(
              dialog().probeView().minX() == 1 && dialog().probeView().maxX() == 2, "Fit failed");
          click(dialog().probeControl("cancel"));
          frames(2);
          require(
              draft.equals(ProjectIO.JSON.valueToTree(state.draft)),
              "Preview changed private draft");
        }
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Preview changed document");
        require(
            revision == state.revision && history == state.document.historyBytes(),
            "Preview changed history/revision");
        state.document.save(out.resolve("curve-wrapping-project.json"));
        Files.writeString(
            out.resolve("curve-wrapping-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "farInputs",
                    positions,
                    "allSamplesFinite",
                    true,
                    "fitRestored",
                    true,
                    "authoringUnchanged",
                    true)));
        System.out.println(
            "CURVE_WRAPPING_NATIVE_PASS large offset-domain Repeat/Mirror / finite preview / Fit /"
                + " authoring preserved");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static CurveDialog dialog() {
    return panel.probeCurveDialog();
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

  private static void require(boolean ok, String error) {
    if (!ok) throw new AssertionError(error);
  }
}
