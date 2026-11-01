package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.*;
import java.nio.file.Path;

/** Engine-only replay of concrete defaults; no editor metadata classes are on the classpath. */
public final class ProfileNumericConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var metadata = ProjectIO.JSON.readTree(out.resolve("app-config.json").toFile());
    var value = metadata.path("creationDefaults").path("noise2D.algorithm.CONSTANT.value");
    if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
      throw new AssertionError("Missing finite saved scalar default");
    double constant = value.doubleValue();
    int samples = 0, cells = 0;
    for (String name : new String[] {"after", "created"}) {
      var loaded = new ProjectLoader().load(out.resolve(name + ".json"));
      if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
      var compiled = new CompiledProject(loaded.project());
      for (int x = -4; x <= 4; x++)
        for (int z = -4; z <= 4; z++) {
          if (name.equals("after")) {
            if (compiled.noise2D("existing", 0).sample(x, z) != .125)
              throw new AssertionError("Profile changed an existing field");
            samples++;
          } else {
            if (compiled.noise2D("future-flat", 0).sample(x, z) != constant
                || compiled.noise3D("future-dot", 0).sample(x, -x, z) != 35)
              throw new AssertionError("Concrete default scalar/vector semantics changed");
            samples += 2;
          }
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
                      throw new AssertionError("Profile populated an unauthored World");
                    cells++;
                  }
            }
          }
      }
    }
    if (samples != 243 || cells != 288) throw new AssertionError("Replay coverage changed");
    System.out.println(
        "Profile numeric engine-only PASS; 243 exact scalar observations and 288 EMPTY cells");
  }
}
