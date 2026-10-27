package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiCond;
import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
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
                    1,
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
    var button = views.probeAdmissionControl("trace-coordinate");
    require(button != null, "No Trace Coordinate control");
    clickPoint(button[0], button[1]);
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
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x == 28 ? 1 : -1"));
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
