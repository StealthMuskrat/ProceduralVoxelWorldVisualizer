package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
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

/** Packed surface/voxel arena parity plus real Viewports streaming/fixed lifecycle. */
final class SurfaceStreamingProbe {
  private static Viewports views;
  private static EditorState state;

  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    var report = new LinkedHashMap<String, Object>();
    report.put("formatVersion", 1);
    var packed = PackedMesh.surface(MeshBuilder.isosurface(32, (x, y, z) -> z - 16.257, 0), 32);
    var bytes = packed.readOnly();
    byte[] encoded = new byte[bytes.remaining()];
    bytes.get(encoded);
    Files.write(out.resolve("surface-packed.bin"), encoded);
    try (var viewport = new GlViewport()) {
      var palette = new int[256];
      Arrays.fill(palette, 0xff000000);
      palette[128] = 0xff0000ff;
      palette[1] = 0xff00ff00;
      viewport.setPalette(palette);
      var gpu = viewport.scene(1, 2 << 20);
      var cube = VoxelMesher.greedy(32, (x, y, z) -> 1);
      var temp = new ChunkPos(5, 0, 0);
      var surface = new ChunkPos(0, 0, 0);
      var voxel = new ChunkPos(1, 0, 0);
      int maximum = upload(gpu, new ChunkScene.Upload(1, temp, 1, cube));
      maximum = Math.max(maximum, upload(gpu, new ChunkScene.Upload(1, surface, 1, packed)));
      maximum = Math.max(maximum, upload(gpu, new ChunkScene.Upload(1, voxel, 1, cube)));
      gpu.remove(temp);
      var wanted = Set.of(surface, voxel);
      var origin = new Vector3d();
      var camera = new Camera();
      camera.flying = true;
      camera.position.set(32, 16, 100);
      camera.yaw = 0;
      camera.pitch = 0;
      var matrix = camera.matrix(1, origin);
      viewport.sceneView(origin, wanted, false, false, 0);
      viewport.render(129, 129, matrix, true, 1);
      byte[] fallback = read(viewport, 129);
      var left = pixel(fallback, 129, 32, 64);
      var right = pixel(fallback, 129, 96, 64);
      require(
          left.equals(List.of(255, 0, 0, 255)) && right.equals(List.of(0, 255, 0, 255)),
          "Mixed surface/voxel position scales: " + left + " / " + right);
      viewport.sceneView(origin, wanted, true, false, 0);
      viewport.render(129, 129, matrix, true, 1);
      require(
          Arrays.equals(fallback, read(viewport, 129)), "Mixed surface indirect/fallback mismatch");
      require(gpu.requestCompaction(), "Mixed surface compaction did not start");
      long relocated = 0;
      for (int i = 0; i < 1000 && gpu.compacting(); i++) {
        gpu.beginFrame();
        int moved = gpu.advanceCompaction(4096);
        require(moved <= 4096, "Surface relocation budget");
        relocated += moved;
      }
      require(!gpu.compacting() && relocated > 0, "Surface compaction incomplete");
      viewport.sceneView(origin, wanted, false, false, 0);
      viewport.render(129, 129, matrix, true, 1);
      byte[] compacted = read(viewport, 129);
      require(Arrays.equals(fallback, compacted), "Surface position scale lost during compaction");
      report.put("surfaceUnits", packed.unitsPerBlock());
      report.put("voxelUnits", cube.unitsPerBlock());
      report.put("packedBytes", encoded.length);
      report.put("maximumUploadBytes", maximum);
      report.put("relocatedBytes", relocated);
      report.put("left", left);
      report.put("right", right);
      report.put("fallbackHash", hash(fallback));
      report.put("compactedHash", hash(compacted));
      report.put("indirectParity", true);
    }
    glfwShowWindow(window);
    glfwFocusWindow(window);
    glfwPollEvents();
    require(glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0, "Surface window focus");
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      state.user.performance.renderDistance = 0;
      state.user.performance.verticalDistance = 0;
      state.user.performance.streamChunks = 27;
      state.user.performance.sceneCpuMiB = 256;
      state.user.performance.sceneGpuMiB = 16;
      state.user.performance.uploadKiB = 16;
      state.user.visualization.ambientOcclusion = false;
      state.user.visualization.fog = false;
      state.user.editor.opportunitiesPerSecond = 20;
      require(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "streamed-surface";
                n.dimension = 3;
                n.algorithm.key = "CONSTANT";
                var item = ProcessingRegistry.create("EXPRESSION", "plane", 3);
                item.parameters.put("expression", ProjectIO.JSON.valueToTree("z - 16.25"));
                n.processing.add(item);
                n.outputBinding = Binding.earlier(item.uuid);
                p.noise3D.add(n);
              }),
          "Streamed surface project");
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var id = state.project().noise3D.getFirst().uuid;
      views = new Viewports(state, new PropertyPanel(state), window);
      try {
        views.probeSurface(id, false, 16, 16, 16);
        until(() -> views.probeSurfaceReady() && views.probeSurfaceChunk(0, 0, 0));
        long epoch = views.probeSurfaceEpoch();
        var stages = new ArrayList<Map<String, Object>>();
        stages.add(stage("stream-origin", 0));
        state.user.editor.autoRegenerate = false;
        views.probeSurfaceTravel(48, 16, 16);
        until(() -> views.probeSurfaceChunk(1, 0, 0));
        require(views.probeSurfaceEpoch() == epoch, "Positive travel rebuilt source epoch");
        stages.add(stage("stream-positive", 1));
        views.probeSurfaceTravel(-16, 16, 16);
        until(() -> views.probeSurfaceChunk(-1, 0, 0));
        require(views.probeSurfaceEpoch() == epoch, "Negative travel rebuilt source epoch");
        stages.add(stage("stream-negative", -1));
        state.user.editor.autoRegenerate = true;
        views.probeSurface(id, true, -16, 16, 16);
        until(() -> views.probeNoiseReady(1) && views.probeSurfaceEpoch() == 0);
        frames(3);
        var fixed = views.probeSurfacePixel();
        checkSurfacePixel(fixed);
        report.put("fixedPixel", fixed);
        views.probeSurface(id, false, -16, 16, 16);
        until(() -> views.probeSurfaceReady() && views.probeSurfaceChunk(-1, 0, 0));
        require(views.probeSurfaceEpoch() != epoch, "Returning to streaming reused a closed epoch");
        stages.add(stage("stream-return", -1));
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Surface navigation changed document/history");
        report.put("stages", stages);
        report.put("viewOnly", true);
        report.put("fixedReturn", true);
        new ProjectIO().save(out.resolve("surface-streaming-project.json"), state.project());
      } finally {
        views.close();
      }
    }
    require(glGetError() == GL_NO_ERROR, "Surface streaming GL error");
    Files.writeString(
        out.resolve("surface-streaming-native.json"), ProjectIO.JSON.writeValueAsString(report));
    glfwHideWindow(window);
    System.out.println(
        "SURFACE_STREAMING_NATIVE_PASS mixed precision arena / bounded upload / indirect parity /"
            + " compaction / production Isosurface signed camera travel / fixed analysis return /"
            + " unchanged project");
  }

  private static Map<String, Object> stage(String name, int x) {
    frames(2);
    var pixel = views.probeSurfacePixel();
    checkSurfacePixel(pixel);
    var stats = views.probeSurfaceStatistics();
    require(
        stats.resident() <= 27 && stats.cpuBytes() + stats.reserved() <= 256L << 20,
        "Surface scene budgets");
    return Map.of(
        "name",
        name,
        "chunk",
        List.of(x, 0, 0),
        "epoch",
        views.probeSurfaceEpoch(),
        "pixel",
        pixel,
        "resident",
        stats.resident(),
        "cpuBytes",
        stats.cpuBytes(),
        "reserved",
        stats.reserved());
  }

  private static void checkSurfacePixel(List<Integer> pixel) {
    int palette = Viewports.color(128 / 127.5 - 1, "Heatmap", 0);
    for (int c = 0; c < 3; c++)
      require(
          Math.abs(pixel.get(c) - ((palette >>> (8 * c)) & 255) * .35) <= 2,
          "Surface viewport pixel " + pixel);
    require(pixel.get(3) == 255, "Surface viewport alpha");
  }

  private static int upload(SceneGpuRenderer gpu, ChunkScene.Upload upload) {
    require(gpu.stage(upload), "Surface arena admission");
    int maximum = 0;
    for (int i = 0; i < 1000; i++) {
      gpu.beginFrame();
      var complete = gpu.advance(4096);
      maximum = Math.max(maximum, (int) gpu.statistics().uploadedBytes());
      require(gpu.statistics().uploadedBytes() <= 4096, "Surface upload exceeded budget");
      if (complete != null) return maximum;
      require(!gpu.contains(upload), "Incomplete surface was published");
    }
    throw new AssertionError("Surface upload timeout");
  }

  private static byte[] read(GlViewport view, int side) {
    var bytes = BufferUtils.createByteBuffer(side * side * 4);
    glBindTexture(GL_TEXTURE_2D, view.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
    var result = new byte[bytes.remaining()];
    bytes.get(result);
    return result;
  }

  private static List<Integer> pixel(byte[] bytes, int side, int x, int y) {
    int i = (y * side + x) * 4;
    return List.of(
        Byte.toUnsignedInt(bytes[i]),
        Byte.toUnsignedInt(bytes[i + 1]),
        Byte.toUnsignedInt(bytes[i + 2]),
        Byte.toUnsignedInt(bytes[i + 3]));
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void until(BooleanSupplier done) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!done.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end)
        throw new AssertionError("Surface scene timeout: " + state.status);
      Thread.yield();
    }
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
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
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
