package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.render.VolumeData;
import io.voxelcreator.render.VolumeTextureUpload;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred real-uploader handoff against independent CPU storage and source/origin metadata. */
class VolumePreviewHandoffTest {
  private static final class Memory implements VolumeTextureUpload.Backend {
    final Map<Integer, float[]> textures = new HashMap<>();
    final Map<Integer, Integer> sides = new HashMap<>();
    final List<Integer> deletes = new ArrayList<>();
    int next;
    Throwable writeFault, retirementFault;
    boolean releaseFirst;
    public int allocate(int side) {
      int id = ++next; textures.put(id, new float[side * side * side]); sides.put(id, side); return id;
    }
    public void write(int texture, int x, int y, int z, int w, int h, int d, FloatBuffer values) {
      if (writeFault != null) fail(writeFault);
      int side = sides.get(texture); assertEquals(w * h * d, values.remaining());
      for (int dz = 0; dz < d; dz++) for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++)
        textures.get(texture)[((z + dz) * side + y + dy) * side + x + dx] = values.get();
    }
    public void delete(int texture) {
      deletes.add(texture);
      if (texture == 1 && retirementFault != null && !releaseFirst) fail(retirementFault);
      assertNotNull(textures.remove(texture)); sides.remove(texture);
      if (texture == 1 && retirementFault != null) fail(retirementFault);
    }
  }
  private record Accepted(String source, int origin, int side, int texture, long version) {}
  private static final Accepted OLD = new Accepted("old", -7, 2, 1, 1);
  private static final Accepted NEXT = new Accepted("new", 11, 3, 2, 2);

  private static final class Fixture implements AutoCloseable {
    final Memory memory = new Memory();
    final VolumeTextureUpload upload = new VolumeTextureUpload(memory);
    final AtomicReference<Accepted> accepted = new AtomicReference<>(OLD);
    int publications;
    Fixture() { upload.stage(data(2, 0), 1000); upload.advance(32); }
    void stage() { assertTrue(upload.stage(data(3, 1000), 1000)); }
    void publish() {
      publications++; assertEquals(2, upload.resident().texture()); assertEquals(2, upload.statistics().version());
      assertFalse(upload.statistics().pending()); assertArrayEquals(values(3, 1000), memory.textures.get(2)); accepted.set(NEXT);
    }
    RuntimeException advance(int budget) {
      return VolumePreviewHandoff.advance(() -> upload.advance(budget), () -> upload.statistics().version(), this::publish);
    }
    public void close() {
      memory.writeFault = memory.retirementFault = null; upload.close(); memory.textures.clear(); memory.sides.clear();
    }
  }
  private static void fail(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }
  private static Throwable fault(boolean error, AtomicInteger reads) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
    } : new IllegalStateException() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
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
  void onlyANewCompleteVersionPublishesMetadataOnce() {
    try (var f = new Fixture()) {
      assertNull(f.advance(0)); assertSame(OLD, f.accepted.get()); assertEquals(0, f.publications);
      f.stage(); assertNull(f.advance(20)); assertSame(OLD, f.accepted.get()); assertEquals(0, f.publications);
      assertEquals(1, f.upload.statistics().version()); assertEquals(88, f.upload.statistics().remainingBytes());
      assertNull(f.advance(88)); assertSame(NEXT, f.accepted.get()); assertEquals(1, f.publications);
      assertNull(f.advance(108)); assertEquals(1, f.publications); assertEquals(List.of(1), f.memory.deletes);
    }
  }

  @Test
  void recoverableRetirementFaultReturnsOnlyAfterMatchingMetadataPublishes() {
    for (boolean after : new boolean[] {false, true}) try (var f = new Fixture()) {
      var reads = new AtomicInteger(); var failure = fault(false, reads); f.stage();
      f.memory.retirementFault = failure; f.memory.releaseFirst = after;
      assertSame(failure, f.advance(108)); assertSame(NEXT, f.accepted.get()); assertEquals(1, f.publications);
      assertEquals(108, f.upload.statistics().lastBytes()); assertEquals(140, f.upload.statistics().uploadedBytes());
      assertEquals(after ? Set.of(2) : Set.of(1, 2), f.memory.textures.keySet()); assertEquals(List.of(1), f.memory.deletes);
      assertEquals(0, failure.getSuppressed().length); assertEquals(0, reads.get());
      assertNull(f.advance(0)); assertEquals(1, f.publications); assertEquals(List.of(1), f.memory.deletes);
    }
  }

  @Test
  void retirementErrorStillPublishesMetadataBeforePropagatingUnchanged() {
    for (boolean after : new boolean[] {false, true}) try (var f = new Fixture()) {
      var reads = new AtomicInteger(); var failure = fault(true, reads); f.stage();
      f.memory.retirementFault = failure; f.memory.releaseFirst = after;
      assertSame(failure, assertThrows(Throwable.class, () -> f.advance(108)));
      assertSame(NEXT, f.accepted.get()); assertEquals(1, f.publications); assertEquals(108, f.upload.statistics().lastBytes());
      assertEquals(after ? Set.of(2) : Set.of(1, 2), f.memory.textures.keySet()); assertEquals(List.of(1), f.memory.deletes);
      assertEquals(0, failure.getSuppressed().length); assertEquals(0, reads.get());
    }
  }

  @Test
  void prepublicationWriteFaultRetainsOldMetadataAndPropagatesUnchanged() {
    for (boolean error : new boolean[] {false, true}) try (var f = new Fixture()) {
      var reads = new AtomicInteger(); var failure = fault(error, reads); f.stage(); f.memory.writeFault = failure;
      assertSame(failure, assertThrows(Throwable.class, () -> f.advance(108)));
      assertSame(OLD, f.accepted.get()); assertEquals(0, f.publications); assertEquals(1, f.upload.statistics().version());
      assertEquals(1, f.upload.resident().texture()); assertFalse(f.upload.statistics().pending());
      assertArrayEquals(values(2, 0), f.memory.textures.get(1)); assertEquals(List.of(2), f.memory.deletes); assertEquals(0, reads.get());
    }
  }

  @Test
  void optionalMeasurementFaultsCannotChangePartialOrCompletePublication() {
    for (boolean error : new boolean[] {false, true}) for (int mask = 0; mask < 8; mask++) try (var f = new Fixture()) {
      var reads = new AtomicInteger(); var calls = new int[3]; int failures = mask;
      Throwable[] faults = {fault(error, reads), fault(!error, reads), fault(error, reads)};
      Runnable[] measurements = new Runnable[3];
      for (int i = 0; i < 3; i++) {
        int index = i;
        measurements[i] = () -> { calls[index]++; if ((failures & (1 << index)) != 0) fail(faults[index]); };
      }
      f.stage();
      for (int budget : new int[] {20, 88}) {
        Throwable observed;
        try { assertNull(f.advance(budget)); }
        finally { observed = VolumePreviewHandoff.observe(measurements); }
        assertSame(mask == 0 ? null : faults[Integer.numberOfTrailingZeros(mask)], observed);
        assertArrayEquals(budget == 20 ? new int[] {1, 1, 1} : new int[] {2, 2, 2}, calls);
        assertSame(budget == 20 ? OLD : NEXT, f.accepted.get()); assertEquals(budget == 20 ? 0 : 1, f.publications);
      }
      assertEquals(140, f.upload.statistics().uploadedBytes()); assertEquals(88, f.upload.statistics().lastBytes());
      assertArrayEquals(values(3, 1000), f.memory.textures.get(2)); assertEquals(0, reads.get());
      for (var failure : faults) assertEquals(0, failure.getSuppressed().length);
    }
  }

  @Test
  void measurementFinalizationCannotReplaceAnOriginalTransferFailure() {
    for (boolean error : new boolean[] {false, true}) for (boolean observerError : new boolean[] {false, true}) try (var f = new Fixture()) {
      var reads = new AtomicInteger(); var primary = fault(error, reads); var observation = fault(observerError, reads);
      var observed = new AtomicReference<Throwable>(); var attempts = new AtomicInteger(); f.stage(); f.memory.writeFault = primary;
      assertSame(primary, assertThrows(Throwable.class, () -> {
        try { f.advance(108); }
        finally { observed.set(VolumePreviewHandoff.observe(
            () -> { attempts.incrementAndGet(); fail(observation); },
            attempts::incrementAndGet, attempts::incrementAndGet)); }
      }));
      assertSame(observation, observed.get()); assertEquals(3, attempts.get()); assertSame(OLD, f.accepted.get()); assertEquals(0, f.publications);
      assertEquals(1, f.upload.statistics().version()); assertFalse(f.upload.statistics().pending());
      assertArrayEquals(values(2, 0), f.memory.textures.get(1)); assertEquals(List.of(2), f.memory.deletes);
      assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length); assertEquals(0, observation.getSuppressed().length);
    }
  }

  @Test
  void committedRetirementOutcomeIsIndependentOfMeasurementFailures() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) try (var f = new Fixture()) {
      var reads = new AtomicInteger(); var primary = fault(error, reads); var observation = fault(!error, reads);
      var observed = new AtomicReference<Throwable>(); f.stage(); f.memory.retirementFault = primary; f.memory.releaseFirst = after;
      java.util.function.Supplier<RuntimeException> advance = () -> {
        try { return f.advance(108); }
        finally { observed.set(VolumePreviewHandoff.observe(() -> fail(observation))); }
      };
      if (error) assertSame(primary, assertThrows(Throwable.class, advance::get));
      else assertSame(primary, advance.get());
      assertSame(observation, observed.get()); assertSame(NEXT, f.accepted.get()); assertEquals(1, f.publications);
      assertEquals(108, f.upload.statistics().lastBytes()); assertArrayEquals(values(3, 1000), f.memory.textures.get(2));
      assertEquals(after ? Set.of(2) : Set.of(1, 2), f.memory.textures.keySet());
      assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length); assertEquals(0, observation.getSuppressed().length);
    }
  }

  @Test
  void observationDiagnosticsAreGuardedAndDeliveryFaultsCannotChangeAcceptedData() {
    for (boolean error : new boolean[] {false, true}) try (var f = new Fixture()) {
      var reads = new AtomicInteger(); var reportingReads = new AtomicInteger(); var attempts = new AtomicInteger();
      var description = new AtomicReference<String>();
      var observation = fault(error, reads); var reporting = fault(!error, reportingReads); f.stage(); f.advance(108);
      VolumePreviewHandoff.reportObservation(observation, () -> true, detail -> {
        attempts.incrementAndGet(); description.set(detail); fail(reporting);
      });
      assertEquals(1, attempts.get()); assertEquals(1, reads.get()); assertEquals(0, reportingReads.get());
      assertEquals(observation.getClass().getName(), description.get());
      assertSame(NEXT, f.accepted.get()); assertEquals(1, f.publications); assertFalse(f.upload.statistics().pending());
      assertArrayEquals(values(3, 1000), f.memory.textures.get(2)); assertEquals(List.of(1), f.memory.deletes);
      assertEquals(0, observation.getSuppressed().length); assertEquals(0, reporting.getSuppressed().length);
    }
  }

  @Test
  void obsoleteOrReentrantObservationTextCannotDeliverToANewRequest() {
    try (var f = new Fixture()) {
      var epoch = new AtomicLong(7); var reads = new AtomicInteger(); var reports = new AtomicInteger();
      var observation = new IllegalStateException() {
        public String getMessage() { reads.incrementAndGet(); epoch.incrementAndGet(); return "obsolete"; }
        public String toString() { throw new AssertionError("text reader"); }
      };
      f.stage(); f.advance(108);
      VolumePreviewHandoff.reportObservation(observation, () -> epoch.get() == 6, detail -> reports.incrementAndGet());
      assertEquals(0, reads.get());
      VolumePreviewHandoff.reportObservation(observation, () -> epoch.get() == 7, detail -> reports.incrementAndGet());
      assertEquals(1, reads.get()); assertEquals(0, reports.get());
      VolumePreviewHandoff.reportObservation(null, () -> { throw new AssertionError("null fault guard"); }, detail -> reports.incrementAndGet());
      assertSame(NEXT, f.accepted.get()); assertEquals(1, f.publications); assertArrayEquals(values(3, 1000), f.memory.textures.get(2));
    }
  }

  @Test
  void publicationFaultsNeverReplaceAnObservedRetirementCauseOrSelfSuppress() {
    for (boolean error : new boolean[] {false, true}) for (int mode = 0; mode < 3; mode++) try (var f = new Fixture()) {
      var reads = new AtomicInteger(); var primary = fault(error, reads);
      var secondary = mode == 2 ? primary : fault(!error, reads); f.stage();
      if (mode > 0) { f.memory.retirementFault = primary; f.memory.releaseFirst = true; }
      var expected = mode == 0 ? secondary : primary;
      assertSame(expected, assertThrows(Throwable.class, () -> VolumePreviewHandoff.advance(
          () -> f.upload.advance(108), () -> f.upload.statistics().version(), () -> fail(secondary))));
      assertEquals(mode == 1 ? 1 : 0, expected.getSuppressed().length);
      if (mode == 1) assertSame(secondary, expected.getSuppressed()[0]);
      assertEquals(2, f.upload.resident().texture()); assertEquals(2, f.upload.statistics().version()); assertEquals(0, reads.get());
    }
  }
}
