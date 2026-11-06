package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.Diagnostic;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Deferred definitions: timestamp observation precedes every event-store mutation. */
class EditorEventPublicationTest {
  private static final Instant TIME = Instant.parse("2026-10-24T12:00:00Z");
  private final ScriptedClock clock = new ScriptedClock();

  private EditorEvents.Entry publish(EditorEvents store, String code, boolean required) {
    return store.publish(
        code, Diagnostic.Severity.ERROR, EditorEvents.Subsystem.PROJECT, code, null, required);
  }

  @Test
  void failedRepeatPreservesTheExactEntryCachedViewAndLaterOccurrenceCount() {
    var store = new EditorEvents(3, clock);
    var first = publish(store, "A", true);
    long count = 1;
    for (Throwable failure :
        List.of(new DateTimeException("clock"), new AssertionError("clock"))) {
      var before = store.view();
      var retained = store.get(first.id()).orElseThrow();
      clock.read =
          () -> {
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw (Error) failure;
          };
      assertSame(failure, assertThrows(Throwable.class, () -> publish(store, "A", false)));
      assertSame(retained, store.get(first.id()).orElseThrow());
      assertSame(before, store.view());
      assertEquals(1, store.counts().retained());
      assertEquals(0, store.counts().evicted());
      clock.read = () -> TIME.plusSeconds(1);
      var repeat = publish(store, "A", false);
      assertEquals(first.id(), repeat.id());
      assertEquals(++count, repeat.count());
      assertEquals(TIME, repeat.first());
      assertEquals(TIME.plusSeconds(1), repeat.last());
      assertTrue(repeat.actionRequired());
    }
  }

  @Test
  void realOffsetClockOverflowDoesNotEvictOrConsumeTheNextGroupIdentity() {
    var store = new EditorEvents(2, clock);
    var a = publish(store, "A", false);
    var b = publish(store, "B", false);
    var before = store.view();
    var overflowing = Clock.offset(Clock.fixed(Instant.MAX, ZoneOffset.UTC), Duration.ofSeconds(1));
    clock.read = overflowing::instant;
    assertThrows(DateTimeException.class, () -> publish(store, "C", false));
    assertSame(before, store.view());
    assertSame(a, store.get(a.id()).orElseThrow());
    assertSame(b, store.get(b.id()).orElseThrow());
    assertEquals(0, store.counts().evicted());
    clock.read = () -> TIME;
    var c = publish(store, "C", false);
    assertEquals(b.id() + 1, c.id());
    assertTrue(store.get(a.id()).isEmpty());
    assertEquals(1, store.counts().evicted());
  }

  @Test
  void nullTimestampRejectsNewAndRepeatedEventsBeforeAnyMutation() {
    var store = new EditorEvents(2, clock);
    var a = publish(store, "A", true);
    var before = store.view();
    clock.read = () -> null;
    assertThrows(NullPointerException.class, () -> publish(store, "A", false));
    assertThrows(NullPointerException.class, () -> publish(store, "B", false));
    assertSame(before, store.view());
    assertSame(a, store.get(a.id()).orElseThrow());
    clock.read = () -> TIME;
    var b = publish(store, "B", false);
    assertEquals(a.id() + 1, b.id());
    assertEquals(TIME, b.first());
    assertEquals(TIME, b.last());
  }

  @Test
  void reentrantRepeatUsesTheLatestGroupWithoutLosingItsIdentityOrOccurrence() {
    var store = new EditorEvents(3, clock);
    var first = publish(store, "A", false);
    var inner = new AtomicReference<EditorEvents.Entry>();
    clock.read =
        () -> {
          clock.read = () -> TIME.plusSeconds(1);
          inner.set(publish(store, "A", true));
          return TIME.plusSeconds(2);
        };
    var outer = publish(store, "A", false);
    assertEquals(first.id(), inner.get().id());
    assertEquals(2, inner.get().count());
    assertEquals(first.id(), outer.id());
    assertEquals(3, outer.count());
    assertEquals(TIME, outer.first());
    assertEquals(TIME.plusSeconds(2), outer.last());
    assertTrue(outer.actionRequired());
    assertSame(outer, store.get(first.id()).orElseThrow());
    assertEquals(1, store.counts().retained());
  }

  @Test
  void reentrantAcknowledgementSeesACoherentGroupAndItsResolutionSurvivesTheRepeat() {
    var store = new EditorEvents(3, clock);
    var first = publish(store, "A", true);
    var acknowledged = new AtomicReference<EditorEvents.View>();
    clock.read =
        () -> {
          assertSame(first, store.get(first.id()).orElseThrow());
          assertTrue(store.markRead(first));
          assertTrue(store.resolve(store.get(first.id()).orElseThrow()));
          assertTrue(store.dismiss(store.get(first.id()).orElseThrow()));
          acknowledged.set(store.view());
          return TIME.plusSeconds(1);
        };
    var repeated = publish(store, "A", false);
    assertEquals(first.id(), repeated.id());
    assertEquals(2, repeated.count());
    assertFalse(repeated.actionRequired());
    assertFalse(repeated.dismissed());
    assertFalse(repeated.read());
    assertEquals(0, acknowledged.get().counts().unread());
    assertEquals(0, acknowledged.get().counts().actionRequired());
    assertEquals(1, store.counts().unread());
    assertEquals(0, store.counts().actionRequired());
    assertNotSame(acknowledged.get(), store.view());
  }

  @Test
  void reentrantEvictionCannotResurrectAnEvictedGroupOrItsOldCount() {
    var store = new EditorEvents(2, clock);
    var original = publish(store, "A", true);
    clock.read =
        () -> {
          clock.read = () -> TIME.plusSeconds(1);
          publish(store, "B", false);
          publish(store, "C", false);
          assertTrue(store.get(original.id()).isEmpty());
          assertEquals(1, store.counts().evicted());
          return TIME.plusSeconds(2);
        };
    var replacement = publish(store, "A", false);
    assertEquals(original.id() + 3, replacement.id());
    assertEquals(1, replacement.count());
    assertEquals(TIME.plusSeconds(2), replacement.first());
    assertFalse(replacement.actionRequired());
    assertTrue(store.get(original.id()).isEmpty());
    assertEquals(2, store.counts().evicted());
    assertEquals(List.of("A", "C"), store.snapshot().stream().map(EditorEvents.Entry::code).toList());
  }

  @Test
  void aClockFailureAfterReentrantPublicationKeepsThatPublicationAndTheOriginalGroup() {
    var store = new EditorEvents(3, clock);
    var original = publish(store, "A", true);
    var failure = new DateTimeException("after nested publication");
    var nested = new AtomicReference<EditorEvents.Entry>();
    var reviewed = new AtomicReference<EditorEvents.View>();
    clock.read =
        () -> {
          clock.read = () -> TIME.plusSeconds(1);
          nested.set(publish(store, "B", false));
          reviewed.set(store.view());
          throw failure;
        };
    assertSame(failure, assertThrows(DateTimeException.class, () -> publish(store, "A", false)));
    assertSame(original, store.get(original.id()).orElseThrow());
    assertSame(nested.get(), store.get(nested.get().id()).orElseThrow());
    assertSame(reviewed.get(), store.view());
    assertEquals(List.of("B", "A"), store.snapshot().stream().map(EditorEvents.Entry::code).toList());
    var next = publish(store, "C", false);
    assertEquals(nested.get().id() + 1, next.id());
  }

  @Test
  void concurrentClockRejectionsNeverEraseSuccessfulOccurrences() throws Exception {
    var reads = new AtomicInteger();
    var rejected = new AtomicInteger();
    clock.read =
        () -> {
          int read = reads.incrementAndGet();
          if (read % 2 == 0) throw new DateTimeException("clock");
          return TIME.plusMillis(read);
        };
    var store = new EditorEvents(2, clock);
    var original = publish(store, "A", true);
    try (var workers = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 4; i++)
        jobs.add(
            () -> {
              for (int n = 0; n < 64; n++)
                try {
                  publish(store, "A", false);
                } catch (DateTimeException expected) {
                  rejected.incrementAndGet();
                }
              return null;
            });
      for (var job : workers.invokeAll(jobs)) job.get();
    }
    assertEquals(257, reads.get());
    assertEquals(128, rejected.get());
    assertEquals(1, store.counts().retained());
    assertEquals(0, store.counts().evicted());
    var retained = store.snapshot().getFirst();
    assertEquals(original.id(), retained.id());
    assertEquals(129, retained.count());
    assertEquals(original.first(), retained.first());
    assertTrue(retained.actionRequired());
    clock.read = () -> TIME;
    assertEquals(original.id() + 1, publish(store, "B", false).id());
  }

  private static final class ScriptedClock extends Clock {
    Supplier<Instant> read = () -> TIME;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return read.get();
    }
  }
}
