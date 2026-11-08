package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred private raw history, document/navigation boundaries and callback ownership. */
class BookmarkLocalHistoryTest {
  @TempDir Path directory;
  private Map<String, String> shown(BookmarkEditSession session) {
    var text = new LinkedHashMap<String, String>();
    session.axes().forEach(axis -> text.put(axis, session.text(axis)));
    return text;
  }

  @Test
  void everyTabRestoresGroupedRawInputWithoutChangingParentOrPrivateProperties() {
    for (var tab : Tab.values())
      try (var state = new EditorState(new UserConfig(), false)) {
        assertTrue(state.change(p -> { var n = new Noise(); n.id = "source"; p.noise2D.add(n); }));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(state.project());
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var draft = state.draft;
        long revision = state.revision, bytes = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var session = BookmarkEditSession.add(state, tab, new Coordinates(-3, tab.threeDimensional() ? 5 : null, -7));
        UUID id = session.uuid();
        String name = session.name();
        session.editName("l", "Name:text");
        session.editName("local", "Name:text");
        session.focusEdit("X:text");
        session.edit("X", "-", "X:text");
        session.edit("X", "+0017", "X:text");
        assertTrue(session.undo());
        assertEquals("-3", session.text("X"));
        assertEquals("local", session.name());
        assertTrue(session.undo());
        assertEquals(name, session.name());
        assertTrue(session.redo());
        assertTrue(session.redo());
        assertEquals("+0017", session.text("X"));
        assertEquals(id, session.uuid());
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(preferences, ProjectIO.JSON.valueToTree(state.user));
        assertEquals(revision, state.revision);
        assertEquals(bytes, state.document.historyBytes());
        assertSame(draft, state.draft);
        assertEquals("private", ((Noise) state.draft).id);
        assertEquals("-", state.seedInputs.text("seedOffset"));
        assertEquals(dirty, state.document.dirty());
      }
  }

  @Test
  void rawInvalidTextAndControlBoundariesRemainDistinctAndNoOpPreservesRedo() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var session = BookmarkEditSession.teleport(state, Tab.WORLD, new Coordinates(1, 2, 3));
      session.edit("X", "-", "X:text");
      session.focusEdit("X:slider");
      session.edit("X", "12", "X:slider");
      session.edit("X", "13", "X:slider");
      assertTrue(session.undo());
      assertEquals("-", session.text("X"));
      long version = session.inputVersion();
      session.edit("X", "-", "X:text");
      assertEquals(version, session.inputVersion());
      assertTrue(session.canRedo());
      session.edit("X", "+", "X:text");
      session.edit("X", "-", "X:text");
      session.finishEdit();
      assertTrue(session.canRedo());
      assertTrue(session.redo());
      assertEquals("13", session.text("X"));
      assertTrue(session.undo());
      assertTrue(session.undo());
      assertEquals("1", session.text("X"));
      session.edit("Z", "-9");
      assertFalse(session.canRedo());
    }
  }

  @Test
  void rejectedApplyKeepsHistoryAndRestorationClearsFeedbackBeforeOneFinalTransaction() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var session = BookmarkEditSession.add(state, Tab.NOISE_2D, new Coordinates(-3, null, -7));
      session.editName("exact");
      session.edit("X", "+0017");
      state.document.setValidator(p -> List.of(Diagnostic.error("REJECTED", "/bookmarks", "fixture")));
      assertFalse(session.apply(session.name(), shown(session), null));
      assertEquals(1, session.result().issueCount());
      assertTrue(session.undo());
      assertEquals(0, session.result().issueCount());
      assertEquals("-3", session.text("X"));
      assertTrue(session.redo());
      session.edit("Z", "2147483648");
      assertThrows(IllegalArgumentException.class, () -> session.apply(session.name(), shown(session), null));
      assertTrue(session.undo());
      state.document.setValidator(Validator::validate);
      assertTrue(session.apply(session.name(), shown(session), null));
      assertTrue(session.closed());
      assertFalse(session.canUndo());
      assertFalse(session.canRedo());
      var applied = ProjectIO.JSON.valueToTree(state.project());
      var bookmark = state.project().bookmarks.noise2D.getFirst();
      assertEquals(session.uuid(), bookmark.uuid);
      assertEquals("exact", bookmark.name);
      assertEquals(17, bookmark.x);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(applied, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void renameSurvivesUnrelatedEditsButTargetChangesAndSameUuidReopenDisableHistory() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(state.change(p -> { var b = new Bookmark(); b.name = "old"; b.y = 2; p.bookmarks.world.add(b); }));
      UUID id = state.project().bookmarks.world.getFirst().uuid;
      var rename = BookmarkEditSession.rename(state, Tab.WORLD, id);
      rename.editName("new");
      assertTrue(state.change(p -> p.project.seed = 19));
      assertTrue(rename.undo());
      assertEquals("old", rename.name());
      assertTrue(rename.redo());
      assertTrue(state.change(p -> p.bookmarks.world.getFirst().x = 5));
      assertFalse(rename.canUndo());
      assertThrows(IllegalStateException.class, rename::undo);
      var teleport = BookmarkEditSession.teleport(state, Tab.WORLD, new Coordinates(1, 2, 3));
      teleport.edit("X", "9");
      var file = directory.resolve("same-uuid.json");
      new ProjectIO().save(file, state.project());
      assertTrue(state.document.open(file).isEmpty());
      assertFalse(teleport.canUndo());
      assertThrows(IllegalStateException.class, teleport::undo);
      assertEquals("9", teleport.text("X"));
    }
  }

  @Test
  void navigationNeverRunsOnHistoryAndRunningOrClosedOwnersCannotRestore() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var session = BookmarkEditSession.teleport(state, Tab.WORLD, new Coordinates(1, 2, 3));
      session.edit("X", "9");
      int[] calls = {0};
      assertThrows(IllegalStateException.class, () -> session.apply(null, shown(session), (tab, position) -> {
        calls[0]++;
        assertFalse(session.canUndo());
        assertThrows(IllegalStateException.class, session::undo);
        throw new IllegalStateException("navigation failed");
      }));
      assertTrue(session.canUndo());
      assertTrue(session.undo());
      assertEquals("", session.result().failure());
      assertEquals(1, calls[0]);
      assertTrue(session.redo());
      assertTrue(session.apply(null, shown(session), (tab, position) -> {
        calls[0]++;
        assertEquals(Tab.WORLD, tab);
        assertEquals(new Coordinates(9, 2, 3), position);
      }));
      assertEquals(2, calls[0]);
      assertFalse(session.canUndo());
      assertThrows(IllegalStateException.class, session::redo);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(0, state.document.historyBytes());
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void rejectedTextDoesNotConsumeHistoryAndCancelStartsAFreshPrivateBaseline() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var session = BookmarkEditSession.add(state, Tab.NOISE_2D, new Coordinates(1, null, 3));
      session.editName("private");
      long version = session.inputVersion();
      assertThrows(IllegalArgumentException.class, () -> session.edit("X", "1".repeat(129)));
      assertThrows(IllegalArgumentException.class, () -> session.edit("Y", "2"));
      assertThrows(IllegalArgumentException.class, () -> session.editName("n".repeat(session.nameLimit() + 1)));
      assertEquals(version, session.inputVersion());
      assertTrue(session.canUndo());
      session.cancel();
      assertFalse(session.canUndo());
      assertThrows(IllegalStateException.class, session::undo);
      var successor = BookmarkEditSession.add(state, Tab.NOISE_2D, new Coordinates(1, null, 3));
      assertFalse(successor.canUndo());
      assertFalse(successor.canRedo());
      assertNotEquals(session.uuid(), successor.uuid());
    }
  }
}
