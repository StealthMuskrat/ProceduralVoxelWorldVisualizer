package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred background candidate preparation and exact single-transaction publication contracts. */
class PreparedDocumentEditTest {
  @TempDir Path directory;

  private DocumentSession document() {
    var s = new DocumentSession();
    assertTrue(s.edit(p -> p.project.seed = 17).isEmpty());
    return s;
  }

  @Test
  void preparationRunsOnWorkerAndDoesNotPublishUntilExplicitOwnerApply() throws Exception {
    var s = document();
    var owner = Thread.currentThread();
    var called = new AtomicReference<Thread>();
    s.setValidator(
        p -> {
          called.set(Thread.currentThread());
          return Validator.validate(p);
        });
    var request = s.requestEdit(p -> p.project.seed = 18);
    long bytes = s.historyBytes();
    try (var worker = Executors.newSingleThreadExecutor()) {
      var prepared = worker.submit(request::prepare).get(10, TimeUnit.SECONDS);
      assertNotSame(owner, called.get());
      assertTrue(prepared.success());
      assertTrue(s.currentEdit(prepared));
      assertEquals(17, s.data().project.seed);
      assertEquals(bytes, s.historyBytes());
      s.applyEdit(prepared);
      assertFalse(s.currentEdit(prepared));
      assertEquals(18, s.data().project.seed);
      s.undo();
      assertEquals(17, s.data().project.seed);
      s.redo();
      assertEquals(18, s.data().project.seed);
    }
  }

  @Test
  void retainedChangeAndValidatorCandidatesCannotMutatePreparedPublicationOrHistory() {
    var s = document();
    var retained = new AtomicReference<ProjectData>();
    s.setValidator(
        p -> {
          retained.set(p);
          p.project.seed += 1;
          return List.of();
        });
    var prepared = s.requestEdit(p -> p.project.seed = 18).prepare();
    retained.get().project.seed = 999;
    s.applyEdit(prepared);
    assertEquals(19, s.data().project.seed);
    s.setValidator(Validator::validate);
    s.undo();
    assertEquals(17, s.data().project.seed);
    s.redo();
    assertEquals(19, s.data().project.seed);
  }

  @Test
  void rejectionOwnsDiagnosticsAndCannotChangeDocumentOrItsHistory() {
    var s = document();
    var diagnostics =
        new ArrayList<>(List.of(Diagnostic.error("FIXTURE", "/project/seed", "Rejected")));
    s.setValidator(p -> diagnostics);
    long bytes = s.historyBytes();
    var source = s.data();
    var prepared = s.requestEdit(p -> p.project.seed = 18).prepare();
    diagnostics.clear();
    assertFalse(prepared.success());
    assertTrue(s.currentEdit(prepared));
    assertEquals(1, prepared.diagnostics().size());
    assertThrows(UnsupportedOperationException.class, () -> prepared.diagnostics().clear());
    assertThrows(IllegalStateException.class, () -> s.applyEdit(prepared));
    assertSame(source, s.data());
    assertEquals(bytes, s.historyBytes());
    assertEquals(17, s.data().project.seed);
    s.setValidator(Validator::validate);
    assertFalse(s.currentEdit(prepared));
  }

  @Test
  void noOpIsConsumedWithoutClearingRedoOrReplacingTheActiveObject() {
    var s = document();
    s.edit(p -> p.project.seed = 18);
    s.undo();
    var source = s.data();
    long bytes = s.historyBytes();
    var prepared = s.requestEdit(p -> {}).prepare();
    s.applyEdit(prepared);
    assertSame(source, s.data());
    assertTrue(s.canRedo());
    assertEquals(bytes, s.historyBytes());
    assertThrows(IllegalStateException.class, () -> s.applyEdit(prepared));
    s.redo();
    assertEquals(18, s.data().project.seed);
  }

  @Test
  void trackedAndRawSourceEditsRejectAnEarlierPreparedCandidate() {
    for (boolean raw : List.of(false, true)) {
      var s = document();
      var prepared = s.requestEdit(p -> p.project.seed = 18).prepare();
      if (raw) s.data().project.seed = 19;
      else s.edit(p -> p.project.seed = 19);
      var values = ProjectIO.JSON.valueToTree(s.data());
      long bytes = s.historyBytes();
      assertThrows(IllegalStateException.class, () -> s.applyEdit(prepared));
      assertEquals(values, ProjectIO.JSON.valueToTree(s.data()));
      assertEquals(bytes, s.historyBytes());
    }
  }

  @Test
  void historyMovementAndIdenticalSaveCheckpointSupersedeAnEarlierPreparedEdit() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var s = document();
      var file = directory.resolve("checkpoint-" + mode + ".json");
      s.save(file);
      var prepared = s.requestEdit(p -> p.project.seed = 18).prepare();
      if (mode == 0) s.undo();
      else if (mode == 1) {
        s.undo();
        s.redo();
      } else s.save(file);
      var values = ProjectIO.JSON.valueToTree(s.data());
      long bytes = s.historyBytes();
      assertThrows(IllegalStateException.class, () -> s.applyEdit(prepared));
      assertEquals(values, ProjectIO.JSON.valueToTree(s.data()));
      assertEquals(bytes, s.historyBytes());
    }
  }

  @Test
  void validationPolicyNewAndSameUuidOpenInvalidatePreparation() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var s = document();
      var file = directory.resolve("lifetime-" + mode + ".json");
      s.save(file);
      var prepared = s.requestEdit(p -> p.project.seed = 18).prepare();
      if (mode == 0) s.setValidator(p -> List.of());
      else if (mode == 1) s.newProject();
      else assertTrue(s.open(file).isEmpty());
      var values = ProjectIO.JSON.valueToTree(s.data());
      assertThrows(IllegalStateException.class, () -> s.applyEdit(prepared));
      assertEquals(values, ProjectIO.JSON.valueToTree(s.data()));
    }
  }

  @Test
  void foreignDocumentsAndRepeatedPublicationsCannotTakeAValidatedCandidate() {
    var s = document();
    var other = document();
    var prepared = s.requestEdit(p -> p.project.seed = 18).prepare();
    assertThrows(IllegalStateException.class, () -> other.applyEdit(prepared));
    assertEquals(17, other.data().project.seed);
    s.applyEdit(prepared);
    assertEquals(18, s.data().project.seed);
    assertThrows(IllegalStateException.class, () -> s.applyEdit(prepared));
  }

  @Test
  void preparationFaultsAndStaleRequestsCannotPublishPartialWork() {
    var s = document();
    var before = ProjectIO.JSON.valueToTree(s.data());
    long bytes = s.historyBytes();
    var request =
        s.requestEdit(
            p -> {
              p.project.seed = 999;
              throw new AssertionError("change failure");
            });
    assertThrows(AssertionError.class, request::prepare);
    assertEquals(before, ProjectIO.JSON.valueToTree(s.data()));
    s.setValidator(
        p -> {
          throw new IllegalStateException("validator failure");
        });
    assertThrows(
        IllegalStateException.class, () -> s.requestEdit(p -> p.project.seed = 18).prepare());
    assertEquals(bytes, s.historyBytes());
    assertEquals(before, ProjectIO.JSON.valueToTree(s.data()));
    s.setValidator(Validator::validate);
    var stale = s.requestEdit(p -> p.project.seed = 18);
    s.edit(p -> p.project.seed = 19);
    var prepared = stale.prepare();
    assertTrue(prepared.success());
    assertThrows(IllegalStateException.class, () -> s.applyEdit(prepared));
    assertEquals(19, s.data().project.seed);
  }

  @Test
  void preparedEditsRetainTheExistingHistoryBoundAndDiscardRedoOnlyForARealChange() {
    var s = document();
    for (int i = 0; i < 120; i++) {
      final int value = 100 + i;
      s.applyEdit(s.requestEdit(p -> p.project.seed = value).prepare());
    }
    int count = 0;
    while (s.canUndo()) {
      s.undo();
      count++;
    }
    assertEquals(100, count);
    assertTrue(s.canRedo());
    s.applyEdit(s.requestEdit(p -> p.project.seed = 700).prepare());
    assertFalse(s.canRedo());
    assertEquals(700, s.data().project.seed);
  }
}
