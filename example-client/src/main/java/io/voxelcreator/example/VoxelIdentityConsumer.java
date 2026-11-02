package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Engine-only semantic-rename and old-archive replay; no editor index or view on the classpath. */
public final class VoxelIdentityConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    String[] names = {
      "before",
      "properties",
      "properties-undone",
      "properties-redone",
      "inline",
      "inline-undone",
      "inline-redone"
    };
    String[] ids = {"stone", "granite", "stone", "granite", "marble", "granite", "marble"};
    int samples = 0, cells = 0;
    byte[] archive = null;
    VoxelRegistry finalRegistry = null;
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int i = 0; i < names.length; i++) {
        var loaded = new ProjectLoader().load(out.resolve(names[i] + ".json"));
        if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
        var compiled = new CompiledProject(loaded.project());
        finalRegistry = compiled.registry();
        if (!compiled.registry().semanticId(1).equals(ids[i]))
          throw new AssertionError("Semantic registry ID did not follow explicit rename");
        for (int x = -4; x <= 4; x++)
          for (int z = -4; z <= 4; z++) {
            if (compiled.noise2D("constant", 0).sample(x, z) != 1)
              throw new AssertionError("Identity review changed a field");
            samples++;
          }
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int c : new int[] {-1, 0}) {
              var chunk = world.generateChunk(new ChunkPos(c, c, c));
              for (int z = 0; z < root; z++)
                for (int y = 0; y < root; y++)
                  for (int x = 0; x < root; x++) {
                    if (chunk.getVoxelTypeId(x, y, z) != 1)
                      throw new AssertionError(
                          "UUID-preserving rename changed generated occupancy");
                    cells++;
                  }
              if (i == 0 && root == 4 && c == 0) {
                VoxelArchive.writeSingleChunk(
                    out.resolve("original.voxels"), chunk, compiled.registry());
                archive = Files.readAllBytes(out.resolve("original.voxels"));
              }
            }
          }
      }
    }
    if (archive == null || finalRegistry == null)
      throw new AssertionError("Archive fixture missing");
    var originalRegistry = finalRegistry.semanticIds();
    boolean unresolved = false;
    try {
      VoxelArchive.readSingleChunk(out.resolve("original.voxels"), finalRegistry);
    } catch (IOException expected) {
      if (!expected.getMessage().contains("stone")) throw expected;
      unresolved = true;
    }
    if (!unresolved || !finalRegistry.semanticIds().equals(originalRegistry))
      throw new AssertionError("An old archive silently adopted a renamed semantic ID");
    var compatible = VoxelRegistry.semantic("marble", "snow", "stone");
    var decoded = VoxelArchive.readSingleChunk(out.resolve("original.voxels"), compatible);
    for (int z = 0; z < 4; z++)
      for (int y = 0; y < 4; y++)
        for (int x = 0; x < 4; x++)
          if (decoded.getVoxelTypeId(x, y, z) != 3)
            throw new AssertionError("Archive did not remap its original stone identity");
    if (!Arrays.equals(archive, Files.readAllBytes(out.resolve("original.voxels"))))
      throw new AssertionError("Renamed registry decoding rewrote an old archive");
    if (samples != 567 || cells != 1008) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Voxel identity engine-only PASS; 567 scalar observations, 1008 cells and 64 original"
            + " semantic archive cells");
  }
}
