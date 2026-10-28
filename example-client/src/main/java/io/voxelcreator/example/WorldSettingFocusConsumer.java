package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;

/** Deferred independent headless replay of explicit zero versus live layer inheritance. */
public final class WorldSettingFocusConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var original = ProjectIO.JSON.valueToTree(loaded.project());
    int checked = 0;
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (int mode = 0; mode < 3; mode++) {
        var project = ProjectIO.JSON.convertValue(original, ProjectData.class);
        var layer = project.worldGenerator.layers.getFirst();
        var operation = layer.operations.getFirst();
        if (mode > 0) operation.overrides.remove("threshold");
        if (mode == 2) layer.defaults.threshold = -.5;
        int expected = mode == 1 ? 0 : 1;
        for (int root : new int[] {2, 4}) {
          try (var world =
              engine.openWorld(
                  new CompiledProject(project),
                  new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int x = -2; x <= 2; x++)
              for (int y = -2; y <= 2; y++)
                for (int z = -1; z <= 1; z++) {
                  var point = new BlockPos(x, y, z);
                  if (world.getVoxelTypeId(point) != expected
                      || world.trace(point).finalType() != expected)
                    throw new AssertionError(
                        "Threshold inheritance at " + point + ", mode " + mode);
                  checked++;
                }
            var source =
                world
                    .trace(new BlockPos(0, 0, 0))
                    .steps()
                    .getFirst()
                    .settingsSources()
                    .get("threshold");
            if (!(mode == 0 ? "Placement override" : "Layer default").equals(source))
              throw new AssertionError("Trace source " + source);
          }
        }
      }
    }
    if (!original.equals(ProjectIO.JSON.valueToTree(loaded.project())))
      throw new AssertionError("Loaded project mutated");
    if (checked != 450) throw new AssertionError("Observation count");
    System.out.println(
        "WORLD_SETTING_FOCUS_ENGINE_ONLY_PASS 450 exact cells / explicit zero / reset / live"
            + " inheritance / trace sources");
  }
}
