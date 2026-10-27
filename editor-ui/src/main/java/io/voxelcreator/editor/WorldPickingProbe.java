package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.Coordinates;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.lwjgl.opengl.GL;

/** Real World image mouse input, asynchronous stage picking and obsolete publication rejection. */
public final class WorldPickingProbe {
  private static Viewports views;
  private static EditorState state;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    System.setProperty("joml.nounsafe", "true");
    require(glfwInit(), "GLFW init");
    long window = 0;
    try {
      glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
      window = glfwCreateWindow(64, 64, "World picking probe", 0, 0);
      require(window != 0, "GL context");
      glfwShowWindow(window);
      glfwFocusWindow(window);
      glfwPollEvents();
      require(glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0, "World probe window focus");
      glfwMakeContextCurrent(window);
      GL.createCapabilities();
      ImGui.createContext();
      try {
        var io = ImGui.getIO();
        io.setIniFilename(null);
        io.setDisplaySize(1200, 900);
        io.setDeltaTime(1f / 60);
        io.getFonts().addFontDefault();
        io.getFonts().build();
        try (var editor = new EditorState(new UserConfig(), false)) {
          state = editor;
          state.user.editor.opportunitiesPerSecond = 20;
          state.user.visualization.streaming = false;
          state.user.performance.renderDistance = 0;
          state.user.performance.verticalDistance = 0;
          state.user.performance.streamChunks = 27;
          require(state.change(WorldPickingProbe::fixture), "Fixture validation");
          var authored = ProjectIO.JSON.valueToTree(state.project());
          long revision = state.revision, history = state.document.historyBytes();
          var set = state.project().worldGenerator.layers.get(0).uuid;
          var clear = state.project().worldGenerator.layers.get(1).uuid;
          views = new Viewports(state, new PropertyPanel(state), window);
          boolean closed = false;
          try {
            var hits = new ArrayList<Map<String, Object>>();
            for (var stage : List.of(set, clear)) {
              String mode = stage.equals(set) ? "After" : "Before";
              views.probeWorld(stage, mode, 8, .25);
              until(views::probeWorldReady);
              click();
              until(views::probeInspectorOpen);
              require(
                  new Coordinates(3, 0, 0).equals(views.probeWorldPicked()),
                  "Fixed hit outside displayed cube");
              require(
                  views.probeInspectorTrace().trace().position().equals(new BlockPos(3, 0, 0)),
                  "Trace coordinate mismatch");
              require(
                  views.probeInspectorTrace().trace().finalType() == 0,
                  "Final CLEAR provenance mismatch");
              hits.add(
                  Map.of(
                      "mode",
                      mode,
                      "side",
                      8,
                      "centerX",
                      .25,
                      "x",
                      3,
                      "y",
                      0,
                      "z",
                      0,
                      "finalType",
                      0));
            }
            views.probeWorld(clear, "After", 8, .25);
            until(views::probeWorldReady);
            click();
            until(views::probeInspectorOpen);
            require(
                views.probeWorldPicked() == null && state.status.contains("No occupied voxel"),
                "EMPTY stage did not miss");

            views.probeWorld(clear, "Only Changes", 3, -.25);
            until(views::probeWorldReady);
            click();
            until(views::probeInspectorOpen);
            require(
                new Coordinates(0, 0, 0).equals(views.probeWorldPicked()),
                "Odd/fractional removal ghost mismatch");
            hits.add(
                Map.of(
                    "mode",
                    "Only Changes",
                    "side",
                    3,
                    "centerX",
                    -.25,
                    "x",
                    0,
                    "y",
                    0,
                    "z",
                    0,
                    "finalType",
                    views.probeInspectorTrace().trace().finalType()));
            var retained = views.probeInspectorTrace();

            views.probeWorldFallback(set);
            until(views::probeWorldFallbackReady);
            click();
            until(views::probeInspectorOpen);
            require(
                new Coordinates(3, 0, 0).equals(views.probeWorldPicked()),
                "World fallback picked outside its displayed cube");
            hits.add(
                Map.of(
                    "mode",
                    "Fallback",
                    "side",
                    8,
                    "centerX",
                    .25,
                    "x",
                    3,
                    "y",
                    0,
                    "z",
                    0,
                    "finalType",
                    views.probeInspectorTrace().trace().finalType()));
            retained = views.probeInspectorTrace();

            var streamHits = new ArrayList<Map<String, Object>>();
            for (String mode : List.of("After", "Before", "Only Changes")) {
              views.probeWorldStream(mode.equals("After") ? set : clear, mode);
              until(() -> views.probeWorldStreamReady(0));
              click();
              until(views::probeInspectorOpen);
              require(
                  new Coordinates(31, 0, 0).equals(views.probeWorldPicked()),
                  "Streaming pick selected an unshown cell near the camera");
              streamHits.add(
                  Map.of(
                      "mode",
                      mode,
                      "chunkX",
                      0,
                      "x",
                      31,
                      "y",
                      0,
                      "z",
                      0,
                      "finalType",
                      views.probeInspectorTrace().trace().finalType()));
            }
            click(250);
            until(views::probeInspectorOpen);
            require(
                views.probeWorldPicked() == null && state.status.contains("displayed chunks"),
                "Unshown procedural solid region did not miss");
            var streamRetained = views.probeInspectorTrace();
            try (var hold = holdWorkers()) {
              click();
              require(!state.background.getQueue().isEmpty(), "Stream trace was not queued");
              state.user.performance.sceneGpuMiB =
                  state.user.performance.sceneGpuMiB == 32 ? 48 : 32;
              frame();
            }
            until(() -> views.probeWorldStreamReady(0) && idle());
            frames(3);
            require(
                !views.probeInspectorOpen() && views.probeInspectorTrace() == streamRetained,
                "GPU replacement allowed obsolete streamed trace publication");
            long streamEpoch = views.probeWorldStreamEpoch();
            views.probeWorldTravel(-.25);
            until(() -> views.probeWorldStreamReady(-1));
            click();
            until(views::probeInspectorOpen);
            require(
                new Coordinates(-1, 0, 0).equals(views.probeWorldPicked())
                    && streamEpoch == views.probeWorldStreamEpoch(),
                "Negative chunk travel picked an old display or recreated the source");
            streamHits.add(
                Map.of(
                    "mode",
                    "Travel",
                    "chunkX",
                    -1,
                    "x",
                    -1,
                    "y",
                    0,
                    "z",
                    0,
                    "finalType",
                    views.probeInspectorTrace().trace().finalType()));
            var streamReport =
                Map.of(
                    "hits",
                    streamHits,
                    "unloadedMiss",
                    true,
                    "gpuReplacementRejected",
                    true,
                    "epochReused",
                    true);
            retained = views.probeInspectorTrace();

            views.probeWorld(set, "After", 8, .25);
            until(views::probeWorldReady);
            try (var hold = holdWorkers()) {
              click();
              require(!state.background.getQueue().isEmpty(), "Pick was not queued behind workers");
              views.probeWorld(clear, "After", 8, .25);
            }
            until(() -> views.probeWorldReady() && idle());
            frames(3);
            require(
                !views.probeInspectorOpen()
                    && views.probeInspectorTrace() == retained
                    && views.probeWorldPicked() == null,
                "Obsolete pick reopened inspector after mode change");

            views.probeWorld(set, "After", 8, .25);
            until(views::probeWorldReady);
            try (var hold = holdWorkers()) {
              click();
              require(!state.background.getQueue().isEmpty(), "Camera pick was not queued");
              io.addMouseWheelEvent(0, 1);
              frame();
            }
            until(WorldPickingProbe::idle);
            frames(3);
            require(
                !views.probeInspectorOpen() && views.probeInspectorTrace() == retained,
                "Obsolete pick reopened inspector after zoom");

            views.probeWorld(set, "After", 8, .25);
            until(views::probeWorldReady);
            try (var hold = holdWorkers()) {
              click();
              require(!state.background.getQueue().isEmpty(), "Closure pick was not queued");
              views.close();
              closed = true;
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!idle()) {
              if (System.nanoTime() > deadline)
                throw new AssertionError("Late pick did not finish");
              Thread.yield();
            }
            state.drain();
            require(
                !views.probeInspectorOpen() && views.probeInspectorTrace() == retained,
                "Late pick published after closure");
            require(
                authored.equals(ProjectIO.JSON.valueToTree(state.project()))
                    && revision == state.revision
                    && history == state.document.historyBytes(),
                "Picking changed authored state");
            Files.writeString(
                out.resolve("world-picking-native.json"),
                ProjectIO.JSON.writeValueAsString(
                    Map.of(
                        "formatVersion",
                        2,
                        "stream",
                        streamReport,
                        "hits",
                        hits,
                        "emptyMiss",
                        true,
                        "obsoleteModeRejected",
                        true,
                        "obsoleteZoomRejected",
                        true,
                        "closedPublicationRejected",
                        true,
                        "viewOnly",
                        true)));
            System.out.println(
                "WORLD_PICKING_NATIVE_PASS real image clicks / fixed bounds / Before-After /"
                    + " removal ghost / odd fractional bounds / queued mode change / late closure /"
                    + " view-only");
          } finally {
            if (!closed) views.close();
          }
        }
        WorldLodPickingProbe.run(out.resolve("lod-proxy"), window);
        InspectorFreshnessProbe.run(out.resolve("inspector-freshness"), window);
        FreeFlyInspectorProbe.run(out.resolve("free-fly"), window);
        OrbitInputProbe.run(out.resolve("orbit-input"), window);
        SliceInputProbe.run(out.resolve("slice-input"), window);
      } finally {
        ImGui.destroyContext();
      }
    } finally {
      if (window != 0) glfwDestroyWindow(window);
      glfwTerminate();
    }
  }

  private static void fixture(ProjectData p) {
    p.project.minY = -16;
    p.project.maxY = 16;
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var source = new Noise();
    source.id = "constant";
    source.dimension = 3;
    source.algorithm.key = "CONSTANT";
    source.algorithm.parameters.put("value", .5);
    p.noise3D.add(source);
    for (String action : List.of("SET", "CLEAR")) {
      var layer = new Layer();
      layer.id = action.toLowerCase(Locale.ROOT);
      layer.defaults.action = action;
      layer.defaults.voxelTypeUuid = type.uuid;
      var operation = new Operation();
      operation.id = "everywhere";
      operation.noiseGeneratorUuid = source.uuid;
      operation.interpretation = "DENSITY";
      layer.operations.add(operation);
      p.worldGenerator.layers.add(layer);
    }
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
    var entered = new CountDownLatch(2);
    var workers = new ArrayList<Future<?>>();
    for (int i = 0; i < 2; i++)
      workers.add(
          state.background.submit(
              () -> {
                entered.countDown();
                try {
                  if (!release.await(20, TimeUnit.SECONDS))
                    throw new AssertionError("Held worker timed out");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }));
    require(entered.await(5, TimeUnit.SECONDS), "Workers not held");
    return new WorkerHold(release, workers);
  }

  private static boolean idle() {
    return state.background.getActiveCount() == 0 && state.background.getQueue().isEmpty();
  }

  private static void click() {
    click(0);
  }

  private static void click(float offset) {
    views.probeHideInspector();
    frames(2);
    var p = views.probeAdmissionControl("image");
    require(p != null, "Missing World image");
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0] + offset, p[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void until(BooleanSupplier condition) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!condition.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end)
        throw new AssertionError(
            "World picking timeout: " + views.probeSourceStatus(2) + " / " + state.status);
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

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
