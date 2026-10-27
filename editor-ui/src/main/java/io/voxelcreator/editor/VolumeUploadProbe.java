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
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;

/** Bounded texture writes, exact texel readback and production upload ownership. */
final class VolumeUploadProbe {
  private static EditorState state;
  private static Viewports views;
  private static int maxUi;

  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    var report = new LinkedHashMap<String, Object>();
    report.put("formatVersion", 1);
    int activeId, pendingId;
    var viewport = new GlViewport();
    try {
      var palette = new int[256];
      Arrays.fill(palette, 0xff0000ff);
      viewport.setPalette(palette);
      var empty = VolumeData.sample(4, 0, 0, 0, (x, y, z) -> 0, new CancellationToken());
      viewport.setVolume(empty);
      viewport.volumeAppearance(0, 1, .1, 0, 256);
      var camera = new Camera();
      camera.flying = true;
      camera.position.set(8.5, 8.5, 30);
      camera.yaw = camera.pitch = 0;
      var matrix = camera.matrix(1, new Vector3d());
      viewport.render(65, 65, matrix, true, 1);
      byte[] retained = read(viewport, 65);
      var field = VolumeData.sample(17, 0, 0, 0, (x, y, z) -> x / 17, new CancellationToken());
      long bytes = 4L * 17 * 17 * 17;
      activeId = viewport.volumeResident().texture();
      require(
          !viewport.stageVolume(field, bytes + empty.copySamples().length * 4L - 1),
          "GPU admission accepted undersized budget");
      require(
          viewport.volumeResident().texture() == activeId && !viewport.volumeStatistics().pending(),
          "Rejected upload replaced resident");
      // Nondefault unpack state and a bound PBO must neither corrupt uploads nor leak changes.
      int pbo = glGenBuffers();
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
      glBufferData(GL_PIXEL_UNPACK_BUFFER, 64, GL_STATIC_DRAW);
      glPixelStorei(GL_UNPACK_ALIGNMENT, 8);
      glPixelStorei(GL_UNPACK_ROW_LENGTH, 19);
      glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 23);
      glPixelStorei(GL_UNPACK_SKIP_PIXELS, 1);
      glPixelStorei(GL_UNPACK_SKIP_ROWS, 2);
      glPixelStorei(GL_UNPACK_SKIP_IMAGES, 3);
      glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_TRUE);
      require(viewport.stageVolume(field, bytes + 256), "Exact volume admission");
      var writes = new ArrayList<Integer>();
      long version = viewport.volumeStatistics().version();
      while (viewport.volumeStatistics().pending()) {
        int written = viewport.advanceVolume(79);
        writes.add(written);
        require(written <= 79 && written % 4 == 0, "Volume per-frame byte bound");
        require(
            glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING) == pbo
                && glGetInteger(GL_UNPACK_ALIGNMENT) == 8
                && glGetInteger(GL_UNPACK_ROW_LENGTH) == 19
                && glGetInteger(GL_UNPACK_IMAGE_HEIGHT) == 23
                && glGetInteger(GL_UNPACK_SKIP_PIXELS) == 1
                && glGetInteger(GL_UNPACK_SKIP_ROWS) == 2
                && glGetInteger(GL_UNPACK_SKIP_IMAGES) == 3
                && glGetInteger(GL_UNPACK_SWAP_BYTES) == GL_TRUE,
            "Unpack state not restored");
        if (viewport.volumeStatistics().pending()) {
          require(viewport.volumeStatistics().version() == version, "Partial volume published");
          viewport.render(97, 97, matrix, true, 1);
          require(
              Arrays.equals(retained, read(viewport, 65)),
              "Partial upload changed retained framebuffer");
        }
      }
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
      glDeleteBuffers(pbo);
      glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
      glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
      glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
      glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
      glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
      glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
      glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE);
      require(!glIsTexture(activeId), "Replaced texture leaked");
      var resident = viewport.volumeResident();
      var texels = BufferUtils.createFloatBuffer(17 * 17 * 17);
      glBindTexture(GL_TEXTURE_3D, resident.texture());
      glGetTexImage(GL_TEXTURE_3D, 0, GL_RED, GL_FLOAT, texels);
      float[] actual = new float[texels.remaining()];
      texels.get(actual);
      require(Arrays.equals(field.copySamples(), actual), "Encoded texel readback differs");
      viewport.render(65, 65, matrix, true, 1);
      var pixel = pixel(read(viewport, 65), 65);
      double t = Math.exp(-.5 * .1 * 17);
      double[] expected = {255 * (1 - t + t * .055), 255 * t * .075, 255 * t * .1, 255};
      for (int c = 0; c < 4; c++)
        require(
            Math.abs(pixel.get(c) - expected[c]) <= 2,
            "Uploaded volume analytical optics: " + pixel);
      viewport.stageVolume(empty, bytes + 256);
      viewport.advanceVolume(4);
      viewport.cancelVolume();
      require(
          viewport.volumeResident().texture() == resident.texture()
              && !viewport.volumeStatistics().pending(),
          "Cancel replaced accepted texture");
      viewport.stageVolume(empty, bytes + 256);
      activeId = resident.texture();
      pendingId = glGetInteger(GL_TEXTURE_BINDING_3D);
      // The backend leaves the 3D binding neutral; close assertions use resident plus GL object
      // count semantics in contracts.
      require(pendingId == 0, "Volume allocation binding leak");
      report.put("side", 17);
      report.put("byteBudget", 79);
      report.put("writes", writes);
      report.put("texels", actual);
      report.put("encoding", field.encoding());
      report.put("pixel", pixel);
      report.put("transmittance", t);
      report.put("retainedHash", hash(retained));
      report.put("unpackRestored", true);
      report.put("rejectionRetained", true);
      report.put("cancelRetained", true);
    } finally {
      viewport.close();
    }
    require(
        viewport.volumeResident() == null && viewport.volumeStatistics().pendingBytes() == 0,
        "Close retained volume resources");
    require(!glIsTexture(activeId), "Closed resident texture leaked");
    report.put("closed", true);

    glfwShowWindow(window);
    glfwFocusWindow(window);
    glfwPollEvents();
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      maxUi = 0;
      state.user.performance.sceneCpuMiB = 256;
      state.user.performance.sceneGpuMiB = 16;
      state.user.performance.uploadKiB = 16;
      state.user.performance.previewCellBudget = 256 * 256 * 256;
      state.user.performance.maxPreviewDimension = 256;
      state.user.editor.opportunitiesPerSecond = 20;
      require(
          state.change(
              p -> {
                for (int i = 0; i < 2; i++) {
                  var noise = new Noise();
                  noise.id = i == 0 ? "filled" : "empty";
                  noise.dimension = 3;
                  noise.algorithm.key = "CONSTANT";
                  var item = ProcessingRegistry.create("EXPRESSION", "density", 3);
                  item.parameters.put("expression", ProjectIO.JSON.valueToTree(i == 0 ? "1" : "0"));
                  noise.processing.add(item);
                  noise.outputBinding = Binding.earlier(item.uuid);
                  p.noise3D.add(noise);
                }
              }),
          "Volume upload project");
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var filled = state.project().noise3D.getFirst().uuid;
      var empty = state.project().noise3D.getLast().uuid;
      views = new Viewports(state, new PropertyPanel(state), window);
      try {
        views.probeVolume(filled, 32, 16);
        until(() -> views.probeNoiseReady(1));
        long accepted = views.probeVolumeStatistics().version();
        require(views.probeVolumeOrigin().equals(new Vector3d()), "Initial accepted volume origin");
        views.probeVolume(empty, 32, 48);
        until(() -> views.probeVolumeStatistics().pending());
        var oldOrigin = views.probeVolumeOrigin();
        var held = views.probeSurfacePixel();
        frame();
        require(
            views.probeVolumeStatistics().pending()
                && views.probeVolumeStatistics().version() == accepted
                && views.probeVolumeOrigin().equals(oldOrigin)
                && held.equals(views.probeSurfacePixel()),
            "Incomplete UI upload changed display metadata/pixels");
        views.probeVolume(filled, 16, -16);
        require(
            !views.probeVolumeStatistics().pending(), "Request change retained obsolete upload");
        until(() -> views.probeNoiseReady(1));
        require(
            views.probeVolumeOrigin().equals(new Vector3d(-24)),
            "Canceled request changed accepted origin");
        long complete = views.probeVolumeStatistics().version();
        views.probeVolume(filled, 256, -16);
        until(() -> views.probeVolumeMessage().contains("GPU MiB"));
        require(
            !views.probeVolumeStatistics().pending()
                && views.probeVolumeStatistics().version() == complete,
            "GPU rejection published a candidate");
        require(
            views.probeVolumeOrigin().equals(new Vector3d(-24)), "Rejected request changed origin");
        state.user.performance.sceneGpuMiB = 32;
        views.probeVolume(empty, 32, 48);
        until(() -> views.probeNoiseReady(1));
        require(
            views.probeVolumeOrigin().equals(new Vector3d(32)),
            "Retry did not publish matching origin");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Volume upload changed project/history");
        report.put("uiMaxBytes", maxUi);
        report.put(
            "uiVersions", List.of(accepted, complete, views.probeVolumeStatistics().version()));
        report.put(
            "uiOrigins", List.of(List.of(0, 0, 0), List.of(-24, -24, -24), List.of(32, 32, 32)));
        report.put("uiCanceled", true);
        report.put("uiGpuRejected", true);
        report.put("viewOnly", true);
        new ProjectIO().save(out.resolve("volume-upload-project.json"), state.project());
      } finally {
        views.close();
      }
    }
    require(glGetError() == GL_NO_ERROR, "Volume upload GL error");
    Files.writeString(
        out.resolve("volume-upload-native.json"), ProjectIO.JSON.writeValueAsString(report));
    glfwHideWindow(window);
    System.out.println(
        "VOLUME_UPLOAD_NATIVE_PASS bounded texture replacement / exact texels / retained"
            + " framebuffer / unpack state / admission-cancel-retry / production metadata ownership"
            + " / view-only");
  }

  private static void until(BooleanSupplier done) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!done.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end)
        throw new AssertionError("Volume upload UI timeout: " + views.probeVolumeMessage());
      Thread.yield();
    }
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
    int bytes = views.probeVolumeStatistics().lastBytes();
    maxUi = Math.max(maxUi, bytes);
    require(bytes <= 16 * 1024, "UI volume frame upload exceeded preference");
  }

  private static byte[] read(GlViewport view, int side) {
    var buffer = BufferUtils.createByteBuffer(side * side * 4);
    glBindTexture(GL_TEXTURE_2D, view.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }

  private static List<Integer> pixel(byte[] bytes, int side) {
    int i = (side / 2 * side + side / 2) * 4;
    return List.of(
        Byte.toUnsignedInt(bytes[i]),
        Byte.toUnsignedInt(bytes[i + 1]),
        Byte.toUnsignedInt(bytes[i + 2]),
        Byte.toUnsignedInt(bytes[i + 3]));
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
