package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.ChunkPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only complete bookmark edit/history and computational isolation replay. */
public final class BookmarkDecisionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var report = ProjectIO.JSON.readTree(out.resolve("bookmark-decisions-native.json").toFile());
    var before = ProjectIO.JSON.readTree(out.resolve("before.json").toFile());
    var updated = before.deepCopy();
    var world =
        (com.fasterxml.jackson.databind.node.ArrayNode) updated.path("bookmarks").path("world");
    var id = report.path("alpha").path("world").asText();
    int position = -1;
    for (int i = 0; i < world.size(); i++)
      if (world.get(i).path("uuid").asText().equals(id)) {
        position = i;
        var target = (com.fasterxml.jackson.databind.node.ObjectNode) world.get(i);
        target.put("x", 51);
        target.put("y", 52);
        target.put("z", 53);
      }
    if (position < 0) throw new AssertionError("Missing owned update target");
    var deleted = updated.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) deleted.path("bookmarks").path("world"))
        .remove(position);
    for (String name :
        List.of(
            "before",
            "updated",
            "update-undone",
            "update-redone",
            "deleted",
            "delete-undone",
            "delete-redone")) {
      var expected =
          name.equals("before") || name.equals("update-undone")
              ? before
              : name.equals("deleted") || name.equals("delete-redone") ? deleted : updated;
      var file = out.resolve(name + ".json");
      if (!expected.equals(ProjectIO.JSON.readTree(file.toFile())))
        throw new AssertionError("Unexpected whole-document edit " + name);
      var load = new ProjectLoader().load(file);
      if (!load.success()) throw new AssertionError(load.diagnostics());
      var p = load.project();
      var compiled = new CompiledProject(p);
      for (int x = -17; x <= 17; x++)
        if (compiled.noise2D("existing", 42).sample(x, -x) != .125)
          throw new AssertionError("Bookmark edits changed source samples");
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
        for (int root : new int[] {2, 4})
          try (var context =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            if (!context.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty())
              throw new AssertionError("Bookmark edits changed EMPTY World");
          }
      }
    }
    if (!before.equals(ProjectIO.JSON.readTree(out.resolve("protected-project.json").toFile()))
        || !ProjectIO.JSON
            .readTree(out.resolve("guard-target.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("blocked-capture.json").toFile()))
        || !ProjectIO.JSON
            .readTree(out.resolve("preferences-before.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Capture guard or preferences isolation failed");
    System.out.println(
        "Bookmark decisions engine-only PASS; exact Update/Delete and Undo/Redo documents, capture"
            + " guard, .125 samples and EMPTY World");
  }
}
