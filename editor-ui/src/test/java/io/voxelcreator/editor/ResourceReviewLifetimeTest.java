package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceReviewLifetimeTest {
  @TempDir Path directory;

  private static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(p -> p.noise2D.addAll(List.of(noise("a", .125), noise("b", .75)))));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  private static ProjectData incoming() {
    var p = new ProjectData();
    p.noise2D.addAll(List.of(noise("added", .625), noise("other", .875)));
    return p;
  }

  private static ExportEditSession export(EditorState state) {
    var edit = new ExportEditSession(state, Set.of(state.project().noise2D.getFirst().uuid));
    edit.markShown(edit.preview());
    return edit;
  }

  private static ImportEditSession importing(EditorState state) {
    var edit = new ImportEditSession(state, incoming());
    edit.markShown(edit.preview());
    return edit;
  }

  @Test
  void anIdenticalSameUuidReopenCannotReloadOrApplyAnEarlierImport() throws Exception {
    try (var state = state()) {
      var file = directory.resolve("source.json");
      state.document.save(file);
      var edit = importing(state);
      var preview = edit.preview();
      var token = state.document.sessionToken();
      var draft = state.draft;
      long revision = state.revision;
      assertTrue(state.document.open(file).isEmpty());
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertNotSame(token, state.document.sessionToken());
      assertEquals(revision, state.revision);
      assertFalse(edit.current());
      assertFalse(edit.canReload());
      assertFalse(edit.canApply());
      assertThrows(IllegalStateException.class, edit::reload);
      assertEquals(ImportEditSession.Result.STALE, edit.apply());
      assertSame(preview, edit.preview());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertFalse(state.document.canUndo());
    }
  }

  @Test
  void anIdenticalSameUuidReopenCannotReloadOrPublishAnEarlierExport() throws Exception {
    try (var state = state()) {
      var file = directory.resolve("source.json");
      state.document.save(file);
      var edit = export(state);
      var preview = edit.preview();
      var values = ProjectIO.JSON.valueToTree(preview.resources());
      long revision = state.revision;
      assertTrue(state.document.open(file).isEmpty());
      var target = directory.resolve("stale.pvresources.json");
      assertEquals(revision, state.revision);
      assertFalse(edit.current());
      assertFalse(edit.canReload());
      assertThrows(IllegalStateException.class, edit::reload);
      assertThrows(
          IllegalStateException.class, () -> edit.write(target, new ResourcePackageFiles()));
      assertFalse(Files.exists(target));
      assertSame(preview, edit.preview());
      assertEquals(values, ProjectIO.JSON.valueToTree(preview.resources()));
    }
  }

  @Test
  void saveAndFailedOpenPreserveCurrentReviewedTokensAndDoNotMaterializePreferences()
      throws Exception {
    try (var state = state()) {
      var imports = importing(state);
      var exports = export(state);
      var imported = imports.preview();
      var exported = exports.preview();
      var preferences = ProjectIO.JSON.valueToTree(state.user);
      var token = state.document.sessionToken();
      state.document.save(directory.resolve("saved.json"));
      assertThrows(IOException.class, () -> state.document.open(directory.resolve("missing.json")));
      assertSame(token, state.document.sessionToken());
      assertTrue(imports.current());
      assertTrue(imports.canApply());
      assertTrue(exports.current());
      assertTrue(exports.canExport());
      assertSame(imported, imports.preview());
      assertSame(exported, exports.preview());
      assertEquals(preferences, ProjectIO.JSON.valueToTree(state.user));
    }
  }

  @Test
  void sameDocumentEditsAndHistoryStillPermitExplicitReloadAndFreshReview() {
    try (var state = state()) {
      var imports = importing(state);
      var exports = export(state);
      var skipped =
          imports.preview().rows().stream()
              .filter(row -> row.id().equals("added"))
              .findFirst()
              .orElseThrow()
              .uuid();
      imports.choose(skipped, ResourceImport.Choice.SKIP);
      var token = state.document.sessionToken();
      assertTrue(state.change(p -> p.project.seed++));
      assertFalse(imports.current());
      assertFalse(exports.current());
      assertTrue(imports.canReload());
      assertTrue(exports.canReload());
      imports.reload();
      exports.reload();
      assertEquals(ResourceImport.Choice.SKIP, imports.choice(skipped));
      assertFalse(imports.canApply());
      assertFalse(exports.canExport());
      imports.markShown(imports.preview());
      exports.markShown(exports.preview());
      assertTrue(imports.canApply());
      assertTrue(exports.canExport());
      state.document.undo();
      assertSame(token, state.document.sessionToken());
      assertTrue(imports.canReload());
      assertTrue(exports.canReload());
    }
  }

  @Test
  void logicalProjectIdentityChangesCannotCertifyAnEarlierImportAsCurrent() {
    try (var state = state()) {
      var edit = importing(state);
      state.project().project.uuid = UUID.randomUUID();
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertFalse(edit.current());
      assertFalse(edit.canReload());
      assertEquals(ImportEditSession.Result.STALE, edit.apply());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void importPublicationRejectsNestedApplyChoiceReloadAndCancellation() {
    try (var state = state()) {
      var edit = importing(state);
      var preview = edit.preview();
      var before = ProjectIO.JSON.valueToTree(state.project());
      var draft = state.draft;
      long revision = state.revision;
      var calls = new AtomicInteger();
      state.document.setValidator(
          p -> {
            calls.incrementAndGet();
            assertTrue(edit.busy());
            assertFalse(edit.current());
            assertFalse(edit.canApply());
            assertFalse(edit.canReload());
            assertEquals(ImportEditSession.Result.BUSY, edit.apply());
            edit.cancel();
            assertFalse(edit.closed());
            assertThrows(IllegalStateException.class, edit::reload);
            assertThrows(
                IllegalStateException.class,
                () -> edit.choose(preview.rows().getFirst().uuid(), ResourceImport.Choice.SKIP));
            return NoiseCompiler.validate(p);
          });
      assertEquals(ImportEditSession.Result.APPLIED, edit.apply());
      assertEquals(1, calls.get());
      assertFalse(edit.busy());
      assertTrue(edit.closed());
      assertEquals(revision + 1, state.revision);
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      state.document.setValidator(NoiseCompiler::validate);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void rejectedImportReleasesTheFenceAndRetainsTheExactPlanForExplicitRetry() {
    try (var state = state()) {
      var edit = importing(state);
      var preview = edit.preview();
      state.document.setValidator(p -> List.of(Diagnostic.error("REJECT", "/fixture", "Rejected")));
      assertEquals(ImportEditSession.Result.REJECTED, edit.apply());
      assertFalse(edit.busy());
      assertFalse(edit.closed());
      assertSame(preview, edit.preview());
      assertTrue(edit.canApply());
      state.document.setValidator(NoiseCompiler::validate);
      assertEquals(ImportEditSession.Result.APPLIED, edit.apply());
      assertFalse(edit.busy());
    }
  }

  @Test
  void importPanelCannotDropOrReplaceThePublishingWorkspace() {
    try (var state = state()) {
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      assertTrue(panel.open(incoming()));
      var edit = panel.session();
      edit.markShown(edit.preview());
      state.document.setValidator(
          p -> {
            panel.cancel();
            assertSame(edit, panel.session());
            assertTrue(panel.pending());
            assertFalse(panel.open(new ProjectData()));
            return NoiseCompiler.validate(p);
          });
      assertEquals(ImportEditSession.Result.APPLIED, edit.apply());
      assertFalse(edit.busy());
      panel.cancel();
      assertNull(panel.session());
    }
  }

  @Test
  void exportPublicationRetainsItsWorkspaceAndSelectionAcrossReentrantUiCleanup() throws Exception {
    try (var state = state()) {
      var resources = new ResourcePanel(state, new PropertyPanel(state));
      var imports = new ResourceImportPanel(state, () -> false, () -> {});
      var dialog = new ResourcePackageDialog(state, resources, imports, () -> false);
      assertTrue(dialog.open("Export"));
      var edit = resources.exportView().session();
      edit.markShown(edit.preview());
      var selected = Set.copyOf(resources.exportSelection);
      var writes = new AtomicInteger();
      var nested = directory.resolve("nested.pvresources.json");
      var files =
          new ResourcePackageFiles(
              new ResourcePackageFiles.Storage() {
                public void write(Path temporary, byte[] bytes) throws IOException {
                  writes.incrementAndGet();
                  assertTrue(edit.busy());
                  assertTrue(resources.blocksProjectTransition());
                  assertFalse(edit.canExport());
                  assertFalse(edit.canReload());
                  dialog.cancel();
                  resources.reset();
                  resources.cancelExport();
                  resources.beginExport();
                  resources.exportView().reset();
                  assertSame(edit, resources.exportView().session());
                  assertTrue(dialog.pending());
                  assertEquals(selected, resources.exportSelection);
                  assertThrows(
                      IllegalStateException.class,
                      () -> edit.write(nested, new ResourcePackageFiles()));
                  assertThrows(IllegalStateException.class, edit::reload);
                  edit.cancel();
                  assertFalse(edit.closed());
                  Files.write(temporary, bytes);
                }

                public void replace(Path temporary, Path target) throws IOException {
                  Files.move(
                      temporary,
                      target,
                      StandardCopyOption.ATOMIC_MOVE,
                      StandardCopyOption.REPLACE_EXISTING);
                }
              });
      var output = directory.resolve("selection.pvresources.json");
      resources.writeExport(output, files);
      assertEquals(1, writes.get());
      assertFalse(edit.busy());
      assertTrue(edit.closed());
      assertFalse(Files.exists(nested));
      assertEquals("a", new ResourcePackageFiles().read(output).noise2D.getFirst().id);
      dialog.cancel();
      assertFalse(dialog.pending());
      assertNull(resources.exportView().session());
      assertTrue(resources.exportSelection.isEmpty());
    }
  }

  @Test
  void failedExportReleasesTheFenceAndPreservesItsReviewedPreviewForRetry() throws Exception {
    try (var state = state()) {
      var edit = export(state);
      var preview = edit.preview();
      var target = directory.resolve("retry.pvresources.json");
      Files.writeString(target, "sentinel");
      var files =
          new ResourcePackageFiles(
              new ResourcePackageFiles.Storage() {
                public void write(Path temporary, byte[] bytes) throws IOException {
                  assertTrue(edit.busy());
                  edit.cancel();
                  assertFalse(edit.closed());
                  throw new IOException("stage boundary");
                }

                public void replace(Path temporary, Path output) {
                  fail("Replacement after failed staging");
                }
              });
      assertThrows(IOException.class, () -> edit.write(target, files));
      assertFalse(edit.busy());
      assertTrue(edit.canExport());
      assertSame(preview, edit.preview());
      assertEquals("sentinel", Files.readString(target));
      edit.write(target, new ResourcePackageFiles());
      assertTrue(edit.closed());
      assertFalse(edit.busy());
    }
  }

  @Test
  void aProjectSavedOntoTheExportTargetDuringStagingCannotBeReplacedByThePackage()
      throws Exception {
    try (var state = state()) {
      state.document.save(directory.resolve("source.json"));
      var edit = export(state);
      var target = directory.resolve("became-project.json");
      var replacements = new AtomicInteger();
      var files =
          new ResourcePackageFiles(
              new ResourcePackageFiles.Storage() {
                public void write(Path temporary, byte[] bytes) throws IOException {
                  state.document.save(target);
                  Files.write(temporary, bytes);
                }

                public void replace(Path temporary, Path destination) throws IOException {
                  replacements.incrementAndGet();
                  Files.move(
                      temporary,
                      destination,
                      StandardCopyOption.ATOMIC_MOVE,
                      StandardCopyOption.REPLACE_EXISTING);
                }
              });
      assertThrows(IOException.class, () -> edit.write(target, files));
      assertEquals(0, replacements.get());
      assertEquals(target, state.document.path());
      assertFalse(edit.busy());
      assertFalse(edit.closed());
      assertTrue(edit.canExport());
      var load = new ProjectIO().load(target);
      assertTrue(load.success());
      assertEquals("a", load.project().noise2D.getFirst().id);
      assertEquals("private", ((Noise) state.draft).id);
      edit.write(directory.resolve("retry.pvresources.json"), new ResourcePackageFiles());
      assertTrue(edit.closed());
      assertTrue(new ProjectIO().load(target).success());
    }
  }
}
