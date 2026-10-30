package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only carrier/dependency replay of the native export hierarchy fixture. */
public final class ExportHierarchyConsumer {
  public static void main(String[] args) throws Exception {
    var tree = ProjectIO.JSON.readTree(Path.of(args[0]).toFile());
    if (tree.path("packageVersion").asInt() != 1) throw new AssertionError("Package version");
    var p = ProjectIO.JSON.treeToValue(tree.path("resources"), ProjectData.class);
    if (!Validator.validate(p).isEmpty()) throw new AssertionError(Validator.validate(p));
    if (p.noise2D.size() != 1 || !p.noise2D.getFirst().id.equals("signal") || !p.noise3D.isEmpty())
      throw new AssertionError("Noise closure");
    if (p.voxelTypes.size() != 1
        || !p.voxelTypes.getFirst().id.equals("solid")
        || p.worldGenerator.layers.size() != 1) throw new AssertionError("Carrier closure");
    var layer = p.worldGenerator.layers.getFirst();
    if (!layer.id.equals("carrier")
        || !layer.defaults.action.equals("SET")
        || layer.defaults.threshold != .5
        || layer.operations.size() != 1
        || !layer.operations.getFirst().id.equals("chosen")
        || !layer.operations.getFirst().overrides.isEmpty())
      throw new AssertionError("Carrier defaults or selected operation");
    if (!new HashSet<>(p.folders.stream().map(f -> f.id).toList())
        .equals(Set.of("fields", "nested", "world-folder", "deep-world")))
      throw new AssertionError("Folder context");
    // Portable resources do not supply the destination project's world bounds.
    p.project.minY = -8;
    p.project.maxY = 15;
    var compiled = new CompiledProject(p);
    for (int x = -17; x <= 17; x++)
      if (compiled.noise2D("signal", 42).sample(x, -x) != .625)
        throw new AssertionError("Constant field");
    try (var engine = new NoiseGeneratorEngine(2, 8, 64L << 20)) {
      for (int root : new int[] {2, 4, 8})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (var pos : List.of(new ChunkPos(-1, -1, -1), new ChunkPos(0, 0, 0))) {
            var chunk = world.generateChunk(pos);
            for (int x = 0; x < root; x++)
              for (int y = 0; y < root; y++)
                for (int z = 0; z < root; z++)
                  if (chunk.getVoxelTypeId(x, y, z) != 1)
                    throw new AssertionError("Carrier SET/inheritance mismatch");
          }
        }
    }
    System.out.println(
        "Export hierarchy engine-only PASS; exact carrier/dependencies, .625 field, inherited SET,"
            + " signed chunks and roots 2/4/8");
  }
}
