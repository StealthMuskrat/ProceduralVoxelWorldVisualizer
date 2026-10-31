package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Deferred engine-only proof that palette/source-order edits retain exact World semantics. */
public final class WorldPaletteConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    if (!ProjectIO.JSON
        .readTree(out.resolve("preferences-before.json").toFile())
        .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Palette work changed preferences");
    int finalCells = 0, stageCells = 0;
    for (var name :
        new String[] {
          "palette-source.json", "fixed-after.json", "stream-after.json", "ghost-after.json"
        }) {
      var load = new ProjectLoader().load(out.resolve(name));
      if (!load.success()) throw new AssertionError(load.diagnostics());
      var compiled = new CompiledProject(load.project());
      boolean ghost = name.equals("ghost-after.json");
      for (int x = -17; x <= 17; x++)
        if (compiled.noise3D("constant", 42).sample(x, 2 * x, -x) != .25)
          throw new AssertionError("Palette edits changed raw fields");
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int cx : new int[] {-1, 0}) {
              var position = new ChunkPos(cx, 0, -1);
              var chunk = world.generateChunk(position);
              var pair =
                  ghost
                      ? world.generateStageChunk(
                          position,
                          load.project().worldGenerator.layers.getFirst().operations.getLast().uuid)
                      : null;
              for (int z = 0; z < root; z++)
                for (int y = 0; y < root; y++)
                  for (int x = 0; x < root; x++) {
                    int type = chunk.getVoxelTypeId(x, y, z);
                    if (ghost ? type != 0 : !world.registry().semanticId(type).equals("stone"))
                      throw new AssertionError("Palette edit changed exact final cell: " + name);
                    finalCells++;
                    if (pair != null) {
                      if (!world
                              .registry()
                              .semanticId(pair.before().getVoxelTypeId(x, y, z))
                              .equals("stone")
                          || pair.after().getVoxelTypeId(x, y, z) != 0
                          || !pair.changed(x, y, z))
                        throw new AssertionError(
                            "Removal-ghost display changed Before/After/Only Changes semantics");
                      stageCells++;
                    }
                  }
            }
          }
      }
    }
    if (finalCells != 576 || stageCells != 144)
      throw new AssertionError("Exact signed/root replay coverage changed");
    System.out.println(
        "World palette engine-only PASS; .25 field, 576 exact final cells and 144 Before/After/Only"
            + " Changes coordinates");
  }
}
