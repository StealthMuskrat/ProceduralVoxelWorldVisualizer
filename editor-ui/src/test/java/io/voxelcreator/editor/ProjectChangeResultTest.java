package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Deferred attempt-scoped feedback definitions; these do not execute native UI. */
class ProjectChangeResultTest {
  private EditorState state() {
    return new EditorState(new UserConfig(), false);
  }

  @Test
  void reusedValidatorListStillProducesOwnedBoundedDetailsForEveryRejection() {
    try (var state = state()) {
      var issues = new ArrayList<Diagnostic>();
      IntStream.range(0, 300)
          .forEach(i -> issues.add(Diagnostic.error("REJECT", "/fixture/" + i, "Rejected")));
      state.document.setValidator(p -> issues);
      var before = ProjectIO.JSON.valueToTree(state.project());
      var first = state.changeResult(p -> p.project.seed = 17);
      var second = state.changeResult(p -> p.project.seed = 19);
      assertFalse(first.applied());
      assertFalse(second.applied());
      assertEquals(300, first.issueCount());
      assertEquals(300, second.issueCount());
      assertEquals(256, first.issues().size());
      assertEquals(first.issues(), second.issues());
      assertThrows(UnsupportedOperationException.class, () -> second.issues().clear());
      issues.clear();
      assertEquals(256, first.issues().size());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void throwingValidatorCannotAttachEarlierRejectionDetailsToANewFailure() {
    try (var state = state()) {
      var earlier = List.of(Diagnostic.error("EARLIER", "/fixture", "Earlier rejection"));
      state.document.setValidator(p -> earlier);
      var first = state.changeResult(p -> p.project.seed = 17);
      assertEquals(earlier, first.issues());
      var active = state.project();
      long revision = state.revision, bytes = state.document.historyBytes();
      state.document.setValidator(
          p -> {
            throw new IllegalStateException();
          });
      var next = state.changeResult(p -> p.project.seed = 19);
      assertFalse(next.applied());
      assertEquals(0, next.issueCount());
      assertTrue(next.issues().isEmpty());
      assertTrue(next.failure().contains("IllegalStateException"));
      assertEquals(earlier, first.issues());
      assertSame(earlier, state.issues); // Shared Validation retains its separately scoped results.
      assertSame(active, state.project());
      assertEquals(revision, state.revision);
      assertEquals(bytes, state.document.historyBytes());
      assertTrue(
          state.events.snapshot().stream()
              .anyMatch(
                  e ->
                      e.code().equals("PROJECT_EDIT_FAILED")
                          && e.subsystem() == EditorEvents.Subsystem.PROJECT
                          && e.actionRequired()
                          && e.message().contains("IllegalStateException")));
      state.document.setValidator(NoiseCompiler::validate);
      assertTrue(state.changeResult(p -> p.project.seed = 19).applied());
      assertEquals(19, state.project().project.seed);
    }
  }

  @Test
  void throwingCandidateNeverReachesValidationOrChangesHistoryAndRedo() {
    try (var state = state()) {
      assertTrue(state.change(p -> p.project.seed = 2));
      state.document.undo();
      var active = state.project();
      var before = ProjectIO.JSON.valueToTree(active);
      long revision = state.revision, history = state.document.historyBytes();
      var calls = new AtomicInteger();
      state.document.setValidator(
          p -> {
            calls.incrementAndGet();
            return NoiseCompiler.validate(p);
          });
      var failure =
          state.changeResult(
              p -> {
                p.project.seed = 99;
                throw new IllegalArgumentException("Candidate failed");
              });
      assertFalse(failure.applied());
      assertEquals("Candidate failed", failure.failure());
      assertEquals(0, calls.get());
      assertSame(active, state.project());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertTrue(state.document.canRedo());
      state.document.redo();
      assertEquals(2, state.project().project.seed);
    }
  }

  @Test
  void acceptedResultIsIndependentOfEarlierErrorsAndMakesOneDocumentTransaction() {
    try (var state = state()) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      state.issues = List.of(Diagnostic.error("OLD", "/old", "Old error"));
      long revision = state.revision;
      var result = state.changeResult(p -> p.project.seed = 17);
      assertTrue(result.applied());
      assertEquals(0, result.issueCount());
      assertTrue(result.issues().isEmpty());
      assertEquals("", result.failure());
      assertTrue(state.issues.isEmpty());
      assertEquals(revision + 1, state.revision);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertFalse(state.document.canUndo());
      state.document.redo();
      assertEquals(17, state.project().project.seed);
    }
  }

  @Test
  void booleanEntryPointKeepsItsCommitRejectionAndFailureBehavior() {
    try (var state = state()) {
      assertTrue(state.change(p -> p.project.seed = 17));
      assertFalse(state.change(p -> p.project.minY = p.project.maxY + 1));
      assertEquals(17, state.project().project.seed);
      assertFalse(
          state.change(
              p -> {
                throw new IllegalStateException("failed");
              }));
      assertEquals(17, state.project().project.seed);
    }
  }

  @Test
  void failureTextIsNonemptyForBlankMessagesAndBoundedForLargeMessages() {
    for (String message : new String[] {null, "", "  ", "x".repeat(10000)}) {
      try (var state = state()) {
        state.document.setValidator(
            p -> {
              throw new IllegalStateException(message);
            });
        var result = state.changeResult(p -> p.project.seed = 1);
        assertFalse(result.applied());
        assertFalse(result.failure().isBlank());
        assertTrue(result.failure().length() <= ProjectChangeResult.MAX_FAILURE);
        assertTrue(result.issues().isEmpty());
        assertEquals(0, state.project().project.seed);
      }
    }
  }
}
