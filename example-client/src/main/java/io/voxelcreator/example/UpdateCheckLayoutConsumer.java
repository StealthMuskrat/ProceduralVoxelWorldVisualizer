package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.net.URI;
import java.nio.file.*;

/** Deferred engine-only project replay and ordinary JSON inspection; no update client is linked. */
public final class UpdateCheckLayoutConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var loaded = new ProjectLoader().load(out.resolve("unchanged-project.json"));
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    if (p.noise2D.size() != 1
        || !p.noise2D.getFirst().id.equals("existing")
        || p.noise2D.getFirst().seedOffset != 0
        || !p.bookmarks.noise2D.isEmpty()
        || !p.bookmarks.noise3D.isEmpty()
        || !p.bookmarks.world.isEmpty())
      throw new AssertionError("Update UI changed authored/private resource data");
    var compiled = new CompiledProject(p);
    for (int x = -17; x <= 17; x++)
      if (compiled.noise2D("existing", 42).sample(x, -x) != .125)
        throw new AssertionError("Update UI changed source samples");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          if (!world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
            throw new AssertionError("Update UI changed EMPTY World");
        }
    }
    var before = ProjectIO.JSON.readTree(out.resolve("preferences-before.json").toFile());
    var after = ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile());
    if (!before.equals(after)) throw new AssertionError("Update actions changed preferences");
    var metadata = ProjectIO.JSON.readTree(out.resolve("reviewed-metadata.json").toFile());
    var uri = URI.create(metadata.path("releaseUrl").asText());
    if (metadata.path("formatVersion").intValue() != 1
        || !metadata.path("version").asText().equals("0.2.0")
        || !uri.getScheme().equals("https")
        || !uri.getHost().equals("releases.example.test")
        || !uri.getPath().equals("/" + "segment/".repeat(300)))
      throw new AssertionError("Controlled reviewed metadata mismatch");
    System.out.println(
        "Update UI engine-only PASS; unchanged project/preferences, .125 samples and EMPTY World");
  }
}
