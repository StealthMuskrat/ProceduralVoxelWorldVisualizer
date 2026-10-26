package io.voxelcreator.editor;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless production-cache reference; the desktop smoke separately exercises async/GL publication.
 */
public final class ImageRowsReference {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var p = new ProjectData();
    var n = new Noise();
    n.id = "image-rows";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.put("value", 0.0);
    var item = ProcessingRegistry.create("EXPRESSION", "field");
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("x*0.5+z*3"));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    p.noise2D.add(n);
    Files.writeString(out.resolve("image-rows-project.json"), ProjectIO.JSON.writeValueAsString(p));
    var records = new ArrayList<Map<String, Object>>();
    var calls = new AtomicInteger();
    try (var cache = new NoiseImageRows()) {
      long version = 0;
      double panX = -17.25, panZ = 3.5, step = .25;
      String[] names = {
        "initial", "recolor", "vertical", "return", "horizontal", "zoom", "forced", "sourceChanged"
      };
      int[] sampled = {256, 0, 1, 0, 256, 256, 256, 256};
      int[] sampledValues = {65536, 0, 256, 0, 256, 49024, 65536, 65536};
      for (int phase = 0; phase < names.length; phase++) {
        if (phase == 2) panZ += step;
        if (phase == 3) panZ -= step;
        if (phase == 4) panX += step;
        if (phase == 5) step = .5;
        int offset = phase == 7 ? 1 : 0;
        if (phase == 7)
          item.parameters.put("expression", ProjectIO.JSON.valueToTree("x*0.5+z*3+1"));
        var field = new NoiseCompiler(p, p.project.seed).compile(n.uuid);
        int before = calls.get();
        var image =
            cache.sample(
                PreviewIdentity.of(p, 0, n.uuid),
                ++version,
                (x, y, z) -> {
                  calls.incrementAndGet();
                  return field.sample(x, y, z);
                },
                256,
                panX,
                panZ,
                step,
                NoiseImageRows.MAX_BYTES,
                phase == 6,
                new CancellationToken());
        require(
            image.statistics().sampledRows() == sampled[phase],
            "Wrong row work: " + names[phase] + " " + image.statistics());
        require(calls.get() - before == sampledValues[phase], "Wrong sampler-call count");
        var raw = ByteBuffer.allocate(256 * 256 * 8);
        var pixels = ByteBuffer.allocate(256 * 256 * 4);
        int[] histogram = new int[64];
        String mode = phase == 1 ? "Heatmap" : "Grayscale";
        for (int z = 0; z < 256; z++)
          for (int x = 0; x < 256; x++) {
            double expected =
                (panX + (x - 128.) * step) * .5 + (panZ + (z - 128.) * step) * 3 + offset;
            double value = image.value(x, z);
            require(
                Double.doubleToLongBits(expected) == Double.doubleToLongBits(value),
                "Raw coordinate/value drift");
            raw.putLong(Double.doubleToLongBits(value));
            pixels.putInt(Viewports.color(value, mode, 0));
            histogram[Math.max(0, Math.min(63, (int) ((value + 1) * 32)))]++;
          }
        var row = new LinkedHashMap<String, Object>();
        row.put("name", names[phase]);
        row.put("side", 256);
        row.put("panX", panX);
        row.put("panZ", panZ);
        row.put("step", step);
        row.put("offset", offset);
        row.put("mode", mode);
        row.put("sampledRows", image.statistics().sampledRows());
        row.put("reusedRows", image.statistics().reusedRows());
        require(image.statistics().sampledValues() == sampledValues[phase], "Wrong value work");
        require(
            image.statistics().reusedValues() == 65536 - sampledValues[phase], "Wrong value reuse");
        row.put("sampledValues", image.statistics().sampledValues());
        row.put("reusedValues", image.statistics().reusedValues());
        row.put("retainedBytes", image.statistics().retainedBytes());
        row.put("limitBytes", image.statistics().limitBytes());
        row.put("rawSha256", hash(raw.array()));
        row.put("pixelSha256", hash(pixels.array()));
        row.put("histogram", histogram);
        records.add(row);
      }
    }
    Files.writeString(
        out.resolve("image-rows-changed-project.json"), ProjectIO.JSON.writeValueAsString(p));
    Files.writeString(
        out.resolve("image-rows-reference.json"),
        ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "frames", records)));
    System.out.println(
        "IMAGE_ROWS_REFERENCE_PASS exact rows / colors / histograms / vertical reuse / force /"
            + " source invalidation");
  }

  private static String hash(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
