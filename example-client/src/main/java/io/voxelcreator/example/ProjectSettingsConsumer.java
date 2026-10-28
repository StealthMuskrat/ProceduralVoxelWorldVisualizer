package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;

/** Deferred engine-JAR replay of project seed, inclusive bounds and ambient expression values. */
public final class ProjectSettingsConsumer {
  public static void main(String[] args) throws Exception {
    var loaded = new ProjectLoader().load(Path.of(args[0]));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var project = new CompiledProject(loaded.project());
    if (project.defaultSeed() != 17 || project.minY() != -2 || project.maxY() != 3)
      throw new AssertionError("Saved project settings");
    int observations = 0;
    var sampler = project.noise2D("context", project.defaultSeed());
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++) {
        if (sampler.sample(x, z) != x + 24) throw new AssertionError("Project ambient expression");
        observations++;
      }
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                project, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (int x = -1; x <= 1; x++)
            for (int y = -4; y <= 5; y++) {
              int expected = y >= -2 && y <= 3 ? 1 : 0;
              if (world.getVoxelTypeId(new BlockPos(x, y, 0)) != expected)
                throw new AssertionError("Inclusive project clipping at " + y);
              observations++;
            }
        }
    }
    if (observations != 141) throw new AssertionError("Observation count");
    System.out.println(
        "PROJECT_SETTINGS_ENGINE_ONLY_PASS 141 exact observations / seed ambient values / inclusive"
            + " vertical clipping");
  }
}
