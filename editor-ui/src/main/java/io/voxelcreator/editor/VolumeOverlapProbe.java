package io.voxelcreator.editor;

import static org.lwjgl.opengl.GL33.*;

import io.voxelcreator.core.NoiseAlgorithms.Field;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.render.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;

/**
 * OpenGL 3.0 layer blits: exact signed texels, split writes, retained display and full-upload
 * parity.
 */
final class VolumeOverlapProbe {
  private record Case(
      String id, int side, int x, int y, int z, String source, String field, long copied) {}

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases =
        List.of(
            new Case("initial", 64, 0, 0, 0, "affine", "affine", 0),
            new Case("positive-x", 64, 32, 0, 0, "affine", "affine", 524288),
            new Case("negative-y", 64, 32, -32, 0, "affine", "affine", 524288),
            new Case("positive-z", 64, 32, -32, 32, "affine", "affine", 524288),
            new Case("diagonal", 64, 48, -16, 48, "affine", "affine", 442368),
            new Case("same-window", 64, 48, -16, 48, "affine", "affine", 1048576),
            new Case("shrink", 32, 64, 0, 64, "affine", "affine", 131072),
            new Case("expand", 64, 48, -16, 48, "affine", "affine", 131072),
            new Case("disjoint", 64, 256, 256, 256, "affine", "affine", 0),
            new Case("different-source", 64, 256, 256, 256, "negative", "negative", 0),
            new Case("piecewise-initial", 64, 0, 0, 0, "piecewise", "piecewise", 0),
            new Case("different-encoding", 64, 32, 0, 0, "piecewise", "piecewise", 0));
    var rows = new ArrayList<Map<String, Object>>();
    try (var gl = new GlViewport();
        var reference = new GlViewport()) {
      int[] palette = new int[256];
      for (int i = 0; i < palette.length; i++)
        palette[i] = i | ((255 - i) << 8) | (((i * 17) & 255) << 16) | 0xff000000;
      gl.setPalette(palette);
      reference.setPalette(palette);
      gl.volumeAppearance(-30000, 30000, .01, -Double.MAX_VALUE, 64);
      reference.volumeAppearance(-30000, 30000, .01, -Double.MAX_VALUE, 64);
      var camera = new Camera();
      camera.flying = true;
      camera.yaw = camera.pitch = 0;
      byte[] retained = null;
      for (var scenario : cases) {
        Field field =
            (x, y, z) -> {
              double value = x + 10 * y + 100 * z - 1000;
              if (scenario.field.equals("negative")) return -value;
              if (scenario.field.equals("piecewise") && x >= 64) return 1e300 + (x - 64) * 1e298;
              return value;
            };
        var data = VolumeData.sample(scenario.side, scenario.x, scenario.y, scenario.z, field);
        var region =
            new VolumeTextureUpload.Region(scenario.source, scenario.x, scenario.y, scenario.z);
        var before = gl.volumeStatistics();
        var old = gl.volumeResident();
        require(gl.stageVolume(data, 4L << 20, region), "Overlap admission " + scenario.id);
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
            draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int readBuffer = glGetInteger(GL_READ_BUFFER), drawBuffer = glGetInteger(GL_DRAW_BUFFER);
        int clamp = glGetInteger(GL_CLAMP_READ_COLOR);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        int[] box = new int[4];
        glGetIntegerv(GL_SCISSOR_BOX, box);
        byte[] mask = new byte[4];
        var maskBuffer = BufferUtils.createByteBuffer(4);
        glGetBooleanv(GL_COLOR_WRITEMASK, maskBuffer);
        maskBuffer.get(mask);
        int dummyRead = glGenFramebuffers(), dummyDraw = glGenFramebuffers(), pbo = glGenBuffers();
        int maximum = 0;
        long total = 0;
        int advances = 0;
        try {
          glBindFramebuffer(GL_READ_FRAMEBUFFER, dummyRead);
          glBindFramebuffer(GL_DRAW_FRAMEBUFFER, dummyDraw);
          glEnable(GL_SCISSOR_TEST);
          glScissor(0, 0, 0, 0);
          glColorMask(false, false, false, false);
          glClampColor(GL_CLAMP_READ_COLOR, GL_TRUE);
          glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
          glBufferData(GL_PIXEL_UNPACK_BUFFER, 64, GL_STATIC_DRAW);
          while (gl.volumeStatistics().pending()) {
            int bytes = gl.advanceVolume(16384);
            maximum = Math.max(maximum, bytes);
            total += bytes;
            advances++;
            require(bytes > 0 && bytes <= 16384, "Combined overlap destination budget");
            require(
                glGetInteger(GL_READ_FRAMEBUFFER_BINDING) == dummyRead
                    && glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == dummyDraw
                    && glIsEnabled(GL_SCISSOR_TEST)
                    && glGetInteger(GL_CLAMP_READ_COLOR) == GL_TRUE
                    && glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING) == pbo,
                "Overlap copy leaked GL bindings/state");
            int[] actualBox = new int[4];
            glGetIntegerv(GL_SCISSOR_BOX, actualBox);
            require(Arrays.equals(actualBox, new int[4]), "Copy altered scissor box");
            if (retained != null && gl.volumeStatistics().pending()) {
              require(gl.volumeResident() == old, "Partial copied texture published");
              gl.render(
                  97,
                  97,
                  camera.matrix(1, new Vector3d(scenario.x, scenario.y, scenario.z)),
                  true,
                  1);
              require(
                  Arrays.equals(retained, readFrame(gl)),
                  "Partial copied texture changed framebuffer");
            }
          }
        } finally {
          glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
          glDeleteBuffers(pbo);
          glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
          glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
          glReadBuffer(readBuffer);
          glDrawBuffer(drawBuffer);
          glDeleteFramebuffers(dummyRead);
          glDeleteFramebuffers(dummyDraw);
          glClampColor(GL_CLAMP_READ_COLOR, clamp);
          if (scissor) glEnable(GL_SCISSOR_TEST);
          else glDisable(GL_SCISSOR_TEST);
          glScissor(box[0], box[1], box[2], box[3]);
          glColorMask(mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
        }
        var after = gl.volumeStatistics();
        long copied = after.copiedBytes() - before.copiedBytes(),
            host = after.hostBytes() - before.hostBytes();
        require(
            copied == scenario.copied, "Unexpected copied overlap " + scenario.id + ": " + copied);
        require(
            host + copied == 4L * scenario.side * scenario.side * scenario.side
                && total == host + copied,
            "Overlap host/copy coverage");
        glBindTexture(GL_TEXTURE_3D, gl.volumeResident().texture());
        var samples = BufferUtils.createFloatBuffer(scenario.side * scenario.side * scenario.side);
        glGetTexImage(GL_TEXTURE_3D, 0, GL_RED, GL_FLOAT, samples);
        float[] actual = new float[samples.remaining()];
        samples.get(actual);
        require(
            Arrays.equals(data.copySamples(), actual),
            "GPU copied scalar texels differ: " + scenario.id);
        var binary = ByteBuffer.allocate(actual.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : actual) binary.putFloat(value);
        Files.write(out.resolve(scenario.id + ".bin"), binary.array());
        camera.position.set(
            scenario.x + scenario.side / 2.,
            scenario.y + scenario.side / 2.,
            scenario.z + scenario.side / 2. + 100);
        var matrix = camera.matrix(1, new Vector3d(scenario.x, scenario.y, scenario.z));
        gl.render(65, 65, matrix, true, 1);
        retained = readFrame(gl);
        reference.setVolume(data);
        reference.render(65, 65, matrix, true, 1);
        byte[] full = readFrame(reference);
        require(
            Arrays.equals(retained, full),
            "Copied/full-upload framebuffer differs: " + scenario.id);
        var row = new LinkedHashMap<String, Object>();
        row.put("id", scenario.id);
        row.put("side", scenario.side);
        row.put("origin", List.of(scenario.x, scenario.y, scenario.z));
        row.put("source", scenario.source);
        row.put("field", scenario.field);
        row.put("encoding", data.encoding());
        row.put("copiedBytes", copied);
        row.put("hostBytes", host);
        row.put("destinationBytes", total);
        row.put("maximumBytes", maximum);
        row.put("advances", advances);
        row.put("copiedHash", hash(retained));
        row.put("fullHash", hash(full));
        row.put("stateRestored", true);
        rows.add(row);
      }
    }
    require(glGetError() == GL_NO_ERROR, "Overlap probe GL error");
    Files.writeString(
        out.resolve("volume-overlap-native.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "cases",
                rows,
                "glVersion",
                glGetString(GL_VERSION),
                "openGL31",
                GL.getCapabilities().OpenGL31)));
    System.out.println(
        "VOLUME_OVERLAP_NATIVE_PASS exact signed float layer blits / copied-host byte split /"
            + " diagonal and extent reuse / source-encoding guards / hostile GL state /"
            + " complete-only framebuffer / full upload parity");
  }

  private static byte[] readFrame(GlViewport gl) {
    var bytes = BufferUtils.createByteBuffer(65 * 65 * 4);
    glBindTexture(GL_TEXTURE_2D, gl.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
    var result = new byte[bytes.remaining()];
    bytes.get(result);
    return result;
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
