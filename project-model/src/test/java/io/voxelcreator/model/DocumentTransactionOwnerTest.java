package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred reentrant-source ownership across edit callbacks and history validation. */
class DocumentTransactionOwnerTest {
  @TempDir Path directory;

  private enum Action {
    EDIT,
    UNDO,
    REDO
  }

  private record State(
      ProjectData active,
      Object lifetime,
      Path path,
      long bytes,
      boolean undo,
      boolean redo,
      boolean dirty,
      JsonNode values) {}

  private State snapshot(DocumentSession d) {
    return new State(
        d.data(),
        d.sessionToken(),
        d.path(),
        d.historyBytes(),
        d.canUndo(),
        d.canRedo(),
        d.dirty(),
        ProjectIO.JSON.valueToTree(d.data()));
  }

  private void retained(DocumentSession d, State s) {
    assertSame(s.active(), d.data());
    assertSame(s.lifetime(), d.sessionToken());
    assertEquals(s.path(), d.path());
    assertEquals(s.bytes(), d.historyBytes());
    assertEquals(s.undo(), d.canUndo());
    assertEquals(s.redo(), d.canRedo());
    assertEquals(s.dirty(), d.dirty());
    assertEquals(s.values(), ProjectIO.JSON.valueToTree(d.data()));
  }

  private DocumentSession document(String id) throws Exception {
    var d = new DocumentSession();
    assertTrue(d.edit(p -> p.project.seed = 17).isEmpty());
    d.save(directory.resolve(id + "-saved.json"));
    assertTrue(d.edit(p -> p.project.seed = 29).isEmpty());
    return d;
  }

  private void action(DocumentSession d, Action action) {
    switch (action) {
      case EDIT -> d.edit(p -> p.project.seed = 42);
      case UNDO -> d.undo();
      case REDO -> d.redo();
    }
  }

  private void validatorMutation(String id, Consumer<DocumentSession> mutation) throws Exception {
    for (var action : Action.values()) {
      var d = document(id + action);
      if (action == Action.REDO) d.undo();
      var once = new AtomicBoolean();
      var after = new AtomicReference<State>();
      d.setValidator(
          candidate -> {
            if (once.compareAndSet(false, true)) {
              mutation.accept(d);
              after.set(snapshot(d));
            }
            return Validator.validate(candidate);
          });
      assertThrows(IllegalStateException.class, () -> action(d, action));
      assertNotNull(after.get());
      retained(d, after.get());
    }
  }

  @Test
  void aReplacementInsideTheEditCallbackCannotBeOverwrittenByTheOuterCandidate() throws Exception {
    for (boolean reopen : List.of(false, true)) {
      var d = document("callback-replace-" + reopen);
      var saved = d.path();
      var after = new AtomicReference<State>();
      var validations = new AtomicInteger();
      d.setValidator(
          candidate -> {
            validations.incrementAndGet();
            return Validator.validate(candidate);
          });
      assertThrows(
          IllegalStateException.class,
          () ->
              d.edit(
                  candidate -> {
                    candidate.project.seed = 42;
                    if (reopen) {
                      try {
                        assertTrue(d.open(saved).isEmpty());
                      } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                      }
                    } else d.newProject();
                    after.set(snapshot(d));
                  }));
      retained(d, after.get());
      assertEquals(reopen ? 1 : 0, validations.get());
      assertFalse(d.canUndo());
    }
  }

  @Test
  void aNestedEditKeepsOnlyItsOwnHistoryEntryAndCanStillUndoAndRedo() throws Exception {
    var d = document("callback-edit");
    var after = new AtomicReference<State>();
    assertThrows(
        IllegalStateException.class,
        () ->
            d.edit(
                candidate -> {
                  candidate.project.seed = 42;
                  assertTrue(d.edit(p -> p.project.seed = 31).isEmpty());
                  after.set(snapshot(d));
                }));
    retained(d, after.get());
    assertEquals(31, d.data().project.seed);
    d.undo();
    assertEquals(29, d.data().project.seed);
    d.redo();
    assertEquals(31, d.data().project.seed);
  }

  @Test
  void aCallbackSaveRetainsTheNewCheckpointAndDoesNotValidateOrPublishTheOuterEdit()
      throws Exception {
    var d = document("callback-save");
    var after = new AtomicReference<State>();
    var calls = new AtomicInteger();
    var file = directory.resolve("callback-save-new.json");
    d.setValidator(
        candidate -> {
          calls.incrementAndGet();
          return Validator.validate(candidate);
        });
    assertThrows(
        IllegalStateException.class,
        () ->
            d.edit(
                candidate -> {
                  candidate.project.seed = 42;
                  try {
                    d.save(file);
                  } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                  }
                  after.set(snapshot(d));
                }));
    retained(d, after.get());
    assertEquals(1, calls.get());
    assertFalse(d.dirty());
    assertEquals(29, new ProjectIO().load(file).project().project.seed);
  }

  @Test
  void callbackPolicyReplacementRejectsTheOldRequestAndPermitsAnExplicitFreshEdit()
      throws Exception {
    var d = document("callback-policy");
    var after = new AtomicReference<State>();
    assertThrows(
        IllegalStateException.class,
        () ->
            d.edit(
                candidate -> {
                  candidate.project.seed = 42;
                  d.setValidator(p -> Validator.validate(p));
                  after.set(snapshot(d));
                }));
    retained(d, after.get());
    assertTrue(d.edit(p -> p.project.seed = 43).isEmpty());
    assertEquals(43, d.data().project.seed);
  }

  @Test
  void callbackHistoryMovementCannotConsumeAnotherDeltaAfterItReturns() throws Exception {
    var d = document("callback-history");
    var after = new AtomicReference<State>();
    assertThrows(
        IllegalStateException.class,
        () ->
            d.edit(
                candidate -> {
                  candidate.project.seed = 42;
                  d.undo();
                  after.set(snapshot(d));
                }));
    retained(d, after.get());
    assertEquals(17, d.data().project.seed);
    d.redo();
    assertEquals(29, d.data().project.seed);
  }

  @Test
  void directRawSourceMutationRejectsPublicationWithoutTryingToRepairTheSideEffect()
      throws Exception {
    var d = document("callback-raw");
    var after = new AtomicReference<State>();
    assertThrows(
        IllegalStateException.class,
        () ->
            d.edit(
                candidate -> {
                  candidate.project.seed = 42;
                  d.data().project.seed = 31;
                  after.set(snapshot(d));
                }));
    retained(d, after.get());
    assertEquals(31, d.data().project.seed);
  }

  @Test
  void validationCannotOverwriteAReentrantDocumentReplacementInAnyDirection() throws Exception {
    validatorMutation("validator-new", DocumentSession::newProject);
  }

  @Test
  void validationCannotOverwriteANewerCommittedEditOrItsHistoryInAnyDirection() throws Exception {
    validatorMutation(
        "validator-edit", d -> assertTrue(d.edit(p -> p.project.seed = 31).isEmpty()));
  }

  @Test
  void validationCannotMoveAWrongHistoryHeadAfterNestedUndoOrRedo() throws Exception {
    for (var action : Action.values()) {
      var d = document("validator-history" + action);
      if (action == Action.REDO) d.undo();
      var once = new AtomicBoolean();
      var after = new AtomicReference<State>();
      d.setValidator(
          candidate -> {
            if (once.compareAndSet(false, true)) {
              if (action == Action.REDO) d.redo();
              else d.undo();
              after.set(snapshot(d));
            }
            return Validator.validate(candidate);
          });
      assertThrows(IllegalStateException.class, () -> action(d, action));
      retained(d, after.get());
    }
  }

  @Test
  void validationCannotRetireANewerSaveCheckpointInAnyDirection() throws Exception {
    var serial = new AtomicInteger();
    validatorMutation(
        "validator-save",
        d -> {
          try {
            d.save(directory.resolve("inner-save-" + serial.incrementAndGet() + ".json"));
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
  }

  @Test
  void validationPolicyReplacementInvalidatesAlreadyPreparedEditsAndHistorySteps()
      throws Exception {
    validatorMutation("validator-policy", d -> d.setValidator(p -> Validator.validate(p)));
  }

  @Test
  void rawMutationDuringValidationIsDetectedEvenWhenManagedIdentityAndHistoryAreUnchanged()
      throws Exception {
    validatorMutation("validator-raw", d -> d.data().project.seed = 31);
  }

  @Test
  void ordinaryNoopAndHistoryStepsRetainTheirIdentityAccountingAndSingleValidationRules()
      throws Exception {
    var d = document("ordinary");
    var before = snapshot(d);
    var calls = new AtomicInteger();
    d.setValidator(
        candidate -> {
          calls.incrementAndGet();
          return Validator.validate(candidate);
        });
    assertTrue(d.edit(candidate -> {}).isEmpty());
    retained(d, before);
    assertEquals(1, calls.get());
    d.undo();
    assertEquals(17, d.data().project.seed);
    assertFalse(d.dirty());
    d.redo();
    assertEquals(29, d.data().project.seed);
    assertEquals(before.bytes(), d.historyBytes());
    assertEquals(3, calls.get());
  }

  @Test
  void anEqualValueUndoRedoRoundTripStillInvalidatesTheOldActiveObjectOwner() throws Exception {
    var d = document("roundtrip");
    var before = snapshot(d);
    var after = new AtomicReference<State>();
    assertThrows(
        IllegalStateException.class,
        () ->
            d.edit(
                candidate -> {
                  candidate.project.seed = 42;
                  d.undo();
                  d.redo();
                  after.set(snapshot(d));
                }));
    retained(d, after.get());
    assertNotSame(before.active(), d.data());
    assertEquals(before.values(), after.get().values());
  }

  @Test
  void callbackFailurePreservesTheOriginalThrowableAndDoesNotTrapTheNextTransaction()
      throws Exception {
    var d = document("throwing");
    var before = snapshot(d);
    var failure = new AssertionError("callback failed");
    assertSame(
        failure,
        assertThrows(
            AssertionError.class,
            () ->
                d.edit(
                    candidate -> {
                      throw failure;
                    })));
    retained(d, before);
    assertTrue(d.edit(p -> p.project.seed = 43).isEmpty());
  }

  @Test
  void aNestedNoopDoesNotInventAnOwnershipChangeOrExtraHistoryEntry() throws Exception {
    var d = document("nested-noop");
    assertTrue(
        d.edit(
                candidate -> {
                  candidate.project.seed = 42;
                  assertTrue(d.edit(p -> {}).isEmpty());
                })
            .isEmpty());
    assertEquals(42, d.data().project.seed);
    d.undo();
    assertEquals(29, d.data().project.seed);
  }
}
