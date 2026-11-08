package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred CPU ownership definitions; allocation/raster state transactions remain separate. */
class ShadowRetirementTest {
  private static Throwable fault(boolean error, AtomicInteger reads) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("text"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text"); }
    } : new IllegalStateException() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("text"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text"); }
    };
  }
  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }
  private static void empty(ShadowResources owner) {
    assertEquals(0, owner.texture); assertEquals(0, owner.framebuffer);
    assertEquals(0, owner.program); assertEquals(0, owner.resolution);
  }
  private static void populate(ShadowResources owner, int texture, int framebuffer, int program) {
    owner.requireOpen(); owner.texture = texture; owner.framebuffer = framebuffer;
    owner.program = program; owner.resolution = 512;
  }

  @Test
  void everyTerminalDeleteFaultKeepsNamesDetachedAndAttemptsAllThreeNamespaces() {
    for (int slot = 1; slot <= 3; slot++) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) {
        var reads = new AtomicInteger(); var primary = fault(error, reads);
        var attempts = new ArrayList<RenderRetirement.Handle>(); var effects = new HashSet<Integer>();
        var ref = new AtomicReference<ShadowResources>(); int failed = slot;
        var owner = new ShadowResources(handle -> {
          var current = ref.get(); empty(current);
          assertThrows(IllegalStateException.class, current::requireOpen); current.close();
          attempts.add(handle);
          if (handle.id() == failed && !after) raise(primary);
          assertTrue(effects.add(handle.id()));
          if (handle.id() == failed) raise(primary);
        });
        ref.set(owner); populate(owner, 1, 2, 3);
        assertSame(primary, assertThrows(Throwable.class, owner::close));
        assertEquals(List.of(RenderRetirement.Kind.TEXTURE, RenderRetirement.Kind.FRAMEBUFFER,
            RenderRetirement.Kind.PROGRAM), attempts.stream().map(RenderRetirement.Handle::kind).toList());
        assertEquals(after ? 3 : 2, effects.size()); empty(owner); owner.close();
        assertEquals(3, attempts.size()); assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
      }
  }

  @Test
  void resizeReleaseIsGuardedDuringCallbacksAndFreshAllocationRetainsItsOwnLifetime() {
    var reads = new AtomicInteger(); var primary = fault(true, reads);
    var attempts = new ArrayList<Integer>(); var ref = new AtomicReference<ShadowResources>();
    var fail = new AtomicBoolean(true);
    var owner = new ShadowResources(h -> {
      var current = ref.get(); empty(current);
      assertThrows(IllegalStateException.class, current::requireOpen);
      attempts.add(h.id()); if (fail.get()) raise(primary);
    });
    ref.set(owner); populate(owner, 11, 12, 13);
    assertSame(primary, assertThrows(AssertionError.class, owner::release));
    empty(owner); assertDoesNotThrow(owner::requireOpen);
    fail.set(false); populate(owner, 21, 22, 23);
    assertEquals(1 << 20, DirectionalShadow.bytes(owner.resolution));
    owner.close(); owner.close(); empty(owner);
    assertEquals(List.of(11, 12, 13, 21, 22, 23), attempts);
    assertThrows(IllegalStateException.class, owner::requireOpen); assertEquals(0, reads.get());
    assertEquals(0, primary.getSuppressed().length);
  }

  @Test
  void partialSignedNamesAndNestedCloseDuringResizeCannotReopenTheOwner() {
    for (int populated = 0; populated < 8; populated++) {
      var attempts = new ArrayList<RenderRetirement.Handle>(); var ref = new AtomicReference<ShadowResources>();
      var owner = new ShadowResources(h -> {
        empty(ref.get()); ref.get().close();
        assertThrows(IllegalStateException.class, ref.get()::requireOpen); attempts.add(h);
      });
      ref.set(owner);
      populate(owner, (populated & 1) != 0 ? -7 : 0,
          (populated & 2) != 0 ? -7 : 0, (populated & 4) != 0 ? -7 : 0);
      owner.release(); assertEquals(Integer.bitCount(populated), attempts.size());
      assertEquals(attempts.size(), new HashSet<>(attempts).size());
      if (populated == 0) assertDoesNotThrow(owner::requireOpen);
      else assertThrows(IllegalStateException.class, owner::requireOpen);
      owner.close(); empty(owner); assertEquals(Integer.bitCount(populated), attempts.size());
    }
  }

  @Test
  void actualEmptyShadowOwnerClosesAndRejectsEveryNativeWorkEntryWithoutAContext() {
    var map = new GlShadowMap(); map.close();
    assertEquals(0, map.texture()); assertEquals(0, map.framebuffer());
    assertEquals(0, map.resolution()); assertEquals(0, map.bytes());
    for (int size : new int[] {0, 512, 1024, -1})
      assertThrows(IllegalStateException.class, () -> map.allocate(size));
    assertThrows(IllegalStateException.class, () -> map.render(null, null));
    assertDoesNotThrow(map::close);
  }
}
