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
        var opening = dialog();
        panel.probeOpenCurve();
        require(dialog() == opening, "A second Edit Curve request replaced private work");
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
        // Collapsing or losing application focus rolls back the held private preview.
        holdMove(.1);
        collapsed = true;
        frames(2);
        check(.35, .85);
        collapsed = false;
        frames(2);
        moveHeld(.2);
        check(.35, .85);
        release();
        holdMove(.1);
        io.addFocusEvent(false);
        frames(2);
        check(.35, .85);
        io.addFocusEvent(true);
        frames(2);
        release();
        require(
            dialog().selectedPoints().equals(Set.of(first, second)), "Focus recovery selection");
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
        check(.25 + deliveredDelta, .75 + deliveredDelta);
        require(Math.abs(.25 + deliveredDelta - .35) > .01, "Alt move accidentally snapped");
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
        publicationOwnership(state.project());
        Files.writeString(
            out.resolve("curve-gesture-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("collapseRollback", true),
                    Map.entry("appFocusRollback", true),
                    Map.entry("duplicateOpenPreserved", true),
                    Map.entry("stageFailureRetry", true),
                    Map.entry("refreshFailureConsumed", true),
                    Map.entry("staleSourcePreserved", true),
                    Map.entry("sourceTokenConsumed", true),
                    Map.entry("rawSeedPreserved", true),
                    Map.entry("formatVersion", 1),
                    Map.entry("before", .25),
                    Map.entry("after", .35),
                    Map.entry("points", 4),
                    Map.entry("privateStage", true),
                    Map.entry("cancel", true),
                    Map.entry("escape", true),
                    Map.entry("altBypass", true),
                    Map.entry("oneGestureUndo", true),
                    Map.entry("oneDocumentUndoRedo", true))));
        System.out.println(
            "CURVE_GESTURE_NATIVE_PASS group snap / Alt bypass / Escape / active Undo / private"
                + " Apply / document Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static float startX, startY, plotHeight;
  private static boolean collapsed;
  private static double pressedY, deliveredDelta;
  private static CurveDialog standalone;
  private static boolean standaloneDone;

  /**
   * Additional production publication paths; the arithmetic replay above keeps its exact fixture.
   */
  private static void publicationOwnership(ProjectData project) {
    try (var state = new EditorState(new UserConfig(), false)) {
      require(
          state.change(p -> p.noise2D.add(NoiseAuthoring.copy(project.noise2D.getFirst()))),
          "Publication fixture rejected");
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var draft = (Noise) state.draft;
      var item = draft.processing.getFirst();
      var source = ProjectIO.JSON.convertValue(item.parameters.get("curve"), Curve.class);
      var publication = new CurveDraftEdit(state, draft, item);
      int[] refreshes = {0};
      standalone =
          new CurveDialog(
              source,
              state,
              publication,
              () -> {
                refreshes[0]++;
                throw new IllegalStateException();
              });
      standalone.probeEnable();
      standaloneDone = false;
      frames(4);
      draft.id = "pending-name";
      require(
          state.seedInputs.edit(state, "seedOffset", "Seed offset", "-").isEmpty(),
          "Raw seed fixture failed");
      var committed = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      click(standalone.probeControl("apply"));
      frames(3);
      require(standaloneDone && publication.closed(), "Refresh failure reopened staging");
      require(refreshes[0] == 1, "Refresh failure repeated staging/refresh");
      require(
          state.events.snapshot().stream()
                  .filter(e -> e.code().equals("CURVE_EDIT_REFRESH_FAILED"))
                  .filter(e -> e.message().contains("IllegalStateException") && e.actionRequired())
                  .count()
              == 1,
          "Refresh failure was not reported separately from staging");
      require(draft.id.equals("pending-name"), "Staging replaced pending ID");
      require(state.seedInputs.text("seedOffset").equals("-"), "Staging replaced raw seed");
      require(!state.applyDraft(), "Staging bypassed invalid raw seed");
      require(
          committed.equals(ProjectIO.JSON.valueToTree(state.project()))
              && revision == state.revision
              && history == state.document.historyBytes(),
          "Private staging changed document/history");
      try {
        publication.stage(state, source);
        throw new AssertionError("Accepted source token staged a second time");
      } catch (IllegalStateException expected) {
        // The source token is consumed even for an equal-value Apply.
      }

      var stale = new CurveDraftEdit(state, draft, item);
      standalone = new CurveDialog(source, state, stale, () -> {});
      standalone.probeEnable();
      standaloneDone = false;
      frames(4);
      var privateCurve = ProjectIO.JSON.valueToTree(standalone.probeCurve());
      var replacement = item.parameters.get("curve").deepCopy();
      item.parameters.put("curve", replacement);
      frames(2);
      require(!stale.current(state), "Equal-value replacement retargeted opening");
      click(standalone.probeControl("apply"));
      frames(2);
      require(!standaloneDone && !stale.closed(), "Stale Apply closed private editor");
      require(
          item.parameters.get("curve") == replacement
              && privateCurve.equals(ProjectIO.JSON.valueToTree(standalone.probeCurve())),
          "Stale Apply changed source/private curve");
      click(standalone.probeControl("cancel"));
      frames(2);
      require(standaloneDone && stale.closed(), "Cancel retained stale source token");

      int[] attempts = {0};
      standalone =
          new CurveDialog(
              source,
              value -> {
                if (++attempts[0] == 1) throw new IllegalStateException();
              });
      standalone.probeEnable();
      standaloneDone = false;
      frames(4);
      click(standalone.probeControl("apply"));
      frames(2);
      require(!standaloneDone && attempts[0] == 1, "Rejected staging closed or repeated");
      require(
          privateCurve.equals(ProjectIO.JSON.valueToTree(standalone.probeCurve())),
          "Rejected staging changed private curve");
      click(standalone.probeControl("apply"));
      frames(3);
      require(standaloneDone && attempts[0] == 2, "Corrected staging did not close exactly once");
      require(
          committed.equals(ProjectIO.JSON.valueToTree(state.project()))
              && revision == state.revision
              && history == state.document.historyBytes(),
          "Publication fixtures changed committed state");
    } finally {
      standalone = null;
    }
  }

  private static CurveDialog dialog() {
    return panel.probeCurveDialog();
  }

  private static void holdMove(double dy) {
    var io = ImGui.getIO();
    var p = dialog().probePoint(first);
    startX = p[0];
    startY = p[1];
    var c = dialog().probeCurve();
    plotHeight = (float) (dialog().probeControl("plot")[3] / (c.displayMax - c.displayMin));
    io.addMousePosEvent(startX, startY);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    pressedY = io.getMousePosY();
    moveHeld(dy / 2);
    moveHeld(dy);
  }

  private static void moveHeld(double dy) {
    ImGui.getIO().addMousePosEvent(startX, startY - (float) (dy * plotHeight));
    frame();
    deliveredDelta = (pressedY - ImGui.getIO().getMousePosY()) / plotHeight;
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
    ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
    if (standalone == null) panel.probeCurvePanel();
    else standaloneDone = standalone.draw();
    ImGui.render();
  }

  private static void require(boolean value, String error) {
    if (!value) throw new AssertionError(error);
  }
}
