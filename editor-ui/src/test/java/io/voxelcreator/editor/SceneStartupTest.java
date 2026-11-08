package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred startup ownership with real immutable source palettes and modeled GPU side effects. */
class SceneStartupTest {
  private static final int[] ORIGINAL = {0, 0xff0000ff, 0xff00ff00, 0xff2d1eeb, 0xff2d55eb, 0x12345678};
  private static final int[] REFRESHED = {0, 0xfff0b428, 0xff965a14, 0xff503ceb, 0xff321eeb, 0x12345678};
  private record Prepared(int budget, WorldPreviewPalette palette, int[] colors) {}
  private record Accepted(Object owner, int budget, WorldPreviewPalette palette) {}

  private static final class Model {
    final ProjectData project = new ProjectData();
    final List<String> calls = new ArrayList<>();
    final Map<String, Runnable> callbacks = new HashMap<>();
    final WorldPreviewPalette source;
    Object owner = new Object();
    Accepted accepted;
    boolean closed, after;
    String failing;
    Throwable failure;
    int requestedBudget = 23, gpuBudget = 7, fallbackSettlements, gpuReleases, cpuCloses;
    int[] gpuColors = ORIGINAL.clone();

    Model() {
      var a = new ProjectData.VoxelType(); a.id = "a"; a.color = new ProjectData.Color(255, 0, 0);
      var b = new ProjectData.VoxelType(); b.id = "b"; b.color = new ProjectData.Color(0, 255, 0);
      project.voxelTypes.addAll(List.of(a, b)); source = WorldPreviewPalette.capture(project, ORIGINAL);
      accepted = new Accepted(owner, 7, source);
      a.color = new ProjectData.Color(40, 180, 240); b.color = new ProjectData.Color(20, 90, 150);
    }

    boolean current(Object request) { return !closed && owner == request; }
    Prepared prepare() { var palette = source.refresh(project); return new Prepared(requestedBudget, palette, palette.colors()); }

    void step(String name, Runnable effect) {
      calls.add(name);
      if (name.equals(failing) && !after) raise(failure);
      effect.run(); var callback = callbacks.get(name); if (callback != null) callback.run();
      if (name.equals(failing) && after) raise(failure);
    }

    boolean start() {
      Object request = owner;
      return SceneStartup.apply(() -> current(request),
          () -> { var value = new AtomicReference<Prepared>(); step("prepare", () -> value.set(prepare())); return value.get(); },
          prepared -> step("activate", () -> gpuBudget = prepared.budget()),
          prepared -> step("upload", () -> gpuColors = prepared.colors().clone()),
          prepared -> step("publish", () -> accepted = new Accepted(request, prepared.budget(), prepared.palette())));
    }

    void successor() { owner = new Object(); accepted = new Accepted(owner, 41, source); gpuBudget = 41; gpuColors = new int[] {0, 41}; }

    boolean fallback(Object request, Throwable primary) {
      Object ticket = new Object(); var settled = new AtomicReference<Object>();
      return ScenePreviewFallback.fail(new ScenePreviewFallback.Owner() {
        public boolean eligible() { return Model.this.current(request); }
        public void settle() { fallbackSettlements++; settled.set(ticket); }
        public boolean current() { return Model.this.current(request) && settled.get() == ticket; }
      }, () -> gpuReleases++, () -> cpuCloses++, primary,
          detail -> calls.add("primary"), detail -> calls.add("cleanup"));
    }
  }

  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }

  private static Throwable fault(boolean error, AtomicInteger reads) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("unreadable"); }
    } : new IllegalStateException() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("unreadable"); }
    };
  }

  @Test
  void preparationPrecedesActivationAndMatchingMetadataPublishesOnlyAfterPaletteUpload() {
    var m = new Model(); var old = m.accepted;
    m.callbacks.put("prepare", () -> { assertSame(old, m.accepted); assertEquals(7, m.gpuBudget); });
    m.callbacks.put("activate", () -> { assertSame(old, m.accepted); assertArrayEquals(ORIGINAL, m.gpuColors); m.requestedBudget = 99; });
    m.callbacks.put("upload", () -> { assertSame(old, m.accepted); assertArrayEquals(REFRESHED, m.gpuColors); });
    assertTrue(m.start()); assertEquals(List.of("prepare", "activate", "upload", "publish"), m.calls);
    assertEquals(23, m.accepted.budget()); assertEquals(23, m.gpuBudget); assertSame(m.owner, m.accepted.owner());
    assertArrayEquals(REFRESHED, m.accepted.palette().colors()); assertArrayEquals(ORIGINAL, m.source.colors());
  }

  @Test
  void preparationFaultsAndMalformedLivePaletteNeverTouchGpuOrPublishCpuMetadata() {
    for (boolean error : new boolean[] {false, true}) {
      var m = new Model(); var old = m.accepted; var reads = new AtomicInteger();
      m.failing = "prepare"; m.after = true; m.failure = fault(error, reads);
      assertSame(m.failure, assertThrows(Throwable.class, m::start));
      assertEquals(List.of("prepare"), m.calls); assertSame(old, m.accepted); assertEquals(7, m.gpuBudget);
      assertArrayEquals(ORIGINAL, m.gpuColors); assertEquals(0, reads.get()); assertEquals(0, m.failure.getSuppressed().length);
    }
    var malformed = new Model(); var old = malformed.accepted; malformed.project.voxelTypes.add(null);
    assertThrows(NullPointerException.class, malformed::start); assertSame(old, malformed.accepted);
    assertEquals(List.of("prepare"), malformed.calls); assertEquals(7, malformed.gpuBudget);
  }

  @Test
  void eachCallbackBoundaryCanCloseOrReplaceTheOwnerWithoutLaterOldWork() {
    for (String stage : List.of("prepare", "activate", "upload")) for (boolean close : new boolean[] {false, true}) {
      var m = new Model(); var old = m.accepted;
      m.callbacks.put(stage, () -> { if (close) m.closed = true; else m.successor(); });
      assertFalse(m.start());
      var order = List.of("prepare", "activate", "upload", "publish");
      assertEquals(order.subList(0, order.indexOf(stage) + 1), m.calls);
      if (close) assertSame(old, m.accepted);
      else { assertSame(m.owner, m.accepted.owner()); assertEquals(41, m.accepted.budget()); assertArrayEquals(new int[] {0, 41}, m.gpuColors); }
    }
  }

  @Test
  void activationAndPaletteFaultsBeforeOrAfterSideEffectsCannotPublishAttemptedMetadata() {
    for (String stage : List.of("activate", "upload")) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) {
        var m = new Model(); var old = m.accepted; var reads = new AtomicInteger();
        m.failing = stage; m.after = after; m.failure = fault(error, reads);
        assertSame(m.failure, assertThrows(Throwable.class, m::start)); assertSame(old, m.accepted);
        assertFalse(m.calls.contains("publish")); assertEquals(0, reads.get()); assertEquals(0, m.failure.getSuppressed().length);
        assertEquals(stage.equals("activate") && !after ? 7 : 23, m.gpuBudget);
        assertArrayEquals(stage.equals("upload") && after ? REFRESHED : ORIGINAL, m.gpuColors);
      }
  }

  @Test
  void callbackReplacementBeforeThrowCannotTriggerFallbackAgainstItsNewOwner() {
    for (String stage : List.of("prepare", "activate", "upload")) {
      var m = new Model(); Object request = m.owner; var reads = new AtomicInteger();
      m.failing = stage; m.after = true; m.failure = fault(false, reads); m.callbacks.put(stage, m::successor);
      var failure = assertThrows(RuntimeException.class, m::start); assertSame(m.failure, failure);
      assertFalse(m.fallback(request, failure)); assertEquals(0, m.fallbackSettlements); assertEquals(0, m.gpuReleases); assertEquals(0, m.cpuCloses);
      assertEquals(41, m.accepted.budget()); assertSame(m.owner, m.accepted.owner()); assertEquals(0, reads.get());
      assertFalse(m.calls.contains("publish")); assertFalse(m.calls.contains("primary"));
    }
  }

  @Test
  void preparedPaletteKeepsSourceUuidOrderAndOwnedColorsAcrossLaterLiveEdits() {
    var m = new Model(); var a = m.project.voxelTypes.getFirst(); var b = m.project.voxelTypes.getLast();
    var inserted = new ProjectData.VoxelType(); inserted.id = "inserted"; inserted.color = new ProjectData.Color(1, 2, 3);
    m.project.voxelTypes.clear(); m.project.voxelTypes.addAll(List.of(inserted, b, a));
    m.callbacks.put("activate", () -> { a.color = new ProjectData.Color(0, 0, 0); b.color = new ProjectData.Color(255, 255, 255); });
    assertTrue(m.start()); assertEquals(List.of(a.uuid, b.uuid), m.accepted.palette().types());
    assertArrayEquals(REFRESHED, m.gpuColors); assertArrayEquals(REFRESHED, m.accepted.palette().colors());
    var external = m.accepted.palette().colors(); Arrays.fill(external, 0);
    assertArrayEquals(REFRESHED, m.accepted.palette().colors()); assertArrayEquals(ORIGINAL, m.source.colors());
  }

  @Test
  void initiallyObsoleteAndNullPreparationCannotAdmitAnyGpuWork() {
    var m = new Model(); m.closed = true; var old = m.accepted;
    assertFalse(m.start()); assertTrue(m.calls.isEmpty()); assertSame(old, m.accepted);
    var calls = new AtomicInteger();
    assertThrows(NullPointerException.class, () -> SceneStartup.apply(() -> true, () -> null,
        value -> calls.incrementAndGet(), value -> calls.incrementAndGet(), value -> calls.incrementAndGet()));
    assertEquals(0, calls.get());
  }

  @Test
  void ambiguousFailedUploadCanBeFollowedByACompleteHealthyNewRequest() {
    var m = new Model(); var old = m.accepted; m.failing = "upload"; m.after = true; m.failure = new IllegalStateException("after write");
    assertSame(m.failure, assertThrows(IllegalStateException.class, m::start)); assertSame(old, m.accepted);
    assertArrayEquals(REFRESHED, m.gpuColors); m.owner = new Object(); m.failing = null; m.failure = null; m.calls.clear();
    m.requestedBudget = 59; assertTrue(m.start()); assertEquals(59, m.accepted.budget()); assertSame(m.owner, m.accepted.owner());
    assertArrayEquals(REFRESHED, m.gpuColors); assertArrayEquals(REFRESHED, m.accepted.palette().colors());
    assertEquals(List.of("prepare", "activate", "upload", "publish"), m.calls);
  }

  @Test
  void currentRuntimeFailureUsesExistingFallbackWhileFatalErrorsRetainIdentity() {
    var m = new Model(); Object request = m.owner; m.failing = "activate"; m.failure = new IllegalStateException("allocation");
    var primary = assertThrows(IllegalStateException.class, m::start); assertTrue(m.fallback(request, primary));
    assertEquals(1, m.fallbackSettlements); assertEquals(1, m.gpuReleases); assertEquals(1, m.cpuCloses); assertTrue(m.calls.contains("primary"));
    var fatal = new Model(); var reads = new AtomicInteger(); fatal.failing = "upload"; fatal.after = true; fatal.failure = fault(true, reads);
    assertSame(fatal.failure, assertThrows(AssertionError.class, fatal::start));
    assertEquals(0, fatal.fallbackSettlements); assertFalse(fatal.calls.contains("publish")); assertEquals(0, reads.get());
  }

  @Test
  void rawNoisePaletteNeedsNoWorldMetadataAndPublishesItsExactPreparedBudget() {
    int[] noise = {0, 0xff010203, 0xffa0b0c0}; var accepted = new AtomicReference<Prepared>();
    var uploaded = new AtomicReference<int[]>(); var budget = new AtomicInteger();
    assertTrue(SceneStartup.apply(() -> true, () -> new Prepared(31, null, noise),
        prepared -> budget.set(prepared.budget()),
        prepared -> { assertNull(accepted.get()); uploaded.set(prepared.colors().clone()); }, accepted::set));
    assertEquals(31, budget.get()); assertEquals(31, accepted.get().budget()); assertNull(accepted.get().palette());
    assertArrayEquals(new int[] {0, 0xff010203, 0xffa0b0c0}, uploaded.get());
    assertArrayEquals(new int[] {0, 0xff010203, 0xffa0b0c0}, noise);
  }
}
