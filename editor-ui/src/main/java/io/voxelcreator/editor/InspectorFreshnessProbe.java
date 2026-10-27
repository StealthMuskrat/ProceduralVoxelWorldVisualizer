package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Native committed-project cancellation and displayed-coordinate refresh, in an isolated state. */
final class InspectorFreshnessProbe {
  private static EditorState state;
  private static Viewports views;
  private static TracePanel panel;

  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toAbsolutePath().toString());
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      state.user.editor.opportunitiesPerSecond = 20;
      require(state.change(InspectorFreshnessProbe::fixture), "Freshness fixture rejected");
      var properties = new PropertyPanel(state);
      panel = new TracePanel(state, new GuardedNavigation(state, properties));
      try (var viewport = new Viewports(state, properties, window)) {
        views = viewport;
        views.tracePanel(panel);
        views.probeWorld(state.project().worldGenerator.layers.getFirst().uuid, "After", 8, .25);
        until(views::probeWorldReady);
        clickImage();
        until(views::probeInspectorOpen);
        frames(2);
        var original = views.probeInspectorTrace();
        var layerTarget =
            new EditorEvents.Target(
                state.project().project.uuid,
                state.project().worldGenerator.layers.getFirst().uuid,
                null,
                null,
                "",
                false);
        require(
            original.trace().position().equals(new BlockPos(3, 0, 0))
                && original.trace().finalType() == 1,
            "Initial occupied trace mismatch");
        state.user.editor.autoRegenerate = false;
        new ProjectIO().save(out.resolve("freshness-before.json"), state.project());
        editX(7);
        long stamp = views.probeSubmissionStamp(2);
        int count = state.traces.size();
        long bytes = state.traces.bytes();
        try (var hold = holdWorkers()) {
          control("trace-coordinate");
          require(
              views.probeCoordinateTracePending() && state.background.getQueue().size() == 1,
              "Old-project coordinate trace not queued");
          require(
              state.change(p -> p.worldGenerator.layers.getFirst().defaults.action = "CLEAR"),
              "Committed CLEAR edit rejected");
          frame();
          require(
              !views.probeCoordinateTracePending() && state.background.getQueue().isEmpty(),
              "Project change retained queued old work");
        }
        until(InspectorFreshnessProbe::idle);
        frames(2);
        require(
            views.probeInspectorTrace() == original
                && state.traces.size() == count
                && state.traces.bytes() == bytes,
            "Canceled old-project work replaced the trace or Logs");
        require(
            views.probeInspectorFreshness() == TraceSnapshot.Freshness.PROJECT_CHANGED
                && views.probeInspectorFreshnessNoticeVisible(),
            "Committed edit lacks stale-trace feedback");
        require(
            !original.available(state, layerTarget),
            "Historical trace allowed a changed source link");
        var changed = ProjectIO.JSON.valueToTree(state.project());
        long changedRevision = state.revision, changedHistory = state.document.historyBytes();
        control("trace-refresh");
        until(
            () -> views.probeInspectorTrace() != original && !views.probeCoordinateTracePending());
        frames(2);
        var refreshed = views.probeInspectorTrace();
        require(
            refreshed.trace().position().equals(new BlockPos(3, 0, 0))
                && refreshed.trace().finalType() == 0
                && refreshed.revision() == changedRevision,
            "Refresh did not evaluate the displayed coordinate in the new project revision");
        require(
            views.probeInspectorCoordinate().equals(new BlockPos(7, 0, 0))
                && views.probeInspectorDraftNoticeVisible(),
            "Refresh changed entered XYZ");
        require(
            views.probeInspectorFreshness() == TraceSnapshot.Freshness.CURRENT
                && !views.probeInspectorFreshnessNoticeVisible()
                && views.probeAdmissionControl("trace-refresh") == null,
            "Successful refresh retained stale state");
        require(
            original.trace().finalType() == 1
                && original.revision() != refreshed.revision()
                && state.traces.size() >= 2,
            "Refresh mutated or discarded the old snapshot");
        require(
            refreshed.available(state, layerTarget),
            "Refresh did not restore its current source link");
        require(
            changed.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == changedRevision
                && state.document.historyBytes() == changedHistory
                && stamp == views.probeSubmissionStamp(2),
            "Refresh changed authored state or resubmitted the manual preview");
        new ProjectIO().save(out.resolve("freshness-after.json"), state.project());

        var oldProject = state.project().project.uuid;
        state.newSession();
        frames(3);
        require(
            !oldProject.equals(state.project().project.uuid)
                && views.probeInspectorTrace() == refreshed
                && views.probeInspectorFreshness() == TraceSnapshot.Freshness.OTHER_PROJECT
                && views.probeInspectorFreshnessNoticeVisible()
                && views.probeAdmissionControl("trace-refresh") == null,
            "Foreign snapshot offered refresh or lost its historical scope");
        require(state.traces.size() == 0, "New project retained the old project's Logs payloads");
        require(
            !refreshed.available(state, layerTarget),
            "Foreign trace allowed old-project source navigation");
        var empty = ProjectIO.JSON.valueToTree(state.project());
        long emptyRevision = state.revision, emptyHistory = state.document.historyBytes();
        control("trace-coordinate");
        until(
            () -> views.probeInspectorTrace() != refreshed && !views.probeCoordinateTracePending());
        frames(2);
        var current = views.probeInspectorTrace();
        require(
            current.project().equals(state.project().project.uuid)
                && current.trace().position().equals(new BlockPos(7, 0, 0))
                && current.trace().finalType() == 0
                && views.probeInspectorFreshness() == TraceSnapshot.Freshness.CURRENT,
            "Explicit trace did not recover in the empty new project");
        require(
            views.probeInspectorTraceVisible()
                && panel.submittedRows() == 0
                && panel.submittedSteps() == 0
                && panel.goToCenter() == null,
            "Empty trace retained previous row/control telemetry");
        require(
            empty.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == emptyRevision
                && state.document.historyBytes() == emptyHistory,
            "New-project tracing changed authored state");
        new ProjectIO().save(out.resolve("freshness-empty.json"), state.project());
        Files.writeString(
            out.resolve("inspector-freshness-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("initial", List.of(3, 0, 0, original.trace().finalType())),
                    Map.entry("refreshed", List.of(3, 0, 0, refreshed.trace().finalType())),
                    Map.entry(
                        "entered",
                        List.of(
                            views.probeInspectorCoordinate().x(),
                            views.probeInspectorCoordinate().y(),
                            views.probeInspectorCoordinate().z())),
                    Map.entry("newProjectTrace", List.of(7, 0, 0, current.trace().finalType())),
                    Map.entry("projectChangeCanceled", true),
                    Map.entry("refreshPreservedDraft", true),
                    Map.entry("refreshViewOnly", true),
                    Map.entry("manualPreviewUnchanged", true),
                    Map.entry("oldSnapshotImmutable", true),
                    Map.entry("sourceLinksRefreshed", true),
                    Map.entry("foreignReadOnly", true),
                    Map.entry("foreignRefreshUnavailable", true),
                    Map.entry("newTraceViewOnly", true),
                    Map.entry("emptyTraceCountersReset", true))));
        System.out.println(
            "INSPECTOR_FRESHNESS_NATIVE_PASS queued project-change cancellation /"
                + " displayed-coordinate refresh / entered XYZ preserved / immutable prior result /"
                + " foreign read-only / new empty-project trace");
      }
    }
  }

  private static void fixture(ProjectData p) {
    p.project.minY = 0;
    p.project.maxY = 31;
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var field = new Noise();
    field.id = "constant";
    field.dimension = 3;
    field.algorithm.key = "CONSTANT";
    field.algorithm.parameters.put("value", 1.);
    p.noise3D.add(field);
    var layer = new Layer();
    layer.id = "world";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    var op = new Operation();
    op.id = "everywhere";
    op.noiseGeneratorUuid = field.uuid;
    op.interpretation = "DENSITY";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
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
                    throw new AssertionError("Held freshness worker timed out");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }));
    require(entered.await(5, TimeUnit.SECONDS), "Freshness workers were not held");
    return new WorkerHold(release, workers);
  }

  private static boolean idle() {
    return state.background.getActiveCount() == 0 && state.background.getQueue().isEmpty();
  }

  private static void control(String name) {
    frames(2);
    var p = views.probeAdmissionControl(name);
    require(p != null, "Missing freshness control " + name);
    click(p[0], p[1]);
  }

  private static void clickImage() {
    views.probeHideInspector();
    frames(2);
    var p = views.probeAdmissionControl("image");
    click(p[0], p[1]);
  }

  private static void click(float x, float y) {
    var io = ImGui.getIO();
    io.addMousePosEvent(x, y);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void editX(int x) {
    control("trace-x");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(Integer.toString(x));
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(2);
    require(views.probeInspectorCoordinate().x() == x, "Freshness XYZ edit failed");
  }

  private static void until(BooleanSupplier condition) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!condition.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end) throw new AssertionError("Freshness timeout: " + state.status);
      Thread.yield();
    }
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
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
