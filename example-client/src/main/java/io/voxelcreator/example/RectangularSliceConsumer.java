package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Engine-only independent expectations for the four rectangular orthogonal image fixtures. */
public final class RectangularSliceConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    int samples = 0, cells = 0;
    for (String name : new String[] {"before", "after"}) {
      var loaded = new ProjectLoader().load(out.resolve(name + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var compiled = new CompiledProject(loaded.project());
      var sampler = compiled.noise3D("affine", 0);
      int[][] dimensions = {{5, 7}, {3, 7}, {3, 5}, {4, 2}};
      double[][] origins = {{10.625, 3.125}, {-8.375, 3.125}, {-8.375, 10.625}, {11, 5}};
      for (int plane = 0; plane < 4; plane++)
        for (int y = 0; y < dimensions[plane][1]; y++)
          for (int x = 0; x < dimensions[plane][0]; x++) {
            double u = origins[plane][0] + x * .75, v = origins[plane][1] + y * .75;
            double wx = plane == 0 || plane == 3 ? -3 : u;
            double wy = plane == 1 ? -3 : plane == 2 ? v : u;
            double wz = plane == 2 ? -3 : v;
            if (sampler.sample(wx, wy, wz) != wx + 10 * wy + 100 * wz)
              throw new AssertionError("Rectangular slice altered canonical sampling");
            samples++;
          }
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int c : new int[] {-1, 0}) {
              var chunk = world.generateChunk(new ChunkPos(c, c, c));
              for (int z = 0; z < root; z++)
                for (int y = 0; y < root; y++)
                  for (int x = 0; x < root; x++) {
                    if (chunk.getVoxelTypeId(x, y, z) != 0)
                      throw new AssertionError("Slices populated an unauthored World");
                    cells++;
                  }
            }
          }
      }
    }
    if (samples != 158 || cells != 288) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Rectangular slice engine-only PASS; 158 affine observations and 288 EMPTY cells");
  }
}
