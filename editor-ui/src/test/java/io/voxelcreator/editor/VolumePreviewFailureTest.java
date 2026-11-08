package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.render.VolumeData;
import io.voxelcreator.render.VolumeTextureUpload;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

/** Deferred recovery ownership using the real uploader and independent CPU pixels/accepted state. */
class VolumePreviewFailureTest {
  private record Accepted(String source, int origin, int side, int texture, long version) {}
  private record Event(String code, String detail) {}
  private static final Accepted OLD = new Accepted("old", -7, 2, 1, 1);

  private static final class Memory implements VolumeTextureUpload.Backend {
    final Map<Integer, float[]> textures = new HashMap<>();
    final Map<Integer, Integer> sides = new HashMap<>();
    final List<Integer> deletes = new ArrayList<>();
    int next;
    Throwable deletionFault;
    boolean releaseFirst;
    IntConsumer beforeDelete, afterDelete;
    public int allocate(int side) {
      int id = ++next; sides.put(id, side); textures.put(id, new float[side * side * side]); return id;
    }
    public void write(int id, int x, int y, int z, int w, int h, int d, FloatBuffer pixels) {
      int side = sides.get(id); assertEquals(w * h * d, pixels.remaining());
      for (int dz = 0; dz < d; dz++) for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++)
        textures.get(id)[((z + dz) * side + y + dy) * side + x + dx] = pixels.get();
    }
    public void delete(int id) {
      deletes.add(id); if (beforeDelete != null) beforeDelete.accept(id);
      if (id == 2 && deletionFault != null && !releaseFirst) raise(deletionFault);
      assertNotNull(textures.remove(id)); sides.remove(id); if (afterDelete != null) afterDelete.accept(id);
      if (id == 2 && deletionFault != null) raise(deletionFault);
    }
  }

  private static final class Model implements AutoCloseable {
    final Memory memory = new Memory();
    final VolumeTextureUpload upload = new VolumeTextureUpload(memory);
    final List<Event> events = new ArrayList<>();
    final List<String> reportAttempts = new ArrayList<>();
    Accepted accepted = OLD;
    Object pending = new Object(), ticket;
    long epoch = 7, working = 108;
    boolean active = true, dirty = true, force = true, failed, closed, reportBefore;
    String message = "Uploading";
    Throwable reportingFault;
    Consumer<String> onReport;
    Model() {
      upload.stage(data(2, 0), 1000); upload.advance(32); upload.stage(data(3, 100), 1000); upload.advance(4);
    }
    void fail(String reason) { fail(() -> reason); }
    void fail(Supplier<String> reason) {
      Object owner = new Object(); long request = epoch;
      VolumePreviewFailure.fail(new VolumePreviewFailure.Owner() {
        public void settle() {
          ticket = owner; pending = null; working = 0; active = dirty = force = false; failed = true; message = "Failed; retry required";
        }
        public boolean current() { return !closed && epoch == request && ticket == owner && pending == null && !active && !dirty && !force && failed; }
        public void feedback(String detail) { message = "Failed: " + detail; }
        public void report(String code, String detail) {
          reportAttempts.add(code);
          if (reportingFault != null && reportBefore) raise(reportingFault);
          events.add(new Event(code, detail)); if (onReport != null) onReport.accept(code);
          if (reportingFault != null) raise(reportingFault);
        }
      }, upload::cancel, reason);
    }
    void settled() { assertNull(pending); assertEquals(0, working); assertFalse(active); assertFalse(dirty); assertFalse(force); assertTrue(failed); }
    void successor() {
      epoch++; ticket = null; pending = new Object(); working = 256; active = dirty = force = true; failed = false; message = "Sampling successor";
      assertTrue(upload.stage(data(4, 1000), 1000));
    }
    void finishSuccessor() {
      memory.deletionFault = null; memory.beforeDelete = memory.afterDelete = null;
      assertNull(VolumePreviewHandoff.advance(() -> upload.advance(256), () -> upload.statistics().version(), () -> {
        pending = null; working = 0; active = dirty = force = false; failed = false; ticket = null; message = "Ready";
        accepted = new Accepted("new", 9, 4, upload.resident().texture(), upload.statistics().version());
      }));
      assertEquals(new Accepted("new", 9, 4, 3, 2), accepted);
      assertArrayEquals(values(4, 1000), memory.textures.get(3)); assertFalse(upload.statistics().pending());
    }
    void closeDuringCallback() { closed = true; message = "Closed"; upload.close(); }
    public void close() {
      closed = true; memory.deletionFault = null; memory.beforeDelete = memory.afterDelete = null;
      upload.close(); memory.textures.clear(); memory.sides.clear();
    }
  }
  private static float[] values(int side, int base) {
    var result = new float[side * side * side];
    for (int z = 0; z < side; z++) for (int y = 0; y < side; y++) for (int x = 0; x < side; x++)
      result[(z * side + y) * side + x] = base + x + 10 * y + 100 * z + .25f;
    return result;
  }
  private static VolumeData data(int side, int base) { return new VolumeData(side, values(side, base)); }
  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }
  private static Throwable fault(boolean error, AtomicInteger messages, AtomicInteger texts, Runnable reading, String text) {
    return error ? new AssertionError() {
      public String getMessage() { messages.incrementAndGet(); reading.run(); if (text == null) throw new AssertionError("unreadable"); return text; }
      public String toString() { texts.incrementAndGet(); throw new AssertionError("text reader"); }
    } : new IllegalStateException() {
      public String getMessage() { messages.incrementAndGet(); reading.run(); if (text == null) throw new AssertionError("unreadable"); return text; }
      public String toString() { texts.incrementAndGet(); throw new AssertionError("text reader"); }
    };
  }

  @Test
  void cancellationFaultCannotStrandUiOwnershipOrReplacePrimaryRejection() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) try (var m = new Model()) {
      var messages = new AtomicInteger(); var texts = new AtomicInteger(); var observed = new AtomicBoolean();
      var cleanup = fault(error, messages, texts, () -> {}, null); m.memory.deletionFault = cleanup; m.memory.releaseFirst = after;
      m.memory.beforeDelete = id -> {
        if (id == 2) { m.settled(); assertFalse(m.upload.statistics().pending()); assertTrue(m.message.startsWith("Failed")); observed.set(true); }
      };
      m.fail("GPU limit"); assertTrue(observed.get()); m.settled(); assertSame(OLD, m.accepted);
      assertEquals(List.of(new Event("VOLUME_UPLOAD_FAILED", "GPU limit"),
          new Event("VOLUME_UPLOAD_CLEANUP_FAILED", cleanup.getClass().getName())), m.events);
      assertEquals("Failed: GPU limit", m.message); assertEquals(List.of(2), m.memory.deletes);
      assertEquals(after ? Set.of(1) : Set.of(1, 2), m.memory.textures.keySet());
      assertArrayEquals(values(2, 0), m.memory.textures.get(1)); assertEquals(1, m.upload.statistics().version());
      assertEquals(1, messages.get()); assertEquals(0, texts.get()); assertEquals(0, cleanup.getSuppressed().length);
      m.successor(); m.finishSuccessor(); assertEquals("Ready", m.message);
    }
  }

  @Test
  void unreadablePrimaryTextIsReadOnlyAfterBothUiSettlementAndCandidateCancellation() {
    for (boolean error : new boolean[] {false, true}) try (var m = new Model()) {
      var messages = new AtomicInteger(); var texts = new AtomicInteger(); var observed = new AtomicBoolean();
      var primary = fault(error, messages, texts, () -> {
        m.settled(); assertFalse(m.upload.statistics().pending()); assertEquals(List.of(2), m.memory.deletes); observed.set(true);
      }, null);
      m.fail(() -> BackgroundFailure.describe(primary)); assertTrue(observed.get()); m.settled();
      assertEquals(List.of(new Event("VOLUME_UPLOAD_FAILED", primary.getClass().getName())), m.events);
      assertSame(OLD, m.accepted); assertArrayEquals(values(2, 0), m.memory.textures.get(1));
      assertEquals(1, messages.get()); assertEquals(0, texts.get()); assertEquals(0, primary.getSuppressed().length);
    }
  }

  @Test
  void cancellationReentryCanCloseOrAdmitASuccessorWithoutReadingOldFailureText() {
    for (boolean close : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true}) try (var m = new Model()) {
      var messages = new AtomicInteger(); var texts = new AtomicInteger(); var reasons = new AtomicInteger();
      m.memory.releaseFirst = true; m.memory.deletionFault = fault(error, messages, texts, () -> {}, null);
      m.memory.afterDelete = id -> { if (id == 2) { m.settled(); if (close) m.closeDuringCallback(); else m.successor(); } };
      m.fail(() -> { reasons.incrementAndGet(); return "obsolete"; });
      assertTrue(m.events.isEmpty()); assertEquals(0, reasons.get()); assertEquals(0, messages.get()); assertEquals(0, texts.get());
      if (close) { assertEquals("Closed", m.message); assertNull(m.upload.resident()); assertFalse(m.upload.statistics().pending()); }
      else { assertEquals("Sampling successor", m.message); assertTrue(m.active); assertTrue(m.upload.statistics().pending()); m.finishSuccessor(); }
    }
  }

  @Test
  void primaryMessageReentryCannotOverwriteAClosedOrReplacementOwner() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Model()) {
      var primaryReads = new AtomicInteger(); var cleanupReads = new AtomicInteger(); var texts = new AtomicInteger();
      m.memory.releaseFirst = true; m.memory.deletionFault = fault(true, cleanupReads, texts, () -> {}, null);
      var primary = fault(false, primaryReads, texts, () -> { if (close) m.closeDuringCallback(); else m.successor(); }, "obsolete");
      m.fail(() -> BackgroundFailure.describe(primary));
      assertTrue(m.events.isEmpty()); assertEquals(1, primaryReads.get()); assertEquals(0, cleanupReads.get()); assertEquals(0, texts.get());
      assertEquals(close ? "Closed" : "Sampling successor", m.message); if (!close) m.finishSuccessor();
    }
  }

  @Test
  void reportingFaultsPreservePrimaryFeedbackAndDoNotSkipTheCleanupNotificationAttempt() {
    for (boolean error : new boolean[] {false, true}) for (boolean before : new boolean[] {false, true}) try (var m = new Model()) {
      var cleanupReads = new AtomicInteger(); var reportReads = new AtomicInteger(); var texts = new AtomicInteger();
      var cleanup = fault(!error, cleanupReads, texts, () -> {}, "cleanup");
      var reporting = fault(error, reportReads, texts, () -> {}, null);
      m.memory.releaseFirst = true; m.memory.deletionFault = cleanup; m.reportingFault = reporting; m.reportBefore = before;
      m.fail("GPU limit"); m.settled(); assertEquals("Failed: GPU limit", m.message); assertSame(OLD, m.accepted);
      assertEquals(List.of("VOLUME_UPLOAD_FAILED", "VOLUME_UPLOAD_CLEANUP_FAILED"), m.reportAttempts);
      assertEquals(before ? 0 : 2, m.events.size()); assertEquals(1, cleanupReads.get()); assertEquals(0, reportReads.get()); assertEquals(0, texts.get());
      assertEquals(0, cleanup.getSuppressed().length); assertEquals(0, reporting.getSuppressed().length);
    }
  }

  @Test
  void primaryReportOrCleanupMessageReentryPreventsAnySecondaryStaleNotification() {
    for (boolean duringReport : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); var texts = new AtomicInteger();
      m.memory.releaseFirst = true; m.memory.deletionFault = fault(true, reads, texts, () -> { if (!duringReport) m.successor(); }, "cleanup");
      if (duringReport) m.onReport = code -> { if (code.equals("VOLUME_UPLOAD_FAILED")) m.successor(); };
      m.fail("GPU limit"); assertEquals(List.of(new Event("VOLUME_UPLOAD_FAILED", "GPU limit")), m.events);
      assertEquals("Sampling successor", m.message); assertEquals(duringReport ? 0 : 1, reads.get()); assertEquals(0, texts.get());
      m.finishSuccessor();
    }
  }

  @Test
  void sameEpochNestedFailureReplacesTheOriginalFeedbackTicket() {
    try (var m = new Model()) {
      var reads = new AtomicInteger(); var texts = new AtomicInteger(); long epoch = m.epoch;
      var primary = fault(false, reads, texts, () -> m.fail("nested"), "obsolete");
      m.fail(() -> BackgroundFailure.describe(primary));
      assertEquals(epoch, m.epoch); m.settled(); assertEquals("Failed: nested", m.message);
      assertEquals(List.of(new Event("VOLUME_UPLOAD_FAILED", "nested")), m.events);
      assertEquals(List.of(2), m.memory.deletes); assertEquals(1, reads.get()); assertEquals(0, texts.get()); assertSame(OLD, m.accepted);
    }
  }
}
