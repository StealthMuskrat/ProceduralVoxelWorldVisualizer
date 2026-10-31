package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred production finite options/Inspect chrome; view invalidation and opening are fixtures.
 */
public final class ViewportOptionProbe {
  private static EditorState state;
  private static ViewportChoicePanel[] panels;
  private static ViewportCommands inspector;
  private static final Object noiseView = new Object();
  private static final Object[] views = {new Object(), noiseView, noiseView, new Object()};
  private static final String[] current = {"Grayscale", "Threshold Voxels", "Y", "Final"};
  private static final String[] targets = {"Heatmap", "Slices", "Z", "Only Changes"};
  private static final long[] epochs = {1, 1, 1};
  private static final List<Delivery> deliveries = new ArrayList<>();
  private static int form, width = 1200, height = 900, host, reads;
  private static float global = 1, multiplier = 1;
  private static boolean visible = true,
      open,
      fail,
      successor,
      failInspect,
      inspectOpen,
      replaceReview;
  private static UUID two, three, stage;

  private record Delivery(String form, String value) {}

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
        require(state.change(ViewportOptionProbe::fixture), "Fixture rejected");
        var file = out.resolve("option-project.json");
        state.document.save(file);
        require(state.document.open(file).isEmpty(), "Fixture reopen failed");
        two = state.project().noise2D.getFirst().uuid;
        three = state.project().noise3D.getFirst().uuid;
        stage = state.project().worldGenerator.layers.getFirst().operations.get(1).uuid;
        state.select("noise2D", two);
        var draft = state.draft;
        var selected = state.selected;
        ((Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision;
        var engine = state.engine;
        panels =
            Arrays.stream(ViewportChoicePanel.Option.values())
                .map(option -> new ViewportChoicePanel(state, option))
                .toArray(ViewportChoicePanel[]::new);
        inspector = new ViewportCommands(state);
        var cases = new ArrayList<Map<String, Object>>();
        for (form = 0; form < 5; form++) {
          if (form == 2) current[1] = "Slices";
          frames(4);
          if (form < 4) open();
          capture(cases);
          if (form < 4) escape();
        }
        form = 0;
        dimensions(1200, 900, 1, 1);
        frames(4);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        for (form = 0; form < 5; form++) {
          frames(4);
          if (form < 4) {
            open();
            escapeAt(panels[form].rowCenter(targets[form]));
          } else escapeAt(inspector.inspectCenter());
        }
        require(deliveries.isEmpty() && !inspectOpen, "Escape accepted option/Inspect");
        for (form = 0; form < 4; form++) {
          frames(4);
          int id = panels[form].buttonId();
          press(panels[form].buttonCenter());
          epochs[tab()]++;
          frame();
          release();
          frames(3);
          require(
              deliveries.isEmpty()
                  && panels[form].rowIds().isEmpty()
                  && id != panels[form].buttonId(),
              "Held trigger opened successor source");
          open();
          press(panels[form].rowCenter(targets[form]));
          epochs[tab()]++;
          frame();
          release();
          frames(3);
          require(deliveries.isEmpty(), "Held choice adopted successor view epoch");
          open();
          press(panels[form].rowCenter(targets[form]));
          String previous = current[form];
          current[form] = targets[form];
          frame();
          release();
          frames(3);
          require(deliveries.isEmpty(), "Held choice adopted a new field-local current value");
          current[form] = previous;
          open();
          press(panels[form].rowCenter(targets[form]));
          reopen(file);
          frame();
          release();
          frames(3);
          require(deliveries.isEmpty(), "Held choice crossed same-UUID document replacement");
          open();
          press(panels[form].rowCenter(targets[form]));
          visible = false;
          frame();
          visible = true;
          frame();
          release();
          frames(3);
          require(deliveries.isEmpty(), "Hidden option retained held row");
          open();
          press(panels[form].rowCenter(targets[form]));
          escape();
          open();
          move(panels[form].rowCenter(targets[form]));
          release();
          frames(3);
          require(deliveries.isEmpty(), "Reopened option adopted predecessor release");
          escape();
        }
        form = 2;
        current[1] = "Slices";
        frames(4);
        open();
        press(panels[2].rowCenter("Z"));
        replaceReview = true;
        release();
        frames(3);
        require(
            deliveries.isEmpty() && !replaceReview,
            "Option missed post-draw field-local source recheck");
        escape();
        form = 4;
        frames(4);
        for (int variant = 0; variant < 3; variant++) {
          int id = inspector.inspectId();
          press(inspector.inspectCenter());
          if (variant == 0) epochs[2]++;
          else if (variant == 1) reopen(file);
          else {
            visible = false;
            frame();
            visible = true;
          }
          frame();
          release();
          frames(3);
          require(
              deliveries.isEmpty() && !inspectOpen && id != inspector.inspectId(),
              "Inspect adopted successor source");
        }
        press(inspector.inspectCenter());
        replaceReview = true;
        release();
        frames(3);
        require(deliveries.isEmpty() && !replaceReview, "Inspect missed complete button recheck");
        // All finite choices are delivered by production rows. The callback changes only fixture
        // view state.
        for (form = 0; form < 4; form++)
          for (String value : panels[form].option.values) {
            frames(4);
            click(panels[form].buttonCenter());
            click(panels[form].rowCenter(value));
          }
        require(deliveries.size() == 17, "Finite mode vocabulary route missing");
        form = 0;
        frames(4);
        open();
        fail = true;
        click(panels[0].rowCenter("Heatmap"));
        fail = false;
        frames(4);
        require(
            deliveries.size() == 17
                && !panels[0].feedback().isEmpty()
                && !panels[0].rowIds().isEmpty(),
            "Failed choice lost explicit owned retry");
        click(panels[0].rowCenter("Heatmap"));
        require(
            deliveries.size() == 18 && current[0].equals("Heatmap"),
            "Explicit option retry failed");
        open();
        successor = true;
        click(panels[0].rowCenter("Contour"));
        successor = false;
        frames(4);
        require(
            deliveries.size() == 18
                && panels[0].feedback().isEmpty()
                && !panels[0].rowIds().isEmpty(),
            "Failed predecessor overwrote successor popup");
        click(panels[0].rowCenter("Contour"));
        require(deliveries.size() == 19, "Successor option selection failed");
        form = 4;
        frames(4);
        int id = inspector.inspectId();
        failInspect = true;
        click(inspector.inspectCenter());
        failInspect = false;
        frames(4);
        require(
            !inspectOpen && id == inspector.inspectId() && deliveries.size() == 19,
            "Inspect callback failure lost retry presentation");
        click(inspector.inspectCenter());
        require(inspectOpen && deliveries.size() == 20, "Explicit Inspect retry failed");
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
            "Options changed protected project/private/preferences/runtime state");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("option-events.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        Files.writeString(
            out.resolve("viewport-options-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("deliveries", deliveries),
                    Map.entry("escapePreserved", true),
                    Map.entry("triggerSourceScoped", true),
                    Map.entry("fieldCurrentScoped", true),
                    Map.entry("documentLifetimeScoped", true),
                    Map.entry("hiddenReopenScoped", true),
                    Map.entry("completeReviewRechecked", true),
                    Map.entry("callbacksBalanced", true),
                    Map.entry("failureRetryScoped", true),
                    Map.entry("successorPreserved", true),
                    Map.entry("inspectRouted", true),
                    Map.entry("privatePreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("gpuModeVerified", false))));
        System.out.println(
            "VIEWPORT_OPTIONS_NATIVE_PASS bounded finite choices / source-owned triggers and rows /"
                + " balanced retry / Inspect");
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
      n.algorithm.parameters.put("value", dimension == 2 ? .125 : .25);
      (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    }
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "pipeline";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    for (String name : List.of("fill", "clear")) {
      var op = new Operation();
      op.id = name;
      op.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
      op.interpretation = "SELECTION";
      if (name.equals("clear")) op.overrides.put("action", ProjectIO.JSON.valueToTree("CLEAR"));
      layer.operations.add(op);
    }
    p.worldGenerator.layers.add(layer);
  }

  private static int tab() {
    return form == 0 ? 0 : form == 1 || form == 2 ? 1 : 2;
  }

  private static ViewportActionContext context() {
    int index = form == 4 ? 3 : form;
    int tab = tab();
    return ViewportActionContext.capture(
        state,
        views[index],
        "surface",
        tab,
        epochs[tab],
        tab == 0 ? two : tab == 1 ? three : null,
        tab == 2 ? stage : null,
        tab == 0 ? current[0] : tab == 1 ? current[1] : "Threshold Voxels",
        tab == 2 ? current[3] : "Final",
        null,
        null);
  }

  private static ViewportChoicePanel.Review sample() {
    reads++;
    if (replaceReview && reads == 2) {
      replaceReview = false;
      current[form] = "X";
    }
    return new ViewportChoicePanel.Review(context(), current[form]);
  }

  private static ViewportActionContext inspectSource() {
    reads++;
    if (replaceReview && reads == 2) {
      replaceReview = false;
      epochs[2]++;
    }
    return context();
  }

  private static void choose(String value) {
    balanced();
    if (fail) throw new IllegalStateException("viewport option boundary");
    if (successor) {
      panels[form].probeOpen();
      throw new IllegalStateException("viewport option successor");
    }
    deliveries.add(new Delivery(panels[form].option.name(), value));
    if (!Objects.equals(current[form], value)) {
      current[form] = value;
      epochs[tab()]++;
    }
  }

  private static void inspect(ViewportCommands.Command command, ViewportActionContext review) {
    balanced();
    if (failInspect) throw new IllegalStateException("inspect boundary");
    require(
        command == ViewportCommands.Command.INSPECT_COORDINATE,
        "Inspect routed a different command");
    inspectOpen = true;
    deliveries.add(new Delivery("INSPECT", "open"));
  }

  private static void balanced() {
    require(
        ImGui.getID("option-parent-sentinel") == host,
        "Option/Inspect callback ran inside native scopes");
  }

  private static void reopen(Path file) {
    try {
      require(state.document.open(file).isEmpty(), "Controlled reopen failed");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void capture(List<Map<String, Object>> cases) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> rows = form < 4 ? panels[form].rowIds() : Map.of();
    int button = form < 4 ? panels[form].buttonId() : inspector.inspectId();
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      require(
          button == (form < 4 ? panels[form].buttonId() : inspector.inspectId())
              && (form == 4 || rows.equals(panels[form].rowIds())),
          "Reflow changed option/Inspect identity");
      var receipt = new LinkedHashMap<String, Object>();
      receipt.put("form", form < 4 ? panels[form].option.name() : "INSPECT");
      receipt.put("scenario", i);
      receipt.put("globalScale", global);
      receipt.put("windowScale", multiplier);
      receipt.put("window", new DialogBounds.Rect(16, 16, width, height));
      receipt.put("button", form < 4 ? panels[form].buttonBounds() : inspector.inspectBounds());
      receipt.put("buttonId", button);
      if (form < 4) {
        receipt.put("popup", panels[form].layout.bounds());
        receipt.put("list", panels[form].layout.listBounds());
        receipt.put("ids", panels[form].rowIds());
        receipt.put("rows", panels[form].rowBounds());
        receipt.put("stride", panels[form].stride());
      }
      cases.add(receipt);
    }
  }

  private static void open() {
    frames(3);
    open = true;
    frames(4);
    require(!panels[form].rowIds().isEmpty(), "Option popup did not open");
  }

  private static void dimensions(int w, int h, float g, float m) {
    width = w;
    height = h;
    global = g;
    multiplier = m;
  }

  private static void move(float[] point) {
    require(point != null, "Missing option target");
    ImGui.getIO().addMousePosEvent(point[0], point[1]);
    frame();
  }

  private static void press(float[] point) {
    move(point);
    ImGui.getIO().addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static void click(float[] point) {
    press(point);
    release();
    frames(4);
  }

  private static void escape() {
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void escapeAt(float[] point) {
    press(point);
    ImGui.getIO().addMouseButtonEvent(0, false);
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Viewport options");
    ImGui.setWindowFontScale(multiplier);
    host = ImGui.getID("option-parent-sentinel");
    reads = 0;
    if (visible) {
      if (form < 4) {
        if (open) {
          panels[form].probeOpen();
          open = false;
        }
        panels[form].draw(ViewportOptionProbe::sample, ViewportOptionProbe::choose);
      } else inspector.inspect(ViewportOptionProbe::inspectSource, ViewportOptionProbe::inspect);
    }
    ImGui.end();
    for (var panel : panels) panel.finishFrame(ImGui.getFrameCount());
    inspector.finishFrame(ImGui.getFrameCount());
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
