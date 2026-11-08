package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.render.VolumeData;
import io.voxelcreator.render.VolumeTextureUpload;
import io.voxelcreator.storage.BlockPos;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred request retirement against real mailboxes, Inspector tickets and CPU texture storage. */
class PreviewCancellationTest {
  private record Payload(String source, int origin) {}
  private record Event(String code, String detail) {}
  private static final class Memory implements VolumeTextureUpload.Backend {
    final Map<Integer, float[]> textures = new HashMap<>();
    final Map<Integer, Integer> sides = new HashMap<>();
    int next;
    Throwable fault;
    boolean after;
    Runnable deleting;
    public int allocate(int side) { int id = ++next; textures.put(id, new float[side * side * side]); sides.put(id, side); return id; }
    public void write(int id, int x, int y, int z, int w, int h, int d, FloatBuffer values) {
      int side = sides.get(id); assertEquals(w * h * d, values.remaining());
      for (int dz = 0; dz < d; dz++) for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++)
        textures.get(id)[((z + dz) * side + y + dy) * side + x + dx] = values.get();
    }
    public void delete(int id) {
      if (id == 2 && deleting != null) deleting.run();
      if (id == 2 && fault != null && !after) raise(fault);
      assertNotNull(textures.remove(id)); sides.remove(id);
      if (id == 2 && fault != null) raise(fault);
    }
  }
  private static final class Job extends CompletableFuture<Void> {
    Runnable callback;
    Throwable fault;
    boolean before;
    int cancellations, interrupts;
    public boolean cancel(boolean interrupt) {
      cancellations++; if (interrupt) interrupts++;
      if (callback != null) callback.run();
      if (fault != null && before) raise(fault);
      boolean result = super.cancel(interrupt);
      if (fault != null) raise(fault);
      return result;
    }
  }
  private static final class Model implements AutoCloseable {
    final PreviewMailbox<Payload> box = new PreviewMailbox<>();
    final InspectorRequests requests = new InspectorRequests();
    final InspectorTask inspector = new InspectorTask();
    final List<Job> jobs = new ArrayList<>();
    final List<Event> events = new ArrayList<>();
    final Memory memory = new Memory();
    final VolumeTextureUpload upload = new VolumeTextureUpload(memory);
    final Job old = newJob(), oldPick = newJob();
    final InspectorRequests.Request initialPick = requests.begin(0, true);
    Future<?> job = old;
    Object pending = new Object(), renderer = new Object();
    Object inspectorContext = "old";
    long epoch;
    boolean active = true, dirty, failed, force = true, closed;
    int purges, pickCancellations;
    String message = "Uploading";
    Model() {
      box.publish(0, new Payload("old", -7));
      upload.stage(data(2, 0), 1000); upload.advance(32); upload.stage(data(3, 100), 1000); upload.advance(4);
      inspector.begin(initialPick, null); inspector.attach(initialPick, oldPick);
    }
    Job newJob() { var value = new Job(); jobs.add(value); return value; }
    void retire(Throwable primary) {
      var previous = job; var capturedRenderer = renderer; var pick = inspector.ticket();
      job = null; pending = null; active = false; failed = primary != null; dirty = !failed;
      if (failed) force = false;
      message = failed ? "Worker failed; retry" : "Changed";
      box.advance(++epoch); requests.viewChanged(); long currentEpoch = epoch;
      var cleanup = PreviewNotifications.attempt(upload::cancel,
          () -> { if (previous != null) previous.cancel(true); },
          () -> { if (pick != null && inspector.current(pick)) cancelPick(); }, () -> purges++);
      java.util.function.BooleanSupplier current = () -> !closed && epoch == currentEpoch
          && renderer == capturedRenderer && job == null && !active && failed == (primary != null) && dirty == (primary == null);
      PreviewNotifications.report(primary, current, detail -> events.add(new Event("PREVIEW_FAILED", detail)));
      PreviewNotifications.report(cleanup, current, detail -> events.add(new Event("PREVIEW_CANCEL_FAILED", detail)));
    }
    void cancelPick() {
      cancelPick(false);
    }
    void cancelPick(boolean invalidate) {
      inspectorContext = null; pickCancellations++;
      if (invalidate) requests.cancelCurrent();
      var failure = PreviewNotifications.attempt(inspector::cancel, () -> purges++);
      if (failure != null) raise(failure);
    }
    InspectorRequests.Request newInspector() {
      var request = requests.begin(0, false); inspector.begin(request, new BlockPos(7, 0, 3));
      inspector.attach(request, newJob()); inspectorContext = request; return request;
    }
    void successor() {
      job = newJob(); pending = new Object(); active = true; dirty = failed = false; message = "Successor";
      assertTrue(upload.stage(data(4, 2000), 1000)); assertTrue(box.publish(epoch, new Payload("new", 23)));
    }
    void finishSuccessor() {
      memory.fault = null; memory.deleting = null; upload.advance(256);
      assertArrayEquals(values(4, 2000), memory.textures.get(upload.resident().texture()));
      assertEquals(2, upload.statistics().version()); assertEquals(new Payload("new", 23), box.take());
    }
    void closeDuringCallback() { closed = true; box.close(); requests.close(); upload.close(); inspector.close(); message = "Closed"; }
    public void close() {
      closed = true; for (var value : jobs) { value.callback = null; value.fault = null; }
      memory.fault = null; memory.deleting = null; box.close(); requests.close(); inspector.close(); upload.close();
      memory.textures.clear(); memory.sides.clear();
    }
  }
  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }
  private static Throwable fault(boolean error, AtomicInteger reads, Runnable reading) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); reading.run(); throw new AssertionError("unreadable"); }
      public String toString() { throw new AssertionError("text reader"); }
    } : new IllegalStateException() {
      public String getMessage() { reads.incrementAndGet(); reading.run(); throw new AssertionError("unreadable"); }
      public String toString() { throw new AssertionError("text reader"); }
    };
  }
  private static float[] values(int side, int base) {
    var result = new float[side * side * side];
    for (int z = 0; z < side; z++) for (int y = 0; y < side; y++) for (int x = 0; x < side; x++)
      result[(z * side + y) * side + x] = base + x + 10 * y + 100 * z + .25f;
    return result;
  }
  private static VolumeData data(int side, int base) { return new VolumeData(side, values(side, base)); }

  @Test
  void everyCapturedNotificationIsAttemptedAfterMailboxAndUiOwnershipRetire() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); var jobReads = new AtomicInteger(); var settled = new AtomicBoolean();
      var gpu = fault(error, reads, () -> {}); var worker = fault(!error, jobReads, () -> {});
      var token = m.box.token(0); m.memory.fault = gpu; m.memory.after = after; m.old.fault = worker; m.old.before = after;
      m.memory.deleting = () -> {
        assertNull(m.job); assertNull(m.pending); assertFalse(m.active); assertTrue(m.dirty); assertFalse(m.failed);
        assertTrue(token.isCancelled()); assertFalse(m.box.publish(0, new Payload("obsolete", 0)));
        assertFalse(m.requests.current(m.initialPick, 0)); settled.set(true);
      };
      m.retire(null); assertTrue(settled.get()); assertEquals(1, m.old.cancellations); assertEquals(1, m.old.interrupts);
      assertEquals(!after, m.old.isCancelled());
      assertEquals(1, m.oldPick.cancellations); assertEquals(1, m.pickCancellations); assertEquals(2, m.purges); assertTrue(m.force);
      assertEquals(List.of(new Event("PREVIEW_CANCEL_FAILED", gpu.getClass().getName())), m.events);
      assertEquals(1, reads.get()); assertEquals(0, jobReads.get()); assertEquals(0, gpu.getSuppressed().length); assertEquals(0, worker.getSuppressed().length);
      assertArrayEquals(values(2, 0), m.memory.textures.get(1)); m.successor(); m.finishSuccessor();
    }
  }

  @Test
  void gpuCancellationReentryCannotCancelOrOverwriteTheReplacementWorker() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); m.memory.after = true; m.memory.fault = fault(true, reads, () -> {});
      m.old.fault = fault(false, reads, () -> {});
      m.memory.deleting = () -> { if (close) m.closeDuringCallback(); else m.successor(); };
      m.retire(null); assertEquals(1, m.old.cancellations); assertEquals(0, reads.get()); assertTrue(m.events.isEmpty());
      assertEquals(close ? "Closed" : "Successor", m.message);
      if (!close) { assertTrue(m.active); assertNotNull(m.job); assertEquals(0, ((Job) m.job).cancellations); m.finishSuccessor(); }
    }
  }

  @Test
  void terminalPrimaryFailureRetainsPriorityAndStopsRetryFlagsDespiteCleanupFaults() {
    for (boolean error : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); var cleanupReads = new AtomicInteger(); var workerReads = new AtomicInteger();
      var primary = fault(error, reads, () -> {}); var cleanup = fault(!error, cleanupReads, () -> {});
      m.memory.fault = cleanup; m.old.fault = fault(error, workerReads, () -> {}); m.retire(primary);
      assertFalse(m.active); assertFalse(m.dirty); assertFalse(m.force); assertTrue(m.failed); assertNull(m.job); assertNull(m.pending);
      assertEquals(List.of(new Event("PREVIEW_FAILED", primary.getClass().getName()), new Event("PREVIEW_CANCEL_FAILED", cleanup.getClass().getName())), m.events);
      assertEquals(1, reads.get()); assertEquals(1, cleanupReads.get()); assertEquals(0, workerReads.get());
      assertEquals(1, m.old.cancellations); assertEquals(1, m.oldPick.cancellations); assertEquals(0, primary.getSuppressed().length);
      assertFalse(m.box.publish(0, new Payload("late failure", 0))); assertArrayEquals(values(2, 0), m.memory.textures.get(1));
    }
  }

  @Test
  void primaryTextReentryCannotReportOntoAClosedOrSuccessorRequest() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); var cleanupReads = new AtomicInteger();
      var primary = fault(false, reads, () -> { if (close) m.closeDuringCallback(); else m.successor(); });
      m.memory.after = true; m.memory.fault = fault(true, cleanupReads, () -> {}); m.retire(primary);
      assertEquals(1, reads.get()); assertEquals(0, cleanupReads.get()); assertTrue(m.events.isEmpty());
      assertEquals(close ? "Closed" : "Successor", m.message); if (!close) m.finishSuccessor();
    }
  }

  @Test
  void inspectorCancellationCallbackKeepsItsNewCoordinateOwnerAndContext() {
    try (var m = new Model()) {
      var next = new AtomicReference<InspectorRequests.Request>(); var reads = new AtomicInteger();
      var failure = fault(true, reads, () -> {}); m.oldPick.callback = () -> next.set(m.newInspector()); m.oldPick.fault = failure;
      m.retire(null); assertSame(next.get(), m.inspector.request()); assertSame(next.get(), m.inspectorContext);
      assertTrue(m.requests.current(next.get(), 0)); assertEquals(1, m.oldPick.cancellations);
      assertEquals(0, m.jobs.getLast().cancellations); assertEquals(1, reads.get()); assertEquals(0, failure.getSuppressed().length);
    }
  }

  @Test
  void capturedPickTicketCannotCancelANewerInspectorOpenedByGpuCleanup() {
    try (var m = new Model()) {
      var next = new AtomicReference<InspectorRequests.Request>(); m.memory.deleting = () -> next.set(m.newInspector());
      m.retire(null); assertSame(next.get(), m.inspector.request()); assertSame(next.get(), m.inspectorContext);
      assertEquals(0, m.pickCancellations); assertEquals(1, m.oldPick.cancellations); assertEquals(0, m.jobs.getLast().cancellations);
      assertTrue(m.events.isEmpty()); assertTrue(m.requests.current(next.get(), 0));
    }
  }

  @Test
  void explicitInspectorInvalidationPrecedesItsFutureCallbackAndLeavesPreviewWorkAlone() {
    try (var m = new Model()) {
      var next = new AtomicReference<InspectorRequests.Request>(); var reads = new AtomicInteger();
      var failure = fault(true, reads, () -> {}); m.oldPick.callback = () -> next.set(m.newInspector()); m.oldPick.fault = failure;
      assertSame(failure, assertThrows(Throwable.class, () -> m.cancelPick(true)));
      assertSame(next.get(), m.inspector.request()); assertSame(next.get(), m.inspectorContext); assertTrue(m.requests.current(next.get(), 0));
      assertFalse(m.requests.current(m.initialPick, 0)); assertEquals(0, reads.get()); assertEquals(0, failure.getSuppressed().length);
      assertEquals(0, m.epoch); assertFalse(m.box.token(0).isCancelled()); assertSame(m.old, m.job);
      assertEquals(0, m.old.cancellations); assertTrue(m.upload.statistics().pending());
    }
  }

  @Test
  void aWorkerIgnoringInterruptsStillCannotPublishAfterCancellationFaults() throws Exception {
    try (var m = new Model(); var workers = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var late = new AtomicReference<Boolean>();
      var worker = workers.submit(() -> {
        entered.countDown();
        boolean waiting = true;
        while (waiting) {
          try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker release"); waiting = false; }
          catch (InterruptedException ignored) { Thread.interrupted(); }
        }
        late.set(m.box.publish(0, new Payload("obsolete", -7)));
      });
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS)); var reads = new AtomicInteger();
        m.memory.fault = fault(false, reads, () -> {}); m.old.callback = () -> worker.cancel(true); m.old.fault = fault(true, reads, () -> {});
        m.retire(null); var current = new Payload("current", 23); assertTrue(m.box.publish(m.epoch, current));
        release.countDown(); workers.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertEquals(Boolean.FALSE, late.get()); assertSame(current, m.box.take()); assertFalse(m.box.token(m.epoch).isCancelled());
        assertEquals(1, m.old.cancellations); assertEquals(1, reads.get());
      } finally { release.countDown(); }
    }
  }
}
