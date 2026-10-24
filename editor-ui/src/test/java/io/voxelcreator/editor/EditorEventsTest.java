package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class EditorEventsTest {
  private static final Diagnostic.Severity ERROR = Diagnostic.Severity.ERROR;
  private static final EditorEvents.Subsystem PROJECT = EditorEvents.Subsystem.PROJECT;
  private static final Instant TIME = Instant.parse("2026-10-24T12:00:00Z");

  @Test
  void repeatsKeepStableIdentityFirstTimeAndMoveToFront() {
    var clock = new MutableClock();
    var events = new EditorEvents(3, clock);
    var first = events.publish("BAD", ERROR, PROJECT, "bad input", null, true);
    events.markRead(first.id());
    clock.time = TIME.plusSeconds(2);
    events.publish("OTHER", ERROR, PROJECT, "other", null, false);
    var again = events.publish("BAD", ERROR, PROJECT, "bad input", null, false);
    assertEquals(first.id(), again.id());
    assertEquals(2, again.count());
    assertEquals(TIME, again.first());
    assertEquals(clock.time, again.last());
    assertFalse(again.read());
    assertTrue(again.actionRequired());
    assertEquals(again.id(), events.snapshot().getFirst().id());
    assertEquals(2, events.counts().retained());
  }

  @Test
  void readResolveDismissAreIndependentAndHistoryRemains() {
    var events = new EditorEvents();
    var e = events.publish("ACTION", ERROR, PROJECT, "fix value", null, true);
    events.markAllRead();
    assertEquals(0, events.counts().unread());
    assertEquals(1, events.counts().actionRequired());
    assertFalse(events.dismiss(e.id()));
    events.resolve(e.id());
    assertTrue(events.dismiss(e.id()));
    assertEquals(1, events.snapshot().size());
    assertTrue(events.search("", null, null, true).isEmpty());
    var repeat = events.publish("ACTION", ERROR, PROJECT, "fix value", null, true);
    assertFalse(repeat.dismissed());
    assertFalse(repeat.read());
    assertEquals(1, events.counts().unread());
    assertTrue(repeat.details().contains("Occurrences: 2"));
  }

  @Test
  void retentionIsBoundedAndRecentGroupsSurvive() {
    var events = new EditorEvents(2, Clock.fixed(TIME, ZoneOffset.UTC));
    var a = events.publish("A", ERROR, PROJECT, "a", null, false);
    events.publish("B", ERROR, PROJECT, "b", null, false);
    events.publish("A", ERROR, PROJECT, "a", null, false);
    events.publish("C", ERROR, PROJECT, "c", null, false);
    assertEquals(
        List.of("C", "A"), events.snapshot().stream().map(EditorEvents.Entry::code).toList());
    assertEquals(1, events.counts().evicted());
    assertTrue(events.get(a.id()).isPresent());
    assertThrows(UnsupportedOperationException.class, () -> events.snapshot().clear());
  }

  @Test
  void filtersComposeAndGroupingIncludesSourceSeverityAndSubsystem() {
    var events = new EditorEvents();
    UUID project = UUID.randomUUID();
    var target =
        new EditorEvents.Target(
            project, UUID.randomUUID(), null, "threshold", "/layers/1/threshold", false);
    events.publish("RANGE", ERROR, PROJECT, "Threshold invalid", target, true);
    events.publish(
        "RANGE", Diagnostic.Severity.WARNING, PROJECT, "Threshold invalid", target, false);
    events.publish(
        "RANGE", ERROR, EditorEvents.Subsystem.ENGINE, "Threshold invalid", target, false);
    events.publish("RANGE", ERROR, PROJECT, "Threshold invalid", null, false);
    assertEquals(4, events.counts().retained());
    assertEquals(2, events.search("ThReShOlD", ERROR, PROJECT, false).size());
    assertEquals(1, events.search(target.resource().toString(), ERROR, PROJECT, false).size());
    assertTrue(events.search("missing", null, null, false).isEmpty());
  }

  @Test
  void longMessagesAreBoundedWithoutMergingDifferentTruncatedSuffixes() {
    var events = new EditorEvents();
    String prefix = "x".repeat(10000);
    var a = events.publish("CODE", ERROR, PROJECT, prefix + "a", null, false);
    var b = events.publish("CODE", ERROR, PROJECT, prefix + "b", null, false);
    assertEquals(8192, a.message().length());
    assertNotEquals(a.id(), b.id());
    assertEquals(2, events.counts().retained());
    var repeat = events.publish("CODE", ERROR, PROJECT, prefix + "a", null, false);
    assertEquals(2, repeat.count());
  }

  @Test
  void concurrentWorkerFloodGroupsExactlyWithoutUiCallbacks() throws Exception {
    var events = new EditorEvents(10, Clock.fixed(TIME, ZoneOffset.UTC));
    try (var workers = Executors.newFixedThreadPool(8)) {
      var jobs = new ArrayList<Callable<Void>>();
      for (int j = 0; j < 8; j++)
        jobs.add(
            () -> {
              for (int i = 0; i < 1000; i++)
                events.publish(
                    "OVERLOAD", ERROR, EditorEvents.Subsystem.SCHEDULER, "queue full", null, true);
              return null;
            });
      for (var f : workers.invokeAll(jobs)) f.get();
    }
    assertEquals(1, events.counts().retained());
    assertEquals(8000, events.snapshot().getFirst().count());
    events.markAllRead();
    assertEquals(8000, events.snapshot().getFirst().count());
  }

  private static final class MutableClock extends Clock {
    Instant time = TIME;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return time;
    }
  }
}
