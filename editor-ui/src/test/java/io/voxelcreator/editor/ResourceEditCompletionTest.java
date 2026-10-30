package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred post-commit callback ownership; no document edit is performed by this notification. */
class ResourceEditCompletionTest {
  @Test
  void completionIsConsumedOnceWithoutChangingDocumentOrPreferences() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var project = ProjectIO.JSON.valueToTree(state.project());
      var user = ProjectIO.JSON.valueToTree(state.user);
      var calls = new AtomicInteger();
      var completion = new ResourceEditCompletion(state, calls::incrementAndGet);
      assertTrue(completion.pending());
      completion.complete();
      completion.complete();
      assertFalse(completion.pending());
      assertEquals(1, calls.get());
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
    }
  }

  @Test
  void cancelledCompletionNeverInvokesTheReleasedCallback() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var completion = new ResourceEditCompletion(state, () -> fail("Cancelled refresh"));
      completion.clear();
      completion.clear();
      completion.complete();
      assertFalse(completion.pending());
      assertTrue(state.events.snapshot().isEmpty());
    }
  }

  @Test
  void reentrantCompletionCannotInvokeItselfAgain() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var calls = new AtomicInteger();
      var holder = new ResourceEditCompletion[1];
      holder[0] =
          new ResourceEditCompletion(
              state,
              () -> {
                calls.incrementAndGet();
                holder[0].complete();
              });
      holder[0].complete();
      assertEquals(1, calls.get());
      assertFalse(holder[0].pending());
    }
  }

  @Test
  void callbackFailureIsReportedOnceAfterConsumptionWithoutRepublishingTheEdit() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var completion =
          new ResourceEditCompletion(
              state,
              () -> {
                throw new IllegalStateException("Injected refresh failure");
              });
      completion.complete();
      completion.complete();
      assertFalse(completion.pending());
      var reports =
          state.events.snapshot().stream()
              .filter(e -> e.code().equals("RESOURCE_EDIT_REFRESH_FAILED"))
              .toList();
      assertEquals(1, reports.size());
      assertEquals(1, reports.getFirst().count());
      assertEquals(EditorEvents.Subsystem.APPLICATION, reports.getFirst().subsystem());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }
}
