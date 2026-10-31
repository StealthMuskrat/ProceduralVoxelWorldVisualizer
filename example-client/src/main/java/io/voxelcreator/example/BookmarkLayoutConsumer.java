package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred engine-only consumption of authored bookmark edits, independent of editor/native APIs.
 */
public final class BookmarkLayoutConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    for (String name :
        List.of(
            "before",
            "added",
            "added-undone",
            "added-redone",
            "renamed",
            "renamed-undone",
            "renamed-redone")) {
      var loaded = new ProjectLoader().load(out.resolve(name + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var p = loaded.project();
      boolean added = !name.equals("before") && !name.equals("added-undone");
      boolean renamed = name.equals("renamed") || name.equals("renamed-redone");
      if (p.bookmarks.noise2D.size() != (added ? 2 : 1)
          || p.bookmarks.noise3D.size() != 1
          || p.bookmarks.world.size() != 1
          || !p.bookmarks
              .world
              .getFirst()
              .name
              .equals(renamed ? "successor-world" : "original-world"))
        throw new AssertionError("Independent bookmark counts/name mismatch");
      if (added) {
        var b = p.bookmarks.noise2D.getLast();
        if (!b.name.equals("private-noise2D") || b.x != 17 || b.y != null || b.z != -7)
          throw new AssertionError("Independent 2D bookmark coordinates mismatch");
      }
      if (p.noise2D.size() != 1
          || !p.noise2D.getFirst().id.equals("existing")
          || p.noise2D.getFirst().seedOffset != 0)
        throw new AssertionError("Private Properties input leaked into authored data");
      var compiled = new CompiledProject(p);
      for (int x = -17; x <= 17; x++)
        if (compiled.noise2D("existing", 42).sample(x, -x) != .125)
          throw new AssertionError("Bookmark edit changed authored source samples");
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
              throw new AssertionError("Bookmark edit changed EMPTY World");
          }
      }
    }
    System.out.println(
        "Bookmark layout engine-only PASS; exact authored bookmarks, .125 samples and EMPTY World");
  }
}
