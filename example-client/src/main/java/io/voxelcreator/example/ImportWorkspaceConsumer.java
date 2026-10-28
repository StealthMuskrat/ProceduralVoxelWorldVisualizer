package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only replay of the production COPY/REUSE/SKIP workspace's saved result. */
public final class ImportWorkspaceConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var before = new ProjectLoader().load(out.resolve("import-before.json"));
    var after = new ProjectLoader().load(out.resolve("import-after.json"));
    if (!before.success() || !after.success())
      throw new AssertionError("Import snapshots rejected");
    var p = after.project();
    var targets = ProjectIO.JSON.readTree(out.resolve("import-targets.json").toFile());
    if (before.project().noise2D.size() != 3
        || p.noise2D.size() != 5
        || p.project.seed != 42
        || !p.project.uuid.equals(before.project().project.uuid)
        || !p.worldGenerator.layers.isEmpty())
      throw new AssertionError("Import destination identity/metadata");
    for (var original : before.project().noise2D) {
      var retained =
          p.noise2D.stream().filter(n -> n.uuid.equals(original.uuid)).findFirst().orElseThrow();
      if (!ProjectIO.JSON.valueToTree(original).equals(ProjectIO.JSON.valueToTree(retained)))
        throw new AssertionError("Import replaced an existing definition");
    }
    var expected =
        Map.of(
            "editing", .125, "shared", .25, "conflict", .5, "conflict-copy", .9, "retained", .75);
    var compiled = new CompiledProject(p);
    int samples = 0, cells = 0;
    for (var noise : p.noise2D) {
      var value = expected.get(noise.id);
      if (value == null || noise.seedOffset != 0)
        throw new AssertionError("Discarded/private data persisted");
      var sampler = compiled.noise2D(noise.uuid, p.project.seed);
      for (int x = -4; x <= 4; x++)
        for (int z = -4; z <= 4; z++) {
          if (Double.doubleToRawLongBits(sampler.sample(x, z)) != Double.doubleToRawLongBits(value))
            throw new AssertionError("Imported scalar value");
          samples++;
        }
      String key = noise.id.equals("conflict-copy") ? "conflict" : noise.id;
      if (targets.has(key)
          && !noise.uuid.toString().equals(targets.get(key).asText())
          && !noise.id.equals("conflict")) throw new AssertionError("Imported preview target");
    }
    if (!targets.get("shared").equals(targets.get("existingShared"))
        || targets.get("retained").equals(targets.get("beforeReloadRetained")))
      throw new AssertionError("Reuse/reload ownership");
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (int chunkX : new int[] {-1, 0}) {
            var chunk = world.generateChunk(new ChunkPos(chunkX, 0, 0));
            for (int z = 0; z < root; z++)
              for (int y = 0; y < root; y++)
                for (int x = 0; x < root; x++) {
                  if (chunk.getVoxelTypeId(x, y, z) != 0)
                    throw new AssertionError("Import changed empty world");
                  cells++;
                }
          }
        }
    }
    if (samples != 405 || cells != 144) throw new AssertionError("Observation count");
    System.out.println(
        "IMPORT_WORKSPACE_ENGINE_ONLY_PASS 405 exact scalar values / 144 EMPTY cells / existing"
            + " definitions / preview UUIDs");
  }
}
