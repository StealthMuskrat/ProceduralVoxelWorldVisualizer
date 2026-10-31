package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Deferred engine-only replay; no editor/native dependency or reliance on preview pixels. */
public final class ViewportDocumentConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    if (!ProjectIO.JSON
            .readTree(out.resolve("replacement.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("replacement-after.json").toFile()))
        || !ProjectIO.JSON
            .readTree(out.resolve("preferences-before.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Preview retirement changed project/preferences");
    int cells = 0;
    for (var name :
        new String[] {
          "source.json",
          "committed.json",
          "replacement.json",
          "replacement-after.json",
          "empty.json"
        }) {
      var load = new ProjectLoader().load(out.resolve(name));
      if (!load.success()) throw new AssertionError(load.diagnostics());
      var compiled = new CompiledProject(load.project());
      boolean empty = name.equals("empty.json"),
          replacement = name.startsWith("replacement"),
          solid = name.equals("source.json");
      if (!empty)
        for (int x = -17; x <= 17; x++) {
          if (compiled.noise2D("constant-2", 42).sample(x, -x) != (replacement ? .5 : .125)
              || compiled.noise3D("constant-3", 42).sample(x, 2 * x, -x)
                  != (replacement ? .75 : .25))
            throw new AssertionError("Canonical field changed: " + name);
        }
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int cx : new int[] {-1, 0}) {
              var chunk = world.generateChunk(new ChunkPos(cx, 0, -1));
              for (int z = 0; z < root; z++)
                for (int y = 0; y < root; y++)
                  for (int x = 0; x < root; x++) {
                    var semantic = world.registry().semanticId(chunk.getVoxelTypeId(x, y, z));
                    if (solid ? !semantic.equals("solid") : chunk.getVoxelTypeId(x, y, z) != 0)
                      throw new AssertionError("Unexpected exact cell: " + name);
                    cells++;
                  }
            }
          }
      }
    }
    if (cells != 720) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Viewport document engine-only PASS; exact source/replacement fields and 720 signed/root"
            + " world cells");
  }
}
