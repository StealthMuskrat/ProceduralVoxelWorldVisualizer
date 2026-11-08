package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred production-owner definitions with real CPU arenas and independent handle lifetimes. */
class SceneRendererOwnerTest {
  private static final class Resource {
    final int id, budget;
    final ArenaAllocator arena;
    long epoch = -1;
    int closes;
    boolean released;
    Resource(int id, int budget) { this.id = id; this.budget = budget; arena = new ArenaAllocator(budget); }
  }

  private static final class Memory implements SceneRendererOwner.Backend<Resource>, AutoCloseable {
    final SceneRendererOwner<Resource> owner = new SceneRendererOwner<>(this);
    final List<Resource> created = new ArrayList<>();
    final Set<Resource> live = Collections.newSetFromMap(new IdentityHashMap<>());
    int prepares, epochs, publications, depth, maxDepth, peakLive;
    boolean createAfter, epochAfter, closeAfter;
    Throwable prepareFault, createFault, epochFault, closeFault;
    Runnable preparing;
    Consumer<Resource> creating, resetting, closing;
    Resource accepted;

    public Resource create(int bytes) {
      assertNull(owner.renderer()); assertEquals(0, owner.budget()); assertFalse(owner.active()); assertTrue(owner.transitioning());
      depth++; maxDepth = Math.max(maxDepth, depth); Resource value = null;
      try {
        if (createFault != null && !createAfter) raise(createFault);
        value = new Resource(created.size() + 1, bytes); created.add(value); live.add(value);
        peakLive = Math.max(peakLive, live.size());
        var callback = creating; creating = null; if (callback != null) callback.accept(value);
        if (createFault != null) raise(createFault);
        return value;
      } catch (RuntimeException | Error failure) {
        // Like a native constructor, the factory owns handles it cannot return normally.
        if (value != null && !value.released) { value.closes++; value.released = true; live.remove(value); }
        throw failure;
      } finally { depth--; }
    }

    public void epoch(Resource value, long epoch) {
      assertSame(value, owner.renderer()); assertEquals(value.budget, owner.budget()); assertFalse(owner.active()); assertTrue(owner.transitioning());
      epochs++;
      if (epochFault != null && !epochAfter) raise(epochFault);
      if (value.epoch != epoch) { value.arena.reset(); value.epoch = epoch; }
      var callback = resetting; resetting = null; if (callback != null) callback.accept(value);
      if (epochFault != null) raise(epochFault);
    }

    public void close(Resource value) {
      assertNotSame(value, owner.renderer(), "Retirement still registered its captured resource");
      value.closes++;
      if (closeFault != null && !closeAfter) raise(closeFault);
      value.released = true; live.remove(value);
      var callback = closing; closing = null; if (callback != null) callback.accept(value);
      if (closeFault != null) raise(closeFault);
    }

    Resource activate(long epoch, int bytes) {
      return owner.activate(epoch, bytes, () -> {
        prepares++; var callback = preparing; preparing = null; if (callback != null) callback.run();
        if (prepareFault != null) raise(prepareFault);
      }, value -> { publications++; accepted = value; });
    }

    void empty() { assertNull(owner.renderer()); assertEquals(0, owner.budget()); assertFalse(owner.active()); assertFalse(owner.transitioning()); }
    void healthy(Resource value, long epoch, int bytes) {
      assertSame(value, owner.renderer()); assertSame(value, accepted); assertTrue(owner.active()); assertFalse(owner.transitioning());
      assertEquals(bytes, owner.budget()); assertEquals(epoch, value.epoch); assertFalse(value.released);
      var slice = value.arena.allocate(16); assertNotNull(slice); assertEquals(0, slice.offset()); assertEquals(16, slice.length());
    }

    public void close() {
      preparing = null; creating = resetting = closing = null;
      prepareFault = createFault = epochFault = closeFault = null;
      var captured = owner.closeOwner(); if (captured != null) close(captured);
      // Before-release fault residue is fake storage, not proof of successful native deletion.
      live.clear();
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
  void normalReuseEpochResetDisableAndBudgetReplacementKeepOneRegisteredOwner() {
    try (var m = new Memory()) {
      var first = m.activate(1, 32); assertEquals(0, first.arena.allocate(16).offset());
      assertSame(first, m.activate(1, 32)); assertEquals(16, first.arena.allocate(16).offset());
      assertSame(first, m.activate(2, 32)); assertEquals(0, first.arena.allocate(16).offset());
      m.owner.disable(); assertFalse(m.owner.active()); assertSame(first, m.owner.renderer());
      assertSame(first, m.activate(2, 32)); assertEquals(16, first.arena.allocate(16).offset());
      var detached = new AtomicBoolean(); m.closing = value -> {
        assertSame(first, value); assertNull(m.owner.renderer()); assertEquals(0, m.owner.budget());
        assertFalse(m.owner.active()); assertTrue(m.owner.transitioning()); detached.set(true);
      };
      var second = m.activate(3, 64); assertTrue(detached.get()); assertNotSame(first, second);
      assertEquals(1, first.closes); assertEquals(2, m.created.size()); assertEquals(1, m.peakLive); m.healthy(second, 3, 64);
    }
  }

  @Test
  void invalidBudgetAndClosedOwnersRejectBeforePreparationOrNativeCallbacks() {
    try (var m = new Memory()) {
      var first = m.activate(1, 32); int prepares = m.prepares;
      for (int bytes : new int[] {-16, 0, 1, 15, 17, 31})
        assertThrows(IllegalArgumentException.class, () -> m.activate(2, bytes));
      assertEquals(prepares, m.prepares); assertEquals(1, m.created.size()); assertEquals(0, first.closes); assertTrue(m.owner.active());
      var captured = m.owner.closeOwner(); assertSame(first, captured); m.empty(); assertNull(m.owner.closeOwner());
      m.close(captured); assertThrows(IllegalStateException.class, () -> m.activate(2, 32));
      assertEquals(prepares, m.prepares); assertEquals(1, first.closes);
    }
  }

  @Test
  void preparationFailureRetiresOnlyItsCurrentRendererAndPreservesPrimaryFault() {
    for (boolean error : new boolean[] {false, true}) try (var m = new Memory()) {
      var first = m.activate(1, 32); var reads = new AtomicInteger();
      var primary = fault(error, reads); var cleanup = fault(!error, reads);
      m.prepareFault = primary; m.closeFault = cleanup;
      assertSame(primary, assertThrows(Throwable.class, () -> m.activate(2, 32))); m.empty();
      assertEquals(1, first.closes); assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length); assertEquals(0, cleanup.getSuppressed().length);
      m.prepareFault = m.closeFault = null; var retry = m.activate(3, 32); assertNotSame(first, retry); m.healthy(retry, 3, 32);
    }
  }

  @Test
  void budgetRetirementFaultBeforeOrAfterReleaseCannotLeaveAClosedRendererRegistered() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) try (var m = new Memory()) {
      var first = m.activate(1, 32); var reads = new AtomicInteger(); var failure = fault(error, reads);
      m.closeFault = failure; m.closeAfter = after;
      assertSame(failure, assertThrows(Throwable.class, () -> m.activate(2, 64))); m.empty();
      assertEquals(1, first.closes); assertEquals(after, first.released); assertEquals(1, m.created.size());
      assertEquals(0, reads.get()); assertEquals(0, failure.getSuppressed().length);
      m.closeFault = null; var retry = m.activate(3, 64); assertEquals(1, first.closes); m.healthy(retry, 3, 64);
    }
  }

  @Test
  void failedFactoryOwnsItsPartialHandlesAndCannotPublishBudgetOrActiveState() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) try (var m = new Memory()) {
      var reads = new AtomicInteger(); var primary = fault(error, reads); m.createFault = primary; m.createAfter = after;
      assertSame(primary, assertThrows(Throwable.class, () -> m.activate(7, 64))); m.empty();
      assertEquals(after ? 1 : 0, m.created.size()); assertTrue(m.live.isEmpty()); assertEquals(0, m.epochs); assertEquals(0, m.publications);
      assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
      m.createFault = null; var retry = m.activate(8, 64); m.healthy(retry, 8, 64);
    }
  }

  @Test
  void epochFailureDetachesTheAttemptAndAttemptsCleanupWithoutReplacingThePrimary() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) try (var m = new Memory()) {
      var reads = new AtomicInteger(); var primary = fault(error, reads); var cleanup = fault(!error, reads);
      m.epochFault = primary; m.epochAfter = after; m.closeFault = cleanup;
      assertSame(primary, assertThrows(Throwable.class, () -> m.activate(7, 32))); m.empty();
      var attempted = m.created.getFirst(); assertEquals(after ? 7 : -1, attempted.epoch); assertEquals(1, attempted.closes);
      assertEquals(0, m.publications); assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length); assertEquals(0, cleanup.getSuppressed().length);
      m.epochFault = m.closeFault = null; var retry = m.activate(8, 32); assertNotSame(attempted, retry); m.healthy(retry, 8, 32);
    }
  }

  @Test
  void preparationReentryIntoReplacementOrCloseRevokesOnlyTheOuterOpening() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Memory()) {
      var first = m.activate(1, 32); var next = new AtomicReference<Resource>();
      m.preparing = () -> {
        if (close) { var captured = m.owner.closeOwner(); if (captured != null) m.close(captured); }
        else next.set(m.activate(11, 64));
      };
      assertThrows(CancellationException.class, () -> m.activate(2, 32)); assertEquals(1, first.closes);
      if (close) m.empty(); else { assertEquals(0, next.get().closes); m.healthy(next.get(), 11, 64); }
    }
  }

  @Test
  void oldRendererCloseCallbackCanAdmitASuccessorWithoutOuterAllocationOrLateClear() {
    try (var m = new Memory()) {
      var first = m.activate(1, 32); var next = new AtomicReference<Resource>();
      m.closing = old -> next.set(m.activate(11, 96));
      assertThrows(CancellationException.class, () -> m.activate(2, 64));
      assertEquals(1, first.closes); assertEquals(2, m.created.size()); assertEquals(1, m.peakLive);
      assertEquals(0, next.get().closes); m.healthy(next.get(), 11, 96);
    }
  }

  @Test
  void factoryReentryCannotOverlapAnotherFactoryAndItsUnpublishedCandidateIsRetired() {
    for (String action : List.of("allocate", "detach", "close")) try (var m = new Memory()) {
      var callback = new AtomicBoolean();
      m.creating = candidate -> {
        switch (action) {
          case "allocate" -> assertThrows(CancellationException.class, () -> m.activate(11, 64));
          case "detach" -> assertNull(m.owner.detach());
          default -> assertNull(m.owner.closeOwner());
        }
        callback.set(true);
      };
      assertThrows(CancellationException.class, () -> m.activate(1, 32)); assertTrue(callback.get()); m.empty();
      assertEquals(1, m.maxDepth); assertEquals(1, m.peakLive); assertEquals(1, m.created.size());
      assertEquals(1, m.created.getFirst().closes); assertTrue(m.live.isEmpty()); assertEquals(0, m.publications);
      if (!action.equals("close")) { var retry = m.activate(12, 64); m.healthy(retry, 12, 64); }
    }
  }

  @Test
  void epochCallbackReplacementOrCloseSurvivesTheOldEpochFault() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Memory()) {
      var reads = new AtomicInteger(); var primary = fault(true, reads); var next = new AtomicReference<Resource>();
      m.epochFault = primary; m.epochAfter = true;
      m.resetting = old -> {
        m.epochFault = null;
        if (close) { var captured = m.owner.closeOwner(); if (captured != null) m.close(captured); }
        else next.set(m.activate(11, 64));
        m.epochFault = primary;
      };
      assertSame(primary, assertThrows(AssertionError.class, () -> m.activate(1, 32)));
      assertEquals(1, m.created.getFirst().closes); assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
      if (close) m.empty(); else { assertEquals(0, next.get().closes); m.healthy(next.get(), 11, 64); }
    }
  }

  @Test
  void disablingDuringEpochResetCannotLeaveThePartiallyResetRendererReusable() {
    for (boolean throwsAfter : new boolean[] {false, true}) try (var m = new Memory()) {
      var first = m.activate(1, 32); first.arena.allocate(16);
      var primary = new AssertionError("epoch after disable");
      m.resetting = old -> m.owner.disable();
      if (throwsAfter) { m.epochFault = primary; m.epochAfter = true; }
      if (throwsAfter) assertSame(primary, assertThrows(AssertionError.class, () -> m.activate(2, 32)));
      else assertThrows(CancellationException.class, () -> m.activate(2, 32));
      m.empty(); assertEquals(1, first.closes); assertEquals(1, m.publications);
      m.epochFault = null; var retry = m.activate(3, 32); assertNotSame(first, retry); m.healthy(retry, 3, 32);
    }
  }

  @Test
  void activationReceiptsRevokeSameObjectSameEpochReuseDisableDetachAndClose() {
    try (var m = new Memory()) {
      var first = m.activate(7, 32); long opening = m.owner.version();
      assertTrue(m.owner.current(first, opening));
      assertFalse(m.owner.current(null, opening));
      assertSame(first, m.activate(7, 32));
      assertFalse(m.owner.current(first, opening));
      long reused = m.owner.version(); assertTrue(m.owner.current(first, reused));
      m.owner.disable(); assertFalse(m.owner.current(first, reused));
      assertSame(first, m.activate(7, 32)); long restarted = m.owner.version();
      assertTrue(m.owner.current(first, restarted));
      var second = m.activate(7, 64); long replacement = m.owner.version();
      assertFalse(m.owner.current(first, restarted)); assertTrue(m.owner.current(second, replacement));
      var detached = m.owner.detach(); assertSame(second, detached);
      assertFalse(m.owner.current(second, replacement)); m.close(detached);
      var terminal = m.activate(7, 32); long last = m.owner.version();
      var closed = m.owner.closeOwner(); assertSame(terminal, closed);
      assertFalse(m.owner.current(terminal, last)); m.close(closed);
    }
  }

  @Test
  void preparationAndResetCannotExposeAnAcceptedReceiptBeforePublication() {
    try (var m = new Memory()) {
      var first = m.activate(1, 32); long previous = m.owner.version();
      m.resetting = resource -> assertFalse(m.owner.current(resource, m.owner.version()));
      var selected = m.owner.activate(2, 32, () -> {
        assertFalse(m.owner.current(first, previous));
        assertFalse(m.owner.current(first, m.owner.version()));
      }, resource -> assertTrue(m.owner.current(resource, m.owner.version())));
      assertSame(first, selected); assertTrue(m.owner.current(first, m.owner.version()));
    }
  }

  @Test
  void primaryFailureCleanupCanAdmitANewOwnerEvenWhenThatCleanupAlsoThrows() {
    try (var m = new Memory()) {
      var reads = new AtomicInteger(); var primary = fault(true, reads); var cleanup = fault(false, reads);
      var next = new AtomicReference<Resource>(); m.epochFault = primary; m.epochAfter = true;
      m.closeFault = cleanup; m.closeAfter = true;
      m.closing = old -> {
        m.epochFault = m.closeFault = null; next.set(m.activate(11, 64)); m.closeFault = cleanup;
      };
      assertSame(primary, assertThrows(AssertionError.class, () -> m.activate(1, 32)));
      assertEquals(1, m.created.getFirst().closes); assertEquals(0, next.get().closes); assertEquals(0, reads.get());
      assertEquals(0, primary.getSuppressed().length); assertEquals(0, cleanup.getSuppressed().length); m.healthy(next.get(), 11, 64);
    }
  }
}
