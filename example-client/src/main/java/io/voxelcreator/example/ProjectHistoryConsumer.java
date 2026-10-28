package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-JAR replay of saved history states and headless failed-history retention. */
public final class ProjectHistoryConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    String[] files = {
      "history-before.json", "history-edited.json", "history-undone.json", "history-redone.json"
    };
    int samples = 0, cells = 0;
    UUID source = null, temporary = null;
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (int index = 0; index < files.length; index++) {
        var loaded = new ProjectLoader().load(out.resolve(files[index]));
        if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
        var p = loaded.project();
        boolean edited = (index & 1) == 1;
        if (p.noise2D.size() != (edited ? 2 : 1) || !p.worldGenerator.layers.isEmpty())
          throw new AssertionError("History structure");
        if (source == null) source = p.noise2D.getFirst().uuid;
        if (!source.equals(p.noise2D.getFirst().uuid)
            || !p.noise2D.getFirst().id.equals("owned")
            || p.noise2D.getFirst().seedOffset != (edited ? 17 : 0))
          throw new AssertionError("History source identity/seed");
        if (edited) {
          if (temporary == null) temporary = p.noise2D.getLast().uuid;
          if (!temporary.equals(p.noise2D.getLast().uuid)
              || !p.noise2D.getLast().id.equals("temporary"))
            throw new AssertionError("Redo changed restored identity");
        }
        var compiled = new CompiledProject(p);
        for (int n = 0; n < p.noise2D.size(); n++) {
          double expected = n == 0 ? (edited ? .75 : .25) : .5;
          var sampler = compiled.noise2D(p.noise2D.get(n).uuid, p.project.seed);
          for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) {
              if (Double.doubleToRawLongBits(sampler.sample(x, z))
                  != Double.doubleToRawLongBits(expected))
                throw new AssertionError("History scalar value");
              samples++;
            }
        }
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int chunkX : new int[] {-1, 0}) {
              var chunk = world.generateChunk(new ChunkPos(chunkX, 0, 0));
              for (int z = 0; z < root; z++)
                for (int y = 0; y < root; y++)
                  for (int x = 0; x < root; x++) {
                    if (chunk.getVoxelTypeId(x, y, z) != 0)
                      throw new AssertionError("History changed empty world");
                    cells++;
                  }
            }
          }
      }
    }
    var document = new DocumentSession();
    if (!document.edit(p -> p.project.seed = 41).isEmpty())
      throw new AssertionError("Headless fixture");
    var active = document.data();
    long bytes = document.historyBytes();
    document.setValidator(
        p ->
            List.of(
                new Diagnostic(
                    "HISTORY_FIXTURE",
                    Diagnostic.Severity.ERROR,
                    "Rejected",
                    "",
                    null,
                    null,
                    null)));
    try {
      document.undo();
      throw new AssertionError("Rejected headless history accepted");
    } catch (IllegalStateException expected) {
      /* The public model retains its pending delta. */
    }
    if (document.data() != active
        || !document.canUndo()
        || document.canRedo()
        || bytes != document.historyBytes()) throw new AssertionError("Failed history lost state");
    document.setValidator(Validator::validate);
    document.undo();
    document.redo();
    if (document.data().project.seed != 41 || samples != 486 || cells != 576)
      throw new AssertionError("History retry or observation count");
    System.out.println(
        "PROJECT_HISTORY_ENGINE_ONLY_PASS 486 scalar values / 576 EMPTY cells / restored UUIDs /"
            + " headless history rejection and retry");
  }
}
