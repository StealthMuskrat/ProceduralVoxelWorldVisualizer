package io.voxelcreator.editor;

import static org.lwjgl.opengl.GL33.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.render.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Deferred native contracts for coarse centers, grid-compatible copies and world-unit optics. */
final class VolumeCoarseProbe {
  private static final int IMAGE = 65;

  private record Case(String id, int size, int step, int x, long copied) {}

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases =
        List.of(
            new Case("initial", 16, 2, -32, 0),
            new Case("aligned", 16, 2, -16, 8192),
            new Case("different-phase", 16, 2, -15, 0),
            new Case("coarser", 8, 4, -16, 0),
            new Case("coarse-aligned", 8, 4, 0, 1024),
            new Case("refined", 32, 1, 0, 0));
    var rows = new ArrayList<Map<String, Object>>();
    var optics = new ArrayList<Map<String, Object>>();
    int[] palette = new int[256];
    Arrays.fill(palette, 0xff0000ff);
    var camera = new Camera();
    camera.flying = true;
    camera.yaw = camera.pitch = 0;
    try (var gl = new GlViewport();
        var reference = new GlViewport()) {
      gl.setPalette(palette);
      reference.setPalette(palette);
      gl.volumeAppearance(0, 1, .01, -Double.MAX_VALUE, 64);
      reference.volumeAppearance(0, 1, .01, -Double.MAX_VALUE, 64);
      Matrix4f previousMatrix = null;
      byte[] previous = null;
      for (var c : cases) {
        var origin = new Vector3d(c.x, -32, -32);
        var data =
            VolumeData.sampleGrid(
                c.size,
                c.step,
                c.x,
                -32,
                -32,
                (x, y, z) -> .5 + (x + 2 * y + 4 * z) / 256,
                new CancellationToken());
        camera.position.set(origin).add(16, 16, 80);
        var matrix = camera.matrix(1, origin);
        var before = gl.volumeStatistics();
        var resident = gl.volumeResident();
        require(
            gl.stageVolume(
                data, 4L << 20, new VolumeTextureUpload.Region("affine", c.x, -32, -32, c.step)),
            "Coarse stage " + c.id);
        int total = gl.advanceVolume(4), maximum = total;
        if (resident != null) {
          require(gl.volumeResident() == resident, "Partial coarse metadata published");
          gl.render(IMAGE, IMAGE, previousMatrix, false, 1);
          require(
              Arrays.equals(previous, gl.rgba()), "Partial coarse upload changed accepted display");
        }
        while (gl.volumeStatistics().pending()) {
          int written = gl.advanceVolume(511);
          require(written <= 511, "Coarse upload budget");
          total += written;
          maximum = Math.max(maximum, written);
        }
        require(total == 4 * c.size * c.size * c.size, "Coarse destination write count");
        long copied = gl.volumeStatistics().copiedBytes() - before.copiedBytes();
        require(copied == c.copied, "Coarse copy split " + c.id + ": " + copied);
        require(
            gl.volumeResident().extent() == 32 && gl.volumeResident().sampleStep() == c.step,
            "Accepted physical extent");
        glBindTexture(GL_TEXTURE_3D, gl.volumeResident().texture());
        var texels = BufferUtils.createFloatBuffer(c.size * c.size * c.size);
        glGetTexImage(GL_TEXTURE_3D, 0, GL_RED, GL_FLOAT, texels);
        float[] expected = data.copySamples();
        var raw = ByteBuffer.allocate(4 * expected.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < expected.length; i++) {
          require(
              Float.floatToRawIntBits(texels.get(i)) == Float.floatToRawIntBits(expected[i]),
              "Coarse texel " + c.id + " / " + i);
          raw.putFloat(texels.get(i));
        }
        Files.write(out.resolve(c.id + ".bin"), raw.array());
        gl.render(IMAGE, IMAGE, matrix, false, 1);
        previous = gl.rgba();
        previousMatrix = matrix;
        reference.setVolume(data);
        reference.render(IMAGE, IMAGE, matrix, false, 1);
        require(
            Arrays.equals(previous, reference.rgba()),
            "Copied/full-host coarse framebuffer parity " + c.id);
        rows.add(
            Map.of(
                "id",
                c.id,
                "textureSide",
                c.size,
                "sampleStep",
                c.step,
                "extent",
                32,
                "origin",
                List.of(c.x, -32, -32),
                "copiedBytes",
                copied,
                "destinationBytes",
                total,
                "maximumBytes",
                maximum,
                "frameSha256",
                hash(previous),
                "referenceSha256",
                hash(reference.rgba())));
      }
      camera.position.set(16, 16, 80);
      var matrix = camera.matrix(1, new Vector3d());
      for (boolean light : new boolean[] {false, true}) {
        byte[] unit = null;
        for (int step : new int[] {1, 2, 4}) {
          var data =
              VolumeData.sampleGrid(
                  32 / step, step, 0, 0, 0, (x, y, z) -> 1, new CancellationToken());
          gl.setVolume(data);
          gl.volumeAppearance(0, 1, .01, 0, 128);
          gl.volumeLighting(light, 16);
          gl.render(IMAGE, IMAGE, matrix, false, 1);
          byte[] frame = gl.rgba();
          if (unit == null) unit = frame;
          else require(Arrays.equals(unit, frame), "Homogeneous optics changed with spacing");
          String id = "constant-" + step + (light ? "-light" : "");
          Files.write(out.resolve(id + ".rgba"), frame);
          int pixel = (IMAGE / 2 * IMAGE + IMAGE / 2) * 4;
          optics.add(
              Map.of(
                  "id",
                  id,
                  "sampleStep",
                  step,
                  "light",
                  light,
                  "extent",
                  32,
                  "textureSide",
                  data.size(),
                  "pixel",
                  List.of(
                      frame[pixel] & 255,
                      frame[pixel + 1] & 255,
                      frame[pixel + 2] & 255,
                      frame[pixel + 3] & 255),
                  "sha256",
                  hash(frame)));
        }
      }
    }
    require(glGetError() == GL_NO_ERROR, "Coarse volume GL error");
    Files.writeString(
        out.resolve("volume-coarse-native.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "glVersion",
                glGetString(GL_VERSION),
                "openGL31",
                GL.getCapabilities().OpenGL31,
                "cases",
                rows,
                "optics",
                optics,
                "partialRetained",
                true,
                "fullHostParity",
                true)));
    System.out.println(
        "VOLUME_COARSE_NATIVE_PASS signed coarse centers / spacing-phase copy gates / partial"
            + " metadata / full-host parity / physical optics and lighting");
  }

  private static String hash(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
