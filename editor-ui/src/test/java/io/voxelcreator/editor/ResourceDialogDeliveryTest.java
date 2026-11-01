package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceDialogDeliveryTest {
  @TempDir Path directory;

  private static ProjectData incoming(String id) {
    var p = new ProjectData();
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", .75);
    p.noise2D.add(n);
    return p;
  }

  private EditorState state() throws Exception {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(s.change(p -> p.noise2D.addAll(incoming("source").noise2D)));
    s.document.save(directory.resolve("project.json"));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    ((Noise) s.draft).id = "private";
    s.seedInputs.edit(s, "seedOffset", "Seed", "-");
    return s;
  }

  private static BooleanSupplier gate(AtomicReference<Runnable> hook) {
    return () -> {
      var action = hook.getAndSet(null);
      if (action != null) action.run();
      return false;
    };
  }

  private static void review(ResourceImportPanel panel) {
    panel.session().markShown(panel.session().preview());
  }

  private static void review(ResourcePanel panel) {
    var edit = panel.exportView().session();
    edit.markShown(edit.preview());
  }

  private Path output() {
    return directory.resolve("project.pvresources.json");
  }

  @Test
  void importAdmissionCannotOverwriteAGateOpenedSuccessorEvenWhenTheGateThrows() throws Exception {
    for (boolean throwing : new boolean[] {false, true})
      try (var s = state()) {
        var hook = new AtomicReference<Runnable>();
        var panel = new ResourceImportPanel(s, gate(hook), () -> {});
        hook.set(
            () -> {
              panel.cancel();
              assertTrue(panel.open(incoming("successor")));
              if (throwing) throw new IllegalStateException("admission");
            });
        assertFalse(panel.open(incoming("old")));
        assertTrue(panel.pending());
        assertEquals("successor", panel.session().preview().rows().getFirst().id());
        assertEquals("", panel.message());
      }
  }

  @Test
  void packageAdmissionCannotOverwriteAGateOpenedSuccessorEvenWhenTheGateThrows() throws Exception {
    for (boolean throwing : new boolean[] {false, true})
      try (var s = state()) {
        var hook = new AtomicReference<Runnable>();
        var resources = new ResourcePanel(s, new PropertyPanel(s));
        var imports = new ResourceImportPanel(s, () -> false, () -> {});
        var dialog = new ResourcePackageDialog(s, resources, imports, gate(hook));
        hook.set(
            () -> {
              dialog.cancel();
              assertTrue(dialog.open("Export"));
              if (throwing) throw new IllegalStateException("admission");
            });
        assertFalse(dialog.open("Import"));
        assertTrue(dialog.pending());
        assertEquals("Export", dialog.action());
        assertEquals("Choices", dialog.page());
        assertEquals("", dialog.error());
        assertFalse(Files.exists(output()));
      }
  }

  @Test
  void anOldImportDecisionCannotPublishOrCancelItsGateOpenedSuccessor() throws Exception {
    for (boolean throwing : new boolean[] {false, true})
      try (var s = state()) {
        var hook = new AtomicReference<Runnable>();
        var panel = new ResourceImportPanel(s, gate(hook), () -> {});
        assertTrue(panel.open(incoming("old")));
        review(panel);
        var old = panel.capture(10);
        var before = ProjectIO.JSON.valueToTree(s.project());
        long revision = s.revision;
        hook.set(
            () -> {
              panel.cancel();
              assertTrue(panel.open(incoming("successor")));
              if (throwing) throw new IllegalStateException("decision");
            });
        panel.dispatch(old, ResourceImportPanel.Command.APPLY, 10);
        var successor = panel.session();
        assertEquals("successor", successor.preview().rows().getFirst().id());
        assertEquals("", panel.message());
        panel.dispatch(old, ResourceImportPanel.Command.CANCEL, 10);
        assertSame(successor, panel.session());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(revision, s.revision);
      }
  }

  @Test
  void committedOrPreviewChangesDuringAnImportGateRequireAnotherExplicitDecision()
      throws Exception {
    for (boolean previewChange : new boolean[] {false, true})
      try (var s = state()) {
        var hook = new AtomicReference<Runnable>();
        var panel = new ResourceImportPanel(s, gate(hook), () -> {});
        assertTrue(panel.open(incoming("added")));
        review(panel);
        var edit = panel.session();
        var old = panel.capture(20);
        hook.set(
            () -> {
              if (previewChange)
                edit.choose(edit.preview().rows().getFirst().uuid(), ResourceImport.Choice.SKIP);
              else assertTrue(s.change(p -> p.project.seed++));
            });
        panel.dispatch(old, ResourceImportPanel.Command.APPLY, 20);
        assertTrue(panel.pending());
        assertEquals(1, s.project().noise2D.size());
        assertFalse(edit.closed());
      }
  }

  @Test
  void validationFailureCannotAnnotateOrCloseTheCallbackOpenedImport() throws Exception {
    try (var s = state()) {
      var holder = new ResourceImportPanel[1];
      var calls = new AtomicInteger();
      var panel =
          new ResourceImportPanel(
              s,
              () -> false,
              () -> {
                calls.incrementAndGet();
                holder[0].cancel();
                assertTrue(holder[0].open(incoming("successor")));
                throw new IllegalStateException("x".repeat(5000));
              });
      holder[0] = panel;
      assertTrue(panel.open(incoming("old")));
      review(panel);
      s.document.setValidator(p -> List.of(Diagnostic.error("FIXTURE", "/fixture", "Rejected")));
      assertEquals(ImportEditSession.Result.REJECTED, panel.session().apply());
      s.document.setValidator(NoiseCompiler::validate);
      var old = panel.capture(30);
      panel.dispatch(old, ResourceImportPanel.Command.VALIDATION, 30);
      assertEquals(1, calls.get());
      assertEquals("", panel.message());
      assertEquals("Resources", panel.section());
      assertEquals("successor", panel.session().preview().rows().getFirst().id());
      panel.dispatch(old, ResourceImportPanel.Command.CANCEL, 30);
      assertTrue(panel.pending());
      assertTrue(
          s.events.view().entries().stream()
              .anyMatch(
                  e ->
                      e.code().equals("IMPORT_REVIEW_CALLBACK_FAILED")
                          && e.message().length() <= 1024));
    }
  }

  @Test
  void anOldPackageDecisionCannotWriteOrAnnotateAGateOpenedSuccessor() throws Exception {
    for (boolean throwing : new boolean[] {false, true})
      try (var s = state()) {
        var hook = new AtomicReference<Runnable>();
        var resources = new ResourcePanel(s, new PropertyPanel(s));
        var imports = new ResourceImportPanel(s, () -> false, () -> {});
        var dialog = new ResourcePackageDialog(s, resources, imports, gate(hook));
        assertTrue(dialog.open("Export"));
        review(resources);
        var old = dialog.capture(40);
        hook.set(
            () -> {
              dialog.cancel();
              assertTrue(dialog.open("Import"));
              if (throwing) throw new IllegalStateException("old export");
            });
        dialog.dispatch(old, ResourcePackageDialog.Command.SUBMIT, 40);
        assertTrue(dialog.pending());
        assertEquals("Import", dialog.action());
        assertEquals("", dialog.error());
        dialog.dispatch(old, ResourcePackageDialog.Command.CANCEL, 40);
        assertTrue(dialog.pending());
        assertFalse(Files.exists(output()));
      }
  }

  @Test
  void aChangedSelectionDuringTheExportGateCannotBorrowTheEarlierReview() throws Exception {
    try (var s = state()) {
      assertTrue(s.change(p -> p.noise2D.addAll(incoming("other").noise2D)));
      var hook = new AtomicReference<Runnable>();
      var resources = new ResourcePanel(s, new PropertyPanel(s));
      var imports = new ResourceImportPanel(s, () -> false, () -> {});
      var dialog = new ResourcePackageDialog(s, resources, imports, gate(hook));
      assertTrue(dialog.open("Export"));
      review(resources);
      var old = dialog.capture(50);
      hook.set(() -> resources.exportSelection.add(s.project().noise2D.getLast().uuid));
      dialog.dispatch(old, ResourcePackageDialog.Command.SUBMIT, 50);
      assertTrue(dialog.pending());
      assertFalse(Files.exists(output()));
      assertEquals(2, resources.exportSelection.size());
    }
  }

  @Test
  void aFrameCanDeliverOnlyOneCommandEvenAcrossAnExplicitSuccessorOpening() throws Exception {
    try (var s = state()) {
      var panel = new ResourceImportPanel(s, () -> false, () -> {});
      assertTrue(panel.open(incoming("one")));
      var old = panel.capture(60);
      panel.dispatch(old, ResourceImportPanel.Command.CANCEL, 60);
      assertFalse(panel.pending());
      assertTrue(panel.open(incoming("two")));
      var next = panel.capture(60);
      panel.dispatch(next, ResourceImportPanel.Command.CANCEL, 60);
      assertTrue(panel.pending());
      panel.dispatch(panel.capture(61), ResourceImportPanel.Command.CANCEL, 61);
      assertFalse(panel.pending());
      var resources = new ResourcePanel(s, new PropertyPanel(s));
      var dialog = new ResourcePackageDialog(s, resources, panel, () -> false);
      assertTrue(dialog.open("Export"));
      dialog.dispatch(dialog.capture(70), ResourcePackageDialog.Command.CANCEL, 70);
      assertTrue(dialog.open("Import"));
      dialog.dispatch(dialog.capture(70), ResourcePackageDialog.Command.CANCEL, 70);
      assertTrue(dialog.pending());
      dialog.dispatch(dialog.capture(71), ResourcePackageDialog.Command.CANCEL, 71);
      assertFalse(dialog.pending());
    }
  }

  @Test
  void cancellationBypassesThrowingEligibilityCallbacksAndPreservesPrivateInput() throws Exception {
    try (var s = state()) {
      var hook = new AtomicReference<Runnable>();
      var imports = new ResourceImportPanel(s, gate(hook), () -> {});
      assertTrue(imports.open(incoming("added")));
      hook.set(() -> fail("Cancel called the import gate"));
      imports.dispatch(imports.capture(80), ResourceImportPanel.Command.CANCEL, 80);
      hook.set(null);
      var resources = new ResourcePanel(s, new PropertyPanel(s));
      var dialog = new ResourcePackageDialog(s, resources, imports, gate(hook));
      assertTrue(dialog.open("Export"));
      hook.set(() -> fail("Cancel called the package gate"));
      dialog.dispatch(dialog.capture(90), ResourcePackageDialog.Command.CANCEL, 90);
      assertFalse(dialog.pending());
      assertEquals("private", ((Noise) s.draft).id);
      assertEquals("-", s.seedInputs.text("seedOffset"));
      assertFalse(Files.exists(output()));
    }
  }

  @Test
  void publicationFailureRetainsReviewAndExplicitRetryWritesExactlyOnce() throws Exception {
    try (var s = state()) {
      var resources = new ResourcePanel(s, new PropertyPanel(s));
      var imports = new ResourceImportPanel(s, () -> false, () -> {});
      var holder = new ResourcePackageDialog[1];
      var writes = new AtomicInteger();
      var replacements = new AtomicInteger();
      Files.writeString(output(), "sentinel");
      var files =
          new ResourcePackageFiles(
              new ResourcePackageFiles.Storage() {
                public void write(Path temporary, byte[] bytes) throws IOException {
                  var dialog = holder[0];
                  dialog.cancel();
                  assertTrue(dialog.pending());
                  assertFalse(dialog.open("Import"));
                  dialog.dispatch(dialog.capture(101), ResourcePackageDialog.Command.CANCEL, 101);
                  assertTrue(dialog.pending());
                  if (writes.incrementAndGet() == 1) throw new IOException("stage");
                  Files.write(temporary, bytes);
                }

                public void replace(Path temporary, Path target) throws IOException {
                  replacements.incrementAndGet();
                  Files.move(
                      temporary,
                      target,
                      StandardCopyOption.ATOMIC_MOVE,
                      StandardCopyOption.REPLACE_EXISTING);
                }
              });
      var dialog = new ResourcePackageDialog(s, resources, imports, () -> false, files);
      holder[0] = dialog;
      assertTrue(dialog.open("Export"));
      review(resources);
      var preview = resources.exportView().session().preview();
      dialog.dispatch(dialog.capture(100), ResourcePackageDialog.Command.SUBMIT, 100);
      assertTrue(dialog.pending());
      assertSame(preview, resources.exportView().session().preview());
      assertEquals("sentinel", Files.readString(output()));
      assertEquals(0, replacements.get());
      dialog.dispatch(dialog.capture(102), ResourcePackageDialog.Command.SUBMIT, 102);
      assertFalse(dialog.pending());
      assertEquals(2, writes.get());
      assertEquals(1, replacements.get());
      assertTrue(resources.exportSelection.isEmpty());
      assertEquals("source", new ResourcePackageFiles().read(output()).noise2D.getFirst().id);
    }
  }
}
