package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Deferred request/cancel ownership only; native draw and actual I/O are separate scenarios. */
class ResourcePackageDialogTest {
  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var n = new Noise();
              n.id = "field";
              p.noise2D.add(n);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  @Test
  void aPendingExportCannotBeRetargetedAndCancelConsumesOnlyItsPrivateWorkspace() {
    try (var state = state()) {
      var resources = new ResourcePanel(state, new PropertyPanel(state));
      var imports = new ResourceImportPanel(state, () -> false, () -> {});
      var dialog = new ResourcePackageDialog(state, resources, imports, () -> false);
      var before = ProjectIO.JSON.valueToTree(state.project());
      var draft = state.draft;
      long revision = state.revision, history = state.document.historyBytes();
      assertTrue(dialog.open("Export"));
      var session = resources.exportView().session();
      var path = dialog.pathText();
      assertFalse(dialog.open("Import"));
      assertEquals("Export", dialog.action());
      assertEquals(path, dialog.pathText());
      assertSame(session, resources.exportView().session());
      dialog.cancel();
      dialog.cancel();
      assertFalse(dialog.pending());
      assertTrue(session.closed());
      assertTrue(resources.exportSelection.isEmpty());
      assertNull(resources.exportView().session());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertTrue(dialog.open("Import"));
      assertEquals("Path", dialog.page());
      assertFalse(imports.pending());
      dialog.cancel();
    }
  }

  @Test
  void blockersAndAnExistingImportReviewCannotReplaceTheCurrentSourceOrPreferences() {
    try (var state = state()) {
      var blocked = new AtomicBoolean(true);
      var resources = new ResourcePanel(state, new PropertyPanel(state));
      var imports = new ResourceImportPanel(state, blocked::get, () -> {});
      var dialog = new ResourcePackageDialog(state, resources, imports, blocked::get);
      var user = ProjectIO.JSON.valueToTree(state.user);
      assertFalse(dialog.open("Import"));
      assertFalse(dialog.pending());
      assertNull(resources.exportView().session());
      blocked.set(false);
      var incoming = new ProjectData();
      assertTrue(NoiseCompiler.validate(incoming).isEmpty());
      assertTrue(imports.open(incoming));
      var importSession = imports.session();
      assertFalse(dialog.open("Export"));
      assertSame(importSession, imports.session());
      assertTrue(imports.pending());
      imports.cancel();
      assertTrue(dialog.open("Export"));
      dialog.cancel();
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
    }
  }

  @Test
  void unsupportedRequestsRejectBeforeChangingAnAlreadyOwnedPackageAction() {
    try (var state = state()) {
      var resources = new ResourcePanel(state, new PropertyPanel(state));
      var imports = new ResourceImportPanel(state, () -> false, () -> {});
      var dialog = new ResourcePackageDialog(state, resources, imports, () -> false);
      assertTrue(dialog.open("Import"));
      var path = dialog.pathText();
      assertThrows(IllegalArgumentException.class, () -> dialog.open("Save"));
      assertTrue(dialog.pending());
      assertEquals("Import", dialog.action());
      assertEquals(path, dialog.pathText());
      assertFalse(imports.pending());
      dialog.cancel();
    }
  }

  @Test
  void cancellationCannotConsumeAReplacementExportSessionAfterAnExternalReset() {
    try (var state = state()) {
      var resources = new ResourcePanel(state, new PropertyPanel(state));
      var imports = new ResourceImportPanel(state, () -> false, () -> {});
      var dialog = new ResourcePackageDialog(state, resources, imports, () -> false);
      assertTrue(dialog.open("Export"));
      var original = resources.exportView().session();
      resources.exportView().reset();
      resources.exportView().open();
      var replacement = resources.exportView().session();
      assertNotSame(original, replacement);
      dialog.cancel();
      assertTrue(original.closed());
      assertFalse(replacement.closed());
      assertSame(replacement, resources.exportView().session());
      resources.cancelExport();
    }
  }
}
