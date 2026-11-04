package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;

/** Deferred independent check that preference publication changes no authored generation data. */
public final class BackgroundPreferenceConsumer {
  public static void main(String[] args) throws Exception {
    int samples = 0, cells = 0;
    for (String mode : new String[] {
        "limits-apply", "limits-cancel", "limits-retry", "scales-apply", "scales-escape", "scales-edit", "trust-apply", "trust-revert", "trust-hidden"
    }) {
      var before = new ProjectLoader().load(Path.of(args[0]).resolve(mode).resolve("before.json"));
      var after = new ProjectLoader().load(Path.of(args[0]).resolve(mode).resolve("after.json"));
      if (!before.success() || !after.success()) throw new AssertionError("Rejected " + mode);
      if (!io.voxelcreator.model.ProjectIO.JSON.valueToTree(before.project())
          .equals(io.voxelcreator.model.ProjectIO.JSON.valueToTree(after.project())))
        throw new AssertionError("Preference operation changed authored values: " + mode);
      for (var loaded : java.util.List.of(before, after)) {
        var compiled = new CompiledProject(loaded.project());
        for (int x : new int[] {-17, -2, -1, 0, 1, 2, 17})
          for (int z : new int[] {-3, 0, 7}) {
            if (compiled.noise2D("field", 0).sample(x, z) != .125) throw new AssertionError(mode + ": field changed");
            samples++;
          }
        try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
          for (int root : new int[] {2, 4})
            try (var world = engine.openWorld(compiled,
                new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
              for (int c : new int[] {-1, 0}) {
                var chunk = world.generateChunk(new ChunkPos(c, c, c));
                for (int z = 0; z < root; z++) for (int y = 0; y < root; y++) for (int x = 0; x < root; x++) {
                  if (chunk.getVoxelTypeId(x, y, z) != 0) throw new AssertionError(mode + ": World changed"); cells++;
                }
              }
            }
        }
      }
    }
    if (samples != 378 || cells != 2592) throw new AssertionError("Preference replay coverage changed");
    System.out.println("BACKGROUND_PREFERENCE_ENGINE_ONLY_PASS 378 independent fields / 2592 EMPTY cells");
  }
}
