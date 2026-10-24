package io.voxelcreator.example;

import io.voxelcreator.storage.*;
import java.io.*;
import java.util.*;

/** Explicit storage comparison; no GPU, external dependencies or automatic startup work. */
public final class StorageBenchmark {
  public static void main(String[] args) throws Exception {
    System.out.println(
        "# java="
            + System.getProperty("java.runtime.version")
            + " os="
            + System.getProperty("os.name"));
    System.out.println(
        "# One warmup then three measurements, fixed exact sources; timings are local evidence, not"
            + " a speed guarantee.");
    System.out.println(
        "scene,root,run,generation_ms,fill_ms,snapshot_bytes,palette_entries,packed_leaves,dense_leaves,legacy_tree_bytes,packed_tree_bytes,voxel_hash");
    for (int size : new int[] {32, 64})
      for (String scene : List.of("uniform", "sparse", "checkerboard", "many_types")) {
        Long reference = null;
        for (int run = -1; run < 3; run++) {
          long start = System.nanoTime();
          var c =
              VoxelChunk.generate(
                  new ChunkPos(-1, 0, 1),
                  size,
                  (x, y, z) ->
                      switch (scene) {
                        case "uniform" -> 1;
                        case "sparse" -> x == size - 1 && y == size - 1 && z == size - 1 ? 1 : 0;
                        case "checkerboard" -> (x + y + z) & 1;
                        default ->
                            (x & 1) == 0
                                ? 0
                                : 1
                                    + x / 4
                                    + (z / 4) * (size / 4)
                                    + (y / 4) * (size / 4) * (size / 4);
                      });
          long generated = System.nanoTime();
          var edited = c.withFilledBox(0, 0, 0, size / 2 - 1, size - 1, size - 1, 77);
          long filled = System.nanoTime();
          if (edited.getVoxelTypeId(0, 0, 0) != 77) throw new AssertionError("Bulk fill failed");
          var legacy = bytes(c, false);
          var packed = bytes(c, true);
          var stats = c.storageStatistics();
          long hash = 0xcbf29ce484222325L;
          for (int y = 0; y < size; y++)
            for (int z = 0; z < size; z++)
              for (int x = 0; x < size; x++) {
                hash ^= c.getVoxelTypeId(x, y, z);
                hash *= 0x100000001b3L;
              }
          if (reference != null && reference != hash)
            throw new AssertionError("Unstable exact storage");
          reference = hash;
          if (run >= 0)
            System.out.printf(
                Locale.ROOT,
                "%s,%d,%d,%.4f,%.4f,%d,%d,%d,%d,%d,%d,%016x%n",
                scene,
                size,
                run,
                (generated - start) / 1e6,
                (filled - generated) / 1e6,
                c.estimatedBytes(),
                stats.paletteEntries(),
                stats.packedLeaves(),
                stats.denseLeaves(),
                legacy.length,
                packed.length,
                hash);
        }
      }
    // A full 256^3 covered box must stay an immediate uniform node after bulk replacement.
    var c =
        VoxelChunk.uniform(new ChunkPos(0, 0, 0), 256, 0).withFilledBox(0, 0, 0, 255, 255, 255, 1);
    if (c.estimatedBytes() >= 100 || c.storageStatistics().occupiedVoxels() != 16777216L)
      throw new AssertionError("Bulk root expanded");
  }

  private static byte[] bytes(VoxelChunk c, boolean packed) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      if (packed) c.writePackedTree(out, i -> i);
      else c.writeTree(out, i -> i);
    }
    return bytes.toByteArray();
  }
}
