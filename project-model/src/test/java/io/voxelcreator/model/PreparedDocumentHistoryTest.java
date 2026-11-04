package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred owned history reconstruction/validation and exact single-step stack publication. */
class PreparedDocumentHistoryTest {
  @TempDir Path directory;

  private DocumentSession document() {
    var s = new DocumentSession();
    s.edit(p -> p.project.seed = 17);
    s.edit(p -> p.project.seed = 18);
    return s;
  }

  @Test
  void undoAndRedoPrepareOnWorkerWithoutChangingStacksUntilOwnerPublication() throws Exception {
    var s = document();
    var owner = Thread.currentThread();
    var called = new AtomicReference<Thread>();
    s.setValidator(
        p -> {
          called.set(Thread.currentThread());
          return Validator.validate(p);
        });
    long bytes = s.historyBytes();
    try (var worker = Executors.newSingleThreadExecutor()) {
      var undo = worker.submit(s.requestUndo()::prepare).get(10, TimeUnit.SECONDS);
      assertNotSame(owner, called.get());
      assertEquals(18, s.data().project.seed);
      assertFalse(s.canRedo());
      assertTrue(s.currentHistory(undo));
      s.applyHistory(undo);
      assertEquals(17, s.data().project.seed);
      assertTrue(s.canRedo());
      assertEquals(bytes, s.historyBytes());
      var redo = worker.submit(s.requestRedo()::prepare).get(10, TimeUnit.SECONDS);
      assertEquals(17, s.data().project.seed);
      s.applyHistory(redo);
      assertEquals(18, s.data().project.seed);
      assertEquals(bytes, s.historyBytes());
    }
  }

  @Test
  void aRetainedValidationInputCannotChangePreparedValuesOrItsRedoEntry() {
    var s = document();
    var retained = new AtomicReference<ProjectData>();
    s.setValidator(
        p -> {
          retained.set(p);
          return List.of();
        });
    var undo = s.requestUndo().prepare();
    retained.get().project.seed = 999;
    s.applyHistory(undo);
    assertEquals(17, s.data().project.seed);
    s.setValidator(Validator::validate);
    s.applyHistory(s.requestRedo().prepare());
    assertEquals(18, s.data().project.seed);
  }

  @Test
  void rejectedDiagnosticsAreOwnedAndTheHistoryHeadRemainsAvailableForFreshRetry() {
    var s = document();
    var diagnostics =
        new ArrayList<>(List.of(Diagnostic.error("FIXTURE", "/project/seed", "Rejected")));
    s.setValidator(p -> diagnostics);
    var active = s.data();
    long bytes = s.historyBytes();
    var rejected = s.requestUndo().prepare();
    diagnostics.clear();
    assertFalse(rejected.success());
    assertTrue(s.currentHistory(rejected));
    assertEquals(1, rejected.diagnostics().size());
    assertThrows(UnsupportedOperationException.class, () -> rejected.diagnostics().clear());
    assertThrows(IllegalStateException.class, () -> s.applyHistory(rejected));
    assertSame(active, s.data());
    assertFalse(s.canRedo());
    assertEquals(bytes, s.historyBytes());
    s.setValidator(Validator::validate);
    assertFalse(s.currentHistory(rejected));
    s.applyHistory(s.requestUndo().prepare());
    assertEquals(17, s.data().project.seed);
  }

  @Test
  void trackedAndRawChangesRejectAnEarlierHistoryCandidateWithoutMovingAnotherEntry() {
    for (boolean raw : List.of(false, true)) {
      var s = document();
      var undo = s.requestUndo().prepare();
      if (raw) s.data().project.seed = 19;
      else s.edit(p -> p.project.seed = 19);
      var values = ProjectIO.JSON.valueToTree(s.data());
      long bytes = s.historyBytes();
      assertFalse(s.currentHistory(undo));
      assertThrows(IllegalStateException.class, () -> s.applyHistory(undo));
      assertEquals(values, ProjectIO.JSON.valueToTree(s.data()));
      assertEquals(bytes, s.historyBytes());
    }
  }

  @Test
  void historyRoundTripsAndIdenticalSaveReceiptsInvalidateAnEarlierRequest() throws Exception {
    for (boolean save : List.of(false, true)) {
      var s = document();
      var file = directory.resolve("checkpoint-" + save + ".json");
      s.save(file);
      var undo = s.requestUndo().prepare();
      if (save) s.save(file);
      else {
        s.undo();
        s.redo();
      }
      assertFalse(s.currentHistory(undo));
      assertThrows(IllegalStateException.class, () -> s.applyHistory(undo));
      assertEquals(18, s.data().project.seed);
    }
  }

  @Test
  void policyNewAndIdenticalSameUuidOpenCannotAdoptAnOlderPreparedStep() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var s = document();
      var file = directory.resolve("lifetime-" + mode + ".json");
      s.save(file);
      var undo = s.requestUndo().prepare();
      if (mode == 0) s.setValidator(p -> List.of());
      else if (mode == 1) s.newProject();
      else assertTrue(s.open(file).isEmpty());
      var values = ProjectIO.JSON.valueToTree(s.data());
      assertFalse(s.currentHistory(undo));
      assertThrows(IllegalStateException.class, () -> s.applyHistory(undo));
      assertEquals(values, ProjectIO.JSON.valueToTree(s.data()));
    }
  }

  @Test
  void foreignAndRepeatedApplicationCannotConsumeOrMoveSomeoneElsesHistory() {
    var s = document();
    var other = document();
    var undo = s.requestUndo().prepare();
    assertFalse(other.currentHistory(undo));
    assertThrows(IllegalStateException.class, () -> other.applyHistory(undo));
    assertEquals(18, other.data().project.seed);
    s.applyHistory(undo);
    assertFalse(s.currentHistory(undo));
    assertThrows(IllegalStateException.class, () -> s.applyHistory(undo));
    assertEquals(17, s.data().project.seed);
  }

  @Test
  void emptyDirectionsRejectCaptureAndLegacyEmptyHistoryStillDoesNothing() {
    var s = new DocumentSession();
    var active = s.data();
    assertThrows(IllegalStateException.class, s::requestUndo);
    assertThrows(IllegalStateException.class, s::requestRedo);
    s.undo();
    s.redo();
    assertSame(active, s.data());
    assertEquals(0, s.historyBytes());
  }

  @Test
  void faultsAndNoOpEditsPreserveHistoryWithoutPartialCandidatePublication() {
    var s = document();
    var active = s.data();
    long bytes = s.historyBytes();
    s.setValidator(
        p -> {
          p.project.seed = 999;
          throw new AssertionError("validator fixture");
        });
    assertThrows(AssertionError.class, () -> s.requestUndo().prepare());
    assertSame(active, s.data());
    assertEquals(bytes, s.historyBytes());
    assertFalse(s.canRedo());
    s.setValidator(Validator::validate);
    var undo = s.requestUndo().prepare();
    s.edit(p -> {});
    assertTrue(s.currentHistory(undo));
    s.applyHistory(undo);
    assertEquals(17, s.data().project.seed);
  }

  @Test
  void preparedAndLegacyHistoryProduceIdenticalProjectsAndConstantByteAccounting()
      throws Exception {
    var a = document();
    var file = directory.resolve("source.json");
    a.save(file);
    var b = new DocumentSession();
    assertTrue(b.open(file).isEmpty());
    for (int i = 0; i < 32; i++) {
      final int value = 100 + i;
      a.edit(p -> p.project.seed = value);
      b.edit(p -> p.project.seed = value);
    }
    long aBytes = a.historyBytes(), bBytes = b.historyBytes();
    for (int i = 0; i < 32; i++) {
      a.applyHistory(a.requestUndo().prepare());
      b.undo();
      assertEquals(ProjectIO.JSON.valueToTree(b.data()), ProjectIO.JSON.valueToTree(a.data()));
    }
    for (int i = 0; i < 32; i++) {
      a.applyHistory(a.requestRedo().prepare());
      b.redo();
      assertEquals(ProjectIO.JSON.valueToTree(b.data()), ProjectIO.JSON.valueToTree(a.data()));
    }
    assertEquals(aBytes, a.historyBytes());
    assertEquals(bBytes, b.historyBytes());
  }
}
