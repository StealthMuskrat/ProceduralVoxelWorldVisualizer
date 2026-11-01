package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred native slider history, held cancellation, exact text and parent publication. */
public final class CurveNumericProbe {
  private static PropertyPanel properties;
  private static UUID middle;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1400, 1100);
      io.setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        require(state.change(CurveNumericProbe::fixture), "Curve numeric fixture rejected");
        state.document.save(out.resolve("before.json"));
        state.user.save(out.resolve("preferences-before.json"));
        var before = ProjectIO.JSON.valueToTree(state.project());
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        var privateBefore = ProjectIO.JSON.valueToTree(draft);
        properties = new PropertyPanel(state);
        properties.probeOpenCurve();
        frames(5);
        middle = dialog().probeCurve().points.get(1).uuid;
        focus();
        saveCurve(out, "local-before");
        press(.25);
        move(.65);
        move(.45);
        move(.71);
        require(!dialog().probeCanUndo(), "Held numeric slider split local history");
        release();
        var dragged = ProjectIO.JSON.valueToTree(dialog().probeCurve());
        double slider = dialog().probeCurve().points.get(1).y;
        require(
            slider != .5 && slider != Math.rint(slider * 100000) / 100000,
            "Native numeric slider rounded to its readout");
        saveCurve(out, "local-dragged");
        dialog().shortcut("CurveUndo");
        frames(3);
        saveCurve(out, "local-undo");
        require(
            dialog().probeCurve().points.get(1).y == .5 && !dialog().probeCanUndo(),
            "Numeric drag was not exactly one local Undo");
        dialog().shortcut("CurveRedo");
        frames(3);
        saveCurve(out, "local-redo");
        require(
            dragged.equals(ProjectIO.JSON.valueToTree(dialog().probeCurve())),
            "Numeric Redo changed data");
        focus();
        press(.85);
        move(.2);
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        frame();
        move(.9);
        require(
            dragged.equals(ProjectIO.JSON.valueToTree(dialog().probeCurve())),
            "Escape or held predecessor changed the numeric baseline");
        release();
        saveCurve(out, "local-escape");
        focus();
        press(.3);
        move(.8);
        ImGui.setWindowCollapsed("Curve Editor", true, ImGuiCond.Always);
        frames(3);
        release();
        ImGui.setWindowCollapsed("Curve Editor", false, ImGuiCond.Always);
        frames(4);
        require(
            dragged.equals(ProjectIO.JSON.valueToTree(dialog().probeCurve())),
            "Collapse retained an unfinished numeric edit");
        saveCurve(out, "local-collapse");
        focus();
        type("1.0000000000000002");
        long exactBits = Double.doubleToRawLongBits(dialog().probeCurve().points.get(1).y);
        require(
            exactBits == Double.doubleToRawLongBits(Math.nextUp(1.0)),
            "Curve exact text lost precision");
        type("-2.5");
        require(dialog().probeCurve().points.get(1).y == -2.5, "Curve output text was clamped");
        type("0.75");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && privateBefore.equals(ProjectIO.JSON.valueToTree(draft))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Numeric dialog leaked into Properties or project");
        click(dialog().probeControl("apply"));
        frames(3);
        require(
            properties.probeCurveDialog() == null && state.draft == draft,
            "Curve Apply did not stage in its original Properties draft");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Curve Apply published the project");
        Files.writeString(
            out.resolve("private-staged.json"), ProjectIO.JSON.writeValueAsString(draft));
        require(state.applyDraft(), "Properties Apply rejected numeric curve");
        saveProject(out, "applied", state);
        state.document.undo();
        state.revision++;
        saveProject(out, "undone", state);
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Curve publication was not one project Undo");
        state.document.redo();
        state.revision++;
        saveProject(out, "redone", state);
        state.user.save(out.resolve("preferences.json"));
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Numeric curve changed preferences");
        Files.writeString(
            out.resolve("curve-numeric-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "sliderValue",
                    slider,
                    "exactTextBits",
                    Long.toHexString(exactBits),
                    "oneLocalUndo",
                    true,
                    "escapeHeldCancelled",
                    true,
                    "collapseCancelled",
                    true,
                    "privateStaging",
                    true,
                    "oneProjectUndo",
                    true,
                    "physicalInputVerified",
                    false)));
        System.out.println(
            "CURVE_NUMERIC_NATIVE_PASS one slider history / Escape and collapse / exact text /"
                + " parent Apply");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    var n = new Noise();
    n.id = "numeric-curve";
    var item = ProcessingRegistry.create("CURVE", "response");
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
  }

  private static CurveDialog dialog() {
    return properties.probeCurveDialog();
  }

  private static void focus() {
    require(dialog().focusControl(middle, "y"), "Curve numeric focus rejected");
    for (int i = 0; i < 30; i++) {
      frame();
      if ("y".equals(dialog().probeFocusedControl())) break;
    }
    require("y".equals(dialog().probeFocusedControl()), "Exact curve input was not focused");
    frames(3);
  }

  private static float[] point(double fraction) {
    var r = dialog().probeNumeric("y").slider();
    return new float[] {r.x() + (float) (r.width() * fraction), r.y() + r.height() / 2};
  }

  private static void move(double fraction) {
    var p = point(fraction);
    ImGui.getIO().addMousePosEvent(p[0], p[1]);
    frame();
  }

  private static void press(double fraction) {
    move(fraction);
    ImGui.getIO().addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void click(float[] p) {
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0], p[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void type(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
  }

  private static void saveCurve(Path out, String name) throws Exception {
    Files.writeString(
        out.resolve(name + ".json"), ProjectIO.JSON.writeValueAsString(dialog().probeCurve()));
  }

  private static void saveProject(Path out, String name, EditorState state) throws Exception {
    new ProjectIO().save(out.resolve(name + ".json"), state.project());
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1350, 1050, ImGuiCond.Always);
    properties.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
