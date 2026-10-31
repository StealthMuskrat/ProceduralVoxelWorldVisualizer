package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;

/** Deferred engine-only exact field/world replay, independent of pressure and native UI. */
public final class PreviewResumeConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    if (!ProjectIO.JSON
            .readTree(out.resolve("resume-project.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("after.json").toFile()))
        || !ProjectIO.JSON
            .readTree(out.resolve("preferences-before.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Resume changed complete persisted project/preferences");
    int checked = 0;
    for (var name : new String[] {"resume-project.json", "after.json"}) {
      var load = new ProjectLoader().load(out.resolve(name));
      if (!load.success()) throw new AssertionError(load.diagnostics());
      var compiled = new CompiledProject(load.project());
      for (int x = -17; x <= 17; x++) {
        if (compiled.noise2D("constant-2", 42).sample(x, -x) != .125
            || compiled.noise3D("constant-3", 42).sample(x, 2 * x, -x) != .125)
          throw new AssertionError("Resume changed canonical fields");
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
                    if (!world.registry().semanticId(chunk.getVoxelTypeId(x, y, z)).equals("solid"))
                      throw new AssertionError("Pressure recovery changed exact occupied cells");
                    checked++;
                  }
            }
          }
      }
    }
    if (checked != 288) throw new AssertionError("Unexpected signed/root replay coverage");
    System.out.println(
        "Preview Resume engine-only PASS; complete project/preferences, .125 fields and 288 exact"
            + " solid cells");
  }
}
