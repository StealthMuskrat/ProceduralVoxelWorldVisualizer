package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFileWorkflowTest {
  @TempDir Path dir;

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              p.project.seed = 41;
              var n = new Noise();
              n.id = "owned";
              n.algorithm.key = "CONSTANT";
              p.noise2D.add(n);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "unapplied";
    return state;
  }

  @Test
  void cancelPreservesDirtyProjectPathDraftSelectionAndUndoForEveryIntent() {
    for (var intent : Intent.values())
      try (var s = state()) {
        var before = ProjectIO.JSON.valueToTree(s.project());
        var draft = s.draft;
        var selected = s.selected;
        long revision = s.revision, history = s.document.historyBytes();
        var f = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"));
        f.request(intent);
        assertEquals(Phase.GUARD, f.phase());
        f.decide(Decision.CANCEL);
        assertEquals(Phase.IDLE, f.phase());
        assertTrue(s.document.dirty());
        assertNull(s.document.path());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        assertSame(draft, s.draft);
        assertEquals(selected, s.selected);
        assertEquals(revision, s.revision);
        assertEquals(history, s.document.historyBytes());
      }
  }

  @Test
  void failedSaveAsAbortsEveryIntentAndSuccessfulRetryOnlySaves() throws Exception {
    for (var intent : Intent.values())
      try (var s = state()) {
        int[] calls = new int[2];
        var f = new ProjectFileWorkflow(s, () -> calls[0]++, () -> calls[1]++);
        long revision = s.revision, history = s.document.historyBytes();
        f.request(intent);
        f.decide(Decision.SAVE);
        f.submit("\u0000");
        assertEquals(Phase.SAVE, f.phase());
        assertFalse(f.error().isEmpty());
        assertTrue(s.document.dirty());
        assertNull(s.document.path());
        var target = dir.resolve(intent + ".json");
        f.submit(target.toString());
        assertEquals(Phase.IDLE, f.phase());
        assertArrayEquals(new int[2], calls);
        assertEquals(41, s.project().project.seed);
        assertFalse(s.document.dirty());
        assertEquals(target, s.document.path());
        assertEquals(revision, s.revision);
        assertEquals(history, s.document.historyBytes());
        assertEquals("owned", new ProjectIO().load(target).project().noise2D.getFirst().id);
        assertEquals("unapplied", ((Noise) s.draft).id);
      }
  }

  @Test
  void cancelingSavePathAbortsThePendingTransition() {
    for (var intent : Intent.values())
      try (var s = state()) {
        var f = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"));
        f.request(intent);
        f.decide(Decision.SAVE);
        f.cancel();
        f.submit(dir.resolve("should-not-exist").toString());
        assertEquals(Phase.IDLE, f.phase());
        assertTrue(s.document.dirty());
        assertFalse(Files.exists(dir.resolve("should-not-exist")));
      }
  }

  @Test
  void failedNamedSaveAbortsExitAndPreservesDestinationAndHistory() throws Exception {
    try (var s = state()) {
      Path target = dir.resolve("project.json");
      s.document.save(target);
      assertTrue(s.change(p -> p.project.seed = 42));
      Files.delete(target);
      Files.createDirectory(target);
      Files.writeString(target.resolve("retained"), "sentinel");
      long history = s.document.historyBytes(), revision = s.revision;
      var f = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"));
      f.request(Intent.EXIT);
      f.decide(Decision.SAVE);
      assertEquals(Phase.IDLE, f.phase());
      assertTrue(s.document.dirty());
      assertEquals(target, s.document.path());
      assertFalse(f.error().isEmpty());
      assertEquals("sentinel", Files.readString(target.resolve("retained")));
      assertEquals(history, s.document.historyBytes());
      assertEquals(revision, s.revision);
      try (var paths = Files.list(dir)) {
        assertEquals(1, paths.count());
      }
    }
  }

  @Test
  void successfulSaveRunsEachOwnedTransitionExactlyOnce() throws Exception {
    for (var intent : Intent.values())
      try (var s = state()) {
        int[] calls = new int[2];
        var f = new ProjectFileWorkflow(s, () -> calls[0]++, () -> calls[1]++);
        f.request(intent);
        f.decide(Decision.SAVE);
        Path target = dir.resolve(intent + ".json");
        f.submit(target.toString());
        f.decide(Decision.DISCARD);
        assertEquals(41, new ProjectIO().load(target).project().project.seed);
        assertEquals(intent == Intent.NEW ? 1 : 0, calls[0]);
        assertEquals(intent == Intent.EXIT ? 1 : 0, calls[1]);
        assertEquals(intent == Intent.OPEN ? Phase.OPEN : Phase.IDLE, f.phase());
        if (intent == Intent.NEW) {
          assertTrue(s.project().noise2D.isEmpty());
          assertNull(s.document.path());
        } else {
          assertEquals(target, s.document.path());
          assertFalse(s.document.dirty());
        }
      }
  }

  @Test
  void discardDoesNotMarkTheOriginalProjectSavedWhenOpenIsCanceled() {
    try (var s = state()) {
      var before = ProjectIO.JSON.valueToTree(s.project());
      var draft = s.draft;
      var f = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"));
      f.request(Intent.OPEN);
      f.decide(Decision.DISCARD);
      f.cancel();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      assertTrue(s.document.dirty());
      assertSame(draft, s.draft);
    }
  }

  @Test
  void openFailuresRetainTheWholeSessionAndRetryResetsOnlyAfterSuccess() throws Exception {
    try (var s = state()) {
      var before = ProjectIO.JSON.valueToTree(s.project());
      var draft = s.draft;
      var selected = s.selected;
      long history = s.document.historyBytes(), revision = s.revision;
      int[] replaced = {0};
      var f = new ProjectFileWorkflow(s, () -> replaced[0]++, () -> fail("exit"));
      f.request(Intent.OPEN);
      f.decide(Decision.DISCARD);
      Path malformed = dir.resolve("bad.json");
      Files.writeString(malformed, "{broken");
      for (String path :
          new String[] {"", "\u0000", dir.resolve("missing").toString(), malformed.toString()}) {
        f.submit(path);
        assertEquals(Phase.OPEN, f.phase());
        assertFalse(f.error().isEmpty());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        assertTrue(s.document.dirty());
        assertSame(draft, s.draft);
        assertEquals(selected, s.selected);
        assertEquals(history, s.document.historyBytes());
        assertEquals(revision, s.revision);
        assertEquals(0, replaced[0]);
      }
      var candidate = new ProjectData();
      candidate.project.seed = 99;
      Path valid = dir.resolve("valid.json");
      new ProjectIO().save(valid, candidate);
      f.submit(valid.toString());
      assertEquals(99, s.project().project.seed);
      assertFalse(s.document.dirty());
      assertNull(s.draft);
      assertNull(s.selected);
      assertEquals("", s.selectedKind);
      assertTrue(s.multiSelection.isEmpty());
      assertFalse(s.document.canUndo());
      assertEquals(revision + 1, s.revision);
      assertEquals(1, replaced[0]);
      assertEquals(Phase.IDLE, f.phase());
    }
  }

  @Test
  void anOwnedDialogCannotBeReplacedByASecondCommand() {
    try (var s = state()) {
      int[] exits = {0};
      var f = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> exits[0]++);
      f.request(Intent.EXIT);
      f.request(Intent.NEW);
      f.save(true);
      assertEquals(Phase.GUARD, f.phase());
      f.decide(Decision.DISCARD);
      assertEquals(1, exits[0]);
    }
  }

  @Test
  void cleanTransitionsBypassTheGuardAndSaveAsCancelRetainsSavedPath() throws Exception {
    try (var s = state()) {
      Path path = dir.resolve("saved.json");
      s.document.save(path);
      int[] exits = {0};
      var f = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> exits[0]++);
      f.request(Intent.EXIT);
      assertEquals(1, exits[0]);
      assertEquals(Phase.IDLE, f.phase());
      f.save(true);
      assertEquals(Phase.SAVE, f.phase());
      f.cancel();
      assertEquals(path, s.document.path());
      assertFalse(s.document.dirty());
      f.request(Intent.OPEN);
      assertEquals(Phase.OPEN, f.phase());
    }
  }
}
