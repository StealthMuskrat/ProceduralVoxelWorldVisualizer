package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.Path;

/** Independent engine-only check of concrete values created by an editor application profile. */
public final class CreationDefaultsConsumer {
  public static void main(String[] args) throws Exception {
    var load = new ProjectLoader().load(Path.of(args[0]));
    if (!load.success()) throw new AssertionError(load.diagnostics().toString());
    var project = new CompiledProject(load.project());
    for (int x = -17; x <= 17; x++)
      for (int z = -3; z <= 3; z++) {
        if (project.noise2D("created-multiply", 73).sample(x, z) != 2.5)
          throw new AssertionError("Multiply default");
        if (project.noise3D("created-add", 73).sample(x, 11, z) != 1)
          throw new AssertionError("Add default");
        if (project.noise2D("created-curve", 73).sample(x, z) != 4.5)
          throw new AssertionError("Curve default");
        if (project.noise2D("created-gradient", 73).sample(x, z) != z + 3)
          throw new AssertionError("Transform default");
      }
    var color = load.project().voxelTypes.getFirst().color;
    if (color.r != 12 || color.g != 34 || color.b != 56)
      throw new AssertionError("Voxel color default");
    try (var engine = new NoiseGeneratorEngine(1, 8, 16L << 20);
        var world =
            engine.openWorld(
                project, new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      if (!world.generateChunk(new ChunkPos(0, 0, 0)).isEmpty())
        throw new AssertionError("Creation profile populated the empty world");
    }
    System.out.println(
        "Independent creation-profile project PASS; engine-only 2D/3D/curve/transform/color and"
            + " empty-world expectations");
  }
}
