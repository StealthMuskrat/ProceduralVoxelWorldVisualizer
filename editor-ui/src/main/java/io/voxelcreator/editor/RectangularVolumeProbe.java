package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.opengl.GL33.*;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;

/**
 * Deferred rectangular allocation, bounded replacement, rendering, GPU reuse and real editor path.
 */
final class RectangularVolumeProbe {
  private static final GridExtent SHAPE = new GridExtent(3, 5, 7);

  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    var rows = new ArrayList<Map<String, Object>>();
    float[] overlapTexels;
    try (var view = new GlViewport()) {
      int[] palette = new int[256];
      Arrays.fill(palette, 0xff0000ff);
      view.setPalette(palette);
      view.setVolume(VolumeData.sample(2, 0, 0, 0, (x, y, z) -> .25));
      var prior = view.volumeResident();
      var box = VolumeData.sample(SHAPE, 0, 0, 0, (x, y, z) -> 1);
      require(!view.stageVolume(box, 451), "GPU bound was charged as a cube");
      require(view.volumeResident() == prior, "Rejected upload replaced resident");
      require(view.stageVolume(box, 452), "Exact GPU box reservation rejected");
      int bytes = 0;
      while (view.volumeStatistics().pending()) {
        int written = view.advanceVolume(28);
        bytes += written;
        require(written <= 28 && written % 4 == 0, "Upload exceeded frame budget");
        if (view.volumeStatistics().pending())
          require(view.volumeResident() == prior, "Partial rectangle became resident");
      }
      require(
          bytes == 420 && view.volumeResident().shape().equals(SHAPE),
          "Rectangular upload count/shape");
      require(
          dimensions(view.volumeResident().texture()).equals(List.of(3, 5, 7)),
          "GPU allocated cube dimensions");
      view.volumeAppearance(0, 1, .2, 0, 256);
      double[][] poses = {
        {1.5, 2.5, 10, 0, 0},
        {10, 2.5, 3.5, Math.PI / 2, 0},
        {1.5, 2.5 + 8 * Math.sin(Math.PI / 3), 3.5 + 8 * Math.cos(Math.PI / 3), 0, Math.PI / 3}
      };
      for (double[] pose : poses) {
        var camera = new Camera();
        camera.flying = true;
        camera.position.set(pose[0], pose[1], pose[2]);
        camera.yaw = pose[3];
        camera.pitch = pose[4];
        var ray = camera.ray(0, 0, 1, new Vector3d());
        double path = path(ray);
        view.render(65, 65, camera.matrix(1, new Vector3d()), true, 1);
        var rgba = pixel(view);
        double t = Math.exp(-.2f * path);
        double[] expected = {255 * (1 - t + t * .055), 255 * t * .075, 255 * t * .1};
        for (int channel = 0; channel < 3; channel++)
          require(
              Math.abs(rgba.get(channel) - expected[channel]) <= 2,
              "Rectangular ray-box opacity mismatch");
        rows.add(
            Map.of(
                "camera",
                Arrays.stream(pose).boxed().toList(),
                "origin",
                xyz(ray.origin()),
                "direction",
                xyz(ray.direction()),
                "path",
                path,
                "rgba",
                rgba));
      }
      var original = VolumeData.sample(SHAPE, 0, 0, 0, (x, y, z) -> x + 10 * y + 100 * z);
      require(
          view.stageVolume(original, 840, new VolumeTextureUpload.Region("affine", 0, 0, 0)),
          "Affine stage failed");
      while (view.volumeStatistics().pending()) view.advanceVolume(28);
      long copied = view.volumeStatistics().copiedBytes(),
          uploaded = view.volumeStatistics().uploadedBytes();
      var next =
          VolumeData.sample(new GridExtent(4, 3, 6), 1, 1, 1, (x, y, z) -> x + 10 * y + 100 * z);
      require(
          view.stageVolume(next, 708, new VolumeTextureUpload.Region("affine", 1, 1, 1)),
          "Changed shape stage failed");
      while (view.volumeStatistics().pending()) view.advanceVolume(28);
      require(
          view.volumeStatistics().copiedBytes() - copied == 144
              && view.volumeStatistics().uploadedBytes() - uploaded == 288,
          "Rectangular overlap accounting");
      require(
          dimensions(view.volumeResident().texture()).equals(List.of(4, 3, 6)),
          "Changed shape GPU dimensions");
      overlapTexels = texels(view.volumeResident().texture(), 72);
      require(
          Arrays.equals(overlapTexels, next.copySamples()),
          "GPU rectangular copies used cube strides");
    }
    try (var state = new EditorState(new UserConfig(), false)) {
      state.user.visualization.streaming = false;
      state.user.performance.previewCellBudget = 105;
      state.user.performance.maxPreviewDimension = 8;
      state.user.editor.opportunitiesPerSecond = 20;
      require(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "affine-volume";
                n.dimension = 3;
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", 1.);
                var item = ProcessingRegistry.create("EXPRESSION", "affine", 3);
                item.inputs.clear();
                item.parameters.put("expression", ProjectIO.JSON.valueToTree("x + 10*y + 100*z"));
                n.processing.add(item);
                n.outputBinding = Binding.earlier(item.uuid);
                p.noise3D.add(n);
              }),
          "Volume fixture rejected");
      state.document.save(out.resolve("before.json"));
      state.user.save(out.resolve("preferences-before.json"));
      var noise = state.project().noise3D.getFirst().uuid;
      state.select("noise3D", noise);
      ((Noise) state.draft).id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      Object draft = state.draft;
      var values = ProjectIO.JSON.valueToTree(draft);
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      float[] editorTexels;
      List<Integer> editorShape;
      try (var views = new Viewports(state, new PropertyPanel(state), window)) {
        views.probeFixedExtent(noise, "Volume", SHAPE);
        long end = System.nanoTime() + 30_000_000_000L;
        while (!views.probeNoiseReady(1)) {
          glfwPollEvents();
          state.drain();
          ImGui.newFrame();
          ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
          ImGui.setNextWindowSize(1000, 800, ImGuiCond.Always);
          views.draw();
          ImGui.render();
          if (System.nanoTime() > end)
            throw new AssertionError("Rectangular editor volume timeout");
          Thread.yield();
        }
        require(
            views.probeVolumeStatistics().activeBytes() == 420,
            "Editor volume used padded GPU bytes");
        editorShape = dimensions(views.probeVolumeResidentTexture());
        require(editorShape.equals(List.of(3, 5, 7)), "Editor upload is not rectangular");
        editorTexels = texels(views.probeVolumeResidentTexture(), 105);
      }
      require(
          before.equals(ProjectIO.JSON.valueToTree(state.project()))
              && state.draft == draft
              && values.equals(ProjectIO.JSON.valueToTree(draft))
              && state.revision == revision
              && state.document.historyBytes() == history
              && !state.document.dirty()
              && state.seedInputs.text("seedOffset").equals("-"),
          "Volume changed project/private/history state");
      new ProjectIO().save(out.resolve("after.json"), state.project());
      state.user.save(out.resolve("preferences.json"));
      Files.writeString(
          out.resolve("rectangular-volume-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "formatVersion",
                  1,
                  "rows",
                  rows,
                  "bytes",
                  420,
                  "overlapCopiedBytes",
                  144,
                  "overlapWrittenBytes",
                  288,
                  "overlapTexels",
                  overlapTexels,
                  "editorShape",
                  editorShape,
                  "editorOrigin",
                  List.of(8.75, -5.5, -11.),
                  "editorTexels",
                  editorTexels,
                  "privatePreserved",
                  true)));
    }
    System.out.println(
        "RECTANGULAR_VOLUME_NATIVE_PASS real box allocation / bounded publication / ray-box opacity"
            + " / overlap / editor sampling");
  }

  private static double path(Camera.Ray ray) {
    double enter = 0, exit = Double.POSITIVE_INFINITY;
    double[] o = {ray.origin().x, ray.origin().y, ray.origin().z},
        d = {ray.direction().x, ray.direction().y, ray.direction().z};
    int[] bounds = {3, 5, 7};
    for (int axis = 0; axis < 3; axis++) {
      if (Math.abs(d[axis]) < 1e-12) {
        if (o[axis] < 0 || o[axis] > bounds[axis]) return 0;
      } else {
        double a = -o[axis] / d[axis], b = (bounds[axis] - o[axis]) / d[axis];
        enter = Math.max(enter, Math.min(a, b));
        exit = Math.min(exit, Math.max(a, b));
      }
    }
    return Math.max(0, exit - enter);
  }

  private static List<Integer> dimensions(int texture) {
    int old = glGetInteger(GL_TEXTURE_BINDING_3D);
    try {
      glBindTexture(GL_TEXTURE_3D, texture);
      return List.of(
          glGetTexLevelParameteri(GL_TEXTURE_3D, 0, GL_TEXTURE_WIDTH),
          glGetTexLevelParameteri(GL_TEXTURE_3D, 0, GL_TEXTURE_HEIGHT),
          glGetTexLevelParameteri(GL_TEXTURE_3D, 0, GL_TEXTURE_DEPTH));
    } finally {
      glBindTexture(GL_TEXTURE_3D, old);
    }
  }

  private static float[] texels(int texture, int count) {
    int old = glGetInteger(GL_TEXTURE_BINDING_3D);
    var buffer = BufferUtils.createFloatBuffer(count);
    try {
      glBindTexture(GL_TEXTURE_3D, texture);
      glGetTexImage(GL_TEXTURE_3D, 0, GL_RED, GL_FLOAT, buffer);
      var result = new float[count];
      buffer.get(result);
      return result;
    } finally {
      glBindTexture(GL_TEXTURE_3D, old);
    }
  }

  private static List<Integer> pixel(GlViewport view) {
    int old = glGetInteger(GL_TEXTURE_BINDING_2D);
    var bytes = BufferUtils.createByteBuffer(65 * 65 * 4);
    try {
      glBindTexture(GL_TEXTURE_2D, view.texture());
      glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
      var result = new ArrayList<Integer>();
      for (int c = 0; c < 4; c++) result.add(Byte.toUnsignedInt(bytes.get((32 * 65 + 32) * 4 + c)));
      return result;
    } finally {
      glBindTexture(GL_TEXTURE_2D, old);
    }
  }

  private static List<Double> xyz(Vector3d v) {
    return List.of(v.x, v.y, v.z);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
