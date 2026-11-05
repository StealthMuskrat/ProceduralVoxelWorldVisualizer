package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only replay: background capture export never changes authored generation. */
public final class ProfileExportConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]); int fields = 0, cells = 0;
    for (String mode : List.of("json", "csv", "ready-cancel", "ready-failure-cancel",
        "ready-escape", "ready-close", "ready-collapse", "ready-path")) {
      var before = new ProjectLoader().load(out.resolve(mode).resolve("before.json"));
      var after = new ProjectLoader().load(out.resolve(mode).resolve("after.json"));
      if (!before.success() || !after.success() || !ProjectIO.JSON.valueToTree(before.project())
          .equals(ProjectIO.JSON.valueToTree(after.project()))) throw new AssertionError("Capture export changed " + mode);
      for (var loaded : List.of(before, after)) {
        var compiled = new CompiledProject(loaded.project());
        for (int x : new int[] {-17, -2, -1, 0, 1, 2, 17}) for (int z : new int[] {-3, 0, 7}) {
          if (compiled.noise2D("field", 0).sample(x, z) != .125) throw new AssertionError("Authored field changed"); fields++;
        }
        try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
          for (int root : new int[] {2, 4}) try (var world = engine.openWorld(compiled,
              new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int c : new int[] {-1, 0}) {
              var chunk = world.generateChunk(new ChunkPos(c, c, c));
              for (int z = 0; z < root; z++) for (int y = 0; y < root; y++) for (int x = 0; x < root; x++) {
                if (chunk.getVoxelTypeId(x, y, z) != 0) throw new AssertionError("Capture export changed EMPTY world"); cells++;
              }
            }
          }
        }
      }
      if (mode.equals("json") || mode.equals("csv")) {
        var capture = ProjectIO.JSON.readTree(out.resolve(mode).resolve("capture.json").toFile());
        var samples = capture.path("samples");
        if (samples.size() != 1 || samples.get(0).path("value").asLong() != 123
            || !samples.get(0).path("label").asText().equals("reviewed,雪"))
          throw new AssertionError("Capture review changed");
      }
    }
    if (fields != 336 || cells != 2304) throw new AssertionError("Background capture replay dimensions changed");
    System.out.println("BACKGROUND_PROFILE_EXPORT_ENGINE_ONLY_PASS 336 fields / 2304 EMPTY cells");
  }
}
