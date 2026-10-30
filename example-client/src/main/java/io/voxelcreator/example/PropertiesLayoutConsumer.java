package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only interpretation of a repaired, explicitly applied Properties draft. */
public final class PropertiesLayoutConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    for (String name : List.of("before", "after", "undone", "redone")) {
      boolean changed = Set.of("after", "redone").contains(name);
      var loaded = new ProjectLoader().load(out.resolve("properties-" + name + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var p = loaded.project();
      if (!p.noise2D.stream().map(n -> n.id).toList().equals(List.of("a", "b"))
          || p.noise2D.getFirst().seedOffset != (changed ? 17 : 0)
          || p.noise2D.getFirst().sharedParameters.size() != 24)
        throw new AssertionError("Properties private input isolation");
      var operation = p.worldGenerator.layers.getFirst().operations.getFirst();
      if (!operation.noiseGeneratorUuid.equals(p.noise2D.getLast().uuid)
          || !operation.overrides.isEmpty())
        throw new AssertionError("Unrelated placement inheritance changed");
      var compiled = new CompiledProject(p);
      for (int x = -17; x <= 17; x++) {
        if (compiled.noise2D("a", 42).sample(x, -x) != (changed ? .75 : .125)
            || compiled.noise2D("b", 42).sample(x, -x) != .25)
          throw new AssertionError("Properties field publication mismatch");
      }
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
              throw new AssertionError("Inherited NO_CHANGE world must remain EMPTY");
          }
      }
    }
    System.out.println(
        "Properties layout engine-only PASS; repaired seed/value Apply, exact restored states and"
            + " unchanged inherited EMPTY world");
  }
}
