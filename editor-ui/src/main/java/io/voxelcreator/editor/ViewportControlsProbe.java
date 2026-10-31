package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.RenderPressure;
import java.nio.file.*;
import java.util.*;

/** Deferred real pane/field/scroll/Resume chrome; the preview rectangle has no GL texture. */
public final class ViewportControlsProbe {
  private static EditorState state;
  private static final String[] forms = {"Histogram", "Volume", "Slices", "World"};
  private static final ViewportControlsPane[] panes = {
    new ViewportControlsPane(),
    new ViewportControlsPane(),
    new ViewportControlsPane(),
    new ViewportControlsPane()
  };
  private static final ViewportFields[] fields = {
    new ViewportFields(), new ViewportFields(), new ViewportFields(), new ViewportFields()
  };
  private static final double[] centers = {1.25, 2.25, 3.25, 4.25};
  private static final int[] extents = {32, 32, 32, 32};
  private static final boolean[] fixed = {false, false, false, false};
  private static final NoiseImageInput imageInput = new NoiseImageInput();
  private static NoiseImageNavigation navigation = new NoiseImageNavigation(0, 0, 1);
  private static PreviewResumePanel resume;
  private static final RenderPressure pressure = new RenderPressure();
  private static final Object view = new Object(), source = new Object();
  private static UUID noise;
  private static int form, width = 1200, height = 900, centerWrites, resumes, controlHost;
  private static float global = 1, multiplier = 1, rootScroll;
  private static DialogBounds.Rect image, histogram, status;
  private static ViewportPanelLayout.Surface surfacePlan;
  private static final float[] bins = new float[64];

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(ViewportControlsProbe::fixture), "Fixture rejected");
        var file = out.resolve("resume-project.json");
        state.document.save(file);
        require(state.document.open(file).isEmpty(), "Fixture reopen failed");
        noise = state.project().noise3D.getFirst().uuid;
        state.select("noise3D", noise);
        var draft = state.draft;
        var selected = state.selected;
        ((Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision;
        var engine = state.engine;
        pressure.pause();
        resume = new PreviewResumePanel(state);
        var cases = new ArrayList<Map<String, Object>>();
        for (form = 0; form < 4; form++) {
          frames(6);
          capture(cases);
        }
        form = 0;
        dimensions(1200, 900, 1, 1);
        frames(6);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var before = navigation;
        float scroll = panes[0].scroll();
        move(
            new float[] {
              panes[0].bounds().x() + 12, panes[0].bounds().y() + panes[0].bounds().height() - 20
            });
        ImGui.getIO().addMouseWheelEvent(0, -8);
        frames(4);
        require(
            panes[0].scroll() > scroll && navigation.equals(before),
            "Controls wheel changed the image or failed to scroll");
        scroll = panes[0].scroll();
        frames(8);
        require(
            Math.abs(panes[0].scroll() - scroll) < 1.1,
            "Measured pane scroll drifted across unchanged frames");
        ImGui.getIO().addMouseWheelEvent(0, 40);
        frames(5);
        require(panes[0].scroll() < 1.1, "Controls could not return to top");
        int writes = centerWrites;
        double previous = centers[0];
        type(fields[0].bounds("Center X"), "1e309");
        require(
            centers[0] == previous && centerWrites == writes,
            "Nonfinite numeric input changed the view");
        type(fields[0].bounds("Center X"), "17.5");
        require(centers[0] == 17.5, "Finite numeric edit failed");
        type(fields[0].bounds("Preview extent"), "48");
        require(extents[0] == 48, "Integer edit failed");
        click(center(fields[0].bounds("Fixed world bounds")));
        require(fixed[0], "Wrapped checkbox edit failed");
        form = 1;
        frames(4);
        pressure.resume(System.nanoTime());
        pressure.pause();
        frames(6);
        require(
            inside(panes[1].bounds(), resume.actionBounds()),
            "New pause did not reveal Resume inside control viewport");
        float revealed = panes[1].scroll();
        move(new float[] {panes[1].bounds().x() + 12, panes[1].bounds().y() + 40});
        ImGui.getIO().addMouseWheelEvent(0, 4);
        frames(4);
        require(panes[1].scroll() < revealed, "Paused controls could not scroll away from Resume");
        float pausedScroll = panes[1].scroll();
        frames(8);
        require(
            Math.abs(panes[1].scroll() - pausedScroll) < 1.1,
            "Repeated pause measurement reset user scroll");
        pressure.resume(System.nanoTime());
        pressure.pause();
        frames(6);
        require(
            inside(panes[1].bounds(), resume.actionBounds()),
            "Successor pause did not reveal Resume again");
        click(resume.center());
        require(resumes == 1 && !pressure.paused(), "Visible pane Resume failed");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && !state.document.dirty()
                && state.document.historyBytes() == 0
                && state.draft == draft
                && state.selected.equals(selected)
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Pane layout/input changed protected project/private/preferences/runtime state");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("viewport-controls-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("wheelOwned", true),
                    Map.entry("scrollStable", true),
                    Map.entry("pausedScrollStable", true),
                    Map.entry("finiteInputGuarded", true),
                    Map.entry("numberEdited", true),
                    Map.entry("integerEdited", true),
                    Map.entry("checkboxEdited", true),
                    Map.entry("resumeRevealed", true),
                    Map.entry("resumes", resumes),
                    Map.entry("nativeScopesBalanced", true),
                    Map.entry("privatePreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("gpuPreviewVerified", false))));
        System.out.println(
            "VIEWPORT_CONTROLS_NATIVE_PASS bounded pane/surface / finite fields / wheel ownership /"
                + " visible Resume");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    for (int dimension : new int[] {2, 3}) {
      var n = new Noise();
      n.id = "constant-" + dimension;
      n.dimension = dimension;
      n.algorithm.key = "CONSTANT";
      n.algorithm.parameters.clear();
      n.algorithm.parameters.put("value", .125);
      (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    }
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "fill";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    var op = new Operation();
    op.id = "fill";
    op.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
    op.interpretation = "SELECTION";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
  }

  private static PreviewResumeContext recovery() {
    return PreviewResumeContext.capture(
        state,
        ViewportActionContext.capture(
            state, view, "surface", 1, 1, noise, null, "Volume", "Final", null, null),
        PreviewResumeContext.Kind.VOLUME,
        pressure,
        source,
        "retained-volume",
        64,
        false);
  }

  private static void capture(List<Map<String, Object>> cases) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    var ids = fields[form].ids();
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(8);
      require(ids.equals(fields[form].ids()), "Pane reflow changed numeric IDs");
      var row = new LinkedHashMap<String, Object>();
      row.put("form", forms[form]);
      row.put("scenario", i);
      row.put("globalScale", global);
      row.put("windowScale", multiplier);
      row.put("window", new DialogBounds.Rect(16, 16, width, height));
      row.put("controls", panes[form].bounds());
      row.put("split", panes[form].plan());
      row.put("surface", surfacePlan);
      row.put("image", image);
      row.put("histogram", histogram);
      row.put("status", status);
      row.put("ids", ids);
      row.put("rootScroll", rootScroll);
      var input = new LinkedHashMap<String, DialogBounds.Rect>();
      for (String name : List.of("Center X", "Preview extent", "Fixed world bounds"))
        input.put(name, fields[form].bounds(name));
      row.put("fields", input);
      cases.add(row);
    }
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Viewport controls");
    ImGui.setWindowFontScale(multiplier);
    int parent = ImGui.getID("pane-parent-sentinel");
    var pane = panes[form];
    pane.begin("controls-" + form);
    controlHost = ImGui.getID("control-sentinel");
    fields[form].number(
        "Center X",
        centers[form],
        value -> {
          centers[form] = value;
          centerWrites++;
        });
    fields[form].integer("Preview extent", extents[form], value -> extents[form] = value);
    fields[form].bool("Fixed world bounds", fixed[form], value -> fixed[form] = value);
    for (int i = 0; i < (form == 0 ? 32 : form == 1 ? 24 : form == 2 ? 12 : 8); i++)
      ImGui.textWrapped("Preview control explanation " + i + " / current view settings");
    if (form == 1)
      resume.draw(
          ViewportControlsProbe::recovery,
          System::nanoTime,
          () -> {
            require(
                ImGui.getID("control-sentinel") == controlHost,
                "Resume callback escaped control parent");
            resumes++;
          });
    pane.end();
    require(
        ImGui.getID("pane-parent-sentinel") == parent, "Control pane did not unwind native scope");
    surfacePlan =
        ViewportPanelLayout.surface(
            ImGui.getContentRegionAvailY(),
            ImGui.getTextLineHeight(),
            ImGui.getStyle().getItemSpacingY(),
            form == 0,
            form == 0 || form == 2);
    float w = Math.max(1, ImGui.getContentRegionAvailX());
    histogram = null;
    status = null;
    if (surfacePlan.histogram() > 0) {
      float top = ImGui.getCursorPosY();
      ImGui.plotHistogram(
          "##histogram", bins, bins.length, 0, "Display bins", 0, 1, w, surfacePlan.histogram());
      histogram = PopupLayout.itemBounds();
      ImGui.setCursorPosY(top + surfacePlan.histogram() + surfacePlan.histogramGap());
    }
    float top = ImGui.getCursorPosY();
    ImGui.invisibleButton("preview-surface", w, surfacePlan.image());
    image = PopupLayout.itemBounds();
    boolean hovered = ImGui.isItemHovered();
    if (form == 0)
      navigation =
          imageInput.navigate(
              navigation, hovered, image.x(), image.y(), image.width(), image.height());
    if (surfacePlan.status() > 0) {
      ImGui.setCursorPosY(top + surfacePlan.image() + surfacePlan.statusGap());
      ViewportSampleStatus.draw(
          "sample-" + form,
          "Last hover: X -123456789.125 Z 987654321.25 Raw 0.125 Display 0.5625",
          w,
          surfacePlan.status());
      status = PopupLayout.itemBounds();
    }
    require(
        ImGui.getID("pane-parent-sentinel") == parent, "Status pane did not unwind native scope");
    rootScroll = ImGui.getScrollY();
    ImGui.end();
    resume.finishFrame(ImGui.getFrameCount());
    ImGui.render();
  }

  private static boolean inside(DialogBounds.Rect a, DialogBounds.Rect b) {
    return b != null
        && b.x() >= a.x() - 1.1
        && b.y() >= a.y() - 1.1
        && b.x() + b.width() <= a.x() + a.width() + 1.1
        && b.y() + b.height() <= a.y() + a.height() + 1.1;
  }

  private static float[] center(DialogBounds.Rect r) {
    return new float[] {r.x() + Math.min(r.width() / 2, 30), r.y() + r.height() / 2};
  }

  private static void type(DialogBounds.Rect r, String text) {
    click(center(r));
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

  private static void dimensions(int w, int h, float g, float m) {
    width = w;
    height = h;
    global = g;
    multiplier = m;
  }

  private static void move(float[] p) {
    ImGui.getIO().addMousePosEvent(p[0], p[1]);
    frame();
  }

  private static void click(float[] p) {
    move(p);
    ImGui.getIO().addMouseButtonEvent(0, true);
    frame();
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
    frames(3);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
