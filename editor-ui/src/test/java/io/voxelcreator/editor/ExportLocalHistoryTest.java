package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred explicit export history; dependency coverage and file review are always rebuilt. */
class ExportLocalHistoryTest {
  @TempDir Path directory;

  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(p -> {
      for (String id : List.of("a", "b")) {
        var noise = new Noise();
        noise.id = id;
        noise.algorithm.key = "CONSTANT";
        noise.algorithm.parameters.clear();
        noise.algorithm.parameters.put("value", id.equals("a") ? .125 : .75);
        p.noise2D.add(noise);
      }
      for (String id : List.of("solid", "water")) {
        var type = new VoxelType();
        type.id = id;
        p.voxelTypes.add(type);
      }
      var members = new Membership();
      members.id = "catalog";
      p.voxelTypes.forEach(type -> members.voxelTypeUuids.add(type.uuid));
      p.voxelSets.add(members);
    }));
    state.select("noise2D", first(state));
    ((Noise) state.draft).id = "private-name";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  private static UUID first(EditorState state) {
    return state.project().noise2D.getFirst().uuid;
  }

  private static UUID last(EditorState state) {
    return state.project().noise2D.getLast().uuid;
  }

  private record Before(
      JsonNode project, JsonNode draft, JsonNode user, Object owner, UUID selected,
      Set<UUID> resources, long revision, long history, boolean dirty) {
    Before(EditorState state) {
      this(ProjectIO.JSON.valueToTree(state.project()), ProjectIO.JSON.valueToTree(state.draft),
          ProjectIO.JSON.valueToTree(state.user), state.draft, state.selected,
          Set.copyOf(state.multiSelection), state.revision, state.document.historyBytes(), state.document.dirty());
    }

    void unchanged(EditorState state) {
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertSame(owner, state.draft);
      assertEquals(selected, state.selected);
      assertEquals(resources, state.multiSelection);
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertEquals(dirty, state.document.dirty());
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void historyRestoresExplicitChoicesAndRebuildsDependencyCoverageWithoutParentMutation() {
    try (var state = state()) {
      var before = new Before(state);
      UUID noise = first(state), members = state.project().voxelSets.getFirst().uuid;
      var edit = new ExportEditSession(state, Set.of(noise));
      var initial = edit.preview();
      edit.markShown(initial);
      edit.select(Set.of(noise, members));
      assertEquals(Set.of("solid", "water"), new HashSet<>(
          edit.preview().resources().voxelTypes.stream().map(type -> type.id).toList()));
      assertEquals(1, edit.preview().resources().voxelSets.size());
      assertEquals(Set.of(noise, members), edit.selected());
      assertTrue(edit.undo());
      assertEquals(Set.of(noise), edit.selected());
      assertTrue(edit.preview().resources().voxelSets.isEmpty());
      assertTrue(edit.preview().resources().voxelTypes.isEmpty());
      assertNotSame(initial, edit.preview());
      edit.markShown(initial);
      assertFalse(edit.canExport());
      assertTrue(edit.redo());
      assertEquals(Set.of(noise, members), edit.selected());
      assertEquals(2, edit.preview().resources().voxelTypes.size());
      assertFalse(edit.canExport());
      before.unchanged(state);
      edit.cancel();
    }
  }

  @Test
  void emptySelectionsCanBeUndoneAndNoopsOrInvalidInputsPreserveReviewAndRedo() {
    try (var state = state()) {
      var edit = new ExportEditSession(state, Set.of(first(state)));
      edit.select(Set.of());
      assertFalse(edit.canExport());
      assertTrue(edit.undo());
      var restored = edit.preview();
      edit.markShown(restored);
      long version = edit.selectionVersion();
      edit.select(Set.of(first(state)));
      assertThrows(NullPointerException.class, () -> edit.select(Collections.singleton(null)));
      assertFalse(edit.undo());
      assertEquals(version, edit.selectionVersion());
      assertSame(restored, edit.preview());
      assertTrue(edit.canExport());
      assertTrue(edit.canRedo());
      assertTrue(edit.redo());
      assertTrue(edit.selected().isEmpty());
      assertFalse(edit.canExport());
      edit.cancel();
    }
  }

  @Test
  void reloadClearsHistoryAndUndoOfExplicitMissingRemovalRestoresTheMissingSelection() {
    try (var state = state()) {
      var selected = new LinkedHashSet<>(Set.of(first(state), last(state)));
      var view = new ResourceExportPanel(state, selected);
      view.prepare();
      UUID removed = first(state);
      selected.remove(last(state));
      view.prepare();
      assertTrue(view.canUndo());
      assertTrue(state.change(p -> p.noise2D.removeFirst()));
      assertFalse(view.canUndo());
      assertThrows(IllegalStateException.class, view::undo);
      view.reload();
      assertFalse(view.canUndo());
      assertFalse(view.canRedo());
      assertEquals(1, view.unavailable());
      view.removeUnavailable();
      assertTrue(selected.isEmpty());
      assertTrue(view.undo());
      assertEquals(Set.of(removed), selected);
      assertEquals(selected, view.session().selected());
      assertEquals(1, view.unavailable());
      assertFalse(view.canExport());
      assertTrue(view.redo());
      assertTrue(selected.isEmpty());
      view.reset();
    }
  }

  @Test
  void failedPublicationRetainsHistoryAndBothSuccessfulWriteRoutesConsumeIt() throws Exception {
    for (boolean prepared : List.of(false, true))
      try (var state = state()) {
        var active = directory.resolve("active-" + prepared + ".json");
        state.document.save(active);
        var bytes = Files.readAllBytes(active);
        var before = new Before(state);
        var edit = new ExportEditSession(state, Set.of(first(state)));
        edit.select(Set.of(first(state), last(state)));
        edit.markShown(edit.preview());
        var files = new ResourcePackageFiles();
        assertThrows(IOException.class, () -> edit.write(active, files));
        assertArrayEquals(bytes, Files.readAllBytes(active));
        assertTrue(edit.canUndo());
        assertTrue(edit.undo());
        assertTrue(edit.redo());
        var preview = edit.preview();
        edit.markShown(preview);
        var output = directory.resolve("selection-" + prepared + ".pvresources.json");
        if (prepared) {
          try (var staged = files.prepare(output, edit.exportSnapshot(preview), state.document.path())) {
            edit.publishPrepared(preview, staged);
          }
        } else edit.write(output, files);
        assertTrue(edit.closed());
        assertFalse(edit.canUndo());
        assertFalse(edit.canRedo());
        assertThrows(IllegalStateException.class, edit::undo);
        assertEquals(List.of("a", "b"), files.read(output).noise2D.stream().map(noise -> noise.id).toList());
        before.unchanged(state);
      }
  }

  @Test
  void cancelledOrSameUuidReopenedSessionsCannotReuseLocalHistory() throws Exception {
    for (boolean cancel : List.of(false, true))
      try (var state = state()) {
        var edit = new ExportEditSession(state, Set.of(first(state)));
        edit.select(Set.of(last(state)));
        if (cancel) edit.cancel();
        else {
          var file = directory.resolve("same-project.json");
          state.document.save(file);
          assertTrue(state.document.open(file).isEmpty());
        }
        assertFalse(edit.canUndo());
        assertFalse(edit.canRedo());
        assertThrows(IllegalStateException.class, edit::undo);
        assertThrows(IllegalStateException.class, edit::redo);
        edit.cancel();
      }
  }

  private static void finishPlan(ExportEditSession edit, Deque<Runnable> work, Deque<Runnable> ui) {
    work.remove().run();
    ui.remove().run();
    edit.acceptPlanning();
  }

  @Test
  void undoSupersedesQueuedAndReadyClosurePlanningAndRetiresTheirReceipts() {
    for (boolean ready : List.of(false, true))
      try (var state = state()) {
        var before = new Before(state);
        var work = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var edit = new ExportEditSession(state, Set.of(first(state)), work::add, ui::add, () -> false);
        finishPlan(edit, work, ui);
        edit.select(Set.of(first(state), last(state)));
        var oldWork = work.remove();
        Runnable oldReceipt = null;
        if (ready) {
          oldWork.run();
          oldReceipt = ui.remove();
          oldReceipt.run();
        }
        assertTrue(edit.undo());
        oldWork.run();
        if (oldReceipt != null) oldReceipt.run();
        edit.acceptPlanning();
        assertTrue(edit.planning());
        finishPlan(edit, work, ui);
        assertEquals(Set.of(first(state)), edit.selected());
        assertEquals(List.of("a"), edit.preview().resources().noise2D.stream().map(noise -> noise.id).toList());
        assertTrue(edit.canRedo());
        assertFalse(edit.canExport());
        before.unchanged(state);
        edit.cancel();
      }
  }

  @Test
  void schedulerFailureKeepsDesiredSelectionAndLocalUndoCanRecoverIt() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      var attempts = new AtomicInteger();
      var edit = new ExportEditSession(state, Set.of(first(state)), task -> {
        if (attempts.getAndIncrement() == 1) throw new RejectedExecutionException("fixture");
        work.add(task);
      }, ui::add, () -> false);
      finishPlan(edit, work, ui);
      edit.select(Set.of(first(state), last(state)));
      assertTrue(edit.canRetryPlan());
      assertEquals(2, edit.selected().size());
      assertTrue(edit.undo());
      finishPlan(edit, work, ui);
      assertEquals(Set.of(first(state)), edit.selected());
      assertTrue(edit.canRedo());
      assertFalse(edit.canExport());
      edit.cancel();
    }
  }

  @Test
  void oldHostDecisionsCannotUndoSuccessorHistoryEvenWhenSelectionAndPreviewMatchAgain() {
    try (var state = state();
        var properties = new PropertyPanel(state);
        var resources = new ResourcePanel(state, properties, task -> {}, task -> {}, () -> false);
        var imports = new ResourceImportPanel(state, () -> false, () -> {});
        var dialog = new ResourcePackageDialog(state, resources, imports, () -> false)) {
      var before = new Before(state);
      assertTrue(dialog.open("Export"));
      var old = dialog.capture(10);
      resources.exportSelection.add(last(state));
      resources.exportView().session().select(resources.exportSelection);
      resources.exportSelection.remove(last(state));
      var edit = resources.exportView().session();
      edit.select(resources.exportSelection);
      assertEquals(old.selected(), edit.selected());
      assertSame(old.preview(), edit.preview());
      long version = edit.selectionVersion();
      dialog.dispatch(old, ResourcePackageDialog.Command.UNDO, 10);
      assertEquals(version, edit.selectionVersion());
      assertEquals(Set.of(first(state)), resources.exportSelection);
      dialog.dispatch(dialog.capture(11), ResourcePackageDialog.Command.UNDO, 11);
      assertEquals(Set.of(first(state), last(state)), resources.exportSelection);
      assertEquals(resources.exportSelection, edit.selected());
      assertTrue(edit.canRedo());
      before.unchanged(state);
    }
  }
}
