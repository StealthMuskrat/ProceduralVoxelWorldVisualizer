package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;

/** Engine-only bookmark persistence and generation invariance, using independent expected values. */
public final class BookmarkConsumer {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the native bookmark project");
    var load = new ProjectLoader().load(Path.of(args[0]));
    require(load.success(), load.diagnostics().toString());
    var project = load.project();
    require(project.bookmarks.noise2D.size() == 2001 && project.bookmarks.noise3D.size() == 1
        && project.bookmarks.world.size() == 2, "Separate bookmark collections");
    var picked = project.bookmarks.noise2D.getLast();
    require(picked.name.equals("picked-point") && picked.x == -101 && picked.y == null && picked.z == -103,
        "Native picked coordinate save/reopen");
    var renamed = project.bookmarks.world.getLast();
    require(renamed.name.equals("renamed") && renamed.x == -31 && renamed.y == -37 && renamed.z == -41,
        "Native rename/update/undo-delete");
    var before = new CompiledProject(project);
    var draft = BookmarkTransactions.Draft.rename(project, Tab.WORLD, renamed.uuid);
    draft.name("headless rename"); draft.applyTo(project);
    BookmarkTransactions.update(project, Tab.WORLD, renamed.uuid, new Coordinates(7, -11, 13));
    require(before.noise2D("bookmark-field", 0).sample(-17, 23) == .625, "Immutable compiled field");
    var after = new CompiledProject(project);
    require(after.noise2D("bookmark-field", 0).sample(-17, 23) == .625, "Bookmarks changed field evaluation");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4, 8})
        try (var a = engine.openWorld(before, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0));
             var b = engine.openWorld(after, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          var position = new ChunkPos(-1, 0, -1);
          require(a.generateChunk(position).isEmpty() && b.generateChunk(position).isEmpty(),
              "Bookmark edits changed the empty world");
        }
    }
    System.out.println("BOOKMARK_ENGINE_ONLY_PASS coordinate-only persistence / tab isolation / native edits"
        + " / immutable compiled source / constant field / exact empty negative chunks at roots 2,4,8");
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
