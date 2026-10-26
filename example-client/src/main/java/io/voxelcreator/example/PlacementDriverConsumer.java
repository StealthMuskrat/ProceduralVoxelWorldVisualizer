package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;

public final class PlacementDriverConsumer {
  public static void main(String[] args) throws Exception {
    var result = new ProjectLoader().load(Path.of(args[0]));
    if (!result.success()) throw new AssertionError(result.diagnostics());
    var p = result.project();
    double outputMax = Double.parseDouble(args[1]);
    int checked = 0;
    try (var engine = new NoiseGeneratorEngine(3, 32, 512L << 20)) {
      for (int root : new int[] {2, 4, 8})
        try (var world =
            engine.openWorld(
                new CompiledProject(p),
                new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          world.generateChunk(new ChunkPos(0, 0, 0));
          for (int x = -3; x <= 3; x++)
            for (int y = -3; y <= 3; y++) {
              double offset = 2 + (outputMax - 2) * (y + 4) / 8.;
              int expected = x + offset >= 2.5 ? 1 : 0;
              var pos = new BlockPos(x, y, 0);
              if (world.getVoxelTypeId(pos) != expected || world.trace(pos).finalType() != expected)
                throw new AssertionError(pos + " expected " + expected);
              checked++;
            }
          var trace = world.trace(new BlockPos(0, 0, 0));
          var rows = trace.steps().getFirst().details().parameters();
          if (!rows.getFirst().phase().equals("SAMPLING")
              || !rows.get(1).phase().equals("INTERPRETATION"))
            throw new AssertionError("Phase order");
          if (args.length > 2 && root == 4) {
            var out = Path.of(args[2]);
            Files.writeString(
                out.resolve("placement-driver-trace.json"),
                ProjectIO.JSON.writeValueAsString(trace));
          }
        }
    }
    if (args.length > 2)
      Files.writeString(
          Path.of(args[2]).resolve("placement-driver-engine.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of("formatVersion", 1, "checkedCells", checked, "outputMaximum", outputMax)));
    System.out.println(
        "PLACEMENT_DRIVER_ENGINE_ONLY_PASS 147 independent cells / three roots / exact phased"
            + " trace");
  }
}
