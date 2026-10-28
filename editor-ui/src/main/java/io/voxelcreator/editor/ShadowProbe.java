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
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/**
 * Analytical occluder pixels, published geometry, GL state ownership and production preferences.
 */
final class ShadowProbe {
  private static final int SIZE = 129;
  private static EditorState state;
  private static Viewports views;
  private static ProfilePanel panel;
  private static final Matrix4f CAMERA =
      new Matrix4f().ortho(-8, 8, -8, 8, .1f, 150).lookAt(22, 70, 22, 22, 0, 22, 0, 0, -1);

  static void run(Path out) throws Exception {
    run(out, 0);
  }

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    var report = new LinkedHashMap<String, Object>();
    report.put("formatVersion", 1);
    report.put("glVersion", glGetString(GL_VERSION));
    report.put("openGL31", GL.getCapabilities().OpenGL31);
    report.put("size", SIZE);
    report.put("light", List.of(.4, 1, .3));
    report.put("cameraMatrix", CAMERA.get(new float[16]));
    var frames = new ArrayList<Map<String, Object>>();
    var fixed =
        MeshBuilder.greedy(
            64,
            (x, y, z) ->
                (x >= 0 && x < 32 && z >= 0 && z < 32 && y == 0)
                        || (x >= 36 && x < 40 && y >= 38 && y < 42 && z >= 32 && z < 36)
                    ? 1
                    : 0);
    try (var gl = new GlViewport()) {
      int[] palette = new int[256];
      Arrays.fill(palette, 0xffffffff);
      gl.setPalette(palette);
      gl.setMesh(fixed);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      save(out, frames, "fixed-off", gl);
      require(gl.shadowStatistics().bytes() == 0, "Disabled map allocation");
      // Force color framebuffer allocation while a caller's texture unit 3 remains active.
      gl.render(33, 33, CAMERA, false, 1);
      gl.shadowAppearance(true, 512, 8 << 20);
      int foreignTexture = glGenTextures();
      glActiveTexture(GL_TEXTURE3);
      glBindTexture(GL_TEXTURE_2D, foreignTexture);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      require(
          glGetInteger(GL_ACTIVE_TEXTURE) == GL_TEXTURE3
              && glGetInteger(GL_TEXTURE_BINDING_2D) == foreignTexture,
          "Shadow color texture-unit ownership");
      glActiveTexture(GL_TEXTURE0);
      glDeleteTextures(foreignTexture);
      report.put("colorTextureBindingRestored", true);
      save(out, frames, "fixed-shadow", gl);
      ready(gl, 512);
      expect(gl, 64, 64, 89);
      expect(gl, 32, 96, 238);
      gl.render(SIZE, SIZE, CAMERA, true, 1);
      save(out, frames, "fixed-unlit", gl);
      expect(gl, 64, 64, 255);
      require(gl.shadowStatistics().bytes() == 0, "Unlit retained map");
      gl.shadowAppearance(true, 2048, 8 << 20);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      ready(gl, 1024);
      report.put("fixedBudgetResolution", gl.shadowStatistics().resolution());
      gl.shadowAppearance(true, 512, 8 << 20);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      ready(gl, 512);
      gl.setImage(16, 16, new int[256]);
      require(gl.shadowStatistics().bytes() == 0, "Image retained shadow texture");
      gl.setMesh(fixed);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      ready(gl, 512);
      gl.setVolume(VolumeData.sample(8, 0, 0, 0, (x, y, z) -> 1));
      require(gl.shadowStatistics().bytes() == 0, "Volume retained shadow texture");
      gl.setMesh(new Mesh(new float[] {22, 1, 22, 0, 1, 0, 1}, true));
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      require(gl.shadowStatistics().bytes() == 0, "Points allocated shadow texture");
      gl.setMesh(fixed);
      gl.shadowAppearance(true, 512, 0);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      expect(gl, 64, 64, 238);
      require(
          gl.shadowStatistics().state().equals("BUDGET") && gl.shadowStatistics().bytes() == 0,
          "Budget fallback");
      gl.shadowAppearance(true, 512, 8 << 20);
      var gpu = gl.scene(1, 7 << 20);
      var ground = new ChunkPos(0, 0, 0);
      var caster = new ChunkPos(1, 1, 1);
      var filler = new ChunkPos(5, 5, 5);
      var floor =
          VoxelMesher.greedy(
              32, (x, y, z) -> x >= 0 && x < 32 && z >= 0 && z < 32 && y == 0 ? 1 : 0);
      var box =
          PackedMesh.surface(
              MeshBuilder.greedy(
                  32, (x, y, z) -> x >= 4 && x < 8 && y >= 6 && y < 10 && z >= 0 && z < 4 ? 1 : 0),
              32);
      require(floor.unitsPerBlock() == 2 && box.unitsPerBlock() == 128, "Mixed precision setup");
      upload(gpu, new ChunkScene.Upload(1, filler, 1, floor));
      upload(gpu, new ChunkScene.Upload(1, ground, 1, floor));
      var wanted = Set.of(ground, caster);
      var origin = new Vector3d();
      gl.sceneView(origin, wanted, false, false, 0);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      save(out, frames, "stream-ground", gl);
      expect(gl, 64, 64, 238);
      require(gpu.stage(new ChunkScene.Upload(1, caster, 1, box)), "Partial caster admission");
      gpu.beginFrame();
      require(gpu.advance(48) == null, "Caster published early");
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      save(out, frames, "stream-partial", gl);
      expect(gl, 64, 64, 238);
      require(gl.shadowStatistics().draws() == 1, "Incomplete caster entered shadow pass");
      while (gpu.pending()) {
        gpu.beginFrame();
        gpu.advance(192);
      }
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      byte[] shadow = save(out, frames, "stream-shadow", gl);
      expect(gl, 64, 64, 89);
      expect(gl, 32, 96, 238);
      require(
          gpu.statistics().visible() == 1
              && gpu.statistics().culled() == 1
              && gpu.statistics().draws() == 1,
          "Depth pass changed camera statistics");
      require(
          gpu.pickingVersions().equals(Map.of(ground, 1L)) && gl.shadowStatistics().draws() == 2,
          "Off-camera caster or camera picking metadata");
      var versions = gpu.pickingVersions();
      var levels = gpu.drawnDetailLevels();
      gl.sceneView(origin, wanted, true, false, 0);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      require(Arrays.equals(shadow, read(gl)), "Indirect shadow parity");
      require(
          versions.equals(gpu.pickingVersions()) && levels.equals(gpu.drawnDetailLevels()),
          "Shadow changed detail/version metadata");
      gpu.remove(filler);
      require(gpu.requestCompaction(), "Shadow compaction request");
      long moved = 0;
      int steps = 0;
      while (gpu.compacting() && steps++ < 1000) {
        gpu.beginFrame();
        int bytes = gpu.advanceCompaction(192);
        require(bytes <= 192, "Shadow compaction byte budget");
        moved += bytes;
        gl.render(SIZE, SIZE, CAMERA, false, 1);
        require(Arrays.equals(shadow, read(gl)), "Partial compaction changed shadow pixels");
        require(
            versions.equals(gpu.pickingVersions()) && levels.equals(gpu.drawnDetailLevels()),
            "Compaction changed camera metadata");
      }
      require(!gpu.compacting() && moved > 0, "Shadow compaction did not finish");
      report.put("relocatedBytes", moved);
      report.put("compactionSteps", steps);
      gl.sceneView(origin, wanted, false, false, 1.5f);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      save(out, frames, "stream-fog", gl);
      gl.sceneView(origin, wanted, false, false, 0);
      gpu.remove(caster);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      save(out, frames, "stream-removed", gl);
      expect(gl, 64, 64, 238);
      var negative = new ChunkPos(-16_777_217, -9, -16_777_219);
      var negativeCaster = new ChunkPos(negative.x() + 1, negative.y() + 1, negative.z() + 1);
      gpu = gl.scene(2, 7 << 20);
      upload(gpu, new ChunkScene.Upload(2, negative, 1, floor));
      upload(gpu, new ChunkScene.Upload(2, negativeCaster, 1, box));
      origin.set(
          (double) negative.x() * 32, (double) negative.y() * 32, (double) negative.z() * 32);
      gl.sceneView(origin, Set.of(negative, negativeCaster), false, false, 0);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      require(
          Arrays.equals(shadow, save(out, frames, "stream-negative", gl)),
          "Large signed camera-relative shadow parity");
      report.put("negativeOrigin", List.of(origin.x, origin.y, origin.z));
      report.put("mixedUnitsPerBlock", List.of(floor.unitsPerBlock(), box.unitsPerBlock()));
      report.put("cameraDraws", gpu.statistics().draws());
      report.put("shadowDraws", gl.shadowStatistics().draws());
      require(
          gl.shadowStatistics().bytes() + gpu.statistics().capacityBytes() == 8 << 20,
          "Combined shadow/arena budget");
      gl.shadowAppearance(false, 512, 8 << 20);
      gl.render(SIZE, SIZE, CAMERA, false, 1);
      save(out, frames, "stream-disabled", gl);
      expect(gl, 64, 64, 238);
      require(gl.shadowStatistics().bytes() == 0, "Disabled map survived");
      report.put("disabledCleanup", true);
    }
    nativeState(report);
    if (handle != 0) production(out, handle, report);
    report.put("frames", frames);
    require(glGetError() == GL_NO_ERROR, "Shadow GL error");
    Files.writeString(out.resolve("shadow-native.json"), ProjectIO.JSON.writeValueAsString(report));
    System.out.println(
        "SHADOW_NATIVE_PASS analytical directional occlusion / complete-only off-camera casters /"
            + " mixed packed precision / indirect-compaction-signed parity / bounded depth"
            + " allocation / GL state cleanup / "
            + glGetString(GL_VERSION));
  }

  private static void nativeState(Map<String, Object> report) {
    int read = glGenFramebuffers(),
        draw = glGenFramebuffers(),
        texture = glGenTextures(),
        pbo = glGenBuffers(),
        vao = glGenVertexArrays();
    glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, texture);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
    glBufferData(GL_PIXEL_UNPACK_BUFFER, 16, GL_STREAM_DRAW);
    glBindVertexArray(vao);
    glVertexAttrib4f(4, 1, 2, 3, 4);
    glViewport(2, 3, 37, 41);
    glDepthMask(false);
    glDepthFunc(GL_GEQUAL);
    glClearDepth(.25);
    glDepthRange(.2, .8);
    glEnable(GL_SCISSOR_TEST);
    glScissor(1, 2, 3, 4);
    glEnable(GL_CULL_FACE);
    glEnable(GL_POLYGON_OFFSET_FILL);
    glEnable(GL_RASTERIZER_DISCARD);
    glDisable(GL_DEPTH_TEST);
    int oldTexture, oldFramebuffer;
    try (var map = new GlShadowMap()) {
      map.allocate(512);
      oldTexture = map.texture();
      oldFramebuffer = map.framebuffer();
      require(map.bytes() == 1 << 20, "Depth allocation accounting");
      require(
          glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING) == pbo
              && glGetInteger(GL_TEXTURE_BINDING_2D) == texture
              && glGetInteger(GL_ACTIVE_TEXTURE) == GL_TEXTURE2,
          "Allocation texture/PBO ownership");
      try {
        map.render(
            DirectionalShadow.projection(new DirectionalShadow.Bounds(0, 0, 0, 32, 32, 32), 512),
            (program, matrix) -> {
              throw new IllegalStateException("Injected depth draw failure");
            });
        throw new AssertionError("Injected failure missing");
      } catch (IllegalStateException expected) {
        require(expected.getMessage().equals("Injected depth draw failure"), "Wrong depth failure");
      }
      int[] viewport = new int[4];
      glGetIntegerv(GL_VIEWPORT, viewport);
      float[] attribute = new float[4];
      glGetVertexAttribfv(4, GL_CURRENT_VERTEX_ATTRIB, attribute);
      double[] range = new double[2];
      glGetDoublev(GL_DEPTH_RANGE, range);
      require(
          glGetInteger(GL_READ_FRAMEBUFFER_BINDING) == read
              && glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == draw,
          "Split framebuffer ownership");
      require(
          Arrays.equals(viewport, new int[] {2, 3, 37, 41})
              && glGetInteger(GL_VERTEX_ARRAY_BINDING) == vao
              && Arrays.equals(attribute, new float[] {1, 2, 3, 4}),
          "Viewport/VAO/attribute ownership");
      require(
          !glGetBoolean(GL_DEPTH_WRITEMASK)
              && glGetInteger(GL_DEPTH_FUNC) == GL_GEQUAL
              && glGetDouble(GL_DEPTH_CLEAR_VALUE) == .25
              && Math.abs(range[0] - .2) < 1e-6
              && Math.abs(range[1] - .8) < 1e-6,
          "Depth state ownership");
      require(
          !glIsEnabled(GL_DEPTH_TEST)
              && glIsEnabled(GL_CULL_FACE)
              && glIsEnabled(GL_SCISSOR_TEST)
              && glIsEnabled(GL_POLYGON_OFFSET_FILL)
              && glIsEnabled(GL_RASTERIZER_DISCARD),
          "Raster state ownership");
      map.close();
      require(
          !glIsTexture(oldTexture) && !glIsFramebuffer(oldFramebuffer),
          "Depth objects survived close");
      map.allocate(1024);
      require(map.bytes() == 4 << 20, "Depth replacement accounting");
    } finally {
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      glBindVertexArray(0);
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
      glBindTexture(GL_TEXTURE_2D, 0);
      glActiveTexture(GL_TEXTURE0);
      glDepthMask(true);
      glDepthFunc(GL_LESS);
      glDepthRange(0, 1);
      glClearDepth(1);
      glDisable(GL_SCISSOR_TEST);
      glDisable(GL_CULL_FACE);
      glDisable(GL_POLYGON_OFFSET_FILL);
      glDisable(GL_RASTERIZER_DISCARD);
      glDisable(GL_DEPTH_TEST);
      glDeleteFramebuffers(read);
      glDeleteFramebuffers(draw);
      glDeleteTextures(texture);
      glDeleteBuffers(pbo);
      glDeleteVertexArrays(vao);
    }
    report.put("stateRestoredAfterFailure", true);
    report.put("driverObjectsDeleted", true);
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
      p.sceneCpuMiB = 256;
      p.sceneGpuMiB = 8;
      p.streamChunks = 27;
      state.user.editor.opportunitiesPerSecond = 20;
      require(
          state.change(
              project -> {
                var noise = new Noise();
                noise.id = "shadow-plane";
                noise.dimension = 3;
                noise.algorithm.key = "CONSTANT";
                var item = ProcessingRegistry.create("EXPRESSION", "plane", 3);
                item.parameters.put("expression", ProjectIO.JSON.valueToTree("y-1.25"));
                noise.processing.add(item);
                noise.outputBinding = Binding.earlier(item.uuid);
                project.noise3D.add(noise);
              }),
          "Shadow project");
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      views = new Viewports(state, new PropertyPanel(state), handle);
      panel = new ProfilePanel(state, target -> {});
      try {
        var capture = state.engine.profiler().start(new Profiler.Options(60000, 4096, 128, 65536));
        views.probeSurface(state.project().noise3D.getFirst().uuid, false, 16, 16, 16);
        views.probeSurfaceDistance(35);
        until(() -> views.probeSurfaceReady() && views.probeShadowArenaBytes(1) == 8 << 20);
        long epoch = views.probeSurfaceEpoch(), stamp = views.probeSubmissionStamp(1);
        var stages = new ArrayList<Map<String, Object>>();
        state.user.editor.autoRegenerate = false;
        state.user.visualization.shadows = true;
        state.user.visualization.shadowResolution = 2048;
        until(() -> ready(1024, 4 << 20));
        stages.add(stage(2048));
        state.user.visualization.shadowResolution = 512;
        until(() -> ready(512, 7 << 20));
        stages.add(stage(512));
        views.retryShadows();
        until(() -> ready(512, 7 << 20));
        until(
            () ->
                capture.snapshot().samples().stream()
                    .anyMatch(
                        s ->
                            s.kind() == Profiler.Kind.SCENE_GPU_BYTES
                                && s.label().equals("Shadow depth-map allocation")
                                && s.value() > 0));
        state.user.save(out.resolve("shadow-user-config.json"));
        state.user.visualization.shadows = false;
        until(() -> views.shadowBytes() == 0 && views.probeShadowArenaBytes(1) == 8 << 20);
        stages.add(stage(512));
        for (int i = 0; i < 3; i++) frame();
        require(
            epoch == views.probeSurfaceEpoch() && stamp == views.probeSubmissionStamp(1),
            "Shadow settings resampled source or replaced epoch");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Shadow preferences changed authored document/history");
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
        var snapshot = capture.snapshot();
        require(
            snapshot.samples().stream()
                .anyMatch(
                    s ->
                        s.kind() == Profiler.Kind.SCENE_GPU_BYTES
                            && s.label().equals("Shadow depth-map allocation")
                            && s.value() > 0),
            "Shadow allocation profiler metric missing");
        ProfileIO.writeJSON(out.resolve("shadow-profile.json"), snapshot);
        ProfileIO.writeCSV(out.resolve("shadow-profile.csv"), snapshot);
        new ProjectIO().save(out.resolve("shadow-project.json"), state.project());
        report.put("productionStages", stages);
        report.put("productionEpoch", epoch);
        report.put("productionSubmissionStamp", stamp);
        report.put("productionViewOnly", true);
        report.put("productionShadowBytesAfterDisable", views.shadowBytes());
      } finally {
        views.close();
      }
    }
    glfwHideWindow(handle);
  }

  private static Map<String, Object> stage(int requested) {
    var shadow = views.shadowStatistics().get(1);
    return Map.of(
        "requested",
        requested,
        "effective",
        shadow.resolution(),
        "arenaBytes",
        views.probeShadowArenaBytes(1),
        "shadowBytes",
        shadow.bytes());
  }

  private static boolean ready(int resolution, int arena) {
    var stats = views.shadowStatistics().get(1);
    if (stats.state().equals("FAILED")) throw new AssertionError(stats.toString());
    return views.probeSurfaceReady()
        && stats.state().equals("READY")
        && stats.resolution() == resolution
        && views.probeShadowArenaBytes(1) == arena;
  }

  private static void until(BooleanSupplier done) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!done.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end)
        throw new AssertionError("Shadow production timeout: " + views.shadowStatistics());
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

  private static void upload(SceneGpuRenderer gpu, ChunkScene.Upload value) {
    require(gpu.stage(value), "Shadow upload admission");
    while (gpu.pending()) {
      gpu.beginFrame();
      gpu.advance(192);
    }
  }

  private static void ready(GlViewport gl, int resolution) {
    require(
        gl.shadowStatistics().state().equals("READY")
            && gl.shadowStatistics().resolution() == resolution,
        gl.shadowStatistics().toString());
  }

  private static byte[] read(GlViewport gl) {
    var b = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
    glBindTexture(GL_TEXTURE_2D, gl.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
    byte[] result = new byte[b.remaining()];
    b.get(result);
    return result;
  }

  private static List<Integer> pixel(byte[] b, int x, int y) {
    int i = (y * SIZE + x) * 4;
    return List.of(
        Byte.toUnsignedInt(b[i]),
        Byte.toUnsignedInt(b[i + 1]),
        Byte.toUnsignedInt(b[i + 2]),
        Byte.toUnsignedInt(b[i + 3]));
  }

  private static void expect(GlViewport gl, int x, int y, int target) {
    var p = pixel(read(gl), x, y);
    for (int i = 0; i < 3; i++)
      require(
          Math.abs(p.get(i) - target) <= 3,
          "Analytical shadow pixel: " + p + " expected " + target);
  }

  private static byte[] save(Path out, List<Map<String, Object>> frames, String name, GlViewport gl)
      throws Exception {
    byte[] data = read(gl);
    Files.write(out.resolve(name + ".rgba"), data);
    frames.add(
        Map.of(
            "name",
            name,
            "sha256",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)),
            "shadow",
            pixel(data, 64, 64),
            "plain",
            pixel(data, 32, 96),
            "statistics",
            gl.shadowStatistics()));
    return data;
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
