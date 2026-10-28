package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only replay of the checked-in analytical transform fixture. */
public final class TransformDriverConsumer {
  public static void main(String[] args) throws Exception {
    var result = new ProjectLoader().load(Path.of(args[0]));
    if (!result.success()) throw new AssertionError(result.diagnostics());
    var p = result.project();
    // Exercise actual persisted form, including the new transform indices.
    var roundTrip = new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(p));
    if (!roundTrip.success()) throw new AssertionError(roundTrip.diagnostics());
    var samples = new ArrayList<Map<String, Object>>();
    GenerationTrace selected = null;
    try (var engine = new NoiseGeneratorEngine(3, 32, 512L << 20)) {
      for (int root : new int[] {2, 4, 8})
        try (var world =
            engine.openWorld(
                new CompiledProject(roundTrip.project()),
                new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          world.generateChunk(new ChunkPos(0, 0, 0));
          for (int x = -3; x <= 3; x++)
            for (int y = -3; y <= 3; y++)
              for (int z = -1; z <= 1; z++) {
                var pos = new BlockPos(x, y, z);
                // Translate by y, then scale about X=1: 1 + ((x+1)+y-1)*2.
                double expectedField = 1 + 2 * (x + y);
                int expectedType = expectedField >= 2.5 ? 1 : 0;
                var trace = world.trace(pos);
                double field = trace.steps().getFirst().field();
                if (world.getVoxelTypeId(pos) != expectedType
                    || trace.finalType() != expectedType
                    || field != expectedField)
                  throw new AssertionError(pos + " expected field " + expectedField);
                samples.add(
                    Map.of(
                        "root",
                        root,
                        "x",
                        x,
                        "y",
                        y,
                        "z",
                        z,
                        "type",
                        trace.finalType(),
                        "field",
                        field));
              }
          if (root == 4) selected = world.trace(new BlockPos(1, 1, 0));
        }
    }
    var out = Path.of(args[1]);
    Files.createDirectories(out);
    Files.writeString(
        out.resolve("transform-driver-engine.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "samples",
                samples,
                "trace",
                Objects.requireNonNull(selected))));
    Files.writeString(
        out.resolve("transform-driver-project.json"),
        ProjectIO.JSON.writeValueAsString(roundTrip.project()));
    System.out.println(
        "TRANSFORM_DRIVER_ENGINE_ONLY_PASS 441 analytical cells / three roots / persisted row"
            + " targets");
  }
}
