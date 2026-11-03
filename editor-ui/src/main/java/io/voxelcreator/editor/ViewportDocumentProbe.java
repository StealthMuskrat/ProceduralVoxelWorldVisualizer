package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.lwjgl.opengl.GL;

/** Deferred production GL/worker document-boundary replay; not the native file-dialog UX. */
public final class ViewportDocumentProbe {
  private static EditorState state;
  private static Viewports views;

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
      window = glfwCreateWindow(64, 64, "Document preview probe", 0, 0);
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
          state.user.visualization.streaming = false;
          state.user.visualization.unlit = true;
          state.user.visualization.grid = false;
          require(state.change(ViewportDocumentProbe::fixture), "Fixture rejected");
          var source = out.resolve("source.json");
          state.document.save(source);
          require(state.document.open(source).isEmpty(), "Source reopen failed");
          var replacement = ProjectIO.copy(state.project());
          replacement.noise2D.getFirst().algorithm.parameters.put("value", .5);
          replacement.noise3D.getFirst().algorithm.parameters.put("value", .75);
          replacement.worldGenerator.layers.getFirst().defaults.action = "CLEAR";
          var replacementFile = out.resolve("replacement.json");
          new ProjectIO().save(replacementFile, replacement);
          var replacementValues = ProjectIO.JSON.valueToTree(replacement);
          state.user.save(out.resolve("preferences-before.json"));
          var preferences = ProjectIO.JSON.valueToTree(state.user);
          var projectId = state.project().project.uuid;
          state.select("noise2D", state.project().noise2D.getFirst().uuid);
          var draft = state.draft;
          ((Noise) draft).id = "private draft";
          state.seedInputs.edit(state, "seedOffset", "Seed", "-");
          var privateValues = ProjectIO.JSON.valueToTree(draft);
          var properties = new PropertyPanel(state);
          try (var viewport = new Viewports(state, properties, window)) {
            views = viewport;
            views.tracePanel(new TracePanel(state, new GuardedNavigation(state, properties)));
            views.probeAdmissionSource(state.project().noise2D.getFirst().uuid);
            views.probeNoiseTab(0);
            until(views::probeAdmissionReady);
            var image = views.probeAcceptedImage();
            require(image.sample(.5, .5).raw() == .125, "Accepted source image mismatch");
            views.probeWorld(
                state.project().worldGenerator.layers.getFirst().uuid, "After", 8, .25);
            until(views::probeWorldReady);
            var occupiedPixel = views.probePreviewPixel(2);
            require(red(occupiedPixel), "Source world center is not occupied red");
            var before = views.probeDocumentViews();
            TraceSnapshot historical;
            var traceSource = ProjectIO.copy(state.project());
            try (var world =
                state.engine.openWorld(new io.voxelcreator.api.CompiledProject(traceSource))) {
              historical =
                  new TraceSnapshot(
                      state.document.sessionToken(),
                      traceSource,
                      state.revision,
                      world.trace(new BlockPos(2, 0, 0)));
            }
            long stamp = views.probeSubmissionStamp(2);
            require(
                state.change(p -> p.worldGenerator.layers.getFirst().defaults.action = "CLEAR"),
                "CLEAR edit rejected");
            frame();
            frame();
            require(
                stamp == views.probeSubmissionStamp(2)
                    && occupiedPixel.equals(views.probePreviewPixel(2)),
                "Same-document manual preview was replaced");
            new ProjectIO().save(out.resolve("committed.json"), state.project());
            long revision = state.revision;
            List<Map<String, Object>> retired;
            List<Integer> pendingPixel;
            long replacementStamp;
            try (var hold = holdWorkers()) {
              views.probeTraceCoordinate(new BlockPos(1, 0, 0));
              require(views.probeCoordinateTracePending(), "Old Inspector request not queued");
              require(state.document.open(replacementFile).isEmpty(), "Replacement open failed");
              require(
                  projectId.equals(state.project().project.uuid) && revision == state.revision,
                  "Fixture must reuse UUID and editor revision");
              frame();
              retired = views.probeDocumentViews();
              requireRetired(before, retired);
              require(
                  ((List<?>) views.probeWorldPalette().get("types")).isEmpty()
                      && ((List<?>) views.probeWorldPalette().get("colors")).isEmpty(),
                  "Document replacement retained a source palette binding");
              require(
                  !views.probeCoordinateTracePending()
                      && !views.probeInspectorOpen()
                      && views.probeInspectorTrace() == null
                      && state.traces.size() == 0,
                  "Old Inspector crossed the document boundary");
              replacementStamp = views.probeSubmissionStamp(2);
              require(
                  replacementStamp > 0 && replacementStamp != stamp,
                  "Manual new-document first request was not admitted");
              pendingPixel = views.probePreviewPixel(2);
              require(!red(pendingPixel), "Old World pixels retained while replacement is pending");
            }
            until(views::probeWorldReady);
            frame();
            var emptyPixel = views.probePreviewPixel(2);
            require(
                !red(emptyPixel) && state.traces.size() == 0,
                "Replacement retained old World or trace data");
            require(
                replacementValues.equals(ProjectIO.JSON.valueToTree(state.project())),
                "Preview changed replacement project");
            new ProjectIO().save(out.resolve("replacement-after.json"), state.project());

            var entered = views.probeInspectorCoordinate();
            until(
                () ->
                    state.background.getActiveCount() == 0
                        && state.background.getQueue().isEmpty());
            var fillers = new ArrayList<Future<?>>();
            try (var hold = holdWorkers()) {
              int remaining = state.background.getQueue().remainingCapacity();
              for (int i = 0; i < remaining; i++) fillers.add(state.background.submit(() -> {}));
              views.inspectTrace(TraceCoordinateIntent.capture(state, historical));
              frame();
              require(
                  !views.probeCoordinateTracePending()
                      && new BlockPos(2, 0, 0).equals(views.probeInspectorRetry())
                      && views.probeInspectorTrace() == historical
                      && state.traces.size() == 0,
                  "Queue rejection did not retain the exact failed Logs target");
            }
            for (var filler : fillers) filler.get(5, TimeUnit.SECONDS);
            require(
                new BlockPos(2, 0, 0).equals(views.probeInspectorRetry()),
                "Queue recovery retried without user action");
            clickRetry();
            until(
                () ->
                    !views.probeCoordinateTracePending()
                        && views.probeInspectorTrace() != historical);
            var logsTrace = views.probeInspectorTrace();
            require(
                logsTrace != null
                    && logsTrace.trace().position().equals(new BlockPos(2, 0, 0))
                    && logsTrace.trace().finalType() == 0
                    && logsTrace.freshness(state) == TraceSnapshot.Freshness.CURRENT
                    && entered.equals(new BlockPos(1, 0, 0))
                    && entered.equals(views.probeInspectorCoordinate()),
                "Logs coordinate did not use the current document while preserving entered XYZ");
            int retained = state.traces.size();
            require(
                retained == 1 && views.probeInspectorRetry() == null,
                "Explicit retry did not publish exactly one trace and clear recovery");

            // Complete a request but leave its UI callback queued. Desktop drains callbacks before
            // Viewports observes the replacement, so the request's token must independently reject
            // it.
            until(
                () ->
                    state.background.getActiveCount() == 0
                        && state.background.getQueue().isEmpty());
            state.drain();
            views.inspectTrace(TraceCoordinateIntent.capture(state, historical));
            waitWithoutDrain(() -> !state.uiQueue.isEmpty());
            require(state.document.open(source).isEmpty(), "Second same-UUID reopen failed");
            state.drain();
            require(
                state.traces.size() == retained && views.probeInspectorTrace() == historical,
                "Queued old-document publication escaped its token gate");
            frame();
            until(views::probeWorldReady);
            require(red(views.probePreviewPixel(2)), "New source did not rebuild after reopen");
            state.document.newProject();
            frame();
            until(views::probeWorldReady);
            var afterNew = views.probeDocumentViews();
            require(
                !red(views.probePreviewPixel(2))
                    && state.project().noise2D.isEmpty()
                    && state.project().worldGenerator.layers.isEmpty(),
                "New document retained source content");
            require(
                revision == state.revision
                    && state.traces.size() == retained
                    && image.sample(.5, .5).raw() == .125,
                "Document isolation changed revision, Logs or immutable prior image");
            require(
                state.draft == draft
                    && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                    && "-".equals(state.seedInputs.text("seedOffset")),
                "Direct session fixture changed private Properties input");
            require(
                preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
                "Preview retirement changed preferences");
            new ProjectIO().save(out.resolve("empty.json"), state.project());
            state.user.save(out.resolve("preferences.json"));
            var report = new LinkedHashMap<String, Object>();
            report.put("formatVersion", 1);
            report.put("fixture", "direct-document-session-native-gl");
            report.put("before", before);
            report.put("retired", retired);
            report.put("afterNew", afterNew);
            report.put("occupiedPixel", occupiedPixel);
            report.put("pendingPixel", pendingPixel);
            report.put("emptyPixel", emptyPixel);
            report.put("oldSubmission", stamp);
            report.put("replacementSubmission", replacementStamp);
            report.put("logsCoordinate", List.of(2, 0, 0, logsTrace.trace().finalType()));
            report.put("enteredCoordinate", List.of(entered.x(), entered.y(), entered.z()));
            report.put("retainedTraces", retained);
            for (var key :
                List.of(
                    "manualSameDocumentRetained",
                    "sameUuidRevisionReplacement",
                    "sourcePaletteRetired",
                    "logsCoordinateTraced",
                    "queueRetryExactTarget",
                    "pendingInspectorCanceled",
                    "queuedPublicationRejectedBeforeDraw",
                    "immutableImageRetained",
                    "privateFixturePreserved",
                    "preferencesPreserved")) report.put(key, true);
            report.put("physicalDpiVerified", false);
            report.put("fileDialogUxVerified", false);
            Files.writeString(
                out.resolve("viewport-document-native.json"),
                ProjectIO.JSON.writeValueAsString(report));
            System.out.println(
                "VIEWPORT_DOCUMENT_NATIVE_PASS document retirement / manual retention / worker and"
                    + " UI callback ownership");
          }
        }
        InspectorWorkerRecoveryProbe.run(out.resolve("inspector-worker"), window);
      } finally {
        ImGui.destroyContext();
      }
    } finally {
      if (window != 0) glfwDestroyWindow(window);
      glfwTerminate();
    }
  }

  private static void requireRetired(
      List<Map<String, Object>> before, List<Map<String, Object>> after) {
    for (int i = 0; i < 3; i++) {
      var row = after.get(i);
      require(
          ((Number) row.get("epoch")).longValue()
              > ((Number) before.get(i).get("epoch")).longValue(),
          "Document epoch not retired");
      require(
          row.get("noise") == null
              && row.get("stage") == null
              && row.get("stageMode").equals("Final"),
          "Document selection retained");
      for (var key :
          List.of(
              "force",
              "scene",
              "volumeSnapshot",
              "pendingVolume",
              "acceptedImage",
              "acceptedSlice",
              "completed")) require(Boolean.FALSE.equals(row.get(key)), "Retained " + key);
      require(
          ((Number) row.get("cacheBytes")).longValue() == 0
              && ((Number) row.get("cacheRows")).intValue() == 0,
          "Retained image rows");
    }
  }

  private static void clickRetry() {
    frame();
    var point = views.probeAdmissionControl("trace-refresh");
    require(point != null, "Missing Retry Trace action");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void fixture(ProjectData p) {
    p.project.minY = 0;
    p.project.maxY = 31;
    var two = new Noise();
    two.id = "constant-2";
    two.algorithm.key = "CONSTANT";
    two.algorithm.parameters.put("value", .125);
    p.noise2D.add(two);
    var three = new Noise();
    three.id = "constant-3";
    three.dimension = 3;
    three.algorithm.key = "CONSTANT";
    three.algorithm.parameters.put("value", .25);
    p.noise3D.add(three);
    var type = new VoxelType();
    type.id = "solid";
    type.color.r = 255;
    type.color.g = type.color.b = 0;
    p.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "world";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    var op = new Operation();
    op.id = "everywhere";
    op.noiseGeneratorUuid = three.uuid;
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

  private static boolean red(List<Integer> rgba) {
    return rgba.get(0) > 100 && rgba.get(1) < 40 && rgba.get(2) < 40;
  }

  private static void waitWithoutDrain(BooleanSupplier ready) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!ready.getAsBoolean()) {
      if (System.nanoTime() > end) throw new AssertionError("UI callback timeout");
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
