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
  public static void main(String[] args) {
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
        System.out.println(
            "STREAMING_RENDER_NATIVE_PASS packed / budgeted upload / culling / palette / indirect="
                + GL.getCapabilities().OpenGL43);
      }
    } finally {
      if (window != 0) glfwDestroyWindow(window);
      glfwTerminate();
    }
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
