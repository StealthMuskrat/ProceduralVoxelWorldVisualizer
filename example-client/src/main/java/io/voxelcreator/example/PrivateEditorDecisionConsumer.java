package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred engine-only replay after explicit expression/curve parent Apply and membership commit.
 */
public final class PrivateEditorDecisionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var names = List.of("before", "expression", "curve", "membership");
    for (int i = 0; i < names.size(); i++) {
      var loaded = new ProjectLoader().load(out.resolve(names.get(i) + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var p = loaded.project();
      if (!p.noise2D.stream().map(n -> n.id).toList().equals(List.of("formula", "shaped"))
          || p.voxelSets.size() != 1
          || !p.voxelSets.getFirst().id.equals("catalog")
          || p.voxelSets.getFirst().voxelTypeUuids.size() != (i == 3 ? 2 : 1))
        throw new AssertionError("Private ID/member isolation");
      var compiled = new CompiledProject(p);
      for (int x = -17; x <= 17; x++) {
        if (compiled.noise2D("formula", 42).sample(x, -x) != x + (i == 0 ? 1 : 2))
          throw new AssertionError("Expression parent Apply");
        if (compiled.noise2D("shaped", 42).sample(x, -x) != (i < 2 ? .25 : .5))
          throw new AssertionError("Curve point deletion");
      }
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
              throw new AssertionError("Unexpected World publication");
          }
      }
    }
    System.out.println(
        "Private editor decisions engine-only PASS; explicit parent Apply arithmetic, members-only"
            + " change and EMPTY worlds");
  }
}
