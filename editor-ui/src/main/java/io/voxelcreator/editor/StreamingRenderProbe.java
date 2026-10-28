package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

import io.voxelcreator.render.*;
import io.voxelcreator.storage.*;
import java.util.*;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/**
 * Numerical native check for packed geometry, bounded publication, culling, palette and indirect
 * parity.
 */
public final class StreamingRenderProbe {
  public static void main(String[] args) throws Exception {
    System.setProperty("joml.nounsafe", "true");
    if (!glfwInit()) throw new IllegalStateException("GLFW init");
    long window = 0;
    try {
      glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
      window = glfwCreateWindow(129, 129, "Streaming probe", 0, 0);
      if (window == 0) throw new IllegalStateException("OpenGL 3.0 required");
      glfwMakeContextCurrent(window);
      GL.createCapabilities();
      if (Arrays.asList(args).contains("--require-gl30")
          && (!GL.getCapabilities().OpenGL30 || GL.getCapabilities().OpenGL31))
        throw new AssertionError("Expected an actual OpenGL 3.0 capability path");
      System.out.println("STREAMING_PROBE_ENV gl=" + glGetString(GL_VERSION));
      try (var view = new GlViewport()) {
        int[] palette = new int[20000];
        Arrays.fill(palette, 0xff000000);
        palette[1] = 0xff0000ff;
        palette[19999] = 0xff00ff00;
        view.setPalette(palette);
        var gpu = view.scene(1, 1 << 20);
        Set<ChunkPos> wanted =
            Set.of(new ChunkPos(0, 0, 0), new ChunkPos(1, 0, 0), new ChunkPos(100, 0, 0));
        int size = 32;
        for (var position : wanted) {
          var mesh =
              VoxelMesher.greedy(
                  size,
                  (x, y, z) -> {
                    int worldX = position.x() * size + x;
                    return worldX >= 0 && worldX < 64 && y >= 0 && y < 32 && z >= 0 && z < 32
                        ? (worldX < 32 ? 1 : 19999)
                        : 0;
                  });
          var upload = new ChunkScene.Upload(1, position, 1, mesh);
          if (!gpu.stage(upload)) throw new AssertionError("Unexpected arena rejection");
          boolean complete = false;
          for (int i = 0; i < 500 && !complete; i++) {
            gpu.beginFrame();
            complete = gpu.advance(64) != null;
            if (gpu.statistics().uploadedBytes() > 64)
              throw new AssertionError("Upload budget exceeded");
          }
          if (!complete) throw new AssertionError("Bounded upload failed to finish");
        }
        Camera camera = new Camera();
        camera.flying = true;
        camera.position.set(32, 16, 96);
        camera.yaw = 0;
        camera.pitch = 0;
        var origin = new Vector3d();
        var matrix = camera.matrix(1, origin);
        view.sceneView(origin, wanted, false, false, 0);
        view.render(129, 129, matrix, true, 1);
        byte[] fallback = read(view);
        if (!gpu.pickingVersions()
            .equals(Map.of(new ChunkPos(0, 0, 0), 1L, new ChunkPos(1, 0, 0), 1L)))
          throw new AssertionError(
              "Picking coverage included empty/culled or missing drawn chunks");
        if (gpu.statistics().visible() != 2
            || gpu.statistics().culled() != 1
            || gpu.statistics().draws() != 2)
          throw new AssertionError("Frustum/fallback draw counts: " + gpu.statistics());
        int left = (64 * 129 + 32) * 4, right = (64 * 129 + 96) * 4;
        if (Byte.toUnsignedInt(fallback[left]) < 245 || Byte.toUnsignedInt(fallback[left + 1]) > 10)
          throw new AssertionError("Packed left cube/material color");
        if (Byte.toUnsignedInt(fallback[right + 1]) < 245)
          throw new AssertionError("Multi-row palette/material color");
        view.sceneView(origin, wanted, true, true, 0);
        view.render(129, 129, matrix, true, 1);
        byte[] indirect = read(view);
        if (!Arrays.equals(fallback, indirect))
          throw new AssertionError("Indirect/fallback image mismatch");
        if (GL.getCapabilities().OpenGL43 && gpu.statistics().draws() != 1)
          throw new AssertionError("Indirect batch did not reduce draw calls");
        long bytes = gpu.statistics().gpuBytes();
        palette[1] = 0xffff0000;
        view.setPalette(palette);
        view.render(129, 129, matrix, true, 1);
        byte[] recolored = read(view);
        if (Byte.toUnsignedInt(recolored[left + 2]) < 245 || gpu.statistics().gpuBytes() != bytes)
          throw new AssertionError("Palette-only refresh changed geometry or failed");
        if (glGetError() != GL_NO_ERROR) throw new AssertionError("OpenGL error");
        var empty = new ChunkPos(0, 0, 1);
        upload(gpu, new ChunkScene.Upload(1, empty, 2, VoxelMesher.greedy(32, (x, y, z) -> 0)));
        var withEmpty = new HashSet<>(wanted);
        withEmpty.add(empty);
        view.sceneView(origin, withEmpty, false, false, 0);
        view.render(129, 129, matrix, true, 1);
        if (gpu.pickingVersions().containsKey(empty) || gpu.pickingVersions().size() != 2)
          throw new AssertionError("Empty allocation entered the picking coverage");
        verifyCompaction(view, matrix, origin);
        System.out.println(
            "STREAMING_RENDER_NATIVE_PASS packed / budgeted upload / culling / palette / indirect="
                + GL.getCapabilities().OpenGL43);
      }
      if (Arrays.asList(args).contains("--require-gl30")) {
        VolumeOverlapProbe.run(java.nio.file.Path.of("build/world-picking/volume-overlap-gl30"));
        SurfaceLodProbe.run(java.nio.file.Path.of("build/world-picking/surface-lod-gl30"));
        GpuTimingProbe.run(java.nio.file.Path.of("build/world-picking/gpu-timing-gl30"));
        ShadowProbe.run(java.nio.file.Path.of("build/world-picking/shadows-gl30"));
        VolumeLightProbe.run(java.nio.file.Path.of("build/world-picking/volume-light-gl30"));
      }
    } finally {
      if (window != 0) glfwDestroyWindow(window);
      glfwTerminate();
    }
  }

  private static void verifyCompaction(GlViewport view, org.joml.Matrix4f matrix, Vector3d origin) {
    var left = new ChunkPos(0, 0, 0);
    var right = new ChunkPos(1, 0, 0);
    var gap = new ChunkPos(200, 0, 0);
    var spacer = new ChunkPos(201, 0, 0);
    var retired = new ChunkPos(202, 0, 0);
    var tail = new ChunkPos(203, 0, 0);
    var newPosition = new ChunkPos(204, 0, 0);
    var leftMesh = cube(1);
    var rightMesh = cube(19999);
    var point = points(leftMesh, 1);
    var twoPoints = points(leftMesh, 2);
    int capacity = leftMesh.byteSize() + rightMesh.byteSize() + 80;
    var gpu = view.scene(9, capacity);
    upload(gpu, new ChunkScene.Upload(9, gap, 1, point));
    upload(gpu, new ChunkScene.Upload(9, left, 1, leftMesh));
    upload(gpu, new ChunkScene.Upload(9, spacer, 1, twoPoints));
    upload(gpu, new ChunkScene.Upload(9, right, 1, rightMesh));
    upload(gpu, new ChunkScene.Upload(9, retired, 1, point));
    upload(gpu, new ChunkScene.Upload(9, tail, 1, point));
    if (gpu.requestCompaction())
      throw new AssertionError("Packed arena should not need compaction");
    Set<ChunkPos> wanted = Set.of(left, right);
    byte[] baseline = renderRead(view, matrix, origin, wanted, false);
    byte[] leftOnly = renderRead(view, matrix, origin, Set.of(left), false);
    if (!gpu.pickingVersions().equals(Map.of(left, 1L)))
      throw new AssertionError("Picking coverage retained a non-displayed resident");
    gpu.remove(gap);
    gpu.remove(spacer);
    gpu.remove(tail);
    if (gpu.statistics().freeBytes() != 64 || gpu.statistics().largestFree() != 32)
      throw new AssertionError("Expected fragmented arena: " + gpu.statistics());
    var rejected = new ChunkScene.Upload(9, newPosition, 2, points(leftMesh, 3));
    if (gpu.stage(rejected) || !gpu.compacting() || gpu.pending())
      throw new AssertionError("Fragmented allocation should start bounded compaction");
    int frame = 0;
    while (gpu.compacting()) {
      if (++frame > 100) throw new AssertionError("Compaction failed to finish");
      gpu.beginFrame();
      int moved = gpu.advanceCompaction(48);
      if (moved <= 0
          || moved > 48
          || gpu.statistics().relocatedBytes() != moved
          || gpu.statistics().uploadedBytes() != 0
          || gpu.statistics().capacityBytes() != capacity)
        throw new AssertionError("Relocation transfer or allocation budget: " + gpu.statistics());
      if (frame == 2) {
        gpu.remove(retired);
        if (gpu.statistics().retiring() != 1)
          throw new AssertionError("Removal was not deferred safely");
        if (gpu.stage(rejected)) throw new AssertionError("Upload admitted during relocation");
      }
      if (!Arrays.equals(baseline, renderRead(view, matrix, origin, wanted, false))
          || !Arrays.equals(baseline, renderRead(view, matrix, origin, wanted, true)))
        throw new AssertionError(
            "Partial overlapping relocation changed rendered geometry at step " + frame);
      if (!gpu.contains(new ChunkScene.Upload(9, left, 1, leftMesh)))
        throw new AssertionError("Relocation changed a resident version");
    }
    if (gpu.statistics().compactions() != 1
        || gpu.statistics().freeBytes() != 80
        || gpu.statistics().largestFree() != 80
        || gpu.statistics().retiring() != 0)
      throw new AssertionError("Compaction did not coalesce its free tail: " + gpu.statistics());
    if (!gpu.stage(rejected))
      throw new AssertionError("Compaction failed to admit the previously rejected mesh");
    gpu.remove(left);
    if (!gpu.requestCompaction() || !gpu.statistics().compactionRequested())
      throw new AssertionError("Explicit compaction was not deferred for pending upload");
    byte[] rightOnly = renderRead(view, matrix, origin, Set.of(right), false);
    while (gpu.pending()) {
      gpu.beginFrame();
      gpu.advance(16);
    }
    gpu.beginFrame();
    if (!gpu.compacting())
      throw new AssertionError("Deferred compaction did not start after upload");
    gpu.advanceCompaction(48);
    if (!Arrays.equals(rightOnly, renderRead(view, matrix, origin, Set.of(right), false)))
      throw new AssertionError("Explicit partial relocation changed the remaining mesh");
    gpu.epoch(10);
    if (!gpu.pickingVersions().isEmpty())
      throw new AssertionError("New epoch retained picking coverage");
    if (gpu.hasData()
        || gpu.compacting()
        || gpu.pending()
        || gpu.statistics().compactionRequested()
        || gpu.statistics().freeBytes() != capacity)
      throw new AssertionError("New epoch retained obsolete relocation state");
    upload(gpu, new ChunkScene.Upload(10, left, 1, leftMesh));
    if (!Arrays.equals(leftOnly, renderRead(view, matrix, origin, Set.of(left), false)))
      throw new AssertionError("Epoch replacement after partial compaction changed fresh geometry");
    var partial = new ChunkScene.Upload(10, newPosition, 3, points(leftMesh, 3));
    if (!gpu.stage(partial)) throw new AssertionError("Pending cancellation setup");
    gpu.advance(16);
    gpu.remove(newPosition);
    if (gpu.pending() || gpu.statistics().gpuBytes() != leftMesh.byteSize())
      throw new AssertionError("Cancelled partial upload leaked an allocation");
    var pointPosition = new ChunkPos(1, 0, 0);
    upload(gpu, new ChunkScene.Upload(10, pointPosition, 1, centerPoints()));
    gpu.remove(left);
    var pointWanted = Set.of(pointPosition);
    byte[] pointImage = renderRead(view, matrix, origin, pointWanted, false);
    if (Arrays.equals(pointImage, renderRead(view, matrix, origin, Set.of(), false)))
      throw new AssertionError("Visible point relocation fixture rendered no points");
    if (!gpu.requestCompaction())
      throw new AssertionError("Point relocation request did not start");
    while (gpu.compacting()) {
      gpu.beginFrame();
      if (gpu.advanceCompaction(16) != 16) throw new AssertionError("Point primitive copy budget");
      if (!Arrays.equals(pointImage, renderRead(view, matrix, origin, pointWanted, false))
          || !Arrays.equals(pointImage, renderRead(view, matrix, origin, pointWanted, true)))
        throw new AssertionError("Partial point relocation changed framebuffer pixels");
    }
    if (glGetError() != GL_NO_ERROR) throw new AssertionError("OpenGL compaction error");
    System.out.println(
        "GPU_ARENA_COMPACTION_NATIVE_PASS overlapping moves / primitive budgets / incremental"
            + " pixels / indirect parity / deferred removal / admission retry / explicit request /"
            + " epoch replacement");
  }

  private static PackedMesh cube(int type) {
    return VoxelMesher.greedy(
        32, (x, y, z) -> x >= 0 && y >= 0 && z >= 0 && x < 32 && y < 32 && z < 32 ? type : 0);
  }

  private static PackedMesh points(PackedMesh source, int count) {
    byte[] bytes = new byte[count * PackedMesh.STRIDE];
    source.readOnly().get(bytes);
    return new PackedMesh(bytes, true);
  }

  private static PackedMesh centerPoints() {
    var bytes = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    for (int x : new int[] {8, 16, 24}) {
      bytes.putShort((short) (x * 2)).putShort((short) 32).putShort((short) 32);
      bytes
          .put((byte) 0)
          .put((byte) 127)
          .put((byte) 0)
          .put((byte) 255)
          .putShort((short) 0)
          .putInt(1);
    }
    return new PackedMesh(bytes.array(), true);
  }

  private static void upload(SceneGpuRenderer gpu, ChunkScene.Upload upload) {
    if (!gpu.stage(upload))
      throw new AssertionError("Probe allocation rejected: " + gpu.statistics());
    while (gpu.pending()) {
      gpu.beginFrame();
      gpu.advance(1024);
    }
  }

  private static byte[] renderRead(
      GlViewport view,
      org.joml.Matrix4f matrix,
      Vector3d origin,
      Set<ChunkPos> wanted,
      boolean indirect) {
    view.sceneView(origin, wanted, indirect, false, 0);
    view.render(129, 129, matrix, true, 1);
    return read(view);
  }

  private static byte[] read(GlViewport view) {
    var pixels = BufferUtils.createByteBuffer(129 * 129 * 4);
    glBindTexture(GL_TEXTURE_2D, view.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    byte[] bytes = new byte[pixels.remaining()];
    pixels.get(bytes);
    return bytes;
  }
}
