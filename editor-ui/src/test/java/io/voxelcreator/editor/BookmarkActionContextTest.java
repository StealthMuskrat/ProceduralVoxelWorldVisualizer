package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred toolbar capture/navigation identity; the moving camera is not an action owner. */
class BookmarkActionContextTest {
  private UUID fixture(EditorState state) {
    var bookmark = new ProjectData.Bookmark();
    bookmark.name = "reviewed";
    bookmark.x = 2;
    bookmark.y = 3;
    bookmark.z = 4;
    assertTrue(state.change(p -> p.bookmarks.world.add(bookmark)));
    return bookmark.uuid;
  }

  @Test
  void selectedRowTabAndPendingDialogMustRemainExactlyOwned() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var id = fixture(state);
      var row = new BookmarkIndex(state.project(), Tab.WORLD).get(id);
      var dialog = new String("owner");
      var review = BookmarkActionContext.capture(state, Tab.WORLD, id, row, dialog, true);
      assertTrue(review.current(state, Tab.WORLD, id, dialog, true));
      assertFalse(review.current(state, Tab.NOISE_3D, id, dialog, true));
      assertFalse(review.current(state, Tab.WORLD, UUID.randomUUID(), dialog, true));
      assertFalse(review.current(state, Tab.WORLD, id, new String("owner"), true));
      assertFalse(review.current(state, Tab.WORLD, id, dialog, false));
      assertFalse(
          review.same(
              BookmarkActionContext.capture(
                  state,
                  Tab.WORLD,
                  id,
                  new BookmarkIndex(state.project(), Tab.WORLD).get(id),
                  dialog,
                  true)));
    }
  }

  @Test
  void changedOrDeletedLiveTargetCannotReceiveAnOlderCoordinateCapture() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var id = fixture(state);
      var row = new BookmarkIndex(state.project(), Tab.WORLD).get(id);
      var review = BookmarkActionContext.capture(state, Tab.WORLD, id, row, null, false);
      var target = BookmarkTransactions.find(state.project(), Tab.WORLD, id);
      target.x = 99;
      assertFalse(review.current(state, Tab.WORLD, id, null, false));
      assertEquals(2, row.coordinates().x());
      target.x = 2;
      target.name = "changed";
      assertFalse(BookmarkActionContext.rowCurrent(state, Tab.WORLD, row));
      target.name = "reviewed";
      BookmarkTransactions.delete(state.project(), Tab.WORLD, id);
      assertFalse(review.current(state, Tab.WORLD, id, null, false));
    }
  }

  @Test
  void sameUuidDocumentReplacementAndRevisionChangesRetireTheFormerReview(@TempDir Path out)
      throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var id = fixture(state);
      var row = new BookmarkIndex(state.project(), Tab.WORLD).get(id);
      var file = out.resolve("project.json");
      state.document.save(file);
      var review = BookmarkActionContext.capture(state, Tab.WORLD, id, row, null, false);
      long revision = state.revision;
      assertTrue(state.document.open(file).isEmpty());
      assertEquals(revision, state.revision);
      assertEquals(review.project().project.uuid, state.project().project.uuid);
      assertFalse(review.current(state, Tab.WORLD, id, null, false));
      var next =
          BookmarkActionContext.capture(
              state,
              Tab.WORLD,
              id,
              new BookmarkIndex(state.project(), Tab.WORLD).get(id),
              null,
              false);
      state.revision++;
      assertFalse(next.current(state, Tab.WORLD, id, null, false));
    }
  }

  @Test
  void privatePropertiesAndIndexFilteringDoNotReplaceTheReviewedBookmark() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var id = fixture(state);
      var index = new BookmarkIndex(state.project(), Tab.WORLD);
      var row = index.get(id);
      var review = BookmarkActionContext.capture(state, Tab.WORLD, id, row, null, false);
      state.draft = new ProjectData.Noise();
      state.selected = UUID.randomUUID();
      index.rows("absent");
      assertTrue(review.current(state, Tab.WORLD, id, null, false));
      assertTrue(
          review.same(BookmarkActionContext.capture(state, Tab.WORLD, id, row, null, false)));
    }
  }
}
