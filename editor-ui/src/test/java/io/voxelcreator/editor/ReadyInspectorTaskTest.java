package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.GenerationTrace;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred exact ready-result decisions; no ImGui, GL, live worker or engine compile needed. */
class ReadyInspectorTaskTest {
  static final BlockPos POINT = new BlockPos(-17, 0, 3);

  static InspectorTask.Result result(InspectorRequests.Request request) {
    var trace = new TraceSnapshot(request.document(), new ProjectData(), request.revision(),
        new GenerationTrace(POINT, 0, List.of()));
    return InspectorTask.Result.traced(trace, request.viewBound() ? POINT : null, false);
  }

  static InspectorTask.Ticket prepared(InspectorTask task, InspectorRequests.Request request,
      InspectorTask.Result value) {
    task.begin(request, request.viewBound() ? null : POINT);
    var ticket = task.ticket();
    task.attach(request, CompletableFuture.completedFuture(null));
    assertTrue(task.publish(ticket, value)); task.delivered(ticket);
    return ticket;
  }

  @Test
  void readyTraceKeepsPendingIdentityUntilOneConsumedAcceptance() {
    var request = new InspectorRequests().begin(new Object(), 3, false);
    var value = result(request);
    try (var task = new InspectorTask()) {
      var ticket = prepared(task, request, value); task.delivered(ticket);
      assertSame(request, task.request()); assertSame(ticket, task.ticket());
      assertTrue(task.pending()); assertTrue(task.ready()); assertEquals(POINT, task.coordinate());
      assertSame(value, task.accept(ticket, () -> true)); assertFalse(task.pending());
      assertNull(task.accept(ticket, () -> true)); task.delivered(ticket); assertFalse(task.ready());
    }
  }

  @Test
  void readyTracePickMissAndCapturedFailureCanBeCancelledOrClosedBeforeAcceptance() {
    for (int kind = 0; kind < 3; kind++) for (boolean close : new boolean[] {false, true}) {
      var request = new InspectorRequests().begin(3, kind != 0);
      var value = kind == 2 ? InspectorTask.Result.failed(new AssertionError("held failure"))
          : kind == 1 ? InspectorTask.Result.miss() : result(request);
      try (var task = new InspectorTask()) {
        var ticket = prepared(task, request, value); var checks = new AtomicInteger();
        if (close) task.close(); else task.cancel();
        task.delivered(ticket);
        assertNull(task.accept(ticket, () -> { checks.incrementAndGet(); return true; }));
        assertFalse(task.publish(ticket, value)); assertEquals(0, checks.get()); assertFalse(task.pending());
      }
    }
  }

  @Test
  void successNeedsCompleteWorkerAndUiDeliveryIndependently() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); var value = result(request);
      task.begin(request, POINT); var ticket = task.ticket();
      var future = new FutureTask<Void>(() -> null); task.attach(request, future);
      assertTrue(task.publish(ticket, value)); task.delivered(ticket);
      assertNull(task.accept(ticket, () -> true)); assertFalse(task.completed());
      future.run(); assertSame(value, task.accept(ticket, () -> true));
      task.begin(request, POINT); ticket = task.ticket();
      task.attach(request, CompletableFuture.completedFuture(null)); assertTrue(task.publish(ticket, value));
      assertTrue(task.completed()); assertFalse(task.ready()); assertNull(task.accept(ticket, () -> true));
      task.delivered(ticket); assertSame(value, task.accept(ticket, () -> true));
    }
  }

  @Test
  void olderShownTicketCannotAcceptEqualValuedSuccessor() {
    try (var task = new InspectorTask()) {
      var first = new InspectorRequests().begin(1, false);
      var next = new InspectorRequests().begin(1, false); assertEquals(first, next);
      var old = prepared(task, first, result(first)); var value = result(next);
      var current = prepared(task, next, value); task.delivered(old);
      assertFalse(task.publish(old, result(first))); assertNull(task.accept(old, () -> true));
      assertSame(current, task.ticket()); assertSame(value, task.accept(current, () -> true));
    }
  }

  @Test
  void anotherTaskTicketCannotPublishOrDeliverIntoThisOwnerEvenAtEqualGeneration() {
    var request = new InspectorRequests().begin(1, false);
    try (var first = new InspectorTask(); var next = new InspectorTask()) {
      first.begin(request, POINT); next.begin(request, POINT);
      var current = next.ticket(); next.attach(request, CompletableFuture.completedFuture(null));
      assertFalse(next.publish(first.ticket(), result(request))); next.delivered(first.ticket());
      assertFalse(next.ready()); assertNull(next.accept(first.ticket(), () -> true));
      var value = result(request); assertTrue(next.publish(current, value)); next.delivered(current);
      assertSame(value, next.accept(current, () -> true));
    }
  }

  @Test
  void knownFutureFailureOverridesDeliveredSuccessAndIsConsumedOnceAfterDecision() {
    for (boolean cancel : new boolean[] {false, true}) try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT);
      var ticket = task.ticket(); var failure = new AssertionError("handoff after delivery");
      var future = new FutureTask<Void>(() -> {
        task.publish(ticket, result(request)); task.delivered(ticket);
        assertNull(task.accept(ticket, () -> true)); throw failure;
      });
      task.attach(request, future); future.run(); assertTrue(task.ready()); assertSame(failure, task.failure());
      if (cancel) { task.cancel(); assertNull(task.accept(ticket, () -> true)); }
      else { assertSame(failure, task.accept(ticket, () -> true).failure()); assertNull(task.accept(ticket, () -> true)); }
    }
  }

  @Test
  void escapedFutureFailureAndExternalCancellationNeedNoUiSuccessCallback() {
    for (boolean cancelled : new boolean[] {false, true}) try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, true); task.begin(request, null); var ticket = task.ticket();
      var failure = new AssertionError("escaped worker");
      if (cancelled) {
        var future = new FutureTask<>(() -> 1); task.attach(request, future); future.cancel(false);
      } else task.attach(request, CompletableFuture.failedFuture(failure));
      assertFalse(task.ready()); var value = task.accept(ticket, () -> true); assertNotNull(value);
      if (cancelled) assertInstanceOf(CancellationException.class, value.failure()); else assertSame(failure, value.failure());
      assertFalse(task.pending());
    }
  }

  @Test
  void invalidCurrentInputRetainsNoPublicationAuthorityAndCanBeRetired() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false);
      var ticket = prepared(task, request, result(request));
      assertNull(task.accept(ticket, () -> false)); assertTrue(task.pending()); assertTrue(task.ready());
      task.cancel(); assertNull(task.accept(ticket, () -> true));
    }
  }

  @Test
  void currentCallbackCanPrepareReadySuccessorThenReturnFalseTrueOrThrow() {
    for (int mode = 0; mode < 3; mode++) try (var task = new InspectorTask()) {
      int outcome = mode; var gate = new InspectorRequests(); var request = gate.begin(1, false);
      var old = prepared(task, request, result(request)); var next = gate.begin(1, false); var value = result(next);
      var successor = new AtomicReference<InspectorTask.Ticket>();
      assertNull(task.accept(old, () -> {
        successor.set(prepared(task, next, value));
        if (outcome == 2) throw new IllegalStateException("retired current callback");
        return outcome == 1;
      }));
      assertSame(next, task.request()); assertTrue(task.ready()); assertSame(value, task.accept(successor.get(), () -> true));
    }
  }

  @Test
  void currentCallbackClosureCannotBeReversedOrReportedAgainstANewerOwner() {
    for (boolean close : new boolean[] {false, true}) try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); var ticket = prepared(task, request, result(request));
      assertNull(task.accept(ticket, () -> {
        if (close) task.close(); else task.cancel(); throw new AssertionError("retired validity");
      }));
      assertFalse(task.pending()); assertNull(task.failure()); assertFalse(task.publish(ticket, result(request)));
    }
  }

  @Test
  void acceptedCurrentCallbackFailureBecomesOneOwnedRecoveryResult() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); var ticket = prepared(task, request, result(request));
      var failure = new AssertionError("validity");
      assertSame(failure, task.accept(ticket, () -> { throw failure; }).failure());
      assertFalse(task.pending()); assertNull(task.accept(ticket, () -> true));
    }
  }

  @Test
  void incompleteFutureIsNeverReadEvenAfterReadinessDelivery() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var ticket = task.ticket();
      task.attach(request, new FutureTask<Void>(() -> null) {
        public Void get() { throw new AssertionError("unfinished get"); }
      });
      task.publish(ticket, result(request)); task.delivered(ticket);
      assertNull(task.accept(ticket, () -> true)); assertTrue(task.pending());
    }
  }

  @Test
  void lateWorkerPublishCannotRefillCancelledOrClosedMailbox() {
    for (boolean close : new boolean[] {false, true}) try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var old = task.ticket();
      if (close) task.close(); else task.cancel();
      assertFalse(task.publish(old, result(request))); task.delivered(old); assertFalse(task.ready());
    }
  }

  @Test
  void resultShapeRejectsMixedFailureProxyAndCoordinateOrPickAuthority() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); var value = result(request);
      assertThrows(IllegalArgumentException.class, () -> new InspectorTask.Result(value.trace(), POINT, false, new AssertionError()));
      assertThrows(IllegalArgumentException.class, () -> new InspectorTask.Result(null, POINT, false, null));
      assertThrows(IllegalArgumentException.class, () -> new InspectorTask.Result(value.trace(), null, true, null));
      var ticket = prepared(task, request, InspectorTask.Result.miss());
      assertInstanceOf(IllegalStateException.class, task.accept(ticket, () -> true).failure());
    }
  }

  @Test
  void acceptedSuccessDetachesFutureWithoutInterruptionAndConsumedCleanupCannotReplayIt() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var ticket = task.ticket();
      var cancellations = new AtomicInteger();
      var future = new FutureTask<Void>(() -> null) {
        public boolean cancel(boolean interrupt) { cancellations.incrementAndGet(); return super.cancel(interrupt); }
      };
      task.attach(request, future); future.run(); var value = result(request); task.publish(ticket, value); task.delivered(ticket);
      assertSame(value, task.accept(ticket, () -> true)); task.cancel(); task.close();
      assertEquals(0, cancellations.get()); assertFalse(task.publish(ticket, value));
    }
  }

  @Test
  void cancellationCallbackCreatedSuccessorSurvivesOuterBeginAndReplacementAttachment() {
    for (boolean attachment : new boolean[] {false, true}) try (var task = new InspectorTask()) {
      var gate = new InspectorRequests(); var first = gate.begin(1, false); task.begin(first, POINT);
      var next = gate.begin(1, false); var replacement = new FutureTask<>(() -> 2); var fire = new AtomicBoolean(true);
      task.attach(first, new FutureTask<Integer>(() -> 1) {
        public boolean cancel(boolean interrupt) {
          if (fire.getAndSet(false)) { task.begin(next, POINT); task.attach(next, replacement); }
          return super.cancel(interrupt);
        }
      });
      if (attachment) task.attach(first, new FutureTask<>(() -> 3));
      else task.begin(gate.begin(1, false), POINT);
      assertSame(next, task.request()); assertFalse(replacement.isCancelled());
    }
  }

  @Test
  void sourceGuardRejectsSameRevisionRawMutationAndSameUuidReopen(@TempDir Path directory) throws Exception {
    for (boolean reopen : new boolean[] {false, true}) try (var state = new EditorState(new UserConfig(), false)) {
      var file = directory.resolve("same.json"); state.document.save(file);
      var source = InspectorSource.capture(state, ProjectIO.copy(state.project()));
      assertTrue(source.current(state));
      if (reopen) assertTrue(state.document.open(file).isEmpty()); else state.project().project.seed++;
      assertFalse(source.current(state));
    }
  }

  @Test
  void sourceGuardIgnoresPrivatePreferencesAndPreservesSnapshotValues() {
    try (var state = BackgroundResourceSearchTest.state(1)) {
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      ((ProjectData.Noise) state.draft).id = "private";
      var copy = ProjectIO.copy(state.project()); var source = InspectorSource.capture(state, copy);
      copy.project.seed = 91; state.user.workspace.width = 1234;
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      assertTrue(source.current(state));
      assertTrue(state.change(project -> project.project.seed = 7)); assertFalse(source.current(state));
    }
  }
}
