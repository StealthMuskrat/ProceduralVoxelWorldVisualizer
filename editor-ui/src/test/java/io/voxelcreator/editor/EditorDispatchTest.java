package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred production dispatch boundaries, read-only retry and unpublished-file cleanup. */
class EditorDispatchTest {
  @TempDir Path directory;

  private void fill(EditorState state) {
    for (int i = 0; i < UiDispatchQueue.CAPACITY; i++) state.postUi(() -> {});
  }

  private void empty(EditorState state) {
    for (int i = 0; i < 16 && !state.uiQueue.isEmpty(); i++) state.drain();
    assertTrue(state.uiQueue.isEmpty());
  }

  @Test
  void recursiveSchedulingAndNestedDrainingCannotConsumeAnEntireFrame() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var user = ProjectIO.JSON.valueToTree(state.user);
      var count = new AtomicInteger();
      Runnable[] again = new Runnable[1];
      again[0] = () -> {
        count.incrementAndGet();
        state.postUi(again[0]);
        state.drain();
      };
      state.postUi(again[0]);
      state.notify("INFORMATION", "status still advances");
      state.drain();
      assertEquals(1, count.get());
      assertEquals(1, state.uiQueue.size());
      assertEquals("status still advances", state.status);
      state.drain();
      assertEquals(2, count.get());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(0, state.document.historyBytes());
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void failuresProduceBoundedDiagnosticsWithoutSkippingHealthyReceipts() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var reads = new AtomicInteger();
      state.postUi(() -> { throw new IllegalStateException() {
        @Override public String getMessage() { reads.incrementAndGet(); throw new AssertionError("unreadable"); }
      }; });
      var healthy = new AtomicBoolean();
      state.postUi(() -> healthy.set(true));
      state.drain();
      assertTrue(healthy.get());
      assertEquals(1, reads.get());
      var failure = state.events.snapshot().stream()
          .filter(event -> event.code().equals("EDITOR_UI_CALLBACK_FAILED")).findFirst().orElseThrow();
      assertTrue(failure.actionRequired());
      assertTrue(failure.message().length() < 2200);
      assertEquals(failure.message(), state.status);
      assertTrue(state.uiQueue.isEmpty());
    }
  }

  @Test
  void fullQueueIsObservedAsValidationFailureAndExplicitRetryStillNeedsAcceptance() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var old = List.of(Diagnostic.error("OLD", "/project/seed", "retained"));
      var result = List.of(Diagnostic.error("NEW", "/project/maxY", "prepared"));
      state.issues = old;
      var tasks = new ArrayDeque<Runnable>();
      var errors = new ArrayList<String>();
      try (var work = new ProjectValidationSession(state, tasks::add, state::postUi, p -> result, errors::add)) {
        fill(state);
        assertTrue(work.start());
        tasks.remove().run();
        assertEquals(ProjectValidationSession.Phase.FAILED, work.view().phase());
        assertSame(old, state.issues);
        assertTrue(errors.getFirst().contains("full or closed"));
        state.drain();
        assertEquals(UiDispatchQueue.CAPACITY - UiDispatchQueue.PER_FRAME, state.uiQueue.size());
        assertTrue(work.start());
        tasks.remove().run();
        empty(state);
        assertEquals(ProjectValidationSession.Phase.RUNNING, work.view().phase());
        assertSame(old, state.issues);
        work.acceptPrepared();
        assertEquals(ProjectValidationSession.Phase.READY, work.view().phase());
        assertEquals(result, state.issues);
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(0, state.document.historyBytes());
      }
    }
  }

  @Test
  void deferredDeliveryDoesNotOverrideCancelOrDocumentReplacement() {
    for (boolean replace : new boolean[] {false, true})
      try (var state = new EditorState(new UserConfig(), false)) {
        var tasks = new ArrayDeque<Runnable>();
        try (var work = new ProjectValidationSession(state, tasks::add, state::postUi,
            p -> List.of(Diagnostic.error("LATE", "/project/seed", "obsolete")), message -> fail(message))) {
          assertTrue(work.start());
          tasks.remove().run();
          if (replace) state.newSession();
          else work.cancel();
          state.drain();
          work.acceptPrepared();
          assertEquals(replace ? ProjectValidationSession.Phase.STALE : ProjectValidationSession.Phase.CANCELLED,
              work.view().phase());
          assertTrue(state.issues.isEmpty());
        }
      }
  }

  @Test
  void postingRejectionCleansPreferenceStagingAndRetryPublishesOnlyOnExplicitAccept() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var file = directory.resolve("preferences.json");
      state.user.save(file);
      byte[] original = Files.readAllBytes(file);
      var before = ProjectIO.JSON.valueToTree(state.user);
      var limits = new PerformanceEditSession(state.user);
      limits.edit(PerformancePreferences.Field.CACHE_MIB, "+256");
      long input = limits.version();
      var tasks = new ArrayDeque<Runnable>();
      var errors = new ArrayList<String>();
      try (var work = new PreferenceApplySession(state.user, file, tasks::add, state::postUi, errors::add)) {
        fill(state);
        work.start(limits.request(state.user, limits.text()), () -> limits.ownsInput(state.user, input, limits.text()));
        tasks.remove().run();
        assertFalse(work.running());
        assertTrue(work.message().contains("full or closed"));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.user));
        assertArrayEquals(original, Files.readAllBytes(file));
        try (var files = Files.list(directory)) { assertEquals(List.of(file), files.toList()); }
        assertEquals("+256", limits.text().get(PerformancePreferences.Field.CACHE_MIB));
        empty(state);
        work.start(limits.request(state.user, limits.text()), () -> limits.ownsInput(state.user, input, limits.text()));
        tasks.remove().run();
        state.drain();
        assertTrue(work.ready());
        assertEquals(before, ProjectIO.JSON.valueToTree(state.user));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(work.accept());
        var expected = before.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("performance")).put("cacheMiB", 256);
        assertEquals(expected, ProjectIO.JSON.valueToTree(state.user));
        assertEquals(expected, ProjectIO.JSON.readTree(Files.readString(file)));
        assertEquals(256, state.user.performance.cacheMiB);
        assertEquals(256, ProjectIO.JSON.readTree(Files.readString(file)).path("performance").path("cacheMiB").asInt());
        assertEquals(0, state.document.historyBytes());
        assertFalse(state.document.dirty());
      }
    }
  }

  @Test
  void inspectorPostingRejectionBecomesAnOwnedFailureAndAFreshRequestCanDeliver() {
    try (var state = new EditorState(new UserConfig(), false); var task = new InspectorTask()) {
      var requests = new InspectorRequests();
      var first = requests.begin(state.document.sessionToken(), state.revision, true);
      task.begin(first, null);
      var ticket = task.ticket();
      fill(state);
      var worker = new FutureTask<Void>(() -> {
        assertTrue(task.publish(ticket, InspectorTask.Result.miss()));
        state.postUi(() -> task.delivered(ticket));
        return null;
      });
      task.attach(first, worker);
      worker.run();
      var failed = task.accept(ticket, () -> true);
      assertNotNull(failed);
      assertInstanceOf(RejectedExecutionException.class, failed.failure());
      assertFalse(task.pending());
      empty(state);
      var next = requests.begin(state.document.sessionToken(), state.revision, true);
      task.begin(next, null);
      var fresh = task.ticket();
      var retry = new FutureTask<Void>(() -> {
        assertTrue(task.publish(fresh, InspectorTask.Result.miss()));
        state.postUi(() -> task.delivered(fresh));
        return null;
      });
      task.attach(next, retry);
      retry.run();
      assertNull(task.accept(fresh, () -> true));
      state.drain();
      var accepted = task.accept(fresh, () -> true);
      assertNotNull(accepted);
      assertNull(accepted.failure());
      assertNull(accepted.trace());
      assertFalse(task.pending());
      assertEquals(0, state.document.historyBytes());
    }
  }

  @Test
  void shutdownDiscardsPendingReceiptsAndRejectsLatePosting() {
    var state = new EditorState(new UserConfig(), false);
    var calls = new AtomicInteger();
    state.postUi(calls::incrementAndGet);
    state.close();
    state.drain();
    assertEquals(0, calls.get());
    assertTrue(state.uiQueue.isEmpty());
    assertThrows(RejectedExecutionException.class, () -> state.postUi(calls::incrementAndGet));
  }
}
