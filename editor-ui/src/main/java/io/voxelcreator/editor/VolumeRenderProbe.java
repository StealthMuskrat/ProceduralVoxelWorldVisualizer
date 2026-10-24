package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

import io.voxelcreator.render.*;
import java.util.Arrays;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Native numerical volume acceptance probe, separate from the interactive desktop. */
public final class VolumeRenderProbe {
  public static void main(String[] args) {
    System.setProperty("joml.nounsafe", "true");
    if (!glfwInit()) throw new IllegalStateException("GLFW init");
    long window = 0;
    try {
      glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
      window = glfwCreateWindow(64, 64, "Volume probe", 0, 0);
      if (window == 0) throw new IllegalStateException("OpenGL 3.0 context");
      glfwMakeContextCurrent(window);
      GL.createCapabilities();
      try (var viewport = new GlViewport()) {
        int[] palette = new int[256];
        Arrays.fill(palette, 0xff0000ff);
        viewport.setPalette(palette);
        Camera camera = new Camera();
        camera.flying = true;
        camera.position.set(2, 2, 10);
        camera.yaw = 0;
        camera.pitch = 0;
        var matrix = camera.matrix(1, new Vector3d());
        viewport.setVolume(VolumeData.sample(4, 0, 0, 0, (x, y, z) -> 1));
        viewport.volumeAppearance(0, 1, .5, 0, 192);
        viewport.render(65, 65, matrix, true, 1);
        check(viewport, StrictMath.exp(-2), "homogeneous absorption");
        viewport.volumeAppearance(0, 1, .5, 2, 192);
        viewport.render(65, 65, matrix, true, 1);
        check(viewport, 1, "threshold");
        viewport.setImage(2, 2, new int[] {0, 0, 0, 0});
        viewport.setVolume(VolumeData.sample(4, 0, 0, 0, (x, y, z) -> z / 4));
        viewport.volumeAppearance(0, 1, .5, 0, 256);
        viewport.render(65, 65, matrix, true, 1);
        check(viewport, StrictMath.exp(-1), "trilinear gradient / image-to-volume resize");
        if (glGetError() != GL_NO_ERROR) throw new AssertionError("OpenGL error");
        System.out.println(
            "VOLUME_NUMERICAL_NATIVE_PASS homogeneous / threshold / gradient / framebuffer"
                + " transition");
      }
    } finally {
      if (window != 0) glfwDestroyWindow(window);
      glfwTerminate();
    }
  }

  private static void check(GlViewport viewport, double transmittance, String scenario) {
    var pixels = BufferUtils.createByteBuffer(65 * 65 * 4);
    glBindTexture(GL_TEXTURE_2D, viewport.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    int index = (32 * 65 + 32) * 4;
    double[] expected = {
      255 * (1 - transmittance + transmittance * .055),
      255 * transmittance * .075,
      255 * transmittance * .1
    };
    for (int c = 0; c < 3; c++) {
      int actual = Byte.toUnsignedInt(pixels.get(index + c));
      if (Math.abs(actual - expected[c]) > 2)
        throw new AssertionError(
            scenario + " channel " + c + ": expected " + expected[c] + " observed " + actual);
    }
  }
}
