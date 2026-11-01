package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/**
 * Engine-only oracle for explicit zero, reset, live threshold inheritance and project Y clipping.
 */
public final class WorldNumericConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    String[] files = {
      "before",
      "layer",
      "override",
      "override-undone",
      "override-redone",
      "reset",
      "reset-undone",
      "reset-redone",
      "live"
    };
    int cells = 0, traces = 0;
    try (var engine = new NoiseGeneratorEngine(2, 16, 32L << 20)) {
      for (String file : files) {
        var loaded = new ProjectLoader().load(out.resolve(file + ".json"));
        if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
        var compiled = new CompiledProject(loaded.project());
        boolean override =
            file.equals("override")
                || file.equals("override-redone")
                || file.equals("reset-undone");
        int expected = override || file.equals("before") || file.equals("live") ? 1 : 0;
        for (int root : new int[] {2, 4}) {
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int x = -2; x <= 2; x++)
              for (int y = -2; y <= 2; y++)
                for (int z = -1; z <= 1; z++) {
                  var point = new BlockPos(x, y, z);
                  if (world.getVoxelTypeId(point) != expected)
                    throw new AssertionError("Numeric cell " + file + " / " + point);
                  var trace = world.trace(point);
                  if (trace.finalType() != expected
                      || !(override ? "Placement override" : "Layer default")
                          .equals(trace.steps().getFirst().settingsSources().get("threshold")))
                    throw new AssertionError("Numeric trace/source " + file + " / " + point);
                  cells++;
                  traces++;
                }
            for (int y : new int[] {-9, -8, 8, 9}) {
              int clipped = y < -8 || y > 8 ? 0 : expected;
              if (world.getVoxelTypeId(new BlockPos(0, y, 0)) != clipped)
                throw new AssertionError("Project Y clipping " + file + " / " + y);
              cells++;
            }
          }
        }
      }
    }
    if (cells != 1422 || traces != 1350) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "World numeric engine-only PASS; 1422 exact cells, 1350 traces and live/override sources");
  }
}
