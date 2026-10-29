package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred real Curve Editor bounds, persistent actions, wheel ownership and resize cancellation.
 */
public final class CurveLayoutProbe {
  private static EditorState state;
  private static PropertyPanel properties;
  private static int width = 720, height = 600;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var noise = new Noise();
                  noise.id = "field";
                  noise.algorithm.key = "CONSTANT";
                  noise.algorithm.parameters.clear();
                  noise.algorithm.parameters.put("value", .125);
                  var item = ProcessingRegistry.create("CURVE", "curve");
                  item.inputs.put("a", Binding.constant(.5));
                  var curve = new Curve();
                  curve.points.add(1, new Point(.5, .5));
                  curve.points.get(1).segmentInterpolation = "BEZIER";
                  curve.points.get(1).handleMode = "FREE";
                  item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
                  noise.processing.add(item);
                  noise.outputBinding = Binding.earlier(item.uuid);
                  p.noise2D.add(noise);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        properties = new PropertyPanel(state);
        var cases = new ArrayList<Map<String, Object>>();
        exercise(cases, "normal", 720, 600, 1, 1);
        exercise(cases, "narrow", 360, 240, 2.5f, 1);
        exercise(cases, "product-6.25", 720, 480, 2.5f, 2.5f);
        width = 720;
        height = 600;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.put("Curve Editor", 1f);
        properties.probeOpenCurve();
        frames(5);
        revealPlot();
        var before = dialog().probeView();
        var plot = dialog().probeControl("plot");
        io.addMousePosEvent(plot[0] + plot[2] / 2, plot[1] + plot[3] / 2);
        frame();
        io.addMouseButtonEvent(2, true);
        frame();
        io.addMousePosEvent(plot[0] + plot[2] / 2 + 20, plot[1] + plot[3] / 2);
        frame();
        require(!dialog().probeView().equals(before), "Native pan did not start");
        // Actual named ImGui window resize, while the held button defers the automatic fitting
        // plan.
        ImGui.setWindowSize("Curve Editor", 360, 240, ImGuiCond.Always);
        frames(2);
        require(dialog().probeView().equals(before), "Resize retained a stale-coordinate pan");
        io.addMouseButtonEvent(2, false);
        frames(3);
        var expanded = dialog().layout.bounds();
        ImGui.setWindowCollapsed("Curve Editor", true, ImGuiCond.Always);
        frames(3);
        require(dialog().layout.bounds().height() < expanded.height(), "Window did not collapse");
        ImGui.setWindowCollapsed("Curve Editor", false, ImGuiCond.Always);
        frames(4);
        require(dialog().layout.bounds().equals(expanded), "Collapse changed normal dimensions");
        click(dialog().probeControl("cancel"));
        require(properties.probeCurveDialog() == null, "Cancel after resize did not close");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.draft == draft
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && "-".equals(state.seedInputs.text("seedOffset"))
                && state.engine.profiler().current().isEmpty(),
            "Layout leaked curve/project state");
        Files.writeString(
            out.resolve("curve-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "cases",
                    cases,
                    "projectPreserved",
                    true,
                    "plotWheelOnlyZooms",
                    true,
                    "bodyWheelOnlyScrolls",
                    true,
                    "resizeCancelledPan",
                    true,
                    "collapsePreservedNormal",
                    true,
                    "physicalDetachedVerified",
                    false,
                    "physicalDpiVerified",
                    false)));
        System.out.println(
            "CURVE_LAYOUT_NATIVE_PASS narrow/scaled bounds, persistent Cancel, body/plot wheel"
                + " ownership, resize pan rollback; physical detached/DPI separate");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void exercise(
      List<Map<String, Object>> cases, String name, int w, int h, float global, float window) {
    width = w;
    height = h;
    state.user.appearance.globalScale = global;
    state.user.appearance.windowScales.put("Curve Editor", window);
    properties.probeOpenCurve();
    dialog().selectPoint(dialog().probeCurve().points.get(1).uuid);
    frames(6);
    var bounds = dialog().layout.bounds();
    require(
        DialogBounds.contains(DialogBounds.inset(new DialogBounds.Rect(0, 0, w, h)), bounds),
        "Window outside work area");
    var cancel = dialog().probeControl("cancel").clone();
    require(inside(bounds, cancel), "Persistent Cancel outside window");
    require(inside(bounds, dialog().probeControl("apply")), "Persistent Apply outside window");
    revealPlot();
    var plot = dialog().probeControl("plot").clone();
    var body = dialog().probeBodyBounds();
    require(
        plot[2] > 0 && plot[2] <= body.width() && plot[3] <= body.height() * .65f + .02,
        "Plot outside body budget");
    float scroll = dialog().probeBodyScroll();
    double zoom = dialog().probeZoom();
    var io = ImGui.getIO();
    io.addMousePosEvent(plot[0] + plot[2] / 2, plot[1] + plot[3] / 2);
    frame();
    io.addMouseWheelEvent(0, 1);
    frames(3);
    require(
        dialog().probeZoom() > zoom && dialog().probeBodyScroll() == scroll,
        "Plot wheel also scrolled body");
    zoom = dialog().probeZoom();
    io.addMousePosEvent(body.x() + body.width() - 3, body.y() + body.height() / 2);
    frame();
    io.addMouseWheelEvent(0, scroll > 0 ? 1 : -1);
    frames(3);
    require(
        dialog().probeBodyScroll() != scroll && dialog().probeZoom() == zoom,
        "Body wheel changed plot zoom or did not scroll");
    require(Arrays.equals(cancel, dialog().probeControl("cancel")), "Body scroll moved Cancel");
    cases.add(
        Map.of(
            "name",
            name,
            "workArea",
            new DialogBounds.Rect(0, 0, w, h),
            "bounds",
            bounds,
            "body",
            body,
            "plotWidth",
            plot[2],
            "plotHeight",
            plot[3],
            "cancel",
            cancel,
            "globalScale",
            global,
            "windowScale",
            window));
    click(dialog().probeControl("cancel"));
    require(properties.probeCurveDialog() == null, "Persistent Cancel did not close");
  }

  private static boolean inside(DialogBounds.Rect bounds, float[] point) {
    return point[0] > bounds.x()
        && point[0] < bounds.x() + bounds.width()
        && point[1] > bounds.y()
        && point[1] < bounds.y() + bounds.height();
  }

  private static void revealPlot() {
    for (int i = 0; i < 256; i++) {
      var plot = dialog().probeControl("plot");
      var body = dialog().probeBodyBounds();
      if (plot[1] >= body.y() + 2 && plot[1] + plot[3] <= body.y() + body.height() - 2) return;
      var io = ImGui.getIO();
      io.addMousePosEvent(body.x() + body.width() - 3, body.y() + body.height() / 2);
      frame();
      io.addMouseWheelEvent(0, plot[1] < body.y() + 2 ? .125f : -.125f);
      frames(2);
    }
    throw new AssertionError("Native body wheel did not reveal the plot");
  }

  private static CurveDialog dialog() {
    return properties.probeCurveDialog();
  }

  private static void click(float[] point) {
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    properties.probeCurvePanel();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
