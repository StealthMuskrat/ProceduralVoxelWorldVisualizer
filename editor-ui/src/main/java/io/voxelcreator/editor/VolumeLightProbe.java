package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Deferred native contracts: analytical volume attenuation and accepted-source ownership. */
final class VolumeLightProbe {
  private static final int SIZE = 65;
  private static EditorState state;
  private static Viewports views;

  static void run(Path out) throws Exception {
    run(out, 0);
  }

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    var report = new LinkedHashMap<String, Object>();
    report.put("formatVersion", 1);
    report.put("glVersion", glGetString(GL_VERSION));
    report.put("openGL31", GL.getCapabilities().OpenGL31);
    var rows = new ArrayList<Map<String, Object>>();
    try (var gl = new GlViewport()) {
      int[] palette = new int[256];
      Arrays.fill(palette, 0xff0000ff);
      gl.setPalette(palette);
      var camera = new Camera();
      camera.flying = true;
      camera.position.set(2, 2, 10);
      camera.yaw = camera.pitch = 0;
      var matrix = camera.matrix(1, new Vector3d());
      var data = VolumeData.sample(4, 0, 0, 0, (x, y, z) -> 1);
      gl.setVolume(data);
      long version = gl.volumeStatistics().version(),
          uploaded = gl.volumeStatistics().uploadedBytes();
      for (var item :
          List.of(
              new Object[] {"off", false, 16, false},
              new Object[] {"light-8", true, 8, false},
              new Object[] {"light-16", true, 16, false},
              new Object[] {"light-32", true, 32, false},
              new Object[] {"unlit", true, 32, true})) {
        gl.volumeLighting((boolean) item[1], (int) item[2]);
        gl.volumeAppearance(0, 1, .25, 0, 192);
        gl.render(SIZE, SIZE, matrix, (boolean) item[3], 1);
        rows.add(
            save(
                out,
                (String) item[0],
                gl,
                "constant",
                .25,
                0,
                (boolean) item[1],
                (int) item[2],
                (boolean) item[3]));
        require(
            gl.volumeStatistics().version() == version
                && gl.volumeStatistics().uploadedBytes() == uploaded,
            "Light controls changed accepted texture");
      }
      gl.volumeAppearance(0, 1, .25, 2, 192);
      gl.render(SIZE, SIZE, matrix, false, 1);
      rows.add(save(out, "threshold", gl, "constant", .25, 2, true, 32, false));
      for (double density : new double[] {1e300, -1e300}) {
        gl.setVolume(VolumeData.sample(4, 0, 0, 0, (x, y, z) -> density));
        gl.volumeAppearance(
            density > 0 ? 0 : 2 * density, density, .25, density > 0 ? 0 : 1.5 * density, 192);
        gl.render(SIZE, SIZE, matrix, false, 1);
        rows.add(
            save(out, density > 0 ? "huge" : "negative", gl, "constant", .25, 0, true, 32, false));
      }
      gl.setVolume(VolumeData.sample(4, 0, 0, 0, (x, y, z) -> y / 4));
      gl.volumeAppearance(0, 1, .25, 0, 256);
      gl.render(SIZE, SIZE, matrix, false, 1);
      rows.add(save(out, "gradient", gl, "gradient-y", .25, 0, true, 32, false));
      gl.volumeAppearance(0, 1, 0, 0, 256);
      gl.render(SIZE, SIZE, matrix, false, 1);
      rows.add(save(out, "zero-opacity", gl, "gradient-y", 0, 0, true, 32, false));
      require(gl.shadowStatistics().bytes() == 0, "Volume lighting allocated a mesh shadow map");
      report.put("meshShadowBytes", gl.shadowStatistics().bytes());
    }
    if (handle != 0) production(out, handle, report);
    require(glGetError() == GL_NO_ERROR, "Volume lighting GL error");
    report.put("cases", rows);
    Files.writeString(
        out.resolve("volume-light-native.json"), ProjectIO.JSON.writeValueAsString(report));
    System.out.println(
        "VOLUME_LIGHT_NATIVE_PASS bounded volume light rays / encoded transfer / unlit bypass /"
            + " accepted-texture ownership / "
            + glGetString(GL_VERSION));
  }

  private static Map<String, Object> save(
      Path out,
      String name,
      GlViewport gl,
      String field,
      double opacity,
      double threshold,
      boolean enabled,
      int steps,
      boolean unlit)
      throws Exception {
    var bytes = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
    glBindTexture(GL_TEXTURE_2D, gl.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
    byte[] raw = new byte[bytes.remaining()];
    bytes.get(raw);
    Files.write(out.resolve(name + ".rgba"), raw);
    int i = (32 * SIZE + 32) * 4;
    return Map.of(
        "name",
        name,
        "field",
        field,
        "opacity",
        opacity,
        "threshold",
        threshold,
        "enabled",
        enabled,
        "steps",
        steps,
        "unlit",
        unlit,
        "pixel",
        List.of(
            Byte.toUnsignedInt(raw[i]),
            Byte.toUnsignedInt(raw[i + 1]),
            Byte.toUnsignedInt(raw[i + 2]),
            Byte.toUnsignedInt(raw[i + 3])),
        "sha256",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
  }

  private static void production(Path out, long handle, Map<String, Object> report)
      throws Exception {
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
                n.id = "volume-light";
                n.dimension = 3;
                n.algorithm.key = "CONSTANT";
                var item = ProcessingRegistry.create("EXPRESSION", "density", 3);
                item.parameters.put("expression", ProjectIO.JSON.valueToTree("1"));
                n.processing.add(item);
                n.outputBinding = Binding.earlier(item.uuid);
                p.noise3D.add(n);
              }),
          "Light project");
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      views = new Viewports(state, new PropertyPanel(state), handle);
      try {
        var stages = new ArrayList<Map<String, Object>>();
        var id = state.project().noise3D.getFirst().uuid;
        for (boolean stream : new boolean[] {false, true}) {
          state.user.editor.autoRegenerate = true;
          if (stream) views.probeVolumeStream(id, 32, 16, 16, 16);
          else views.probeVolume(id, 16, 0);
          views.probeVolumeRaySteps(16);
          until(() -> views.probeNoiseReady(1));
          state.user.editor.autoRegenerate = false;
          var original = views.probeVolumeStatistics();
          int texture = views.probeVolumeResidentTexture();
          long stamp = views.probeSubmissionStamp(1);
          var origin = views.probeVolumeOrigin();
          long generation = views.probeVolumeMemory().generation();
          for (int steps : new int[] {8, 16, 32}) {
            state.user.visualization.volumeShadows = true;
            state.user.visualization.volumeShadowSteps = steps;
            frame();
            require(
                views.probeVolumeLight().equals(new VolumeLight.Settings(true, steps)),
                "Volume lighting controls not applied");
          }
          state.user.visualization.unlit = true;
          frame();
          state.user.visualization.unlit = false;
          state.user.visualization.volumeShadows = false;
          frame();
          var current = views.probeVolumeStatistics();
          require(
              original.version() == current.version()
                  && original.uploadedBytes() == current.uploadedBytes()
                  && original.activeBytes() == current.activeBytes()
                  && current.pendingBytes() == 0
                  && texture == views.probeVolumeResidentTexture(),
              "Lighting replaced or uploaded volume");
          require(
              stamp == views.probeSubmissionStamp(1)
                  && origin.equals(views.probeVolumeOrigin())
                  && generation == views.probeVolumeMemory().generation(),
              "Lighting resampled or changed source window");
          stages.add(
              Map.of(
                  "streamed",
                  stream,
                  "version",
                  current.version(),
                  "activeBytes",
                  current.activeBytes(),
                  "uploadedBytes",
                  current.uploadedBytes(),
                  "sourceGeneration",
                  generation,
                  "unchanged",
                  true));
        }
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Lighting changed project/history");
        state.user.visualization.volumeShadows = true;
        state.user.visualization.volumeShadowSteps = 16;
        state.user.save(out.resolve("volume-light-user.json"));
        new ProjectIO().save(out.resolve("volume-light-project.json"), state.project());
        report.put("productionStages", stages);
        report.put("viewOnly", true);
      } finally {
        views.close();
      }
    }
    glfwHideWindow(handle);
  }

  private static void until(BooleanSupplier condition) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!condition.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end) throw new AssertionError("Volume light source timeout");
      Thread.yield();
    }
  }

  private static void frame() {
    glfwPollEvents();
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(420, 480, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(false, ImGuiCond.Always);
    views.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
