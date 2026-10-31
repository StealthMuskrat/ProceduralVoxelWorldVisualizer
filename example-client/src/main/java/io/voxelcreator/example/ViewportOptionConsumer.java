package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;

/** Deferred engine-only exact field/stage replay after every finite view option route. */
public final class ViewportOptionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    if (!ProjectIO.JSON
            .readTree(out.resolve("option-project.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("after.json").toFile()))
        || !ProjectIO.JSON
            .readTree(out.resolve("preferences-before.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("View options changed persisted project/preferences");
    int checked = 0;
    for (var name : new String[] {"option-project.json", "after.json"}) {
      var load = new ProjectLoader().load(out.resolve(name));
      if (!load.success()) throw new AssertionError(load.diagnostics());
      var compiled = new CompiledProject(load.project());
      for (int x = -17; x <= 17; x++)
        if (compiled.noise2D("constant-2", 42).sample(x, -x) != .125
            || compiled.noise3D("constant-3", 42).sample(x, 2 * x, -x) != .25)
          throw new AssertionError("View option changed exact raw fields");
      var clear = load.project().worldGenerator.layers.getFirst().operations.get(1).uuid;
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int cx : new int[] {-1, 0}) {
              var position = new ChunkPos(cx, 0, 0);
              var pair = world.generateStageChunk(position, clear);
              var finalChunk = world.generateChunk(position);
              for (int z = 0; z < root; z++)
                for (int y = 0; y < root; y++)
                  for (int x = 0; x < root; x++) {
                    if (!world
                            .registry()
                            .semanticId(pair.before().getVoxelTypeId(x, y, z))
                            .equals("solid")
                        || pair.after().getVoxelTypeId(x, y, z) != 0
                        || !pair.changed(x, y, z)
                        || finalChunk.getVoxelTypeId(x, y, z) != 0)
                      throw new AssertionError(
                          "View option changed Before/After/Only Changes/Final cells");
                    checked++;
                  }
            }
          }
      }
    }
    if (checked != 288)
      throw new AssertionError("Unexpected signed/root exact stage replay coverage");
    System.out.println(
        "Viewport options engine-only PASS; complete project/preferences, .125/.25 raw fields and"
            + " 288 exact stage/final cells");
  }
}
