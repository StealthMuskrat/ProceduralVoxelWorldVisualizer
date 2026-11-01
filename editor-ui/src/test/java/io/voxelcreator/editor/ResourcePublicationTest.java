package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourcePublicationTest {
  @TempDir Path directory;

  private EditorState state() throws Exception {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              for (String id : List.of("a", "b")) {
                var n = new Noise();
                n.id = id;
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", id.equals("a") ? .125 : .25);
                p.noise2D.add(n);
              }
            }));
    state.document.save(directory.resolve("project.json"));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private static UUID other(EditorState s) {
    return s.project().noise2D.getLast().uuid;
  }

  private static void dirty(EditorState s) {
    ((Noise) s.draft).seedOffset = 17;
  }

  @Test
  void combinedEditRemainsOwnedThroughReentrantValidationAndPublishesOneUndo() throws Exception {
    try (var s = state()) {
      var before = ProjectIO.JSON.valueToTree(s.project());
      dirty(s);
      var edit = new ResourceEditSession(s);
      assertEquals(
          ResourceEditSession.Result.NEEDS_DECISION,
          edit.request("Rename", ResourceBrowserEdits.rename(other(s), "renamed")));
      var calls = new AtomicInteger();
      long revision = s.revision;
      s.document.setValidator(
          p -> {
            calls.incrementAndGet();
            assertTrue(edit.busy());
            assertEquals(
                ResourceEditSession.Result.UNAVAILABLE,
                edit.decide(SearchNavigation.Decision.CANCEL));
            assertEquals(
                ResourceEditSession.Result.UNAVAILABLE,
                edit.decide(SearchNavigation.Decision.APPLY));
            edit.clear();
            assertTrue(edit.pending());
            assertEquals(
                ResourceEditSession.Result.UNAVAILABLE,
                edit.request("Nested", ResourceBrowserEdits.delete(other(s), null)));
            return NoiseCompiler.validate(p);
          });
      assertEquals(
          ResourceEditSession.Result.APPLIED, edit.decide(SearchNavigation.Decision.APPLY));
      assertFalse(edit.busy());
      assertFalse(edit.pending());
      assertEquals(1, calls.get());
      assertEquals(revision + 1, s.revision);
      assertEquals(17, s.project().noise2D.getFirst().seedOffset);
      assertEquals("renamed", s.project().noise2D.getLast().id);
      s.document.setValidator(NoiseCompiler::validate);
      s.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void immediateEditRetiresBeforeRefreshButStaysBusyAndReportsACompletedFailure() throws Exception {
    try (var s = state()) {
      var before = ProjectIO.JSON.valueToTree(s.project());
      var holder = new ResourceEditSession[1];
      var refreshes = new AtomicInteger();
      var edit =
          new ResourceEditSession(
              s,
              () -> {
                refreshes.incrementAndGet();
                assertTrue(holder[0].busy());
                assertFalse(holder[0].pending());
                holder[0].clear();
                assertEquals(
                    ResourceEditSession.Result.UNAVAILABLE,
                    holder[0].request("Nested", ResourceBrowserEdits.delete(other(s), null)));
                throw new IllegalStateException("x".repeat(5000));
              });
      holder[0] = edit;
      assertEquals(
          ResourceEditSession.Result.APPLIED,
          edit.request("Rename", ResourceBrowserEdits.rename(other(s), "renamed")));
      assertFalse(edit.busy());
      assertFalse(edit.pending());
      assertEquals(1, refreshes.get());
      assertEquals(
          ResourceEditSession.Result.UNAVAILABLE, edit.decide(SearchNavigation.Decision.APPLY));
      assertEquals("renamed", s.project().noise2D.getLast().id);
      assertTrue(
          s.events.snapshot().stream()
              .anyMatch(
                  e ->
                      e.code().equals("RESOURCE_EDIT_RECONCILE_FAILED")
                          && e.message().startsWith("Resource edit committed;")
                          && e.message().length() <= 1024
                          && e.count() == 1));
      s.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void rejectedEditReleasesItsFenceAndRetainsTheExactModalForAnExplicitRetry() throws Exception {
    try (var s = state()) {
      dirty(s);
      var draft = s.draft;
      var before = ProjectIO.JSON.valueToTree(s.project());
      var edit = new ResourceEditSession(s);
      edit.request("Rename", ResourceBrowserEdits.rename(other(s), "renamed"));
      s.document.setValidator(
          p -> {
            assertTrue(edit.busy());
            edit.clear();
            return List.of(Diagnostic.error("REJECT", "/fixture", "Rejected"));
          });
      assertEquals(
          ResourceEditSession.Result.REJECTED, edit.decide(SearchNavigation.Decision.DISCARD));
      assertFalse(edit.busy());
      assertTrue(edit.pending());
      assertSame(draft, s.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      s.document.setValidator(NoiseCompiler::validate);
      assertEquals(
          ResourceEditSession.Result.APPLIED, edit.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(0, s.project().noise2D.getFirst().seedOffset);
    }
  }

  @Test
  void identicalReopeningOrLogicalUuidChangesRetireBothKindsOfBrowserDecision() throws Exception {
    for (boolean reopen : new boolean[] {false, true})
      for (var decision :
          List.of(SearchNavigation.Decision.APPLY, SearchNavigation.Decision.DISCARD))
        try (var s = state()) {
          dirty(s);
          var edits = new ResourceEditSession(s);
          var selection = new ResourceSelectionSession(s);
          edits.request("Rename", ResourceBrowserEdits.rename(other(s), "never"));
          selection.click(other(s), false);
          long revision = s.revision;
          if (reopen) assertTrue(s.document.open(directory.resolve("project.json")).isEmpty());
          else s.project().project.uuid = UUID.randomUUID();
          var before = ProjectIO.JSON.valueToTree(s.project());
          var draft = s.draft;
          assertEquals(ResourceEditSession.Result.UNAVAILABLE, edits.decide(decision));
          assertEquals(ResourceSelectionSession.Result.UNAVAILABLE, selection.decide(decision));
          assertSame(draft, s.draft);
          assertEquals(revision, s.revision);
          assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        }
  }

  @Test
  void saveAndFailedOpenPreserveBothPendingDecisionsWithinOneDocument() throws Exception {
    for (boolean editFirst : new boolean[] {false, true})
      try (var s = state()) {
        dirty(s);
        var edits = new ResourceEditSession(s);
        var selection = new ResourceSelectionSession(s);
        edits.request("Rename", ResourceBrowserEdits.rename(other(s), "renamed"));
        selection.click(other(s), false);
        var token = s.document.sessionToken();
        s.document.save(directory.resolve("saved.json"));
        assertThrows(IOException.class, () -> s.document.open(directory.resolve("missing.json")));
        assertSame(token, s.document.sessionToken());
        if (editFirst) {
          assertEquals(
              ResourceEditSession.Result.APPLIED, edits.decide(SearchNavigation.Decision.APPLY));
          assertEquals("renamed", s.project().noise2D.getLast().id);
          assertEquals(17, s.project().noise2D.getFirst().seedOffset);
          assertEquals(
              ResourceSelectionSession.Result.UNAVAILABLE,
              selection.decide(SearchNavigation.Decision.DISCARD));
        } else {
          assertEquals(
              ResourceSelectionSession.Result.SELECTED,
              selection.decide(SearchNavigation.Decision.DISCARD));
          assertEquals(
              ResourceEditSession.Result.UNAVAILABLE,
              edits.decide(SearchNavigation.Decision.APPLY));
          assertEquals("a", s.project().noise2D.getFirst().id);
          assertEquals(0, s.project().noise2D.getFirst().seedOffset);
        }
      }
  }

  @Test
  void selectionApplyStaysBusyThroughInstallationAndCannotReplayACompletedRefreshFailure()
      throws Exception {
    try (var s = state()) {
      var before = ProjectIO.JSON.valueToTree(s.project());
      dirty(s);
      UUID target = other(s);
      var holder = new ResourceSelectionSession[1];
      var installs = new AtomicInteger();
      var selection =
          new ResourceSelectionSession(
              s,
              () -> {
                installs.incrementAndGet();
                assertTrue(holder[0].busy());
                assertFalse(holder[0].pending());
                holder[0].clear();
                assertEquals(
                    ResourceSelectionSession.Result.UNAVAILABLE,
                    holder[0].click(s.project().noise2D.getFirst().uuid, false));
                throw new IllegalStateException("installation boundary");
              });
      holder[0] = selection;
      selection.click(target, false);
      var calls = new AtomicInteger();
      s.document.setValidator(
          p -> {
            calls.incrementAndGet();
            assertTrue(selection.busy());
            selection.clear();
            assertTrue(selection.pending());
            assertEquals(
                ResourceSelectionSession.Result.UNAVAILABLE,
                selection.decide(SearchNavigation.Decision.APPLY));
            assertEquals(
                ResourceSelectionSession.Result.UNAVAILABLE,
                selection.decide(SearchNavigation.Decision.CANCEL));
            return NoiseCompiler.validate(p);
          });
      long revision = s.revision;
      assertEquals(
          ResourceSelectionSession.Result.UNAVAILABLE,
          selection.decide(SearchNavigation.Decision.APPLY));
      assertFalse(selection.busy());
      assertFalse(selection.pending());
      assertEquals(1, calls.get());
      assertEquals(1, installs.get());
      assertEquals(revision + 1, s.revision);
      assertEquals(target, s.selected);
      assertEquals(17, s.project().noise2D.getFirst().seedOffset);
      assertEquals(
          ResourceSelectionSession.Result.UNAVAILABLE,
          selection.decide(SearchNavigation.Decision.APPLY));
      assertTrue(
          s.events.snapshot().stream()
              .anyMatch(
                  e ->
                      e.code().equals("RESOURCE_SELECTION_FAILED")
                          && e.message().startsWith("Properties applied;")
                          && e.count() == 1));
      s.document.setValidator(NoiseCompiler::validate);
      s.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void rejectedSelectionApplyRetainsPrivateInputAndAllowsAnExplicitDiscard() throws Exception {
    try (var s = state()) {
      dirty(s);
      var draft = s.draft;
      var before = ProjectIO.JSON.valueToTree(s.project());
      var selection = new ResourceSelectionSession(s);
      UUID target = other(s);
      selection.click(target, false);
      s.document.setValidator(
          p -> {
            assertTrue(selection.busy());
            selection.clear();
            return List.of(Diagnostic.error("REJECT", "/fixture", "Rejected"));
          });
      assertEquals(
          ResourceSelectionSession.Result.NEEDS_DECISION,
          selection.decide(SearchNavigation.Decision.APPLY));
      assertFalse(selection.busy());
      assertTrue(selection.pending());
      assertSame(draft, s.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      assertEquals(
          ResourceSelectionSession.Result.SELECTED,
          selection.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(target, s.selected);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void aThrowingCompletedCallbackCannotClearItsNewerGuardedEdit() throws Exception {
    try (var s = state()) {
      dirty(s);
      var panel = new ResourcePanel(s, new PropertyPanel(s));
      var callbacks = new AtomicInteger();
      var successors = new AtomicInteger();
      assertEquals(
          ResourceEditSession.Result.NEEDS_DECISION,
          panel.probeRequestEdit(
              "First",
              ResourceBrowserEdits.rename(other(s), "first"),
              () -> {
                callbacks.incrementAndGet();
                ((Noise) s.draft).seedOffset = 23;
                assertEquals(
                    ResourceEditSession.Result.NEEDS_DECISION,
                    panel.probeRequestEdit(
                        "Successor",
                        ResourceBrowserEdits.rename(other(s), "second"),
                        successors::incrementAndGet));
                throw new IllegalStateException("old completion");
              }));
      assertEquals(
          ResourceEditSession.Result.APPLIED, panel.decideEdit(SearchNavigation.Decision.APPLY));
      assertTrue(panel.probeEditPending());
      assertFalse(panel.publishing());
      assertEquals("first", s.project().noise2D.getLast().id);
      assertEquals(17, s.project().noise2D.getFirst().seedOffset);
      assertEquals(23, ((Noise) s.draft).seedOffset);
      assertEquals(1, callbacks.get());
      assertEquals(0, successors.get());
      assertEquals(
          ResourceEditSession.Result.CANCELLED, panel.decideEdit(SearchNavigation.Decision.CANCEL));
      assertFalse(panel.probeEditPending());
      assertEquals("first", s.project().noise2D.getLast().id);
      assertEquals(23, ((Noise) s.draft).seedOffset);
      assertEquals(0, successors.get());
    }
  }

  @Test
  void panelResetAndNestedCommandsCannotDisposeOfAcceptedPublicationOrItsCompletion()
      throws Exception {
    try (var s = state()) {
      dirty(s);
      var panel = new ResourcePanel(s, new PropertyPanel(s));
      var callbacks = new AtomicInteger();
      assertEquals(
          ResourceEditSession.Result.NEEDS_DECISION,
          panel.probeRequestEdit(
              "Rename",
              ResourceBrowserEdits.rename(other(s), "renamed"),
              callbacks::incrementAndGet));
      s.document.setValidator(
          p -> {
            assertTrue(panel.publishing());
            assertTrue(panel.blocksProjectTransition());
            panel.reset();
            panel.shortcut("Delete");
            panel.beginExport();
            panel.probeRequestSelection(other(s));
            assertTrue(panel.probeEditPending());
            assertFalse(panel.probeSelectionPending());
            assertNull(panel.exportView().session());
            assertEquals(
                ResourceEditSession.Result.UNAVAILABLE,
                panel.decideEdit(SearchNavigation.Decision.CANCEL));
            assertEquals(
                ResourceEditSession.Result.UNAVAILABLE,
                panel.probeRequestEdit(
                    "Nested",
                    ResourceBrowserEdits.delete(other(s), null),
                    () -> fail("Nested completion")));
            return NoiseCompiler.validate(p);
          });
      assertEquals(
          ResourceEditSession.Result.APPLIED, panel.decideEdit(SearchNavigation.Decision.APPLY));
      assertFalse(panel.publishing());
      assertFalse(panel.probeEditPending());
      assertEquals(1, callbacks.get());
      assertEquals("renamed", s.project().noise2D.getLast().id);
    }
  }
}
