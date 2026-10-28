package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred native focus/repair workflow through the production Properties and Curve Editor. */
public final class CurveFocusProbe {
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
                  var noise = new Noise();
                  noise.id = "curve-focus";
                  var item = ProcessingRegistry.create("CURVE", "response");
                  var input = Binding.constant(0);
                  input.sourceKind = "Ambient Value";
                  input.ambientKey = "x";
                  item.inputs.put("a", input);
                  var curve = new Curve();
                  curve.points.add(1, new Point(.5, .5));
                  item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
                  noise.processing.add(item);
                  noise.outputBinding = Binding.earlier(item.uuid);
                  p.noise2D.add(noise);
                }),
            "Fixture rejected");
        var source = state.project().noise2D.getFirst();
        state.select("noise2D", source.uuid);
        var draft = (Noise) state.draft;
        var item = draft.processing.getFirst();
        var original = item.parameters.get("curve").deepCopy();
        var curve = ProjectIO.JSON.convertValue(original, Curve.class);
        var mid = curve.points.get(1).uuid;
        var last = curve.points.getLast().uuid;
        var committed = ProjectIO.JSON.valueToTree(state.project());
        var user = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        panel = new PropertyPanel(state);
        var focused = new ArrayList<String>();

        open(state, mid, "curve", "/noise2D/99/processing/88/parameters/curve/points/777/y", "y");
        focused.add(dialog().probeFocusedControl());
        type("0.75");
        require(dialog().probeCurve().points.get(1).y == .75, "Keyboard point repair");
        var privateDialog = dialog();
        require(!panel.goToProblem(target(state, last, "x", "")), "Open editor replaced by a link");
        panel.goToControl(source.uuid, item.uuid);
        require(
            dialog() == privateDialog && state.draft == draft, "Private editor ownership changed");
        require(dialog().probeCurve().points.get(1).y == .75, "Private edit discarded");
        close();
        require(original.equals(item.parameters.get("curve")), "Cancel staged the private repair");

        open(state, last, "x", "", "max");
        focused.add(dialog().probeFocusedControl());
        require(
            original.equals(ProjectIO.JSON.valueToTree(dialog().probeCurve())),
            "Locked X route changed the curve");
        close();
        open(state, mid, "inTangent", "", "segmentInterpolation");
        focused.add(dialog().probeFocusedControl());
        require(
            original.equals(ProjectIO.JSON.valueToTree(dialog().probeCurve())),
            "Tangent route changed modes");
        close();
        open(state, item.uuid, "curve/displayMax", "", "displayMax");
        focused.add(dialog().probeFocusedControl());
        close();

        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Navigation changed project");
        require(
            revision == state.revision && history == state.document.historyBytes(),
            "Navigation changed history");
        require(
            user.equals(ProjectIO.JSON.valueToTree(state.user)), "Navigation changed preferences");
        open(state, mid, "y", "", "y");
        type("0.75");
        click(dialog().probeControl("apply"));
        frames(2);
        require(panel.probeCurveDialog() == null && state.draft == draft, "Curve stage ownership");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Curve stage published project");
        require(state.applyDraft(), "Properties Apply failed");
        require(
            state
                    .project()
                    .noise2D
                    .getFirst()
                    .processing
                    .getFirst()
                    .parameters
                    .get("curve")
                    .path("points")
                    .get(1)
                    .path("y")
                    .asDouble()
                == .75,
            "Curve repair not committed");
        state.document.undo();
        state.revision++;
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "One Undo did not restore project");
        state.document.redo();
        state.revision++;
        state.document.save(out.resolve("curve-focus-project.json"));
        Files.writeString(
            out.resolve("curve-focus-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "focused",
                    focused,
                    "before",
                    .5,
                    "after",
                    .75,
                    "navigationOnly",
                    true,
                    "privateBuffer",
                    true,
                    "modalGuard",
                    true,
                    "oneUndoRedo",
                    true)));
        System.out.println(
            "CURVE_FOCUS_NATIVE_PASS UUID / fields / keyboard repair / private Cancel / staged"
                + " Apply / Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static EditorEvents.Target target(
      EditorState state, UUID control, String parameter, String path) {
    return new EditorEvents.Target(
        state.project().project.uuid, state.selected, control, parameter, path, false);
  }

  private static void open(
      EditorState state, UUID control, String parameter, String path, String expected) {
    require(panel.goToProblem(target(state, control, parameter, path)), "Curve route unavailable");
    dialog().probeEnable();
    for (int i = 0; i < 16; i++) {
      frame();
      if (expected.equals(dialog().probeFocusedControl())) break;
    }
    require(
        expected.equals(dialog().probeFocusedControl()),
        "Curve focus not acknowledged: " + expected);
    frames(3);
  }

  private static CurveDialog dialog() {
    return Objects.requireNonNull(panel.probeCurveDialog());
  }

  private static void close() {
    // Cancel via the production API: the requested field may be scrolled far from the button.
    panel.closeSearchCurve();
    frames(2);
  }

  private static void type(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void click(float[] point) {
    require(point != null, "Control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
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
    panel.probeCurvePanel();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
