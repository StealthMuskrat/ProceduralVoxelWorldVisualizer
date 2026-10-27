package io.voxelcreator.editor;

import static org.lwjgl.opengl.GL33.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;

/** Compiled extreme/tiny fields, finite GPU transfer and independent homogeneous/linear optics. */
final class VolumeEncodingProbe {
  private record Case(
      String id,
      double coefficient,
      double min,
      double max,
      double threshold,
      double opacity,
      double mean,
      String kind,
      double x,
      double y,
      double z,
      double yaw) {
    String expression() {
      return Double.toString(coefficient) + (kind.equals("gradient") ? " * (0.5 + z/16)" : "");
    }
  }

  private static Case c(
      String id,
      double value,
      double min,
      double max,
      double threshold,
      double opacity,
      double mean) {
    return new Case(id, value, min, max, threshold, opacity, mean, "constant", 2, 2, 10, 0);
  }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    double big = Double.MAX_VALUE, tiny = Double.MIN_VALUE;
    var cases =
        List.of(
            c("ordinary", 1, 0, 1, 0, .5, 1),
            c("zero-opacity", 1, 0, 1, 0, 0, 1),
            c("huge-half", big / 2, 0, big, 0, .5, .5),
            c("huge-quarter", -big / 2, -big, big, -big, .5, .25),
            c("tiny-min", tiny, 0, tiny, 0, .5, 1),
            c("tiny-half", 1e-300, 0, 2e-300, 0, .5, .5),
            c("huge-filtered", big / 2, 0, big, big * .75, .5, 0),
            c("tiny-filtered", tiny, 0, 2 * tiny, 2 * tiny, .5, 0),
            c("wide-zero", 0, -big, big, 0, .5, .5),
            c("wide-negative-filtered", -tiny, -big, big, 0, .5, 0),
            c("wide-negative-accepted", -tiny, -big, big, -tiny, .5, .5),
            c("adjacent-upper", big, Math.nextDown(big), big, 0, .5, 1),
            c("adjacent-lower", Math.nextDown(big), Math.nextDown(big), big, 0, .5, 0),
            c("normal-adjacent-upper", 1, Math.nextDown(1.0), 1, 0, .5, 1),
            c("normal-adjacent-lower", Math.nextDown(1.0), Math.nextDown(1.0), 1, 0, .5, 0),
            c("normal-adjacent-filtered", Math.nextDown(1.0), 0, 1, 1, .5, 0),
            c("maximum-opacity", 1, 0, 1, 0, 10, 1),
            new Case("parallel-miss", 1, 0, 1, 0, .5, 1, "constant", 6, 2, 10, 0),
            new Case("parallel-boundary", 1, 0, 1, 0, .5, 1, "constant", 0, 2, 10, 0),
            new Case("inside", 1, 0, 1, 0, .5, 1, "constant", 2, 2, 2, 0),
            new Case("diagonal", 1, 0, 1, 0, .5, 1, "constant", 8, 2, 8, Math.PI / 4),
            new Case("gradient-huge", big, 0, big, 0, .5, .625, "gradient", 2, 2, 10, 0),
            new Case("gradient-tiny", 1e-300, 0, 1e-300, 0, .5, .625, "gradient", 2, 2, 10, 0));
    var project = new ProjectData();
    for (var scenario : cases) {
      var noise = new Noise();
      noise.dimension = 3;
      noise.id = scenario.id;
      noise.algorithm.key = "CONSTANT";
      var item = ProcessingRegistry.create("EXPRESSION", "density", 3);
      item.parameters.put("expression", ProjectIO.JSON.valueToTree(scenario.expression()));
      noise.processing.add(item);
      noise.outputBinding = Binding.earlier(item.uuid);
      project.noise3D.add(noise);
    }
    require(NoiseCompiler.validate(project).isEmpty(), "Volume encoding canonical fixture");
    var compiler = new NoiseCompiler(project, project.project.seed);
    var rows = new ArrayList<Map<String, Object>>();
    try (var viewport = new GlViewport()) {
      var palette = new int[256];
      Arrays.fill(palette, 0xff0000ff);
      viewport.setPalette(palette);
      VolumeData previous = null;
      for (int i = 0; i < cases.size(); i++) {
        var scenario = cases.get(i);
        var field = compiler.compile(project.noise3D.get(i).uuid);
        var count = new AtomicInteger();
        boolean reuse = scenario.id.equals("zero-opacity");
        var data =
            reuse
                ? previous
                : VolumeData.sample(
                    4,
                    0,
                    0,
                    0,
                    (x, y, z) -> {
                      count.incrementAndGet();
                      return field.sample(x, y, z);
                    },
                    new CancellationToken());
        require(data != null && count.get() == (reuse ? 0 : 64), "Volume sample count/reuse");
        if (!reuse) viewport.setImage(2, 2, new int[4]);
        if (i % 2 == 0)
          viewport.volumeAppearance(
              scenario.min, scenario.max, scenario.opacity, scenario.threshold, 256);
        if (!reuse) viewport.setVolume(data);
        if (i % 2 != 0)
          viewport.volumeAppearance(
              scenario.min, scenario.max, scenario.opacity, scenario.threshold, 256);
        var camera = new Camera();
        camera.flying = true;
        camera.position.set(scenario.x, scenario.y, scenario.z);
        camera.yaw = scenario.yaw;
        camera.pitch = 0;
        var ray = camera.ray(0, 0, 1, new Vector3d());
        double path = path(ray);
        viewport.render(65, 65, camera.matrix(1, new Vector3d()), true, 1);
        var pixel = pixel(viewport);
        double transmittance = Math.exp(-scenario.mean * Math.min(10, scenario.opacity) * path);
        double[] expected = {
          255 * (1 - transmittance + transmittance * .055),
          255 * transmittance * .075,
          255 * transmittance * .1
        };
        for (int channel = 0; channel < 3; channel++)
          require(
              Math.abs(pixel.get(channel) - expected[channel]) <= 2,
              "Volume encoding "
                  + scenario.id
                  + " channel "
                  + channel
                  + ": "
                  + pixel
                  + " expected "
                  + Arrays.toString(expected)
                  + " path="
                  + path);
        require(pixel.get(3) == 255, "Volume alpha");
        var settings =
            new VolumeTransfer.Settings(
                scenario.min, scenario.max, scenario.opacity, scenario.threshold, 256);
        var row = new LinkedHashMap<String, Object>();
        row.put("id", scenario.id);
        row.put("kind", scenario.kind);
        row.put("coefficient", scenario.coefficient);
        row.put("expression", scenario.expression());
        row.put("settings", settings);
        row.put("encoding", data.encoding());
        row.put("texels", data.copySamples());
        row.put("transfer", VolumeTransfer.of(data.encoding(), settings));
        row.put("sampleCount", count.get());
        row.put("pixel", pixel);
        row.put("origin", List.of(ray.origin().x, ray.origin().y, ray.origin().z));
        row.put("direction", List.of(ray.direction().x, ray.direction().y, ray.direction().z));
        row.put("camera", List.of(scenario.x, scenario.y, scenario.z, scenario.yaw));
        row.put("path", path);
        row.put("mean", scenario.mean);
        row.put("transmittance", transmittance);
        rows.add(row);
        previous = data;
      }
    }
    require(glGetError() == GL_NO_ERROR, "Volume encoding GL error");
    new ProjectIO().save(out.resolve("volume-encoding-project.json"), project);
    Files.writeString(
        out.resolve("volume-encoding-native.json"),
        ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "cases", rows)));
    System.out.println(
        "VOLUME_ENCODING_NATIVE_PASS 23 compiled-field optics cases / extreme-tiny-neighbor ranges"
            + " / threshold signs / parallel-inside-diagonal rays / controls without resampling /"
            + " finite transfer");
  }

  private static double path(Camera.Ray ray) {
    double enter = 0, exit = Double.POSITIVE_INFINITY;
    double[] o = {ray.origin().x, ray.origin().y, ray.origin().z},
        d = {ray.direction().x, ray.direction().y, ray.direction().z};
    for (int axis = 0; axis < 3; axis++) {
      if (Math.abs(d[axis]) < 1e-12) {
        if (o[axis] < 0 || o[axis] > 4) return 0;
      } else {
        double a = -o[axis] / d[axis], b = (4 - o[axis]) / d[axis];
        enter = Math.max(enter, Math.min(a, b));
        exit = Math.min(exit, Math.max(a, b));
      }
    }
    return Math.max(0, exit - enter);
  }

  private static List<Integer> pixel(GlViewport viewport) {
    var bytes = BufferUtils.createByteBuffer(65 * 65 * 4);
    glBindTexture(GL_TEXTURE_2D, viewport.texture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
    var pixel = new ArrayList<Integer>();
    for (int channel = 0; channel < 4; channel++)
      pixel.add(Byte.toUnsignedInt(bytes.get((32 * 65 + 32) * 4 + channel)));
    return pixel;
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
