package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.CoordinateHash;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-JAR replay of exact persisted source/layer/gate/placement seeds. */
public final class SeedTextConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var project = loaded.project();
    var noise = project.noise2D.getFirst();
    var layer = project.worldGenerator.layers.getFirst();
    var operation = layer.operations.getFirst();
    if (noise.seedOffset != 17
        || layer.defaults.seedOffset != Long.MIN_VALUE
        || layer.defaults.fieldGates.seedOffset != Long.MAX_VALUE
        || operation.overrides.get("seedOffset").longValue() != 43)
      throw new AssertionError("Exact saved seed values");
    var out = Path.of(args[1]);
    Files.createDirectories(out);
    var compiled = new CompiledProject(project);
    var sampler = compiled.noise2D(noise.uuid, project.project.seed);
    long seed = CoordinateHash.seed(project.project.seed, noise.uuid, noise.seedOffset);
    var rows = new StringBuilder("x\tz\tvalue\tbits\n");
    int hashes = 0, cells = 0;
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++) {
        double value = sampler.sample(x, z);
        if (value != CoordinateHash.probability(seed, x, 0, z))
          throw new AssertionError("Source seed hash");
        rows.append(x)
            .append('\t')
            .append(z)
            .append('\t')
            .append(value)
            .append('\t')
            .append(Long.toUnsignedString(Double.doubleToRawLongBits(value), 16))
            .append('\n');
        hashes++;
      }
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
                  if (chunk.getVoxelTypeId(x, y, z) != 1)
                    throw new AssertionError("Probability-one gate must accept");
                  cells++;
                }
          }
        }
    }
    if (hashes != 81 || cells != 144) throw new AssertionError("Observation count");
    Files.writeString(out.resolve("seed-text-values.tsv"), rows);
    Files.writeString(
        out.resolve("seed-text-engine.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "hashes",
                hashes,
                "cells",
                cells,
                "sourceSeed",
                noise.seedOffset,
                "layerSeed",
                layer.defaults.seedOffset,
                "gateSeed",
                layer.defaults.fieldGates.seedOffset,
                "overrideSeed",
                43)));
    System.out.println(
        "SEED_TEXT_ENGINE_ONLY_PASS 81 source hash values / 144 exact voxels / signed-long"
            + " persistence");
  }
}
