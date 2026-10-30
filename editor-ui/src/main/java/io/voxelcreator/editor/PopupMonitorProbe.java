package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Deferred actual file/resource popup integration with explicitly synthetic monitor inventories.
 */
public final class PopupMonitorProbe {
  private static EditorState state;
  private static ProjectFileDialog files;
  private static ResourcePicker picker;
  private static boolean selecting;
  private static UiMonitorWorkAreas.Snapshot inventory;
  private static final DialogBounds.Rect CLIENT = new DialogBounds.Rect(0, 0, 960, 720);
  private static final DialogBounds.Rect LEFT = new DialogBounds.Rect(0, 0, 480, 680);
  private static final DialogBounds.Rect RIGHT = new DialogBounds.Rect(480, 0, 480, 680);
  private static final DialogBounds.Rect INSET = new DialogBounds.Rect(480, 40, 360, 480);
  private static final DialogBounds.Rect OTHER = new DialogBounds.Rect(0, 20, 400, 500);

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
                  p.noise2D.add(noise("field"));
                  p.noise2D.add(noise("other"));
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        files =
            new ProjectFileDialog(
                state,
                () -> {
                  throw new AssertionError("Unexpected replacement");
                },
                () -> {
                  throw new AssertionError("Unexpected exit");
                },
                () -> false,
                new PopupWorkAreaResolver(() -> inventory));
        picker =
            new ResourcePicker(state, new PopupLayout(new PopupWorkAreaResolver(() -> inventory)));
        inventory = absolute(LEFT, RIGHT);
        var cases = new ArrayList<Map<String, Object>>();
        frames(3);
        files.workflow.save(true);
        frames(5);
        require(files.workflow.phase() == ProjectFileWorkflow.Phase.SAVE, "Save As did not open");
        cases.add(modal("modal-left", LEFT));
        Path forbidden = out.resolve("cancelled-monitor-project.pvproj.json");
        type(() -> files.probeControl("Path"), forbidden.toString());
        require(files.probePath().equals(forbidden.toString()), "Path did not retain full text");
        inventory = absolute(RIGHT);
        frames(4);
        cases.add(modal("modal-right", RIGHT));
        inventory = absolute(INSET);
        frames(4);
        cases.add(modal("modal-inset", INSET));
        require(
            files.probePath().equals(forbidden.toString()), "Monitor change edited private path");
        wheelTo(() -> files.probeControl("Cancel"), files.layout);
        click(files.probeControl("Cancel"));
        require(
            files.workflow.phase() == ProjectFileWorkflow.Phase.IDLE && !Files.exists(forbidden),
            "Cancel wrote or retained a file action");
        selecting = true;
        frames(3);
        picker.probeOpen();
        frames(4);
        cases.add(selector("selector-right", INSET, true));
        type(() -> center(picker.layout.queryBounds()), "field");
        require(picker.submitted() == 1, "Resource filter did not isolate field");
        inventory = absolute(OTHER);
        frames(4);
        cases.add(selector("selector-left", OTHER, true));
        require(picker.submitted() == 1, "Moving selector lost its private query");
        inventory = absolute();
        frames(4);
        cases.add(selector("selector-empty", CLIENT, false));
        inventory =
            new UiMonitorWorkAreas.Snapshot(
                false, List.of(new DialogBounds.Rect(-3000, -2000, 800, 600)));
        frames(4);
        cases.add(selector("selector-relative", CLIENT, false));
        require(picker.submitted() == 1, "Fallback edited the resource query");
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        frames(3);
        require(picker.layout.bounds() == null, "Escape did not close selector");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.draft == draft
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && "-".equals(state.seedInputs.text("seedOffset"))
                && state.engine.profiler().current().isEmpty(),
            "Popup fitting changed authoring state");
        Files.writeString(
            out.resolve("popup-monitor-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "inventorySource",
                    "synthetic",
                    "cases",
                    cases,
                    "privatePathPreserved",
                    true,
                    "queryPreserved",
                    true,
                    "cancelAndEscapePreserved",
                    true,
                    "projectPreserved",
                    true,
                    "physicalDetachedVerified",
                    false,
                    "physicalDpiVerified",
                    false)));
        System.out.println(
            "POPUP_MONITOR_NATIVE_PASS actual file/resource popups with synthetic monitor"
                + " intersections, private text and Cancel/Escape; physical detached/DPI separate");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Noise noise(String id) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", .125);
    return n;
  }

  private static UiMonitorWorkAreas.Snapshot absolute(DialogBounds.Rect... values) {
    return new UiMonitorWorkAreas.Snapshot(true, List.of(values));
  }

  private static Map<String, Object> modal(String name, DialogBounds.Rect expected) {
    return record(name, files.layout.bounds(), files.layout.workArea(), expected, true);
  }

  private static Map<String, Object> selector(
      String name, DialogBounds.Rect expected, boolean constrained) {
    return record(name, picker.layout.bounds(), picker.layout.workArea(), expected, constrained);
  }

  private static Map<String, Object> record(
      String name,
      DialogBounds.Rect bounds,
      PopupWorkArea.Plan work,
      DialogBounds.Rect expected,
      boolean constrained) {
    require(
        work.bounds().equals(expected) && work.monitorConstrained() == constrained,
        "Wrong popup work area: " + name);
    require(work.viewportId() == ImGui.getMainViewport().getID(), "Unexpected fixture viewport");
    require(
        DialogBounds.contains(DialogBounds.inset(expected), bounds),
        "Popup outside monitor/client intersection: " + name);
    return Map.of(
        "name", name, "bounds", bounds, "work", work, "inventory", inventory, "client", CLIENT);
  }

  private static float[] center(DialogBounds.Rect rect) {
    return new float[] {rect.x() + rect.width() / 2, rect.y() + rect.height() / 2};
  }

  private static void type(Supplier<float[]> field, String value) {
    click(field.get());
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frames(3);
  }

  private static void wheelTo(Supplier<float[]> control, DialogLayout layout) {
    for (int i = 0; i < 32; i++) {
      var point = control.get();
      var bounds = layout.bounds();
      if (point != null
          && point[0] > bounds.x() + 4
          && point[0] < bounds.x() + bounds.width() - 4
          && point[1] > bounds.y() + 50
          && point[1] < bounds.y() + bounds.height() - 8) return;
      var io = ImGui.getIO();
      io.addMousePosEvent(bounds.x() + bounds.width() - 3, bounds.y() + bounds.height() / 2);
      frame();
      io.addMouseWheelEvent(0, -10);
      frames(3);
    }
    throw new AssertionError("Cancel was not reachable after monitor fitting");
  }

  private static void click(float[] point) {
    require(point != null, "Missing popup control");
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
    io.setDisplaySize(960, 720);
    io.setFontGlobalScale(2.5f);
    ImGui.newFrame();
    ImGui.setNextWindowPos(620, 80, ImGuiCond.Always);
    ImGui.setNextWindowSize(330, 500, ImGuiCond.Always);
    ImGui.begin(
        "Monitor popup fixture host",
        ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoTitleBar);
    ImGui.setWindowFontScale(1);
    if (selecting)
      picker.draw(
          "Field",
          "noise2D",
          null,
          null,
          id -> {
            throw new AssertionError("Unexpected resource selection");
          });
    else files.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
