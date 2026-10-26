package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class ImageRowsConsumer {
  public static void main(String[] args) throws Exception {
    var dir = Path.of(args[0]);
    var a = new ProjectLoader().load(dir.resolve("image-rows-project.json"));
    var b = new ProjectLoader().load(dir.resolve("image-rows-changed-project.json"));
    if (!a.success() || !b.success()) throw new AssertionError("Invalid saved field fixture");
    var original = new CompiledProject(a.project()).noise2D("image-rows", 0);
    var changed = new CompiledProject(b.project()).noise2D("image-rows", 0);
    var rows =
        ProjectIO.JSON.readTree(dir.resolve("image-rows-reference.json").toFile()).path("frames");
    int checked = 0;
    for (var row : rows) {
      int side = row.path("side").asInt();
      double px = row.path("panX").asDouble(),
          pz = row.path("panZ").asDouble(),
          step = row.path("step").asDouble();
      var sampler = row.path("offset").asInt() == 0 ? original : changed;
      var bytes = ByteBuffer.allocate(side * side * 8);
      for (int z = 0; z < side; z++)
        for (int x = 0; x < side; x++) {
          double value = sampler.sample(px + (x - side / 2.) * step, pz + (z - side / 2.) * step);
          bytes.putLong(Double.doubleToLongBits(value));
          checked++;
        }
      String hash =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
      if (!hash.equals(row.path("rawSha256").asText()))
        throw new AssertionError("Independent engine mismatch: " + row.path("name"));
    }
    System.out.println(
        "IMAGE_ROWS_ENGINE_ONLY_PASS " + checked + " exact samples across eight views");
  }
}
