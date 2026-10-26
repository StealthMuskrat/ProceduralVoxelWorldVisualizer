package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

public final class CurveControlsProbe {
  private static PropertyPanel panel;
  private static EditorState current;
  private static String lastAction = "";
  private static final Set<String> choices = new LinkedHashSet<>(),
      menus = new LinkedHashSet<>(),
      keys = new LinkedHashSet<>();

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
        current = state;
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "curve-controls";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.put("value", 0.0);
                  var item = ProcessingRegistry.create("CURVE", "curve");
                  var input = Binding.constant(0);
                  input.sourceKind = "Ambient Value";
                  input.ambientKey = "x";
                  item.inputs.put("a", input);
                  var c = new Curve();
                  c.points.add(1, new Point(.5, .5));
                  item.parameters.put("curve", ProjectIO.JSON.valueToTree(c));
                  n.processing.add(item);
                  n.outputBinding = Binding.earlier(item.uuid);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        var n = state.project().noise2D.getFirst();
        state.select("noise2D", n.uuid);
        var committed = ProjectIO.JSON.valueToTree(state.project());
        long history = state.document.historyBytes(), revision = state.revision;
        panel = new PropertyPanel(state);
        panel.probeOpenCurve();
        frames(5);
        var original = dialog().probeCurve();
        UUID middle = original.points.get(1).uuid;
        number("domainMin", "-1");
        number("domainMax", "2");
        number("displayMin", "-2");
        number("displayMax", "2");
        near(-1, dialog().probeCurve().min);
        near(2, dialog().probeCurve().max);
        number("domainMin", "3");
        near(-1, dialog().probeCurve().min);
        require(!dialog().probeError().isEmpty(), "Invalid domain has no feedback");
        for (String mode : CurveModes.OUTSIDE) choose("Outside domain", mode);
        require(dialog().selectPoint(middle), "Point reveal failed");
        frames(3);
        for (String mode : CurveModes.INTERPOLATIONS) choose("Next segment", mode);
        for (String mode : CurveModes.HANDLES) choose("Handle mode", mode);
        choose("Next segment", "LINEAR");
        number("pointY", "0.75");
        near(.75, dialog().probeCurve().points.get(1).y);
        menu("selectAll");
        require(dialog().selectedPoints().size() == 3, "Context Select All failed");
        menu("delete");
        require(dialog().probeCurve().points.size() == 2, "Context deletion failed");
        click(dialog().probeControl("undo"));
        frames(2);
        require(
            dialog().probeCurve().points.get(1).uuid.equals(middle), "Context Undo lost identity");
        menu("clear");
        require(dialog().selectedPoints().isEmpty(), "Context Clear failed");
        menu("selectAll");
        key(ImGuiKey.Delete, false, "CurveDelete");
        require(dialog().probeCurve().points.size() == 2, "Keyboard Delete failed");
        key(ImGuiKey.Z, true, "CurveUndo");
        require(
            dialog().probeCurve().points.get(1).uuid.equals(middle), "Keyboard Undo lost identity");
        key(ImGuiKey.Y, true, "CurveRedo");
        require(dialog().probeCurve().points.size() == 2, "Keyboard Redo failed");
        key(ImGuiKey.Z, true, "CurveUndo");
        menu("fit");
        var c = dialog().probeCurve();
        near(-1, c.min);
        near(2, c.max);
        near(-2, c.displayMin);
        near(2, c.displayMax);
        require(
            c.outsideDomain.equals("MIRROR")
                && c.points.get(1).segmentInterpolation.equals("LINEAR"),
            "Mode selection lost");
        require(
            c.points.getFirst().uuid.equals(original.points.getFirst().uuid)
                && c.points.getLast().uuid.equals(original.points.getLast().uuid),
            "Endpoint identities changed");
        require(
            dialog().probeView().equals(new CurveView.Bounds(-1, 2, -2, 2)), "Context Fit failed");
        click(dialog().probeControl("apply"));
        frames(2);
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Curve Apply published document");
        require(
            history == state.document.historyBytes() && revision == state.revision,
            "Curve Apply created document history");
        require(state.applyDraft(), "Properties Apply rejected");
        near(.75, sample(state.project(), n.uuid));
        state.document.undo();
        state.revision++;
        near(.5, sample(state.project(), n.uuid));
        state.document.redo();
        state.revision++;
        near(.75, sample(state.project(), n.uuid));
        state.document.save(out.resolve("curve-controls-project.json"));
        Files.writeString(
            out.resolve("curve-controls-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "modeChoices",
                    choices,
                    "contextActions",
                    menus,
                    "keyboardActions",
                    keys,
                    "invalidDomainRejected",
                    true,
                    "privateApply",
                    true,
                    "oneUndoRedo",
                    true)));
        System.out.println(
            "CURVE_CONTROLS_NATIVE_PASS numeric domains/display / 11 mode choices / context actions"
                + " / scoped keys / private Apply / Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static CurveDialog dialog() {
    return panel.probeCurveDialog();
  }

  private static void number(String control, String value) {
    click(dialog().probeControl(control));
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

  private static void choose(String label, String value) {
    click(dialog().probeControl(label));
    frames(2);
    click(dialog().probeControl(label + "/" + value));
    frames(3);
    choices.add(label + "/" + value);
  }

  private static void menu(String action) {
    var p = dialog().probeControl("plot");
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0] + p[2] / 2, p[1] + p[3] / 2);
    frame();
    io.addMouseButtonEvent(1, true);
    frame();
    io.addMouseButtonEvent(1, false);
    frames(3);
    click(dialog().probeControl("menu/" + action));
    frames(3);
    menus.add(action);
  }

  private static void key(int code, boolean ctrl, String expected) {
    lastAction = "";
    var io = ImGui.getIO();
    if (ctrl) io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(code, true);
    frame();
    io.addKeyEvent(code, false);
    if (ctrl) io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frames(2);
    require(
        lastAction.equals(expected),
        "Wrong curve shortcut: " + lastAction + " expected " + expected);
    keys.add(lastAction);
  }

  private static double sample(ProjectData p, UUID id) {
    return new NoiseCompiler(p, 0).compile(id).sample(.5, 0, 0);
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
    ShortcutKeys.match(
            current.user.input.shortcuts,
            panel.curveFocused() ? Shortcuts.Focus.CURVE : Shortcuts.Focus.GENERAL,
            false)
        .filter(a -> a.id().startsWith("Curve"))
        .ifPresent(
            a -> {
              lastAction = a.id();
              panel.curveShortcut(a.id());
            });
    ImGui.render();
  }

  private static void near(double a, double b) {
    require(Math.abs(a - b) < 1e-12, a + " != " + b);
  }

  private static void require(boolean ok, String error) {
    if (!ok) throw new AssertionError(error);
  }
}
