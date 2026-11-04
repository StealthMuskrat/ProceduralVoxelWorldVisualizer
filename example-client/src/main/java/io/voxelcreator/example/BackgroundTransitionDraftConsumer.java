package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;
import java.util.*;

/** Deferred headless-only saved/Undo/Redo observations for transition-owned draft Apply. */
public final class BackgroundTransitionDraftConsumer {
  static void run(Path out) throws Exception {
    int fields = 0, cells = 0, files = 0;
    for (String mode : List.of("apply", "cancel", "escape", "queued", "retry", "queue", "stale", "close", "guard", "exit")) {
      var names = new ArrayList<>(List.of("before", "after"));
      if (Set.of("apply", "retry", "queue").contains(mode)) names.addAll(List.of("undone", "redone"));
      for (String name : names) {
        var loaded = new ProjectLoader().load(out.resolve(mode).resolve(name + ".json"));
        if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
        var project = loaded.project();
        boolean applied = name.equals("redone") || name.equals("after") && Set.of("apply", "retry", "queue", "guard", "exit").contains(mode);
        double expected = applied ? .625 : .125;
        var noise = project.noise2D.getFirst();
        if (project.project.seed != 41 || project.noise2D.size() != 1 || !noise.id.equals("owned")
            || noise.seedOffset != 0 || !noise.algorithm.key.equals("CONSTANT")
            || !project.worldGenerator.layers.isEmpty()) throw new AssertionError("Transition document structure");
        var compiled = new CompiledProject(project);
        for (int x : new int[] {-17, -2, -1, 0, 1, 2, 17}) for (int z : new int[] {-3, 0, 7}) {
          if (compiled.noise2D("owned", 41).sample(x, z) != expected) throw new AssertionError(mode + "/" + name + " field");
          fields++;
        }
        try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
          for (int root : new int[] {2, 4})
            try (var world = engine.openWorld(compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
              for (int c : new int[] {-1, 0}) {
                var chunk = world.generateChunk(new ChunkPos(c, c, c));
                for (int z = 0; z < root; z++) for (int y = 0; y < root; y++) for (int x = 0; x < root; x++) {
                  if (chunk.getVoxelTypeId(x, y, z) != 0) throw new AssertionError("Transition changed empty World");
                  cells++;
                }
              }
            }
        }
        files++;
      }
    }
    if (files != 26 || fields != 546 || cells != 3744) throw new AssertionError("Transition replay coverage changed");
    System.out.println("BACKGROUND_TRANSITION_DRAFT_ENGINE_ONLY_PASS 26 documents / 546 independent fields / 3744 EMPTY cells");
  }
}
