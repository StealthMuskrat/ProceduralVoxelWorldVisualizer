package io.voxelcreator.editor;

import static org.lwjgl.opengl.GL33.*;

import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.render.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;

/** Deferred real raster/picking parity at ordinary and large signed world coordinates. */
final class CameraGeometryProbe {
  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var rows = new ArrayList<Map<String, Object>>();
    int width = 97, height = 65;
    float aspect = width / (float) height;
    var pixels = BufferUtils.createByteBuffer(width * height * 4);
    try (var view = new GlViewport()) {
      var palette = new int[256];
      Arrays.fill(palette, 0xff0000ff);
      view.setPalette(palette);
      view.setMesh(MeshBuilder.greedy(8, (x, y, z) -> x == 2 && y == 1 && z == 3 ? 1 : 0));
      for (int offset : new int[] {0, 2_000_000_000, -2_000_000_000})
        for (double[] angle : new double[][] {{0, 0}, {Math.PI / 2, .6}, {-1.1, -.7}})
          for (boolean pan : new boolean[] {false, true})
            for (boolean flying : new boolean[] {false, true}) {
              var origin = new Vector3d(offset, 64, -offset);
              var target = new BlockPos(offset + 2, 65, -offset + 3);
              var camera = new Camera();
              camera.focus.set(target.x() + .5, target.y() + .5, target.z() + .5);
              camera.yaw = angle[0];
              camera.pitch = angle[1];
              camera.distance = 4;
              if (pan) camera.pan(7, -4);
              if (flying) camera.enterFly();
              view.render(width, height, camera.matrix(aspect, origin), true, 1);
              int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
              try {
                glBindTexture(GL_TEXTURE_2D, view.texture());
                pixels.clear();
                glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
              } finally {
                glBindTexture(GL_TEXTURE_2D, bound);
              }
              for (int[] pixel : new int[][] {{48, 32}, {50, 33}, {46, 31}, {0, 0}}) {
                float nx = (float) (2 * (pixel[0] + .5) / width - 1);
                float ny = (float) (2 * (pixel[1] + .5) / height - 1);
                var ray = camera.ray(nx, ny, aspect, origin);
                var alternative = camera.ray(nx, ny, aspect, new Vector3d(1e12, -1e12, 1e12));
                require(ray.equals(alternative), "World ray changed with render origin");
                var hit =
                    VoxelRaycast.firstOccupied(
                        ray,
                        20,
                        (x, y, z) -> x == target.x() && y == target.y() && z == target.z() ? 1 : 0);
                var rgba = new ArrayList<Integer>();
                for (int c = 0; c < 4; c++)
                  rgba.add(Byte.toUnsignedInt(pixels.get((pixel[1] * width + pixel[0]) * 4 + c)));
                boolean red = rgba.get(0) > 100 && rgba.get(1) < 60 && rgba.get(2) < 60;
                require(
                    red == hit.isPresent(), "Raster and ray disagree at " + Arrays.toString(pixel));
                require(
                    hit.isPresent() == (pixel[0] != 0), "Expected target/miss coverage changed");
                var row = new LinkedHashMap<String, Object>();
                row.put("offset", offset);
                row.put("yaw", camera.yaw);
                row.put("pitch", camera.pitch);
                row.put("distance", camera.distance);
                row.put("pan", pan);
                row.put("flying", flying);
                row.put("focus", xyz(camera.focus));
                row.put("eye", xyz(camera.eye()));
                row.put("pixel", List.of(pixel[0], pixel[1]));
                row.put("ndc", List.of(nx, ny));
                row.put("aspect", aspect);
                row.put("origin", xyz(ray.origin()));
                row.put("direction", xyz(ray.direction()));
                row.put("rgba", rgba);
                row.put("target", List.of(target.x(), target.y(), target.z()));
                row.put("hit", hit.map(p -> List.of(p.x(), p.y(), p.z())).orElse(List.of()));
                rows.add(row);
              }
            }
    }
    require(rows.size() == 144, "Camera raster coverage changed");
    Files.writeString(
        out.resolve("camera-geometry-native.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "width",
                width,
                "height",
                height,
                "rows",
                rows,
                "physicalDpiVerified",
                false)));
    System.out.println(
        "CAMERA_GEOMETRY_NATIVE_PASS 144 double rays / orbit-fly / screen pan / signed origins /"
            + " raster-DDA parity");
  }

  private static List<Double> xyz(Vector3d value) {
    return List.of(value.x, value.y, value.z);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
