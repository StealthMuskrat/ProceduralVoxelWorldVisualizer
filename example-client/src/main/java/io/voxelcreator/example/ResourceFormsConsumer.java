package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only interpretation of explicit creation/placement/replacement/noise edits. */
public final class ResourceFormsConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var names = List.of("before", "created", "placed", "deleted", "resolved");
    for (int step = 0; step < names.size(); step++) {
      var loaded = new ProjectLoader().load(out.resolve(names.get(step) + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var p = loaded.project();
      if (p.noise2D.size() != (step == 0 ? 257 : 258)
          || p.voxelTypes.size() != (step < 3 ? 2 : 1)
          || p.voxelGroups.size() != 512) throw new AssertionError("Resource form counts");
      var n = p.noise2D.getFirst();
      if (!n.id.equals("field")
          || n.sharedParameters.size() != (step == 4 ? 0 : 1)
          || n.seedOffset != 0) throw new AssertionError("Private noise data leaked");
      var layer = p.worldGenerator.layers.getFirst();
      if (layer.operations.size() != (step < 2 ? 0 : 1))
        throw new AssertionError("Placement count");
      if (step >= 2
          && (!layer.operations.getFirst().noiseGeneratorUuid.equals(p.noise2D.get(1).uuid)
              || !layer.operations.getFirst().overrides.isEmpty()
              || !layer.defaults.action.equals("NO_CHANGE")))
        throw new AssertionError("Placement inheritance changed");
      var member = p.voxelTypes.getFirst().uuid;
      for (var group : p.voxelGroups)
        if (!group.voxelTypeUuids.equals(List.of(member)))
          throw new AssertionError("Replacement references");
      var compiled = new CompiledProject(p);
      for (int x : new int[] {-31, 0, 37}) {
        if (compiled.noise2D(n.uuid, 42).sample(x, -x) != 3)
          throw new AssertionError("Inline parameter changed arithmetic");
        for (int i = 1; i <= 256; i++)
          if (compiled.noise2D(p.noise2D.get(i).uuid, 42).sample(x, -x) != .125)
            throw new AssertionError("Unrelated field changed");
      }
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
              throw new AssertionError("Inherited NO_CHANGE must remain EMPTY");
          }
      }
    }
    System.out.println(
        "Resource forms engine-only PASS; created resource, inherited placement, atomic replacement"
            + " and equivalent inline arithmetic");
  }
}
