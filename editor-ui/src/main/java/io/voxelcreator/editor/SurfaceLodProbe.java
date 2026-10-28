package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.api.CancellationToken;
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
 * Unit-face mixed scalar LOD, complete GPU metadata, compaction and production camera selection.
 */
final class SurfaceLodProbe {
  private static Viewports views;
  private static EditorState state;
  private static final ChunkPos LEFT = new ChunkPos(-1, 0, 0),
      MID = new ChunkPos(0, 0, 0),
      RIGHT = new ChunkPos(1, 0, 0);

  static void run(Path out) throws Exception {
    run(out, 0);
  }

  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    var report = new LinkedHashMap<String, Object>();
    report.put("formatVersion", 1);
    report.put("glVersion", glGetString(GL_VERSION));
    report.put("openGL31", GL.getCapabilities().OpenGL31);
    var meshes = new ArrayList<PackedMesh>();
    var cases = new ArrayList<Map<String, Object>>();
    for (int level = 0; level <= 5; level++) {
      var chunk =
          SceneChunk.isosurface(
              new ChunkPos(0, 0, 0), 32, new CancellationToken(), (x, y, z) -> z - 16.257, 0);
      var mesh = chunk.surfaceMesh(level, new CancellationToken());
      meshes.add(mesh);
      var data = mesh.readOnly();
      byte[] binary = new byte[data.remaining()];
      data.get(binary);
      Files.write(out.resolve("plane-lod-" + level + ".bin"), binary);
      cases.add(
          Map.of(
              "level",
              level,
              "bytes",
              mesh.byteSize(),
              "vertices",
              mesh.vertexCount(),
              "units",
              mesh.unitsPerBlock(),
              "maximumMeshBytes",
              SurfaceLodMesh.maximumMeshBytes(32, level),
              "workingBytes",
              SceneChunk.surfaceWorkingBytes(32, level)));
    }
    // Non-affine shared faces need identical segments too; export signed adjacent samples at all
    // levels for a separate mathematical face-curve replay.
    for (int level = 0; level <= 5; level++)
      for (int side = -1; side <= 0; side++) {
        var chunk =
            SceneChunk.isosurface(
                new ChunkPos(side, 0, 0),
                32,
                new CancellationToken(),
                (x, y, z) -> x * x / 256 + (y - 16) * (y - 16) / 64 + .23 * z - 3.417,
                0);
        var mesh = chunk.surfaceMesh(level, new CancellationToken());
        var data = mesh.readOnly();
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        Files.write(out.resolve("curved-" + side + "-lod-" + level + ".bin"), bytes);
      }
    try (var gl = new GlViewport()) {
      int[] palette = new int[256];
      Arrays.fill(palette, 0xff000000);
      palette[128] = 0xff0000ff;
      gl.setPalette(palette);
      var gpu = gl.scene(1, 4 << 20);
      var temp = new ChunkPos(5, 0, 0);
      int maximum = upload(gpu, new ChunkScene.Upload(1, temp, 1, meshes.getFirst(), null, 0));
      for (var pos : List.of(LEFT, MID, RIGHT))
        maximum =
            Math.max(
                maximum, upload(gpu, new ChunkScene.Upload(1, pos, 1, meshes.getFirst(), null, 0)));
      gpu.remove(temp);
      var wanted = Set.of(LEFT, MID, RIGHT);
      var origin = new Vector3d();
      var camera = new Camera();
      camera.flying = true;
      camera.position.set(16, 16, 100);
      camera.yaw = camera.pitch = 0;
      var matrix = camera.matrix(1, origin);
      gl.sceneView(origin, wanted, false, false, 0);
      gl.render(129, 129, matrix, true, 1);
      byte[] fine = read(gl);
      require(pixel(fine).equals(List.of(255, 0, 0, 255)), "Fine plane pixel");
      for (var item : List.of(Map.entry(MID, 2), Map.entry(RIGHT, 5))) {
        var next =
            new ChunkScene.Upload(
                1, item.getKey(), 2, meshes.get(item.getValue()), null, item.getValue());
        require(gpu.stage(next), "LOD replacement admission");
        boolean done = false;
        while (!done) {
          gpu.beginFrame();
          done = gpu.advance(4096) != null;
          maximum = Math.max(maximum, (int) gpu.statistics().uploadedBytes());
          require(gpu.statistics().uploadedBytes() <= 4096, "LOD upload byte bound");
          gl.render(129, 129, matrix, true, 1);
          require(Arrays.equals(fine, read(gl)), "LOD changed the plane framebuffer");
          require(
              gpu.drawnDetailLevels().get(item.getKey()) == (done ? item.getValue() : 0),
              "Incomplete LOD metadata published");
        }
      }
      var levels = Map.of(LEFT, 0, MID, 2, RIGHT, 5);
      require(gpu.drawnDetailLevels().equals(levels), "Mixed complete LOD levels");
      gl.sceneView(origin, wanted, true, false, 0);
      gl.render(129, 129, matrix, true, 1);
      require(Arrays.equals(fine, read(gl)), "Mixed LOD indirect parity");
      require(gpu.requestCompaction(), "LOD compaction request");
      long moved = 0;
      for (int i = 0; i < 2000 && gpu.compacting(); i++) {
        gpu.beginFrame();
        int bytes = gpu.advanceCompaction(4096);
        moved += bytes;
        require(bytes <= 4096, "LOD relocation byte bound");
        gl.render(129, 129, matrix, true, 1);
        require(Arrays.equals(fine, read(gl)), "LOD compaction changed pixels");
        require(gpu.drawnDetailLevels().equals(levels), "LOD metadata lost in compaction");
      }
      require(!gpu.compacting() && moved > 0, "LOD compaction incomplete");
      report.put("relocatedBytes", moved);
      report.put("maximumUploadBytes", maximum);
      report.put("framebufferHash", hash(fine));
      report.put("pixel", pixel(fine));
      report.put("levels", List.of(0, 2, 5));
      report.put("partialLevelsRetained", true);
      report.put("framebufferParity", true);
      report.put("compactionParity", true);
      report.put("indirectParity", true);
      gpu.remove(RIGHT);
      require(!gpu.drawnDetailLevels().containsKey(RIGHT), "Retired LOD metadata retained");
      gl.setImage(2, 2, new int[] {0xff000000, 0xff000000, 0xff000000, 0xff000000});
      require(
          gl.sceneRenderer() == null && gpu.drawnDetailLevels().isEmpty(),
          "Fixed image retained hidden arena");
      report.put("fixedImageReleasedArena", true);
    }
    report.put("planes", cases);
    if (window != 0) production(out, window, report);
    require(glGetError() == GL_NO_ERROR, "Surface LOD GL error");
    Files.writeString(
        out.resolve("surface-lod-native.json"), ProjectIO.JSON.writeValueAsString(report));
    System.out.println(
        "SURFACE_LOD_NATIVE_PASS canonical unit faces / mixed detail / bounded publication"
            + " / retained LOD metadata / full-grid plane pixels / indirect-compaction parity / "
            + glGetString(GL_VERSION));
  }

  private static void production(Path out, long handle, Map<String, Object> report)
      throws Exception {
    glfwShowWindow(handle);
    glfwFocusWindow(handle);
    glfwPollEvents();
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      var p = state.user.performance;
      p.renderDistance = p.verticalDistance = 0;
      p.streamChunks = 27;
      p.sceneCpuMiB = 256;
      p.sceneGpuMiB = 16;
      p.uploadKiB = 16;
      p.lodStart = 1;
      p.lodQuality = 1;
      state.user.editor.opportunitiesPerSecond = 20;
      state.user.visualization.fog = false;
      state.user.visualization.ambientOcclusion = false;
      require(
          state.change(
              project -> {
                var noise = new Noise();
                noise.id = "surface-lod";
                noise.dimension = 3;
                noise.algorithm.key = "CONSTANT";
                var item = ProcessingRegistry.create("EXPRESSION", "plane", 3);
                item.parameters.put("expression", ProjectIO.JSON.valueToTree("z-16.25"));
                noise.processing.add(item);
                noise.outputBinding = Binding.earlier(item.uuid);
                project.noise3D.add(noise);
              }),
          "LOD project");
      var before = ProjectIO.JSON.valueToTree(state.project());
      var configured = ProjectIO.JSON.valueToTree(p);
      long revision = state.revision, history = state.document.historyBytes();
      var id = state.project().noise3D.getFirst().uuid;
      views = new Viewports(state, new PropertyPanel(state), handle);
      try {
        var stages = new ArrayList<Map<String, Object>>();
        views.probeSurface(id, false, 16, 16, 16);
        views.probeSurfaceDistance(35);
        until(() -> level(MID, 0));
        long epoch = views.probeSurfaceEpoch();
        stages.add(stage("near", MID));
        state.user.editor.autoRegenerate = false;
        views.probeSurfaceDistance(160);
        until(() -> level(MID, 3));
        stages.add(stage("far", MID));
        p.lodQuality = 2;
        until(() -> level(MID, 2));
        stages.add(stage("high-quality", MID));
        p.lodQuality = 0;
        until(() -> level(MID, 4));
        stages.add(stage("low-quality", MID));
        p.lodQuality = 1;
        until(() -> level(MID, 3));
        stages.add(stage("quality-restored", MID));
        views.probeSurfaceDistance(35);
        until(() -> level(MID, 0));
        stages.add(stage("near-return", MID));
        views.probeSurfaceTravel(-16, 16, 16);
        until(() -> level(LEFT, 0));
        stages.add(stage("negative", LEFT));
        require(
            views.probeSurfaceEpoch() == epoch, "Camera/quality rebuilt the scalar source epoch");
        state.user.editor.autoRegenerate = true;
        views.probeSurface(id, true, -16, 16, 16);
        until(() -> views.probeNoiseReady(1) && views.probeSurfaceEpoch() == 0);
        require(
            views.probeSurfaceLevels().isEmpty()
                && views.streamingStatistics().gpuCapacityBytes() == 0,
            "Fixed analysis retained streamed LOD metadata");
        report.put("fixedMeshReleasedArena", true);
        views.probeSurface(id, false, -16, 16, 16);
        views.probeSurfaceDistance(35);
        until(() -> level(LEFT, 0));
        stages.add(stage("stream-return", LEFT));
        require(views.probeSurfaceEpoch() != epoch, "Return reused closed scalar epoch");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Surface LOD changed authored data/history");
        require(
            configured.equals(ProjectIO.JSON.valueToTree(p)),
            "LOD selection changed configured quality");
        report.put("production", stages);
        report.put("viewOnly", true);
        report.put("fixedReturn", true);
        new ProjectIO().save(out.resolve("surface-lod-project.json"), state.project());
      } finally {
        views.close();
      }
    }
    glfwHideWindow(handle);
  }

  private static boolean level(ChunkPos p, int expected) {
    return views.probeSurfaceLevels().getOrDefault(p, -1) == expected;
  }

  private static Map<String, Object> stage(String name, ChunkPos pos) {
    frames(2);
    var pixel = views.probeSurfacePixel();
    int color = Viewports.color(128 / 127.5 - 1, "Heatmap", 0);
    for (int c = 0; c < 3; c++)
      require(
          Math.abs(pixel.get(c) - ((color >>> (8 * c)) & 255) * .35) <= 2,
          "LOD production lighting " + name + ": " + pixel);
    var s = views.probeSurfaceStatistics();
    require(s.cpuBytes() + s.reserved() <= 256L << 20, "LOD scene CPU admission");
    return Map.of(
        "name",
        name,
        "level",
        views.probeSurfaceLevels().get(pos),
        "chunk",
        List.of(pos.x(), pos.y(), pos.z()),
        "epoch",
        views.probeSurfaceEpoch(),
        "pixel",
        pixel,
        "cpuBytes",
        s.cpuBytes(),
        "reserved",
        s.reserved());
  }

  private static int upload(SceneGpuRenderer gpu, ChunkScene.Upload upload) {
    require(gpu.stage(upload), "LOD upload admission");
    int max = 0;
    for (int i = 0; i < 2000; i++) {
      gpu.beginFrame();
      var done = gpu.advance(4096);
      max = Math.max(max, (int) gpu.statistics().uploadedBytes());
      if (done != null) return max;
    }
    throw new AssertionError("LOD upload timeout");
  }

  private static byte[] read(GlViewport gl) {
    var pixels = BufferUtils.createByteBuffer(129 * 129 * 4);
    glBindTexture(GL_TEXTURE_2D, gl.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    byte[] data = new byte[pixels.remaining()];
    pixels.get(data);
    return data;
  }

  private static List<Integer> pixel(byte[] data) {
    int at = (64 * 129 + 64) * 4;
    return List.of(
        Byte.toUnsignedInt(data[at]),
        Byte.toUnsignedInt(data[at + 1]),
        Byte.toUnsignedInt(data[at + 2]),
        Byte.toUnsignedInt(data[at + 3]));
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void until(BooleanSupplier done) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!done.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end)
        throw new AssertionError("LOD production timeout: " + views.probeVolumeMessage());
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

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
