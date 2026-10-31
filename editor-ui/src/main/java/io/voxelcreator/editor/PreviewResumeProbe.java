package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.RenderPressure;
import java.nio.file.*;
import java.util.*;

/** Deferred native Resume chrome with real pressure policy and controlled sources/clocks. */
public final class PreviewResumeProbe {
  private static EditorState state;
  private static PreviewResumePanel[] panels;
  private static final RenderPressure[] pressure = {
    new RenderPressure(), new RenderPressure(), new RenderPressure()
  };
  private static final Object[] views = {new Object(), new Object(), new Object()};
  private static final Object[] sources = {new Object(), new Object(), new Object()};
  private static final String[] keys = {"volume-source", "surface-source", "world-source"};
  private static final int[] extents = {64, 64, 64};
  private static final long[] epochs = {1, 1, 1};
  private static final boolean[] rejected = {true, false, false};
  private static int slot, width = 1200, height = 900, host, reads;
  private static float global = 1, multiplier = 1;
  private static long now = 1_000_000_000L;
  private static boolean visible = true, replaceReview, failClock, failRefresh, pauseSuccessor;
  private static Runnable clockTransition;
  private static Object bookmarkOwner;
  private static UUID noise;
  private static final List<Receipt> deliveries = new ArrayList<>();

  private record Receipt(int slot, String kind, long pauseVersion, int level, String sourceKey) {}

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
        require(
            state.change(
                p -> {
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
                  var operation = new Operation();
                  operation.id = "constant-fill";
                  operation.interpretation = "SELECTION";
                  operation.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
                  layer.operations.add(operation);
                  p.worldGenerator.layers.add(layer);
                }),
            "Fixture rejected");
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
        panels =
            new PreviewResumePanel[] {
              new PreviewResumePanel(state),
              new PreviewResumePanel(state),
              new PreviewResumePanel(state)
            };
        var cases = new ArrayList<Map<String, Object>>();
        for (slot = 0; slot < 3; slot++) {
          critical(slot);
          frames(4);
          capture(cases);
        }
        slot = 0;
        dimensions(1200, 900, 1, 1);
        frames(4);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        for (slot = 0; slot < 3; slot++) {
          frames(4);
          escapeAt(panels[slot].center());
          require(pressure[slot].paused(), "Escape resumed pressure");
        }
        require(deliveries.isEmpty(), "Escape invoked Resume refresh");
        slot = 0;
        frames(4);
        int id = panels[0].actionId();
        long version = pressure[0].pauseVersion();
        now += 2_000_000_000L;
        pressure[0].observe(now, 1, 1, 10, true);
        frames(4);
        require(
            id == panels[0].actionId() && version == pressure[0].pauseVersion(),
            "Repeated paused measurement replaced review");
        press(panels[0].center());
        critical(0);
        frame();
        release();
        frames(3);
        require(
            pressure[0].paused() && id != panels[0].actionId() && deliveries.isEmpty(),
            "Held Resume adopted later pause at equal severity");
        int cpu = state.user.performance.sceneCpuMiB;
        for (int variant = 0; variant < 7; variant++) {
          var source = sources[0];
          var key = keys[0];
          int extent = extents[0];
          boolean rejection = rejected[0];
          bookmarkOwner = null;
          state.user.performance.sceneCpuMiB = cpu;
          frames(4);
          id = panels[0].actionId();
          press(panels[0].center());
          switch (variant) {
            case 0 -> sources[0] = new Object();
            case 1 -> keys[0] = "successor-source";
            case 2 -> extents[0] = 32;
            case 3 -> rejected[0] = !rejection;
            case 4 -> state.user.performance.sceneCpuMiB++;
            case 5 -> bookmarkOwner = new Object();
            default -> reopen(file);
          }
          frame();
          release();
          frames(3);
          require(
              pressure[0].paused() && deliveries.isEmpty() && id != panels[0].actionId(),
              "Held Resume crossed source/limit/document review");
          sources[0] = source;
          keys[0] = key;
          extents[0] = extent;
          rejected[0] = rejection;
        }
        state.user.performance.sceneCpuMiB = cpu;
        bookmarkOwner = null;
        frames(4);
        press(panels[0].center());
        visible = false;
        frame();
        visible = true;
        frame();
        release();
        frames(3);
        require(
            pressure[0].paused() && deliveries.isEmpty(), "Hidden Resume survived source omission");
        press(panels[0].center());
        slot = 1;
        frame();
        slot = 0;
        frame();
        release();
        frames(3);
        require(pressure[0].paused() && deliveries.isEmpty(), "Tab return adopted held Resume");
        press(panels[0].center());
        replaceReview = true;
        release();
        frames(3);
        require(
            pressure[0].paused() && deliveries.isEmpty() && !replaceReview,
            "Resume missed post-draw live review");
        extents[0] = 64;
        frames(4);
        clockTransition = () -> reopen(file);
        click(panels[0].center());
        require(
            pressure[0].paused() && deliveries.isEmpty() && clockTransition == null,
            "Clock callback resumed replacement document");
        clockTransition = () -> critical(0);
        click(panels[0].center());
        require(
            pressure[0].paused() && deliveries.isEmpty(), "Clock callback resumed successor pause");
        frames(4);
        id = panels[0].actionId();
        failClock = true;
        click(panels[0].center());
        failClock = false;
        frames(4);
        require(
            pressure[0].paused() && deliveries.isEmpty() && id == panels[0].actionId(),
            "Clock failure lost unchanged explicit retry");
        click(panels[0].center());
        require(
            !pressure[0].paused() && pressure[0].level() == 3 && deliveries.size() == 1,
            "Explicit clock retry failed");
        critical(0);
        frames(4);
        failRefresh = true;
        click(panels[0].center());
        failRefresh = false;
        frames(4);
        require(
            !pressure[0].paused() && deliveries.size() == 2,
            "Accepted Resume was replayed or rolled back after refresh failure");
        frames(8);
        require(deliveries.size() == 2, "Consumed refresh replayed without user action");
        critical(0);
        frames(4);
        version = pressure[0].pauseVersion();
        pauseSuccessor = true;
        click(panels[0].center());
        pauseSuccessor = false;
        frames(4);
        require(
            pressure[0].paused()
                && pressure[0].level() == 5
                && pressure[0].pauseVersion() > version
                && deliveries.size() == 3,
            "Refresh cleanup overwrote successor pause");
        click(panels[0].center());
        require(
            !pressure[0].paused() && deliveries.size() == 4,
            "Explicit successor pause Resume failed");
        for (slot = 1; slot < 3; slot++) {
          frames(4);
          click(panels[slot].center());
          require(
              !pressure[slot].paused() && pressure[slot].level() == 3,
              "Streaming Resume route failed");
        }
        var recovery = new ArrayList<Map<String, Object>>();
        for (slot = 0; slot < 3; slot++) {
          var levels = new ArrayList<Integer>(List.of(5, pressure[slot].level()));
          for (int expected = 2; expected >= 0; expected--) {
            now += 1_000_000_000L;
            pressure[slot].observe(now, 0, 0, 10, false);
            now += 5_000_000_000L;
            pressure[slot].observe(now, 0, 0, 10, false);
            require(pressure[slot].level() == expected, "Gradual pressure restoration changed");
            levels.add(expected);
          }
          recovery.add(
              Map.of(
                  "slot", slot, "levels", levels, "pauseVersion", pressure[slot].pauseVersion()));
        }
        slot = 0;
        frames(4);
        require(
            panels[0].actionId() == 0 && deliveries.size() == 6,
            "Unpaused Resume remained actionable");
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
            "Resume changed protected project/private/preferences/runtime state");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("resume-events.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        Files.writeString(
            out.resolve("preview-resume-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("deliveries", deliveries),
                    Map.entry("recovery", recovery),
                    Map.entry("escapePreserved", true),
                    Map.entry("pauseEpisodesScoped", true),
                    Map.entry("sourceLimitsScoped", true),
                    Map.entry("hiddenTabScoped", true),
                    Map.entry("postDrawClockRechecked", true),
                    Map.entry("clockRetryRetained", true),
                    Map.entry("acceptedFailureConsumed", true),
                    Map.entry("successorPausePreserved", true),
                    Map.entry("callbacksBalanced", true),
                    Map.entry("privatePreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("gpuRecoveryVerified", false))));
        System.out.println(
            "PREVIEW_RESUME_NATIVE_PASS owned pause/source/limits / clock retry / consumed refresh"
                + " / gradual policy restoration");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static PreviewResumeContext source() {
    var kind = slot == 0 ? PreviewResumeContext.Kind.VOLUME : PreviewResumeContext.Kind.STREAMING;
    var view =
        ViewportActionContext.capture(
            state,
            views[slot],
            "surface",
            slot == 2 ? 2 : 1,
            epochs[slot],
            slot == 2 ? null : noise,
            null,
            slot == 0 ? "Volume" : slot == 1 ? "Isosurface" : "Threshold Voxels",
            "Final",
            bookmarkOwner,
            null);
    return PreviewResumeContext.capture(
        state,
        view,
        kind,
        pressure[slot],
        sources[slot],
        keys[slot],
        extents[slot],
        rejected[slot]);
  }

  private static PreviewResumeContext sample() {
    reads++;
    if (replaceReview && reads == 2) {
      replaceReview = false;
      extents[slot]++;
    }
    return source();
  }

  private static long clock() {
    balanced();
    if (failClock) throw new IllegalStateException("resume clock boundary");
    if (clockTransition != null) {
      var transition = clockTransition;
      clockTransition = null;
      transition.run();
    }
    return now;
  }

  private static void refresh() {
    balanced();
    deliveries.add(
        new Receipt(
            slot,
            source().kind().name(),
            pressure[slot].pauseVersion(),
            pressure[slot].level(),
            keys[slot]));
    if (failRefresh) throw new IllegalStateException("resume refresh boundary");
    if (pauseSuccessor) {
      critical(slot);
      throw new IllegalStateException("resume successor boundary");
    }
    if (slot == 0) {
      rejected[slot] = false;
      epochs[slot]++;
    }
  }

  private static void critical(int target) {
    if (pressure[target].paused()) {
      now += 2_000_000_000L;
      pressure[target].resume(now);
    }
    while (pressure[target].level() < 5) {
      now += 1_000_000_000L;
      pressure[target].observe(now, 1, 1, 10, true);
    }
    require(pressure[target].paused(), "Critical fixture did not pause");
  }

  private static void reopen(Path file) {
    try {
      require(state.document.open(file).isEmpty(), "Controlled reopen failed");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void balanced() {
    require(
        ImGui.getID("resume-parent-sentinel") == host,
        "Resume callback ran inside native action scope");
  }

  private static void capture(List<Map<String, Object>> cases) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    int id = panels[slot].actionId();
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      require(id == panels[slot].actionId(), "Reflow changed Resume identity");
      cases.add(
          Map.ofEntries(
              Map.entry("slot", slot),
              Map.entry("kind", source().kind().name()),
              Map.entry("scenario", i),
              Map.entry("globalScale", global),
              Map.entry("windowScale", multiplier),
              Map.entry("window", new DialogBounds.Rect(16, 16, width, height)),
              Map.entry("action", panels[slot].actionBounds()),
              Map.entry("id", panels[slot].actionId())));
    }
  }

  private static void dimensions(int w, int h, float g, float m) {
    width = w;
    height = h;
    global = g;
    multiplier = m;
  }

  private static void move(float[] point) {
    require(point != null, "Resume not displayed");
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
    now += 16_666_667L;
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Preview Resume");
    ImGui.setWindowFontScale(multiplier);
    host = ImGui.getID("resume-parent-sentinel");
    reads = 0;
    if (visible)
      panels[slot].draw(
          PreviewResumeProbe::sample, PreviewResumeProbe::clock, PreviewResumeProbe::refresh);
    ImGui.end();
    for (var panel : panels) panel.finishFrame(ImGui.getFrameCount());
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
