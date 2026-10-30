package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred real core-panel fitting and production Inspector XYZ reflow/input, without GL rendering.
 */
public final class CoreWindowLayoutProbe {
  private static EditorState state;
  private static PropertyPanel properties;
  private static ResourcePanel resources;
  private static int width = 1280, height = 900, edits;
  private static boolean coordinatesMode, initialPanels = true;
  private static float globalScale = 1, windowScale = 1;
  private static final FloatingWindowLayout inspector = new FloatingWindowLayout(600, 600);
  private static final InspectorCoordinateFields fields = new InspectorCoordinateFields();
  private static final InspectorCoordinates coordinates = new InspectorCoordinates();
  private static final Map<String, ImString> buffers = new LinkedHashMap<>();
  private static int resizeWidth, resizeHeight;
  private static float clipTop, clipBottom;

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
                  p.noise2D.add(noise);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var user = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        properties = new PropertyPanel(state);
        resources = new ResourcePanel(state, properties);
        resources.showManager();
        var core = new ArrayList<Map<String, Object>>();
        frames(6);
        require(
            resources.windowLayout.bounds().width() == 280
                && resources.windowLayout.bounds().height() == 520,
            "Core helper replaced caller Resources dimensions");
        require(
            properties.windowLayout.bounds().width() == 400
                && properties.windowLayout.bounds().height() == 600,
            "Core helper replaced caller Properties dimensions");
        core.add(coreCase("core-wide"));
        width = 360;
        height = 240;
        globalScale = 2.5f;
        frames(6);
        core.add(coreCase("core-narrow"));
        coordinatesMode = true;
        var cases = new ArrayList<Map<String, Object>>();
        coordinateCase(cases, "three-columns", 960, 640, 1, 1, 600, 600, 3, false);
        var ids = fields.ids();
        type("X", "-2147483648");
        type("Y", "2147483647");
        type("Z", "-17");
        require(
            coordinates
                .coordinate()
                .equals(new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, -17)),
            "Signed integer values narrowed");
        type("X", "-");
        require(
            !coordinates.problem().isEmpty(), "Incomplete input accepted an earlier coordinate");
        long version = coordinates.version();
        int before = edits;
        coordinateCase(cases, "two-columns", 560, 400, 2.5f, 1, 544, 384, 2, false);
        require(fields.ids().equals(ids), "Two-column layout changed input IDs");
        coordinateCase(cases, "one-column", 360, 240, 2.5f, 1, 344, 224, 1, false);
        require(fields.ids().equals(ids), "One-column layout changed input IDs");
        coordinateCase(cases, "stacked-6.25", 360, 240, 2.5f, 2.5f, 344, 224, 1, true);
        require(fields.ids().equals(ids), "Stacking labels changed input IDs");
        require(
            coordinates.version() == version
                && edits == before
                && coordinates.text("X").equals("-")
                && coordinates.text("Y").equals("2147483647")
                && coordinates.text("Z").equals("-17"),
            "Reflow edited raw XYZ");
        require(!coordinates.problem().isEmpty(), "Reflow repaired invalid input");
        type("X", "-2147483648");
        require(
            coordinates
                .coordinate()
                .equals(new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, -17)),
            "Stacked input lost full signed text");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.draft == draft
                && user.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.seedInputs.text("seedOffset").equals("-")
                && state.engine.profiler().current().isEmpty(),
            "Layout changed authoring state");
        Files.writeString(
            out.resolve("core-window-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "coreCases",
                    core,
                    "coordinateCases",
                    cases,
                    "callerSizesPreserved",
                    true,
                    "rawInputAndIdsPreserved",
                    true,
                    "signedInputCorrected",
                    true,
                    "projectPreserved",
                    true,
                    "physicalDetachedVerified",
                    false,
                    "physicalDpiVerified",
                    false)));
        System.out.println(
            "CORE_WINDOW_LAYOUT_NATIVE_PASS Resources/Properties/Manager fit and real XYZ"
                + " input/reflow/IDs; no renderer or physical DPI claim");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Map<String, Object> coreCase(String name) {
    var area = new DialogBounds.Rect(0, 0, width, height);
    var windows =
        Map.of(
            "Resources",
            resources.windowLayout.bounds(),
            "Properties",
            properties.windowLayout.bounds(),
            "Voxel Manager",
            resources.managerLayout.bounds());
    for (var bounds : windows.values())
      require(
          DialogBounds.contains(DialogBounds.inset(area), bounds),
          "Core window outside client work area");
    return Map.of("name", name, "workArea", area, "scale", globalScale, "windows", windows);
  }

  private static void coordinateCase(
      List<Map<String, Object>> cases,
      String name,
      int w,
      int h,
      float global,
      float window,
      int wantedWidth,
      int wantedHeight,
      int columns,
      boolean stacked) {
    width = w;
    height = h;
    globalScale = global;
    windowScale = window;
    resizeWidth = wantedWidth;
    resizeHeight = wantedHeight;
    frames(6);
    require(
        fields.plan().columns() == columns && fields.plan().stackedLabels() == stacked,
        "Unexpected XYZ reflow: " + name);
    var bounds = inspector.bounds();
    require(
        DialogBounds.contains(DialogBounds.inset(new DialogBounds.Rect(0, 0, w, h)), bounds),
        "Inspector fixture outside viewport");
    var axes = new LinkedHashMap<String, DialogBounds.Rect>();
    for (String axis : List.of("X", "Y", "Z")) {
      var field = fields.bounds(axis);
      require(
          field.x() >= bounds.x() && field.x() + field.width() <= bounds.x() + bounds.width() + .02,
          "Axis outside horizontal content");
      axes.put(axis, field);
    }
    cases.add(
        Map.of(
            "name",
            name,
            "workArea",
            new DialogBounds.Rect(0, 0, w, h),
            "bounds",
            bounds,
            "globalScale",
            global,
            "windowScale",
            window,
            "plan",
            fields.plan(),
            "axes",
            axes));
  }

  private static void type(String axis, String value) {
    reveal(axis);
    var point = fields.bounds(axis);
    var io = ImGui.getIO();
    io.addMousePosEvent(
        point.x() + Math.min(point.width() / 2, 40), point.y() + point.height() / 2);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(2);
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frames(3);
    require(value.equals(coordinates.text(axis)), "Native raw text mismatch: " + axis);
  }

  private static void reveal(String axis) {
    for (int i = 0; i < 256; i++) {
      var field = fields.bounds(axis);
      var bounds = inspector.bounds();
      float top = clipTop;
      if (field.y() >= top && field.y() + field.height() <= clipBottom) return;
      var io = ImGui.getIO();
      io.addMousePosEvent(bounds.x() + bounds.width() - 3, bounds.y() + bounds.height() - 12);
      frame();
      io.addMouseWheelEvent(0, field.y() < top ? .125f : -.125f);
      frames(2);
    }
    throw new AssertionError("Native window wheel could not reveal " + axis);
  }

  private static ImString buffer(String axis) {
    var value =
        buffers.computeIfAbsent(
            axis,
            key -> new ImString(coordinates.text(key), 4 * CoordinateText.MAX_CHARACTERS + 1));
    if (!value.get().equals(coordinates.text(axis))) value.set(coordinates.text(axis));
    return value;
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(globalScale);
    ImGui.newFrame();
    if (coordinatesMode) {
      inspector.prepare();
      if (resizeWidth > 0) {
        ImGui.setNextWindowSize(resizeWidth, resizeHeight, ImGuiCond.Always);
        resizeWidth = 0;
        ImGui.setNextWindowFocus();
      }
      ImGui.begin("Voxel Inspector");
      ImGui.setWindowFontScale(windowScale);
      inspector.observe();
      clipTop =
          ImGui.getWindowPosY() + ImGui.getFrameHeight() + ImGui.getStyle().getWindowPaddingY();
      clipBottom =
          ImGui.getWindowPosY() + ImGui.getWindowSizeY() - ImGui.getStyle().getWindowPaddingY();
      fields.draw(
          CoreWindowLayoutProbe::buffer,
          (axis, raw) -> {
            coordinates.edit(axis, raw);
            edits++;
          },
          axis -> {});
      if (!coordinates.problem().isEmpty()) ImGui.textWrapped(coordinates.problem());
      ImGui.end();
    } else {
      if (initialPanels) {
        ImGui.setNextWindowPos(10, 10, ImGuiCond.Always);
        ImGui.setNextWindowSize(280, 520, ImGuiCond.Always);
      }
      resources.draw();
      if (initialPanels) {
        ImGui.setNextWindowPos(310, 10, ImGuiCond.Always);
        ImGui.setNextWindowSize(400, 600, ImGuiCond.Always);
      }
      properties.draw();
      initialPanels = false;
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
