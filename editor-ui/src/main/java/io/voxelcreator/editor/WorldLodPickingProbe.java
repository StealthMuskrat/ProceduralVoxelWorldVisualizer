package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Isolated engine-backed coarse/proxy/refinement fixture in the caller's native GL/ImGui context.
 */
final class WorldLodPickingProbe {
  private static EditorState state;
  private static Viewports views;
  private static TracePanel tracePanel;

  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toAbsolutePath().toString());
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      state.user.editor.opportunitiesPerSecond = 20;
      state.user.performance.renderDistance = state.user.performance.verticalDistance = 0;
      state.user.performance.streamChunks = 27;
      state.user.performance.lodStart = state.user.performance.lodQuality = 1;
      require(state.change(WorldLodPickingProbe::fixture), "LOD fixture rejected");
      var authored = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var plane = state.project().worldGenerator.layers.get(0).uuid;
      var clear = state.project().worldGenerator.layers.get(2).uuid;
      try (var world = state.engine.openWorld(new CompiledProject(state.project()))) {
        require(
            world.getVoxelTypeId(new BlockPos(29, 2, 2)) == 1,
            "Unchanged full-world cell not occupied");
        require(world.getVoxelTypeId(new BlockPos(28, 2, 2)) == 0, "Plane was not cleared");
      }
      var properties = new PropertyPanel(state);
      var navigation = new GuardedNavigation(state, properties);
      tracePanel = new TracePanel(state, navigation);
      try (var viewport = new Viewports(state, properties, window)) {
        views = viewport;
        views.tracePanel(tracePanel);
        var observations = new ArrayList<Map<String, Object>>();
        views.probeWorldProxyView(plane, "After");
        until(() -> centerIs(29, true));
        clickImage(0);
        until(views::probeInspectorOpen);
        frames(2);
        observations.add(observe("After", 29, true, 1));
        require(
            views.probeInspectorFeedback().contains("X 29, Y 2, Z 2")
                && views.probeInspectorFeedback().contains("full world"),
            "Proxy feedback lacks coordinate/full-world scope");
        retrace();
        require(
            views.probeInspectorFeedback().isEmpty() && views.probeInspectorTraceVisible(),
            "Explicit retrace retained the proxy hint");

        views.probeWorldProxyView(clear, "Only Changes");
        until(() -> centerIs(29, true));
        clickImage(0);
        until(views::probeInspectorOpen);
        frames(2);
        observations.add(observe("Only Changes", 29, true, 1));
        long epoch = views.probeWorldStreamEpoch();
        wheel(10);
        until(() -> centerIs(28, false));
        clickImage(0);
        until(views::probeInspectorOpen);
        frames(2);
        observations.add(observe("Refined", 28, false, 0));
        require(views.probeWorldStreamEpoch() == epoch, "Refinement recreated the CPU scene");
        require(
            views.probeInspectorFeedback().isEmpty() && !views.probeInspectorHintVisible(),
            "Refined exact pick retained proxy feedback");

        wheel(-10);
        until(() -> centerIs(29, true));
        long retainedBytes = state.traces.bytes();
        int retainedCount = state.traces.size();
        clickImage(300);
        until(views::probeInspectorOpen);
        frames(2);
        assertMiss("displayed chunks");
        require(
            state.traces.bytes() == retainedBytes && state.traces.size() == retainedCount,
            "Stream miss removed retained log traces");
        retrace();
        require(
            views.probeInspectorFeedback().isEmpty()
                && views.probeInspectorTraceVisible()
                && state.status.startsWith("Trace ready at"),
            "Explicit coordinate trace did not recover after miss");

        views.probeWorld(plane, "Before", 8, .25);
        until(views::probeWorldReady);
        retainedBytes = state.traces.bytes();
        retainedCount = state.traces.size();
        clickImage(0);
        until(views::probeInspectorOpen);
        frames(2);
        assertMiss("No occupied voxel hit");
        require(
            state.traces.bytes() == retainedBytes && state.traces.size() == retainedCount,
            "Fixed miss removed retained log traces");
        retrace();
        var coordinateWork = coordinateWorkflow();
        require(
            authored.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "LOD picking changed authored data");
        new ProjectIO().save(out.resolve("world-lod-project.json"), state.project());
        Files.writeString(
            out.resolve("world-lod-picking-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    2,
                    "coordinateWork",
                    coordinateWork,
                    "observations",
                    observations,
                    "coordinateTraceClearedHint",
                    true,
                    "streamMissClearedTrace",
                    true,
                    "fixedMissClearedTrace",
                    true,
                    "retainedLogs",
                    true,
                    "coordinateTraceRecovered",
                    true,
                    "sceneEpochReused",
                    true,
                    "viewOnly",
                    true)));
        System.out.println(
            "WORLD_LOD_PICKING_NATIVE_PASS real proxy and Only Changes hint / exact full-world"
                + " trace / native wheel refinement / miss clears current trace / coordinate"
                + " recovery / retained Logs / view-only");
      }
    }
  }

  private static Map<String, Boolean> coordinateWorkflow() throws Exception {
    var retained = views.probeInspectorTrace();
    int logCount = state.traces.size();
    long logBytes = state.traces.bytes();
    try (var hold = holdWorkers()) {
      control("trace-coordinate");
      frames(2);
      require(
          views.probeCoordinateTracePending()
              && views.probeInspectorTraceVisible()
              && views.probeCoordinateTraceFeedback().contains("Tracing X 28"),
          "Pending coordinate status or previous result missing");
      control("trace-cancel");
      require(
          !views.probeCoordinateTracePending() && state.background.getQueue().isEmpty(),
          "Cancel did not release queued coordinate work");
    }
    until(WorldLodPickingProbe::idle);
    frames(3);
    require(
        views.probeInspectorTrace() == retained
            && state.traces.size() == logCount
            && state.traces.bytes() == logBytes,
        "Canceled request published or lost prior trace");

    try (var hold = holdWorkers()) {
      control("trace-coordinate");
      require(views.probeCoordinateTracePending(), "Edit case did not queue coordinate work");
      editX(29);
      require(
          !views.probeCoordinateTracePending() && state.background.getQueue().isEmpty(),
          "XYZ edit did not cancel obsolete queued work");
      control("trace-coordinate");
      control("trace-coordinate");
      require(
          views.probeCoordinateTracePending() && state.background.getQueue().size() == 1,
          "Replacement accumulated queued coordinate jobs");
    }
    until(() -> !views.probeCoordinateTracePending() && views.probeInspectorTrace() != retained);
    frames(2);
    require(
        views.probeInspectorTrace().trace().position().equals(new BlockPos(29, 2, 2))
            && views.probeInspectorTrace().trace().finalType() == 1,
        "Replacement published the wrong coordinate");

    var beforeFailure = views.probeInspectorTrace();
    logCount = state.traces.size();
    logBytes = state.traces.bytes();
    editX(100);
    control("trace-coordinate");
    until(
        () ->
            !views.probeCoordinateTracePending()
                && views.probeCoordinateTraceFeedback().startsWith("Trace failed at X 100"));
    require(
        views.probeInspectorTrace() == beforeFailure
            && state.traces.size() == logCount
            && state.traces.bytes() == logBytes,
        "Runtime failure replaced prior trace or Logs");
    editX(29);
    retrace();
    require(
        views.probeCoordinateTraceFeedback().isEmpty(),
        "Runtime retry did not clear failure feedback");

    var beforeQueue = views.probeInspectorTrace();
    var queued = new ArrayList<Future<?>>();
    try (var hold = holdWorkers()) {
      for (int i = 0; i < 8; i++) queued.add(state.background.submit(() -> {}));
      control("trace-coordinate");
      require(
          !views.probeCoordinateTracePending()
              && views.probeCoordinateTraceFeedback().contains("queue is full")
              && views.probeInspectorTrace() == beforeQueue,
          "Queue rejection lacked stable retry state");
    }
    for (var future : queued) future.get(5, TimeUnit.SECONDS);
    retrace();
    require(views.probeCoordinateTraceFeedback().isEmpty(), "Queue retry did not clear feedback");
    var beforeTab = views.probeInspectorTrace();
    try (var hold = holdWorkers()) {
      control("trace-coordinate");
      views.probeNoiseTab(0);
      frames(2);
      require(
          views.probeCoordinateTracePending(),
          "Tab navigation canceled an explicit coordinate request");
    }
    until(() -> !views.probeCoordinateTracePending() && views.probeInspectorTrace() != beforeTab);
    views.probeNoiseTab(2);
    frames(2);
    return Map.of(
        "pendingVisible",
        true,
        "cancelReleasedQueue",
        true,
        "editCanceled",
        true,
        "replacementBounded",
        true,
        "runtimeFailureRetained",
        true,
        "runtimeRetry",
        true,
        "queueRejected",
        true,
        "queueRetry",
        true,
        "tabIndependent",
        true);
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
                    throw new AssertionError("Held worker timeout");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }));
    require(entered.await(5, TimeUnit.SECONDS), "Coordinate workers were not held");
    return new WorkerHold(release, workers);
  }

  private static boolean idle() {
    return state.background.getActiveCount() == 0 && state.background.getQueue().isEmpty();
  }

  private static void control(String name) {
    frames(2);
    var button = views.probeAdmissionControl(name);
    require(button != null, "No Inspector control " + name);
    clickPoint(button[0], button[1]);
  }

  private static void editX(int value) {
    control("trace-x");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(Integer.toString(value));
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(2);
    require(views.probeInspectorCoordinate().x() == value, "Native XYZ edit failed");
  }

  private static Map<String, Object> observe(String mode, int x, boolean proxy, int finalType) {
    var picked = views.probeWorldPicked();
    var trace = views.probeInspectorTrace();
    require(
        picked != null && picked.x() == x && picked.y() == 2 && picked.z() == 2,
        "Unexpected LOD picked cell");
    require(
        trace != null
            && trace.trace().position().equals(new BlockPos(x, 2, 2))
            && trace.trace().finalType() == finalType,
        "Exact trace disagrees with LOD coordinate");
    require(
        views.probeInspectorTraceVisible() && tracePanel.submittedRows() > 0,
        "Production exact TracePanel was not submitted");
    require(proxy == views.probeInspectorHintVisible(), "Proxy feedback visibility mismatch");
    return Map.of(
        "mode",
        mode,
        "x",
        picked.x(),
        "y",
        picked.y(),
        "z",
        picked.z(),
        "proxy",
        proxy,
        "hint",
        views.probeInspectorFeedback(),
        "finalType",
        trace.trace().finalType(),
        "traceRows",
        tracePanel.submittedRows());
  }

  private static boolean centerIs(int x, boolean proxy) {
    return views.probeWorldStreamReady(0)
        && views
            .probeWorldCenterPick()
            .filter(p -> p.position().equals(new BlockPos(x, 2, 2)) && p.displayProxy() == proxy)
            .isPresent();
  }

  private static void assertMiss(String message) {
    require(
        views.probeInspectorTrace() == null
            && views.probeWorldPicked() == null
            && !views.probeInspectorTraceVisible()
            && views.probeInspectorHintVisible()
            && views.probeInspectorFeedback().contains(message),
        "Miss displayed stale trace or lacked recovery feedback");
  }

  private static void retrace() {
    var previous = views.probeInspectorTrace();
    control("trace-coordinate");
    until(() -> views.probeInspectorTrace() != null && views.probeInspectorTrace() != previous);
    frames(2);
  }

  private static void fixture(ProjectData p) {
    p.project.minY = 0;
    p.project.maxY = 31;
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var plane = new Noise();
    plane.id = "plane";
    plane.dimension = 3;
    plane.algorithm.key = "CONSTANT";
    plane.algorithm.parameters.put("value", 0.);
    var expression = ProcessingRegistry.create("EXPRESSION", "plane-expression", 3);
    expression.parameters.put(
        "expression", ProjectIO.JSON.valueToTree("x == 100 ? 1/(x-100) : x == 28 ? 1 : -1"));
    plane.processing.add(expression);
    plane.outputBinding = Binding.earlier(expression.uuid);
    p.noise3D.add(plane);
    var all = new Noise();
    all.id = "all";
    all.dimension = 3;
    all.algorithm.key = "CONSTANT";
    all.algorithm.parameters.put("value", 1.);
    p.noise3D.add(all);
    for (int i = 0; i < 3; i++) {
      var layer = new Layer();
      layer.id = "stage-" + i;
      layer.defaults.action = i == 2 ? "CLEAR" : "SET";
      layer.defaults.voxelTypeUuid = type.uuid;
      var operation = new Operation();
      operation.id = "apply";
      operation.noiseGeneratorUuid = i == 1 ? all.uuid : plane.uuid;
      operation.interpretation = "DENSITY";
      layer.operations.add(operation);
      p.worldGenerator.layers.add(layer);
    }
  }

  private static void wheel(float amount) {
    views.probeHideInspector();
    frames(2);
    var center = views.probeAdmissionControl("image");
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseWheelEvent(0, amount);
    frame();
  }

  private static void clickImage(float offset) {
    views.probeHideInspector();
    frames(2);
    var center = views.probeAdmissionControl("image");
    clickPoint(center[0] + offset, center[1]);
  }

  private static void clickPoint(float x, float y) {
    var io = ImGui.getIO();
    io.addMousePosEvent(x, y);
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
            "LOD picking timeout: " + views.probeSourceStatus(2) + " / " + state.status);
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
