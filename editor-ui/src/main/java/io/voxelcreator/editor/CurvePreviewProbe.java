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
  private static final List<Map<String, Object>> cacheCases = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    cacheCases.clear();
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
        long initialBuilds = dialog().probeCompilationBuilds(),
            initialCaptures = dialog().probePreviewCaptures();
        frames(12);
        require(
            initialBuilds == 1
                && initialCaptures == 1
                && dialog().probeCompilationBuilds() == initialBuilds
                && dialog().probePreviewCaptures() == initialCaptures,
            "Stationary frames rebuilt canonical curve work");
        cacheCases.add(
            Map.of(
                "id",
                "stationary",
                "frames",
                12,
                "compilations",
                initialBuilds,
                "captures",
                initialCaptures));
        zoomOut();
        int failures = dialog().probePreviewFailures();
        require(failures > 0 && failures < 513, "Expected mixed finite/overflow observations");
        require(
            dialog().probePreviewFailure().contains("Nonfinite"), "Missing preview failure detail");
        require(!dialog().probeCanUndo(), "Preview failure created authoring history");
        long failedCaptures = dialog().probePreviewCaptures();
        frames(12);
        require(
            dialog().probeCompilationBuilds() == initialBuilds
                && dialog().probePreviewCaptures() == failedCaptures
                && dialog().probePreviewFailures() == failures,
            "Stationary failed samples were resampled");
        cacheCases.add(
            Map.of(
                "id",
                "numeric-gaps",
                "frames",
                12,
                "compilations",
                initialBuilds,
                "captures",
                failedCaptures,
                "failedSamples",
                failures));
        click(dialog().probeControl("fit"));
        frames(2);
        require(dialog().probePreviewFailures() == 0, "Fit failed to recover preview");
        long fitCaptures = dialog().probePreviewCaptures();
        dialog().probeCenterView(.5, .25);
        frames(4);
        require(
            dialog().probePreviewCaptures() == fitCaptures
                && dialog().probeCompilationBuilds() == initialBuilds,
            "Vertical view navigation rebuilt input samples");
        cacheCases.add(
            Map.of(
                "id",
                "vertical-view",
                "frames",
                4,
                "compilations",
                initialBuilds,
                "fitCaptures",
                fitCaptures,
                "captures",
                dialog().probePreviewCaptures()));
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
        cachedEdit(out);
        Files.writeString(
            out.resolve("curve-preview-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    2,
                    "cacheCases",
                    cacheCases,
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

  private static void cachedEdit(Path out) throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      require(
          state.change(
              p -> {
                var noise = new Noise();
                noise.id = "cache-edit";
                var item = ProcessingRegistry.create("CURVE", "curve");
                var input = Binding.constant(0);
                input.sourceKind = "Ambient Value";
                input.ambientKey = "x";
                item.inputs.put("a", input);
                item.parameters.put("curve", ProjectIO.JSON.valueToTree(new Curve()));
                noise.processing.add(item);
                noise.outputBinding = Binding.earlier(item.uuid);
                p.noise2D.add(noise);
              }),
          "Cache edit fixture rejected");
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      panel = new PropertyPanel(state);
      panel.probeOpenCurve();
      frames(5);
      var committed = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var last = dialog().probeCurve().points.getLast().uuid;
      require(dialog().focusControl(last, "y"), "Cache endpoint focus rejected");
      frames(6);
      require("y".equals(dialog().probeFocusedControl()), "Cache endpoint input not focused");
      type("2");
      frames(3);
      var edited = ProjectIO.JSON.valueToTree(dialog().probeCurve());
      require(dialog().probeCurve().points.getLast().y == 2, "Cache edit lost exact input");
      long builds = dialog().probeCompilationBuilds();
      require(builds == 2, "Accepted edit did not reuse its validation compilation");
      click(dialog().probeControl("undo"));
      frames(3);
      require(
          dialog().probeCurve().points.getLast().y == 1 && !dialog().probeCanUndo(),
          "Cache local Undo failed");
      click(dialog().probeControl("redo"));
      frames(3);
      require(
          edited.equals(ProjectIO.JSON.valueToTree(dialog().probeCurve())),
          "Cache local Redo changed values");
      long captures = dialog().probePreviewCaptures();
      frames(12);
      require(
          dialog().probeCompilationBuilds() == builds
              && dialog().probePreviewCaptures() == captures,
          "Restored stationary curve work rebuilt");
      var opening = dialog();
      click(opening.probeControl("apply"));
      frames(2);
      require(dialog() == null, "Cache curve Apply did not close");
      require(
          committed.equals(ProjectIO.JSON.valueToTree(state.project()))
              && revision == state.revision
              && history == state.document.historyBytes(),
          "Cached curve staged committed history");
      require(state.applyDraft(), "Cached staged curve failed parent Apply");
      state.document.save(out.resolve("curve-cache-edited.json"));
      state.document.undo();
      state.revision++;
      state.document.save(out.resolve("curve-cache-undo.json"));
      state.document.redo();
      state.revision++;
      state.document.save(out.resolve("curve-cache-redo.json"));
      cacheCases.add(
          Map.of(
              "id",
              "edit-history",
              "frames",
              12,
              "compilations",
              builds,
              "captures",
              captures,
              "privateStage",
              true,
              "oneUndoRedo",
              true));
      panel.close();
    }
  }

  private static void type(String value) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(2);
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
