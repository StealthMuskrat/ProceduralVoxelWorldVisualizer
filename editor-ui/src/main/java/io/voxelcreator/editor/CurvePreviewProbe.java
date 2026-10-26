package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

public final class CurvePreviewProbe {
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
                  n.id = "preview-recovery";
                  var item = ProcessingRegistry.create("CURVE", "curve");
                  var input = Binding.constant(0);
                  input.sourceKind = "Ambient Value";
                  input.ambientKey = "x";
                  item.inputs.put("a", input);
                  var c = new Curve();
                  c.outsideDomain = "EXTEND";
                  c.displayMin = 0;
                  c.displayMax = 1;
                  c.points.getLast().y = 1e308;
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
        require(dialog().probePreviewFailures() == 0, "Initial domain failed");
        zoomOut();
        int failures = dialog().probePreviewFailures();
        require(failures > 0 && failures < 513, "Expected mixed finite/overflow observations");
        require(
            dialog().probePreviewFailure().contains("Nonfinite"), "Missing preview failure detail");
        require(!dialog().probeCanUndo(), "Preview failure created authoring history");
        click(dialog().probeControl("fit"));
        frames(2);
        require(dialog().probePreviewFailures() == 0, "Fit failed to recover preview");
        zoomOut();
        require(dialog().probePreviewFailures() > 0, "Second failure not observable");
        click(dialog().probeControl("cancel"));
        frames(2);
        require(panel.probeCurveDialog() == null, "Cancel failed after preview overflow");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Preview changed document");
        require(draft.equals(ProjectIO.JSON.valueToTree(state.draft)), "Preview changed draft");
        require(
            revision == state.revision && history == state.document.historyBytes(),
            "Preview changed history");
        state.document.save(out.resolve("curve-preview-project.json"));
        Files.writeString(
            out.resolve("curve-preview-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "failedSamples",
                    failures,
                    "fitRecovered",
                    true,
                    "cancelRecovered",
                    true,
                    "authoringUnchanged",
                    true)));
        System.out.println(
            "CURVE_PREVIEW_NATIVE_PASS overflow gaps / bounded feedback / Fit recovery / Cancel /"
                + " authoring preservation");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static CurveDialog dialog() {
    return panel.probeCurveDialog();
  }

  private static void zoomOut() {
    var p = dialog().probeControl("plot");
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0] + p[2] / 2, p[1] + p[3] / 2);
    frame();
    io.addMouseWheelEvent(0, -25);
    frames(2);
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
