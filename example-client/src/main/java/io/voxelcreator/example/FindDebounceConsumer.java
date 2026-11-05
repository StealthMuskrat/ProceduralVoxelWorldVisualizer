package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Engine-only replay of the complete committed source before and after Find typing. */
public final class FindDebounceConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    int samples = 0, cells = 0;
    for (String name : new String[] {"before", "after"}) {
      var loaded = new ProjectLoader().load(out.resolve(name + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var compiled = new CompiledProject(loaded.project());
      for (String id : new String[] {"field-0", "field-549", "needle"})
        for (int x = -4; x <= 4; x++)
          for (int z = -4; z <= 4; z++) {
            if (compiled.noise2D(id, 0).sample(x, z) != .125)
              throw new AssertionError("Find changed committed scalar semantics");
            samples++;
          }
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int c : new int[] {-1, 0}) {
              var chunk = world.generateChunk(new ChunkPos(c, c, c));
              for (int z = 0; z < root; z++)
                for (int y = 0; y < root; y++)
                  for (int x = 0; x < root; x++) {
                    if (chunk.getVoxelTypeId(x, y, z) != 0)
                      throw new AssertionError("Find populated an unauthored World");
                    cells++;
                  }
            }
          }
      }
    }
    if (samples != 486 || cells != 288) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Find debounce engine-only PASS; 486 scalar observations and 288 EMPTY cells");
    background(out.resolve("background"));
  }

  private static void background(Path out) throws Exception {
    int samples = 0, cells = 0;
    for (String mode : new String[] {
        "ready", "coalesced", "retry", "cancel", "collapse", "revision", "live-query", "cancelled-worker", "close", "inline-handoff-retry"
    }) {
      var before = new ProjectLoader().load(out.resolve(mode).resolve("before.json"));
      var after = new ProjectLoader().load(out.resolve(mode).resolve("after.json"));
      if (!before.success() || !after.success()) throw new AssertionError("Rejected " + mode);
      var expected = io.voxelcreator.model.ProjectIO.JSON.valueToTree(before.project());
      if (mode.equals("revision"))
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("project")).put("seed", 1L);
      if (!expected.equals(io.voxelcreator.model.ProjectIO.JSON.valueToTree(after.project())))
        throw new AssertionError("Find modified unrelated committed values: " + mode);
      for (var loaded : java.util.List.of(before, after)) {
        var compiled = new CompiledProject(loaded.project());
        for (String id : new String[] {"field-0", "needle"})
          for (int x : new int[] {-17, -2, -1, 0, 1, 2, 17})
            for (int z : new int[] {-3, 0, 7}) {
              if (compiled.noise2D(id, 0).sample(x, z) != .125)
                throw new AssertionError("Find changed constant field: " + mode);
              samples++;
            }
        try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
          for (int root : new int[] {2, 4})
            try (var world = engine.openWorld(compiled,
                new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
              for (int c : new int[] {-1, 0}) {
                var chunk = world.generateChunk(new ChunkPos(c, c, c));
                for (int z = 0; z < root; z++)
                  for (int y = 0; y < root; y++)
                    for (int x = 0; x < root; x++) {
                      if (chunk.getVoxelTypeId(x, y, z) != 0)
                        throw new AssertionError("Find populated empty World: " + mode);
                      cells++;
                    }
              }
            }
        }
      }
    }
    if (samples != 840 || cells != 2880) throw new AssertionError("Background Find replay coverage");
    System.out.println("BACKGROUND_FIND_ENGINE_ONLY_PASS 840 constant fields / 2880 EMPTY cells");
  }

}
