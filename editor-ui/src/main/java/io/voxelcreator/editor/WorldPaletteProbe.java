package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.lwjgl.opengl.GL;

/** Deferred production fixed/streamed/ghost palette replay with real GL and bounded workers. */
public final class WorldPaletteProbe {
  private static EditorState state;
  private static Viewports views;
  private static UUID stone, other, stage, field;
  private static final List<Map<String, Object>> observations = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    System.setProperty("joml.nounsafe", "true");
    if (!glfwInit()) throw new IllegalStateException("GLFW init");
    long window = 0;
    try {
      glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
      window = glfwCreateWindow(64, 64, "World palette probe", 0, 0);
      require(window != 0, "GL context");
      glfwShowWindow(window);
      glfwFocusWindow(window);
      glfwPollEvents();
      glfwMakeContextCurrent(window);
      GL.createCapabilities();
      ImGui.createContext();
      try {
        ImGui.getIO().setIniFilename(null);
        ImGui.getIO().setDisplaySize(1200, 900);
        ImGui.getIO().setDeltaTime(1f / 60);
        UiFontAtlas.install();
        try (var editor = new EditorState(new UserConfig(), false)) {
          state = editor;
          state.user.editor.autoRegenerate = false;
          state.user.editor.opportunitiesPerSecond = 20;
          state.user.visualization.streaming = true;
          state.user.visualization.unlit = true;
          state.user.visualization.grid = false;
          state.user.performance.renderDistance = state.user.performance.verticalDistance = 0;
          state.user.performance.streamChunks = 27;
          edit(WorldPaletteProbe::fixture);
          stone = state.project().voxelTypes.getFirst().uuid;
          other = state.project().voxelTypes.getLast().uuid;
          stage = state.project().worldGenerator.layers.getFirst().operations.getFirst().uuid;
          field = state.project().noise3D.getFirst().uuid;
          state.document.save(out.resolve("palette-source.json"));
          state.user.save(out.resolve("preferences-before.json"));
          var preferences = ProjectIO.JSON.valueToTree(state.user);
          state.select("noise3D", field);
          var draft = state.draft;
          ((Noise) draft).id = "private";
          state.seedInputs.edit(state, "seedOffset", "Seed", "-");
          var privateValues = ProjectIO.JSON.valueToTree(draft);
          try (var viewport = new Viewports(state, new PropertyPanel(state), window)) {
            views = viewport;
            views.probeWorld(stage, "After", 8, .25);
            until(views::probeWorldReady);
            observe("fixed-initial", 255, 0, 0);
            long fixedStamp = views.probeSubmissionStamp(2);
            edit(p -> Collections.reverse(p.voxelTypes));
            frames(2);
            noSubmission(fixedStamp);
            observe("fixed-reordered", 255, 0, 0);
            edit(
                p -> {
                  type(p, stone).color = new Color(25, 55, 230);
                  type(p, other).color = new Color(210, 130, 30);
                });
            frames(2);
            noSubmission(fixedStamp);
            observe("fixed-recolored", 25, 55, 230);
            edit(
                p -> {
                  p.voxelTypes.removeIf(t -> t.uuid.equals(stone));
                  var reused = new VoxelType();
                  reused.id = "stone";
                  reused.color = new Color(255, 255, 0);
                  p.voxelTypes.add(reused);
                  p.worldGenerator.layers.getFirst().defaults.voxelTypeUuid = reused.uuid;
                });
            frames(2);
            noSubmission(fixedStamp);
            observe("fixed-reused-id", 25, 55, 230);
            state.document.undo();
            state.revision++;
            frames(2);
            require(type(state.project(), stone) != null, "Undo did not restore the original UUID");
            noSubmission(fixedStamp);
            observe("fixed-undo", 25, 55, 230);

            until(WorldPaletteProbe::workersIdle);
            try (var hold = holdWorkers()) {
              regenerate();
              require(views.probeSubmissionStamp(2) != fixedStamp, "Fixed retry not queued");
              edit(p -> type(p, stone).color = new Color(180, 40, 200));
              frame();
            }
            until(views::probeWorldReady);
            observe("fixed-late-accepted", 180, 40, 200);
            save(out, "fixed-after.json");

            until(WorldPaletteProbe::workersIdle);
            try (var hold = holdWorkers()) {
              views.probeWorldStream(stage, "After");
              frame();
            }
            waitWithoutFrames(views::probeWorldSetupPending);
            edit(p -> type(p, stone).color = new Color(40, 180, 240));
            until(() -> views.probeWorldStreamReady(0));
            observe("stream-late-accepted", 40, 180, 240);
            long streamStamp = views.probeSubmissionStamp(2), scene = views.probeWorldStreamEpoch();
            edit(p -> Collections.reverse(p.voxelTypes));
            frames(2);
            noSubmission(streamStamp);
            require(scene == views.probeWorldStreamEpoch(), "Reorder regenerated manual stream");
            observe("stream-reordered", 40, 180, 240);
            edit(p -> type(p, stone).color = new Color(160, 50, 200));
            frames(2);
            noSubmission(streamStamp);
            require(scene == views.probeWorldStreamEpoch(), "Recolor regenerated manual stream");
            observe("stream-recolored", 160, 50, 200);
            save(out, "stream-after.json");

            var clear = new Operation();
            clear.id = "clear";
            clear.noiseGeneratorUuid = field;
            clear.interpretation = "DENSITY";
            clear.overrides.put("action", ProjectIO.JSON.getNodeFactory().textNode("CLEAR"));
            edit(p -> p.worldGenerator.layers.getFirst().operations.add(clear));
            views.probeWorld(clear.uuid, "Only Changes", 8, .25);
            frames(2);
            regenerate();
            until(views::probeWorldReady);
            observe("ghost-initial", 235, 30, 66);
            long ghostStamp = views.probeSubmissionStamp(2);
            edit(p -> Collections.reverse(p.voxelTypes));
            edit(p -> type(p, stone).color = new Color(40, 180, 240));
            frames(2);
            noSubmission(ghostStamp);
            observe("ghost-recolored", 235, 60, 80);
            save(out, "ghost-after.json");

            require(
                preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
                "Palette replay changed preferences");
            require(
                state.draft == draft
                    && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                    && "-".equals(state.seedInputs.text("seedOffset")),
                "Palette replay changed private Properties input");
            state.user.save(out.resolve("preferences.json"));
            Files.writeString(
                out.resolve("world-palette-native.json"),
                ProjectIO.JSON.writeValueAsString(
                    Map.ofEntries(
                        Map.entry("formatVersion", 1),
                        Map.entry("fixture", "production-fixed-streamed-ghost-native-gl"),
                        Map.entry("observations", observations),
                        Map.entry("manualFixedRetained", true),
                        Map.entry("manualStreamRetained", true),
                        Map.entry("latestFixedColors", true),
                        Map.entry("latestSetupColors", true),
                        Map.entry("uuidReuseIgnored", true),
                        Map.entry("undoUuidRestored", true),
                        Map.entry("sourceGhostOffsetRetained", true),
                        Map.entry("viewOnlyRepaint", true),
                        Map.entry("privatePreserved", true),
                        Map.entry("physicalDpiVerified", false),
                        Map.entry("hardwareCertified", false))));
            System.out.println(
                "WORLD_PALETTE_NATIVE_PASS UUID/source order / manual retention / late fixed and"
                    + " streaming colors / removal ghosts");
          }
        }
      } finally {
        ImGui.destroyContext();
      }
    } finally {
      if (window != 0) glfwDestroyWindow(window);
      glfwTerminate();
    }
  }

  private static void observe(String name, int r, int g, int b) {
    var project = ProjectIO.JSON.valueToTree(state.project());
    long revision = state.revision, history = state.document.historyBytes();
    var pixel = views.probePreviewPixel(2);
    require(
        Math.abs(pixel.get(0) - r) <= 2
            && Math.abs(pixel.get(1) - g) <= 2
            && Math.abs(pixel.get(2) - b) <= 2,
        "Unexpected " + name + " pixel " + pixel);
    var row = new LinkedHashMap<String, Object>();
    row.put("phase", name);
    row.put("pixel", pixel);
    row.put("palette", views.probeWorldPalette());
    row.put("submission", views.probeSubmissionStamp(2));
    observations.add(row);
    frame();
    require(
        project.equals(ProjectIO.JSON.valueToTree(state.project()))
            && revision == state.revision
            && history == state.document.historyBytes(),
        "Repaint changed authored state");
  }

  private static void noSubmission(long stamp) {
    require(stamp == views.probeSubmissionStamp(2), "Palette edit resubmitted geometry");
  }

  private static VoxelType type(ProjectData p, UUID uuid) {
    return p.voxelTypes.stream().filter(t -> t.uuid.equals(uuid)).findFirst().orElseThrow();
  }

  private static void edit(java.util.function.Consumer<ProjectData> change) {
    require(state.change(change), "Fixture edit rejected");
  }

  private static void save(Path out, String name) throws Exception {
    new ProjectIO().save(out.resolve(name), state.project());
  }

  private static void fixture(ProjectData p) {
    p.project.minY = 0;
    p.project.maxY = 31;
    var a = new VoxelType();
    a.id = "stone";
    a.color = new Color(255, 0, 0);
    var b = new VoxelType();
    b.id = "other";
    b.color = new Color(0, 255, 0);
    p.voxelTypes.addAll(List.of(a, b));
    var n = new Noise();
    n.id = "constant";
    n.dimension = 3;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", .25);
    p.noise3D.add(n);
    var layer = new Layer();
    layer.id = "world";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = a.uuid;
    var op = new Operation();
    op.id = "set";
    op.noiseGeneratorUuid = n.uuid;
    op.interpretation = "DENSITY";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
  }

  private static void regenerate() {
    frames(2);
    var p = views.probeAdmissionControl("toolbar");
    require(p != null, "Missing Regenerate control");
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0], p[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private record WorkerHold(CountDownLatch release, List<Future<?>> workers)
      implements AutoCloseable {
    public void close() throws Exception {
      release.countDown();
      for (var worker : workers) worker.get(5, TimeUnit.SECONDS);
    }
  }

  private static WorkerHold holdWorkers() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(state.background.getCorePoolSize());
    var workers = new ArrayList<Future<?>>();
    try {
      for (int i = 0; i < state.background.getCorePoolSize(); i++)
        workers.add(
            state.background.submit(
                () -> {
                  entered.countDown();
                  try {
                    if (!release.await(20, TimeUnit.SECONDS))
                      throw new AssertionError("Worker hold timeout");
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
      require(entered.await(5, TimeUnit.SECONDS), "Workers not held");
      return new WorkerHold(release, workers);
    } catch (Exception | Error failure) {
      release.countDown();
      throw failure;
    }
  }

  private static boolean workersIdle() {
    return state.background.getActiveCount() == 0 && state.background.getQueue().isEmpty();
  }

  private static void waitWithoutFrames(BooleanSupplier ready) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!ready.getAsBoolean()) {
      if (System.nanoTime() > end) throw new AssertionError("Source setup timeout");
      Thread.yield();
    }
  }

  private static void until(BooleanSupplier ready) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!ready.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end) throw new AssertionError("Preview timeout");
      Thread.yield();
    }
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(950, 750, ImGuiCond.Always);
    views.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
