package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred real clipboard/rename callers independent of replacement refresh callbacks. */
class ResourcePaneLifetimeTest {
  @TempDir Path directory;

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var n = new Noise();
              n.id = "field";
              n.algorithm.key = "CONSTANT";
              n.algorithm.parameters.clear();
              n.algorithm.parameters.put("value", .25);
              p.noise2D.add(n);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private ResourcePanel panel(EditorState state) {
    return new ResourcePanel(state, new PropertyPanel(state));
  }

  private Path saved(EditorState state) throws Exception {
    var file = directory.resolve("source.json");
    state.document.save(file);
    return file;
  }

  @Test
  void sameUuidReopenRetiresCopyAndCutBeforeShortcutPaste() throws Exception {
    for (String command : List.of("Copy", "Cut")) {
      try (var state = state()) {
        var file = saved(state);
        var panel = panel(state);
        panel.shortcut(command);
        var previous = panel.lifetimeState();
        var token = state.document.sessionToken();
        assertTrue(state.document.open(file).isEmpty());
        var before = ProjectIO.JSON.valueToTree(state.project());
        panel.shortcut("Paste");
        assertNotSame(token, state.document.sessionToken());
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(1, state.project().noise2D.size());
        assertFalse(state.document.canUndo());
        assertFalse(state.document.dirty());
        assertFalse(panel.lifetimeState().clipboard());
        assertTrue(panel.lifetimeState().opening() > previous.opening());
      }
    }
  }

  @Test
  void sameUuidNewLifetimeCannotInheritTheEarlierClipboard() throws Exception {
    try (var state = state()) {
      var panel = panel(state);
      panel.shortcut("Copy");
      var sameValues = ProjectIO.copy(state.project());
      state.newSession();
      assertTrue(state.change(p -> ResourceGraph.assign(p, sameValues)));
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      panel.shortcut("Paste");
      assertEquals(1, state.project().noise2D.size());
      assertFalse(panel.lifetimeState().clipboard());
    }
  }

  @Test
  void saveKeepsCopyAndPasteRemainsOneUndoableTransaction() throws Exception {
    try (var state = state()) {
      var panel = panel(state);
      panel.shortcut("Copy");
      long opening = panel.lifetimeState().opening();
      var file = saved(state);
      String before = Files.readString(file);
      panel.shortcut("Paste");
      assertEquals(2, state.project().noise2D.size());
      assertTrue(state.document.dirty());
      assertEquals(opening, panel.lifetimeState().opening());
      assertTrue(panel.lifetimeState().clipboard());
      state.document.undo();
      assertEquals(1, state.project().noise2D.size());
      assertFalse(state.document.dirty());
      state.document.redo();
      assertEquals(2, state.project().noise2D.size());
      assertEquals(before, Files.readString(file));
    }
  }

  @Test
  void rejectedOpenKeepsTheClipboardAndPendingPrivateInputs() throws Exception {
    try (var state = state()) {
      var panel = panel(state);
      panel.shortcut("Copy");
      long opening = panel.lifetimeState().opening();
      var bad = directory.resolve("bad.json");
      Files.writeString(bad, "{ malformed");
      ((Noise) state.draft).id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var draft = state.draft;
      assertFalse(state.document.open(bad).isEmpty());
      assertTrue(panel.lifetimeState().clipboard());
      assertEquals(opening, panel.lifetimeState().opening());
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void undoRedoChangesCanonicalObjectsWithoutRetiringTheClipboardSnapshot() throws Exception {
    try (var state = state()) {
      var panel = panel(state);
      panel.shortcut("Copy");
      long opening = panel.lifetimeState().opening();
      assertTrue(state.change(p -> p.noise2D.getFirst().algorithm.parameters.put("value", .75)));
      state.document.undo();
      state.document.redo();
      panel.shortcut("Paste");
      if (panel.probeEditPending())
        assertEquals(
            ResourceEditSession.Result.APPLIED,
            panel.decideEdit(SearchNavigation.Decision.DISCARD));
      assertEquals(2, state.project().noise2D.size());
      assertEquals(.75, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
      assertEquals(.25, state.project().noise2D.getLast().algorithm.parameters.get("value"));
      assertEquals(opening, panel.lifetimeState().opening());
    }
  }

  @Test
  void staleRenameCannotKeepTheNewSessionBlocked() throws Exception {
    try (var state = state()) {
      var file = saved(state);
      var panel = panel(state);
      panel.probeVoxelRename(state.selected);
      assertNotNull(panel.lifetimeState().rename());
      assertTrue(panel.blocksProjectTransition());
      assertTrue(state.document.open(file).isEmpty());
      var draft = state.draft;
      assertFalse(panel.blocksProjectTransition());
      assertNull(panel.lifetimeState().rename());
      assertSame(draft, state.draft);
      assertEquals("field", state.project().noise2D.getFirst().id);
    }
  }

  @Test
  void managerAndClipboardRetireOnPreparedOpenWithoutAResetCallback() throws Exception {
    try (var state = state()) {
      var file = saved(state);
      var panel = panel(state);
      panel.shortcut("Copy");
      panel.showManager();
      assertTrue(panel.lifetimeState().manager());
      var prepared = state.document.requestOpen(file).prepare();
      state.document.applyOpen(prepared);
      var view = panel.lifetimeState();
      assertFalse(view.manager());
      assertFalse(view.clipboard());
      assertNull(view.rename());
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void pendingClipboardEditCannotApplyAfterIdenticalReopen() throws Exception {
    try (var state = state()) {
      var file = saved(state);
      var panel = panel(state);
      panel.shortcut("Copy");
      ((Noise) state.draft).id = "private";
      panel.shortcut("Paste");
      assertTrue(panel.probeEditPending());
      assertTrue(state.document.open(file).isEmpty());
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertEquals(
          ResourceEditSession.Result.UNAVAILABLE,
          panel.decideEdit(SearchNavigation.Decision.DISCARD));
      assertFalse(panel.probeEditPending());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals("private", ((Noise) state.draft).id);
      assertFalse(state.document.canUndo());
    }
  }

  @Test
  void aNewCopyAfterReopenIsNotClearedByRetiringItsPredecessor() throws Exception {
    try (var state = state()) {
      var file = saved(state);
      var panel = panel(state);
      panel.shortcut("Cut");
      assertTrue(state.document.open(file).isEmpty());
      panel.shortcut("Copy");
      assertTrue(panel.lifetimeState().clipboard());
      panel.shortcut("Paste");
      assertEquals(2, state.project().noise2D.size());
      assertTrue(panel.lifetimeState().clipboard());
    }
  }

  @Test
  void lifetimeSynchronizationIsIdempotentAndDoesNotDirtyProjectOrPreferences() throws Exception {
    try (var state = state()) {
      var file = saved(state);
      var panel = panel(state);
      panel.shortcut("Copy");
      panel.showManager();
      panel.probeVoxelRename(state.selected);
      var beforePreferences = ProjectIO.JSON.valueToTree(state.user);
      assertTrue(state.document.open(file).isEmpty());
      var beforeProject = ProjectIO.JSON.valueToTree(state.project());
      var view = panel.lifetimeState();
      for (int i = 0; i < 8; i++) assertEquals(view, panel.lifetimeState());
      assertEquals(beforeProject, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(beforePreferences, ProjectIO.JSON.valueToTree(state.user));
      assertFalse(state.document.dirty());
      assertFalse(state.document.canUndo());
    }
  }

  @Test
  void explicitSameSessionResetStillRetiresClipboardAndNativeOpening() {
    try (var state = state()) {
      var panel = panel(state);
      panel.shortcut("Copy");
      var view = panel.lifetimeState();
      panel.reset();
      assertFalse(panel.lifetimeState().clipboard());
      assertTrue(panel.lifetimeState().opening() > view.opening());
      panel.shortcut("Paste");
      assertEquals(1, state.project().noise2D.size());
    }
  }

  @Test
  void cancelledPreparedSaveRetainsResourceSessionState() throws Exception {
    try (var state = state()) {
      var panel = panel(state);
      panel.shortcut("Copy");
      panel.probeVoxelRename(state.selected);
      var before = panel.lifetimeState();
      state.document.requestSave(directory.resolve("cancelled.json")).prepare().close();
      assertEquals(before, panel.lifetimeState());
      assertFalse(Files.exists(directory.resolve("cancelled.json")));
    }
  }
}
