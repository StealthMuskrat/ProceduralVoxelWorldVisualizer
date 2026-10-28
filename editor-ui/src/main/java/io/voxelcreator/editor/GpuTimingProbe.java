package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/**
 * Real timestamps, independent receipt exports, foreign query ownership and production capture UI.
 */
final class GpuTimingProbe {
  private static final Profiler.Options OPTIONS = new Profiler.Options(60000, 4096, 128, 65536);
  private static final UUID PROJECT = new UUID(0, 76);
  private static EditorState state;
  private static Viewports views;
  private static ProfilePanel panel;

  static void run(Path out) throws Exception {
    run(out, 0);
  }

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    var report = new LinkedHashMap<String, Object>();
    report.put("formatVersion", 1);
    report.put("glVersion", glGetString(GL_VERSION));
    report.put("openGL31", GL.getCapabilities().OpenGL31);
    int bits = new GlTimestampQueries().counterBits();
    boolean supported = bits >= 30 && bits <= 64;
    report.put("supported", supported);
    report.put("counterBits", bits);
    var receipts = new ArrayList<GpuTimerQueries.Measurement>();
    var frames = new ArrayList<Map<String, Object>>();
    try (var profiler = new Profiler();
        var gl = new GlViewport()) {
      int[] colors = new int[256];
      Arrays.fill(colors, 0xff000000);
      colors[128] = 0xff0000ff;
      gl.setPalette(colors);
      require(
          gl.gpuTimingStatistics().allocatedQueries() == 0, "Disabled timing allocated queries");
      var capture = profiler.start(OPTIONS);
      var scope = profiler.scope(0, PROJECT, null);
      var camera = new Camera();
      camera.flying = true;
      camera.yaw = camera.pitch = 0;
      for (String mode : List.of("fixed-mesh", "volume", "streamed-scene")) {
        camera.position.set(4, 4, 25);
        if (mode.equals("fixed-mesh"))
          gl.setMesh(MeshBuilder.isosurface(8, (x, y, z) -> z - 4.257, 0));
        else if (mode.equals("volume")) {
          gl.setVolume(VolumeData.sample(8, 0, 0, 0, (x, y, z) -> 1));
          gl.volumeAppearance(0, 1, .3, 0, 192);
        } else {
          camera.position.set(16, 16, 96);
          var gpu = gl.scene(7, 1 << 20);
          var pos = new ChunkPos(0, 0, 0);
          var mesh =
              VoxelMesher.greedy(
                  32,
                  (x, y, z) -> x >= 0 && y >= 0 && z >= 0 && x < 32 && y < 32 && z < 32 ? 128 : 0);
          require(gpu.stage(new ChunkScene.Upload(7, pos, 1, mesh)), "Timing scene admission");
          for (int i = 0; i < 1000 && gpu.pending(); i++) gpu.advance(4096);
          require(!gpu.pending(), "Timing scene upload");
          gl.sceneView(new Vector3d(), Set.of(pos), false, false, 0);
        }
        var matrix = camera.matrix(1, new Vector3d());
        gl.render(65, 65, matrix, true, 1);
        byte[] reference = read(gl);
        int foreign = 0;
        if (supported) {
          foreign = glGenQueries();
          glBeginQuery(GL_TIME_ELAPSED, foreign);
        }
        gl.render(65, 65, matrix, true, 1, scope, mode);
        if (supported) {
          require(
              glGetQueryi(GL_TIME_ELAPSED, GL_CURRENT_QUERY) == foreign,
              "Foreign elapsed query stolen");
          glEndQuery(GL_TIME_ELAPSED);
          glDeleteQueries(foreign);
        }
        glFlush();
        if (supported) receipts.addAll(await(gl, scope));
        else
          require(gl.collectGpuTimings(scope).isEmpty(), "Unsupported context invented GPU sample");
        require(Arrays.equals(reference, read(gl)), "Timing changed " + mode + " framebuffer");
        require(
            gl.gpuTimingStatistics().allocatedQueries() <= 2 * GpuTimerQueries.CAPACITY,
            "Query allocation bound");
        frames.add(
            Map.of("name", mode, "framebufferHash", hash(reference), "pixel", pixel(reference)));
      }
      profiler.stop();
      gl.collectGpuTimings(null);
      require(
          gl.gpuTimingStatistics().pending() == 0
              && gl.gpuTimingStatistics().allocatedQueries() == 0,
          "Stop retained queries");
      ProfileIO.writeJSON(out.resolve("gpu-capture.json"), capture.snapshot());
      ProfileIO.writeCSV(out.resolve("gpu-capture.csv"), capture.snapshot());
      report.put("receipts", receipts);
      report.put("frames", frames);
      report.put("stoppedStatistics", gl.gpuTimingStatistics());
      var old = profiler.start(OPTIONS);
      var oldScope = profiler.scope(0, PROJECT, null);
      gl.render(65, 65, camera.matrix(1, new Vector3d()), true, 1, oldScope, "old capture");
      var next = profiler.start(OPTIONS);
      var nextScope = profiler.scope(0, PROJECT, null);
      require(gl.collectGpuTimings(nextScope).isEmpty(), "Old capture results crossed ownership");
      require(
          old.snapshot().samples().isEmpty() && next.snapshot().samples().isEmpty(),
          "Replacement polluted captures");
      gl.render(65, 65, camera.matrix(1, new Vector3d()), true, 1, nextScope, "replacement");
      glFlush();
      var replacement = supported ? await(gl, nextScope) : List.<GpuTimerQueries.Measurement>of();
      profiler.stop();
      gl.collectGpuTimings(null);
      ProfileIO.writeJSON(out.resolve("replacement-capture.json"), next.snapshot());
      ProfileIO.writeCSV(out.resolve("replacement-capture.csv"), next.snapshot());
      report.put("replacementReceipts", replacement);
      report.put("captureReplaced", true);
      var stopped = profiler.start(OPTIONS);
      var stopScope = profiler.scope(0, PROJECT, null);
      gl.render(65, 65, camera.matrix(1, new Vector3d()), true, 1, stopScope, "discard on stop");
      profiler.stop();
      gl.collectGpuTimings(null);
      require(stopped.snapshot().samples().isEmpty(), "Unfinished stopped query published");
      require(gl.gpuTimingStatistics().allocatedQueries() == 0, "Stopped pair retained");
      report.put("stopDiscarded", true);
    }
    if (supported) {
      // Observe real driver object retirement as well as bounded model statistics.
      var names = new ArrayList<Integer>();
      var real = new GlTimestampQueries();
      var backend =
          new GpuTimerQueries.Backend() {
            public int counterBits() {
              return real.counterBits();
            }

            public int create() {
              int q = real.create();
              names.add(q);
              return q;
            }

            public void timestamp(int q) {
              real.timestamp(q);
            }

            public boolean available(int q) {
              return real.available(q);
            }

            public long result(int q) {
              return real.result(q);
            }

            public void delete(int q) {
              real.delete(q);
            }
          };
      try (var profiler = new Profiler();
          var queries = new GpuTimerQueries(backend)) {
        profiler.start(OPTIONS);
        var scope = profiler.scope(0, PROJECT, null);
        long token = queries.begin(scope, "close pending");
        queries.end(token, true);
        require(
            names.size() == 2 && names.stream().allMatch(q -> glIsQuery(q)),
            "Driver timestamp pair missing");
        queries.close();
        require(names.stream().noneMatch(q -> glIsQuery(q)), "Driver query names survived close");
      }
      report.put("driverNamesDeleted", true);
    } else report.put("driverNamesDeleted", true);
    if (handle != 0) production(out, handle, supported, report);
    require(glGetError() == GL_NO_ERROR, "GPU timing GL error");
    Files.writeString(
        out.resolve("gpu-timing-native.json"), ProjectIO.JSON.writeValueAsString(report));
    System.out.println(
        "GPU_TIMING_NATIVE_PASS asynchronous timestamp receipts / capture-bound exports /"
            + " disabled-capability fallback / foreign elapsed-query ownership / identical pixels /"
            + " driver name cleanup / "
            + glGetString(GL_VERSION));
  }

  private static void production(
      Path out, long handle, boolean supported, Map<String, Object> report) throws Exception {
    glfwShowWindow(handle);
    glfwFocusWindow(handle);
    glfwPollEvents();
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      state.user.editor.opportunitiesPerSecond = 20;
      require(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "gpu-timing";
                n.dimension = 3;
                n.algorithm.key = "CONSTANT";
                var item = ProcessingRegistry.create("EXPRESSION", "density", 3);
                item.parameters.put("expression", ProjectIO.JSON.valueToTree("1"));
                n.processing.add(item);
                n.outputBinding = Binding.earlier(item.uuid);
                p.noise3D.add(n);
              }),
          "GPU timing project");
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      views = new Viewports(state, new PropertyPanel(state), handle);
      panel = new ProfilePanel(state, target -> {});
      try {
        var capture = state.engine.profiler().start(OPTIONS);
        views.probeVolume(state.project().noise3D.getFirst().uuid, 16, 0);
        until(
            () ->
                views.probeNoiseReady(1)
                    && (!supported
                        || capture.snapshot().samples().stream()
                            .anyMatch(s -> s.kind() == Profiler.Kind.GPU_DRAW)));
        long stamp = views.probeSubmissionStamp(1),
            version = views.probeVolumeStatistics().version();
        for (int i = 0; i < 3; i++) frame();
        ImGui.newFrame();
        panel.draw(
            new ImBoolean(true),
            views.streamingStatistics(),
            views::compactArenas,
            views.gpuTimingStatistics());
        ImGui.render();
        panel.assertSmokeDrawn();
        state.engine.profiler().stop();
        panel.frame(1f / 60, views);
        require(
            views.gpuTimingStatistics().stream()
                .allMatch(s -> s.pending() == 0 && s.allocatedQueries() == 0),
            "Hidden/visible view query cleanup");
        var snapshot = capture.snapshot();
        if (supported)
          require(
              !ProfilePanel.filter(snapshot, Profiler.Kind.GPU_DRAW, "3D Noise").isEmpty(),
              "GPU metric missing in production filter");
        require(
            stamp == views.probeSubmissionStamp(1)
                && version == views.probeVolumeStatistics().version(),
            "Profiling resampled accepted volume");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Profiling changed authored state/history");
        ProfileIO.writeJSON(out.resolve("ui-capture.json"), snapshot);
        ProfileIO.writeCSV(out.resolve("ui-capture.csv"), snapshot);
        report.put(
            "productionGpuSamples",
            snapshot.samples().stream().filter(s -> s.kind() == Profiler.Kind.GPU_DRAW).count());
        report.put("productionStatistics", views.gpuTimingStatistics());
        report.put("profileUiDrawn", true);
        report.put("viewOnly", true);
        new ProjectIO().save(out.resolve("gpu-timing-project.json"), state.project());
      } finally {
        views.close();
      }
    }
    glfwHideWindow(handle);
  }

  private static List<GpuTimerQueries.Measurement> await(GlViewport gl, Profiler.Scope scope) {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var rows = gl.collectGpuTimings(scope);
      if (!rows.isEmpty()) return rows;
      require(
          gl.gpuTimingStatistics().state() == GpuTimerQueries.State.READY,
          "GPU timer failed: " + gl.gpuTimingStatistics());
      Thread.yield();
    }
    throw new AssertionError("GPU timestamp result timeout: " + gl.gpuTimingStatistics());
  }

  private static byte[] read(GlViewport gl) {
    var b = BufferUtils.createByteBuffer(65 * 65 * 4);
    glBindTexture(GL_TEXTURE_2D, gl.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
    byte[] out = new byte[b.remaining()];
    b.get(out);
    return out;
  }

  private static List<Integer> pixel(byte[] b) {
    int i = (32 * 65 + 32) * 4;
    return List.of(
        Byte.toUnsignedInt(b[i]),
        Byte.toUnsignedInt(b[i + 1]),
        Byte.toUnsignedInt(b[i + 2]),
        Byte.toUnsignedInt(b[i + 3]));
  }

  private static String hash(byte[] b) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
  }

  private static void until(BooleanSupplier done) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!done.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end) throw new AssertionError("GPU production capture timeout");
      Thread.yield();
    }
  }

  private static void frame() {
    glfwPollEvents();
    state.drain();
    panel.frame(1f / 60, views);
    ImGui.newFrame();
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(520, 720, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(false, ImGuiCond.Always);
    views.draw();
    ImGui.render();
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
