package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred exact-preview, stale-source and retry contracts for the editor export workspace. */
class ExportEditSessionTest {
  @TempDir Path directory;

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              for (String id : List.of("a", "b")) {
                var noise = new Noise();
                noise.id = id;
                noise.algorithm.key = "CONSTANT";
                noise.algorithm.parameters.clear();
                noise.algorithm.parameters.put("value", id.equals("a") ? .125 : .75);
                p.noise2D.add(noise);
              }
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private Set<UUID> first(EditorState state) {
    return Set.of(state.project().noise2D.getFirst().uuid);
  }

  private Path output() {
    return directory.resolve("selection.pvresources.json");
  }

  @Test
  void filePublicationRequiresTheExactShownPreviewAndDoesNotApplyPrivateProperties()
      throws Exception {
    try (var state = state()) {
      ((Noise) state.draft).id = "pending-id";
      state.seedInputs.edit(state, "seedOffset", "Seed offset", "-");
      Object draft = state.draft;
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, bytes = state.document.historyBytes();
      var edit = new ExportEditSession(state, first(state));
      assertFalse(edit.canExport());
      assertThrows(
          IllegalStateException.class, () -> edit.write(output(), new ResourcePackageFiles()));
      assertFalse(Files.exists(output()));
      edit.markShown(edit.preview());
      assertTrue(edit.canExport());
      edit.write(output(), new ResourcePackageFiles());
      assertEquals("a", new ResourcePackageFiles().read(output()).noise2D.getFirst().id);
      assertTrue(edit.closed());
      assertSame(draft, state.draft);
      assertEquals("pending-id", ((Noise) state.draft).id);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(bytes, state.document.historyBytes());
    }
  }

  @Test
  void selectionChangesAndForeignEqualValuePreviewsCannotReuseAReviewedToken() throws Exception {
    try (var state = state()) {
      var edit = new ExportEditSession(state, first(state));
      var old = edit.preview();
      edit.markShown(old);
      edit.select(Set.of(state.project().noise2D.getLast().uuid));
      edit.markShown(old);
      assertFalse(edit.canExport());
      var other = new ResourceExportSession();
      other.prepare(state.project(), state.revision, edit.selected());
      edit.markShown(other.preview());
      assertFalse(edit.canExport());
      assertThrows(
          IllegalStateException.class, () -> edit.write(output(), new ResourcePackageFiles()));
      edit.markShown(edit.preview());
      edit.write(output(), new ResourcePackageFiles());
      assertEquals("b", new ResourcePackageFiles().read(output()).noise2D.getFirst().id);
      assertEquals("a", old.resources().noise2D.getFirst().id);
    }
  }

  @Test
  void unchangedChoicesReuseOneOwnedPreviewAndReturnedCopiesCannotMutateIt() {
    try (var state = state()) {
      var ids = new LinkedHashSet<>(first(state));
      var edit = new ExportEditSession(state, ids);
      var preview = edit.preview();
      edit.markShown(preview);
      for (int i = 0; i < 20; i++) edit.select(new HashSet<>(ids));
      assertSame(preview, edit.preview());
      assertEquals(1, edit.preparations());
      assertTrue(edit.canExport());
      ids.clear();
      preview.resources().noise2D.clear();
      assertEquals(1, edit.selected().size());
      assertEquals(1, edit.preview().resources().noise2D.size());
      assertThrows(UnsupportedOperationException.class, () -> preview.selected().clear());
      assertThrows(UnsupportedOperationException.class, () -> preview.included().clear());
    }
  }

  @Test
  void committedChangesRetainTheOldPreviewUntilExplicitReloadAndReview() throws Exception {
    try (var state = state()) {
      var edit = new ExportEditSession(state, first(state));
      var old = edit.preview();
      edit.markShown(old);
      assertTrue(state.change(p -> p.noise2D.getFirst().algorithm.parameters.put("value", .5)));
      assertFalse(edit.current());
      assertSame(old, edit.preview());
      assertEquals(.125, old.resources().noise2D.getFirst().algorithm.parameters.get("value"));
      Files.writeString(output(), "previous target");
      assertThrows(
          IllegalStateException.class, () -> edit.write(output(), new ResourcePackageFiles()));
      assertEquals("previous target", Files.readString(output()));
      assertThrows(IllegalStateException.class, () -> edit.select(Set.of()));
      edit.reload();
      assertTrue(edit.current());
      assertEquals(old.selected(), edit.selected());
      assertEquals(
          .5, edit.preview().resources().noise2D.getFirst().algorithm.parameters.get("value"));
      edit.markShown(old);
      assertFalse(edit.canExport());
      edit.markShown(edit.preview());
      edit.write(output(), new ResourcePackageFiles());
      assertEquals(
          .5,
          new ResourcePackageFiles()
              .read(output())
              .noise2D
              .getFirst()
              .algorithm
              .parameters
              .get("value"));
    }
  }

  @Test
  void reloadRetainsMissingSelectionsUntilTheyAreExplicitlyRemoved() {
    try (var state = state()) {
      var edit = new ExportEditSession(state, first(state));
      var missing = edit.selected();
      edit.markShown(edit.preview());
      assertTrue(state.change(p -> p.noise2D.removeFirst()));
      edit.reload();
      assertEquals(missing, edit.selected());
      assertEquals(1, edit.unavailableCount());
      assertNull(edit.preview());
      assertFalse(edit.canExport());
      edit.select(Set.of(state.project().noise2D.getFirst().uuid));
      assertEquals(0, edit.unavailableCount());
      assertNotNull(edit.preview());
      assertFalse(edit.canExport());
      edit.markShown(edit.preview());
      assertTrue(edit.canExport());
    }
  }

  @Test
  void anotherProjectCannotBeAdoptedByReloadingAnExistingExport() {
    try (var state = state()) {
      var edit = new ExportEditSession(state, first(state));
      edit.markShown(edit.preview());
      state.newSession();
      assertFalse(edit.current());
      assertFalse(edit.canReload());
      assertThrows(IllegalStateException.class, edit::reload);
      assertThrows(
          IllegalStateException.class, () -> edit.write(output(), new ResourcePackageFiles()));
      assertFalse(Files.exists(output()));
    }
  }

  @Test
  void unsupportedInPlaceMutationCannotLeakIntoTheFrozenPreviewOrFile() throws Exception {
    try (var state = state()) {
      var edit = new ExportEditSession(state, first(state));
      edit.markShown(edit.preview());
      state.project().noise2D.getFirst().algorithm.parameters.put("value", .875);
      assertTrue(edit.current());
      assertEquals(
          .125, edit.preview().resources().noise2D.getFirst().algorithm.parameters.get("value"));
      assertThrows(
          IllegalStateException.class, () -> edit.write(output(), new ResourcePackageFiles()));
      assertFalse(edit.current());
      assertFalse(Files.exists(output()));
      edit.reload();
      assertEquals(
          .875, edit.preview().resources().noise2D.getFirst().algorithm.parameters.get("value"));
      assertFalse(edit.canExport());
    }
  }

  @Test
  void failedFileWritesKeepTheSameReviewedPreviewForCorrectedPathRetry() throws Exception {
    try (var state = state()) {
      var edit = new ExportEditSession(state, first(state));
      var preview = edit.preview();
      edit.markShown(preview);
      var failing =
          new ResourcePackageFiles(
              new ResourcePackageFiles.Storage() {
                public void write(Path temporary, byte[] content) throws IOException {
                  throw new IOException("Injected failure");
                }

                public void replace(Path temporary, Path destination) {
                  fail("Failed staging reached publication");
                }
              });
      assertThrows(IOException.class, () -> edit.write(output(), failing));
      assertSame(preview, edit.preview());
      assertTrue(edit.canExport());
      assertFalse(edit.closed());
      assertFalse(Files.exists(output()));
      edit.write(directory.resolve("retry.pvresources.json"), new ResourcePackageFiles());
      assertTrue(edit.closed());
      assertFalse(edit.canExport());
      assertThrows(
          IllegalStateException.class, () -> edit.write(output(), new ResourcePackageFiles()));
    }
  }

  @Test
  void cancelAndEmptySelectionsCannotPublishOrReuseAConsumedSession() {
    try (var state = state()) {
      var edit = new ExportEditSession(state, Set.of());
      edit.markShown(edit.preview());
      assertFalse(edit.canExport());
      assertThrows(
          IllegalStateException.class, () -> edit.write(output(), new ResourcePackageFiles()));
      edit.cancel();
      edit.cancel();
      assertTrue(edit.closed());
      assertFalse(edit.canReload());
      assertThrows(IllegalStateException.class, () -> edit.select(first(state)));
      assertThrows(IllegalStateException.class, edit::reload);
      assertFalse(Files.exists(output()));
    }
  }

  @Test
  void preparedSnapshotsSurviveLaterModelPreparationClearAndCompatibilityReads() {
    try (var state = state()) {
      var model = new ResourceExportSession();
      model.prepare(state.project(), state.revision, first(state));
      var original = model.preview();
      model.prepare(
          state.project(), state.revision, Set.of(state.project().noise2D.getLast().uuid));
      model.clear();
      assertEquals("a", original.resources().noise2D.getFirst().id);
      assertEquals("a", original.included().getFirst().id());
      var edit = new ExportEditSession(state, first(state));
      edit.markShown(edit.preview());
      edit.preparedModel().clear();
      edit.preparedModel();
      assertFalse(edit.canExport());
      assertEquals("a", edit.preview().resources().noise2D.getFirst().id);
      edit.markShown(edit.preview());
      assertTrue(edit.canExport());
    }
  }
}
