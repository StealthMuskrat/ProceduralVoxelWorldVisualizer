package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;

/** Exact cached scalar chunks, seam-spanning GPU texture and production camera/manual lifecycle. */
final class VolumeStreamingProbe {
  private static EditorState state;
  private static Viewports views;
  private static int maximum;

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    var report = new LinkedHashMap<String, Object>();
    report.put("formatVersion", 1);
    try (var cache = new VolumeWindows();
        var gl = new GlViewport()) {
      var bounds = new VolumeWindows.Bounds(64, -32, -32, -32);
      var window =
          cache.sample(
              cache.begin("affine", 1, 64L << 20),
              bounds,
              () -> (x, y, z) -> .5 + (x + 2 * y + 4 * z) / 512,
              new CancellationToken());
      var palette = new int[256];
      Arrays.fill(palette, 0xff0000ff);
      gl.setPalette(palette);
      require(gl.stageVolume(window.volume(), 2L << 20), "Streamed affine texture admission");
      int max = 0;
      long sum = 0;
      while (gl.volumeStatistics().pending()) {
        int bytes = gl.advanceVolume(16384);
        max = Math.max(max, bytes);
        sum += bytes;
        require(bytes <= 16384, "Streamed window upload budget");
      }
      var texels = BufferUtils.createFloatBuffer(64 * 64 * 64);
      glBindTexture(GL_TEXTURE_3D, gl.volumeResident().texture());
      glGetTexImage(GL_TEXTURE_3D, 0, GL_RED, GL_FLOAT, texels);
      float[] actual = new float[texels.remaining()];
      texels.get(actual);
      require(
          Arrays.equals(window.volume().copySamples(), actual), "Chunk seams changed GPU texels");
      var binary = ByteBuffer.allocate(actual.length * 4).order(ByteOrder.LITTLE_ENDIAN);
      for (float value : actual) binary.putFloat(value);
      Files.write(out.resolve("volume-window.bin"), binary.array());
      var camera = new Camera();
      camera.flying = true;
      camera.position.set(0, 0, 100);
      camera.yaw = camera.pitch = 0;
      gl.volumeAppearance(0, 1, .01, 0, 256);
      gl.render(65, 65, camera.matrix(1, new Vector3d(-32)), true, 1);
      var pixel = readPixel(gl, 65);
      double t = Math.exp(-.5 * .01 * 64);
      checkPixel(
          pixel,
          new double[] {255 * (1 - t + t * .055), 255 * t * .075, 255 * t * .1, 255},
          "Affine optics");
      report.put(
          "affine",
          Map.of(
              "bounds",
              bounds,
              "encoding",
              window.volume().encoding(),
              "pixel",
              pixel,
              "uploadBytes",
              sum,
              "maximumUploadBytes",
              max,
              "sampledChunks",
              window.statistics().sampledChunks()));
    }
    glfwShowWindow(handle);
    glfwFocusWindow(handle);
    glfwPollEvents();
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      maximum = 0;
      state.user.editor.opportunitiesPerSecond = 20;
      state.user.performance.sceneCpuMiB = 256;
      state.user.performance.sceneGpuMiB = 16;
      state.user.performance.uploadKiB = 16;
      state.user.performance.cacheMiB = 64;
      state.user.performance.maxPreviewDimension = 256;
      state.user.performance.previewCellBudget = 256L * 256 * 256;
      require(
          state.change(
              p -> {
                var noise = new Noise();
                noise.id = "streamed-volume";
                noise.dimension = 3;
                noise.algorithm.key = "CONSTANT";
                var item = ProcessingRegistry.create("EXPRESSION", "density", 3);
                item.parameters.put("expression", ProjectIO.JSON.valueToTree("1"));
                noise.processing.add(item);
                noise.outputBinding = Binding.earlier(item.uuid);
                p.noise3D.add(noise);
              }),
          "Streaming volume project");
      var id = state.project().noise3D.getFirst().uuid;
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      views = new Viewports(state, new PropertyPanel(state), handle);
      try {
        var stages = new ArrayList<Map<String, Object>>();
        views.probeVolumeStream(id, 64, 16, 16, 16);
        untilReady(-32, -32, -32);
        stages.add(stage("origin", 1));
        long generation = views.probeVolumeMemory().generation();
        require(
            views.probeVolumeWindow().sampledChunks() == 8,
            "Initial volume must sample eight chunks");
        travel(48, 16, 16, 0, -32, -32);
        stages.add(stage("positive-x", 1));
        require(
            views.probeVolumeWindow().sampledChunks() == 4
                && views.probeVolumeWindow().reusedChunks() == 4,
            "Positive volume overlap not reused");
        travel(-16, 16, 16, -64, -32, -32);
        stages.add(stage("negative-x", 1));
        travel(16, 16, 16, -32, -32, -32);
        stages.add(stage("return", 1));
        require(
            views.probeVolumeWindow().sampledChunks() == 0
                && views.probeVolumeWindow().reusedChunks() == 8,
            "Volume backtrack not reused");
        travel(16, 48, 16, -32, 0, -32);
        stages.add(stage("positive-y", 1));
        travel(16, 48, 48, -32, 0, 0);
        stages.add(stage("positive-z", 1));
        require(
            views.probeVolumeMemory().generation() == generation
                && views.probeVolumeMemory().compilations() == 1,
            "Travel rebuilt compiled volume source");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Volume travel changed document/history");
        report.put("navigationViewOnly", true);
        state.user.editor.autoRegenerate = false;
        require(
            state.change(
                p ->
                    p.noise3D
                        .getFirst()
                        .processing
                        .getFirst()
                        .parameters
                        .put("expression", ProjectIO.JSON.valueToTree("0"))),
            "Manual source edit");
        // The fixture explicitly edits the committed source; subsequent camera/transfer actions
        // stay view-only.
        before = ProjectIO.JSON.valueToTree(state.project());
        revision = state.revision;
        history = state.document.historyBytes();
        frames(2);
        untilReady(-32, 0, 0);
        travel(80, 48, 48, 32, 0, 0);
        stages.add(stage("manual-retained", 1));
        require(
            views.probeVolumeMemory().generation() == generation
                && views.probeVolumeMemory().compilations() == 1,
            "Manual travel changed accepted source");
        views.probeVolumeRegenerate();
        untilReady(32, 0, 0);
        stages.add(stage("regenerated", 0));
        require(
            views.probeVolumeMemory().generation() > generation
                && views.probeVolumeMemory().compilations() == 2,
            "Explicit source regeneration did not rebuild");
        long stamp = views.probeSubmissionStamp(1), epoch = views.probeVolumeMemory().generation();
        views.probeVolumeTransfer(.02, 1);
        frames(3);
        require(
            stamp == views.probeSubmissionStamp(1)
                && epoch == views.probeVolumeMemory().generation(),
            "Display transfer resampled volume");
        views.probeVolumeTransfer(.01, 0);
        // Abandon a complete CPU window during incremental GPU upload, then publish only its
        // successor.
        long accepted = views.probeVolumeStatistics().version();
        views.probeVolumeTravel(112, 48, 48);
        until(() -> views.probeVolumeStatistics().pending());
        var oldOrigin = views.probeVolumeOrigin();
        views.probeVolumeTravel(-80, 48, 48);
        untilReady(-128, 0, 0);
        require(
            views.probeVolumeStatistics().version() == accepted + 1
                && !views.probeVolumeOrigin().equals(oldOrigin),
            "Canceled streamed upload published");
        stages.add(stage("cancel-successor", 0));
        report.put("canceledUpload", true);
        var retained = views.probeVolumeOrigin();
        views.probeVolumeStream(id, 256, -80, 48, 48);
        until(() -> views.probeVolumeMessage().contains("GPU MiB"));
        require(
            views.probeVolumeOrigin().equals(retained) && !views.probeVolumeStatistics().pending(),
            "GPU rejection replaced streamed data");
        report.put("gpuRejected", true);
        views.probeVolumeStream(id, 64, -80, 48, 48);
        untilReady(-128, 0, 0);
        // Real full editor queue; failure retains the accepted GPU window and explicit retry
        // succeeds.
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var jobs = new ArrayList<Future<?>>();
        try {
          for (int i = 0; i < 2; i++)
            jobs.add(
                state.background.submit(
                    () -> {
                      entered.countDown();
                      try {
                        if (!release.await(20, TimeUnit.SECONDS))
                          throw new AssertionError("Queue timeout");
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
          require(entered.await(5, TimeUnit.SECONDS), "Volume queue workers not occupied");
          for (int i = 0; i < 8; i++) jobs.add(state.background.submit(() -> {}));
          views.probeVolumeRegenerate();
          until(() -> views.probeVolumeMessage().contains("queue full"));
          require(
              views.probeVolumeOrigin().equals(retained),
              "Queue failure discarded displayed volume");
        } finally {
          release.countDown();
          for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        views.probeVolumeRegenerate();
        untilReady(-128, 0, 0);
        report.put("queueRetry", true);
        generation = views.probeVolumeMemory().generation();
        state.user.editor.autoRegenerate = true;
        views.probeVolume(id, 16, -16);
        until(() -> views.probeNoiseReady(1) && views.probeVolumeWindow() == null);
        require(
            views.probeVolumeMemory().bytes() == 0, "Fixed analysis retained volume chunk cache");
        views.probeVolumeStream(id, 64, 16, 16, 16);
        untilReady(-32, -32, -32);
        stages.add(stage("stream-return", 0));
        require(
            views.probeVolumeMemory().generation() > generation
                && views.probeVolumeWindow().sampledChunks() == 8,
            "Return reused closed streaming source");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Volume navigation/retry changed authored state");
        report.put("postEditViewOnly", true);
        report.put("fixedReturn", true);
        report.put("transferReused", true);
        report.put("stages", stages);
        report.put("maximumUiBytes", maximum);
        new ProjectIO().save(out.resolve("volume-streaming-project.json"), state.project());
      } finally {
        views.close();
      }
    }
    require(glGetError() == GL_NO_ERROR, "Volume streaming GL error");
    Files.writeString(
        out.resolve("volume-streaming-native.json"), ProjectIO.JSON.writeValueAsString(report));
    glfwHideWindow(handle);
    System.out.println(
        "VOLUME_STREAMING_NATIVE_PASS canonical chunk cache / exact seam texels / analytical optics"
            + " / signed XYZ travel / manual source snapshot / transfer reuse / upload cancellation"
            + " / GPU-queue retry / fixed return / view-only");
  }

  private static void travel(double x, double y, double z, int ox, int oy, int oz) {
    views.probeVolumeTravel(x, y, z);
    untilReady(ox, oy, oz);
  }

  private static void untilReady(int x, int y, int z) {
    until(
        () -> views.probeNoiseReady(1) && views.probeVolumeOrigin().equals(new Vector3d(x, y, z)));
    frames(2);
  }

  private static Map<String, Object> stage(String name, int value) {
    var stats = views.probeVolumeWindow();
    var memory = views.probeVolumeMemory();
    var origin = views.probeVolumeOrigin();
    require(
        stats != null && stats.sampledChunks() + stats.reusedChunks() == 8,
        "Incomplete volume window");
    require(memory.bytes() <= memory.limit() && memory.bytes() <= 64L << 20, "Volume cache budget");
    var pixel = views.probeSurfacePixel();
    double t = Math.exp(-value * .01 * 64);
    int palette = Viewports.color(value * 2 - 1, "Heatmap", 0);
    double[] expected = new double[4];
    for (int c = 0; c < 3; c++)
      expected[c] =
          (1 - t) * ((palette >>> (8 * c)) & 255) + t * 255 * new double[] {.055, .075, .1}[c];
    expected[3] = 255;
    checkPixel(pixel, expected, "Volume " + name);
    return Map.of(
        "name",
        name,
        "value",
        value,
        "origin",
        List.of(origin.x, origin.y, origin.z),
        "pixel",
        pixel,
        "sampledChunks",
        stats.sampledChunks(),
        "reusedChunks",
        stats.reusedChunks(),
        "generation",
        memory.generation(),
        "compilations",
        memory.compilations(),
        "cacheBytes",
        memory.bytes(),
        "copiedBytes",
        views.probeVolumeStatistics().copiedBytes());
  }

  private static List<Integer> readPixel(GlViewport gl, int side) {
    var bytes = BufferUtils.createByteBuffer(side * side * 4);
    glBindTexture(GL_TEXTURE_2D, gl.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
    int i = (side / 2 * side + side / 2) * 4;
    return List.of(
        Byte.toUnsignedInt(bytes.get(i)),
        Byte.toUnsignedInt(bytes.get(i + 1)),
        Byte.toUnsignedInt(bytes.get(i + 2)),
        Byte.toUnsignedInt(bytes.get(i + 3)));
  }

  private static void checkPixel(List<Integer> actual, double[] expected, String label) {
    for (int c = 0; c < 4; c++)
      require(
          Math.abs(actual.get(c) - expected[c]) <= 2,
          label + ": " + actual + " expected " + Arrays.toString(expected));
  }

  private static void until(BooleanSupplier done) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!done.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end)
        throw new AssertionError("Streaming volume timeout: " + views.probeVolumeMessage());
      Thread.yield();
    }
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    glfwPollEvents();
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(950, 750, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(false, ImGuiCond.Always);
    views.draw();
    ImGui.render();
    maximum = Math.max(maximum, views.probeVolumeStatistics().lastBytes());
    require(
        views.probeVolumeStatistics().lastBytes() <= 16384,
        "UI streamed volume upload byte budget");
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
