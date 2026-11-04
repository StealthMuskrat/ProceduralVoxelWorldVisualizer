package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred candidate-only ordered validation, immutable staged results and document authority. */
class PreparedStagedEditTest {
  @TempDir Path directory;

  static DocumentSession document() {
    var s = new DocumentSession();
    assertTrue(s.edit(p -> p.project.seed = 5).isEmpty());
    return s;
  }

  @Test
  void orderedStagesAndBothValidationBoundariesRunOnWorkerBeforeOneOwnedPublication()
      throws Exception {
    var s = document();
    var order = new ArrayList<String>();
    var retained = new AtomicReference<ProjectData>();
    var caller = Thread.currentThread();
    var seen = new AtomicReference<Thread>();
    s.setValidator(
        p -> {
          order.add("final");
          assertEquals(17, p.project.seed);
          return List.of();
        });
    var request =
        s.requestEdit(
            p -> {
              seen.set(Thread.currentThread());
              p.project.seed = 11;
              order.add("initial");
            },
            p -> {
              assertEquals(11, p.project.seed);
              order.add("intermediate");
              return List.of();
            },
            p -> {
              p.project.seed = 17;
              retained.set(p);
              order.add("action");
            });
    try (var worker = Executors.newSingleThreadExecutor()) {
      var result = worker.submit(request::prepare).get(10, TimeUnit.SECONDS);
      assertNotSame(caller, seen.get());
      assertEquals(List.of("initial", "intermediate", "action", "final"), order);
      assertEquals(5, s.data().project.seed);
      retained.get().project.seed = 999;
      s.applyEdit(result);
      assertEquals(17, s.data().project.seed);
      s.setValidator(Validator::validate);
      s.undo();
      assertEquals(5, s.data().project.seed);
      s.redo();
      assertEquals(17, s.data().project.seed);
      assertFalse(s.currentEdit(result));
    }
  }

  @Test
  void immutableIntermediateRejectionNeverRunsTheActionOrFinalValidation() {
    var s = document();
    var issues = new ArrayList<Diagnostic>();
    issues.add(Diagnostic.error("INITIAL", "/fixture", "Rejected"));
    var calls = new AtomicInteger();
    s.setValidator(
        p -> {
          calls.incrementAndGet();
          return List.of();
        });
    long bytes = s.historyBytes();
    var result =
        s.requestEdit(
                p -> p.project.seed = 11,
                p -> issues,
                p -> {
                  calls.incrementAndGet();
                  p.project.seed = 17;
                })
            .prepare();
    issues.clear();
    assertFalse(result.success());
    assertTrue(s.currentEdit(result));
    assertEquals(1, result.diagnostics().size());
    assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
    assertEquals(0, calls.get());
    assertThrows(IllegalStateException.class, () -> s.applyEdit(result));
    assertEquals(5, s.data().project.seed);
    assertEquals(bytes, s.historyBytes());
  }

  @Test
  void errorsAtEveryStageLeaveTheActiveProjectAndHistoryUnchanged() {
    for (int stage = 0; stage < 4; stage++) {
      var s = document();
      var before = ProjectIO.JSON.valueToTree(s.data());
      long bytes = s.historyBytes();
      int failure = stage;
      s.setValidator(
          p -> {
            if (failure == 3) throw new AssertionError("final");
            return List.of();
          });
      var request =
          s.requestEdit(
              p -> {
                p.project.seed = 11;
                if (failure == 0) throw new AssertionError("initial");
              },
              p -> {
                if (failure == 1) throw new AssertionError("intermediate");
                return List.of();
              },
              p -> {
                p.project.seed = 17;
                if (failure == 2) throw new AssertionError("action");
              });
      assertThrows(AssertionError.class, request::prepare);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.data()));
      assertEquals(bytes, s.historyBytes());
    }
  }

  @Test
  void intermediateRejectionsOwnCheckpointPolicyHistoryAndLifetimeAsWellAsAcceptedResults()
      throws Exception {
    for (int mode = 0; mode < 5; mode++) {
      var s = document();
      var path = directory.resolve("source-" + mode + ".json");
      s.save(path);
      var result =
          s.requestEdit(
                  p -> p.project.seed = 11,
                  p -> List.of(Diagnostic.error("INITIAL", "/fixture", "Rejected")),
                  p -> p.project.seed = 17)
              .prepare();
      if (mode == 0) s.save(path);
      else if (mode == 1) s.setValidator(p -> List.of());
      else if (mode == 2) {
        s.undo();
        s.redo();
      } else if (mode == 3) s.data().project.seed = 6;
      else assertTrue(s.open(path).isEmpty());
      assertFalse(s.currentEdit(result));
    }
  }

  @Test
  void acceptedStagedNoOpKeepsRedoAndNullCallbacksCannotCreatePartialRequests() {
    var s = document();
    s.edit(p -> p.project.seed = 6);
    s.undo();
    long bytes = s.historyBytes();
    var result =
        s.requestEdit(p -> p.project.seed = 11, p -> List.of(), p -> p.project.seed = 5).prepare();
    s.applyEdit(result);
    assertEquals(5, s.data().project.seed);
    assertTrue(s.canRedo());
    assertEquals(bytes, s.historyBytes());
    assertThrows(NullPointerException.class, () -> s.requestEdit(null, p -> List.of(), p -> {}));
    assertThrows(NullPointerException.class, () -> s.requestEdit(p -> {}, null, p -> {}));
    assertThrows(NullPointerException.class, () -> s.requestEdit(p -> {}, p -> List.of(), null));
  }
}
