package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.Diagnostic;
import java.time.*;
import org.junit.jupiter.api.Test;

/** Deferred immutable displayed-occurrence acknowledgement and cache definitions. */
class EventViewTest {
  private EditorEvents events(int capacity) {
    return new EditorEvents(capacity, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }

  private EditorEvents.Entry publish(EditorEvents store, String code, boolean required) {
    return store.publish(
        code,
        Diagnostic.Severity.ERROR,
        EditorEvents.Subsystem.PROJECT,
        "message " + code,
        null,
        required);
  }

  @Test
  void immutableHistoryAndCountsShareAViewUntilARealChange() {
    var store = events(3);
    var a = publish(store, "A", true);
    var view = store.view();
    assertSame(view, store.view());
    assertSame(view.entries(), store.snapshot());
    assertSame(view.counts(), store.counts());
    assertThrows(UnsupportedOperationException.class, () -> view.entries().clear());
    store.markRead(999);
    store.resolve(999);
    assertSame(view, store.view());
    assertTrue(store.markRead(a));
    assertNotSame(view, store.view());
    assertFalse(view.entries().getFirst().read());
    assertEquals(1, view.counts().unread());
    assertEquals(0, store.counts().unread());
    assertEquals(1, store.counts().actionRequired());
  }

  @Test
  void repeatedOccurrenceCannotBeReadResolvedOrDismissedThroughAnOlderDisplayedRecord() {
    var store = events(3);
    var old = publish(store, "A", true);
    var latest = publish(store, "A", true);
    assertEquals(old.id(), latest.id());
    assertEquals(old.last(), latest.last());
    assertFalse(store.markRead(old));
    assertFalse(store.resolve(old));
    assertFalse(store.dismiss(old));
    assertFalse(store.get(old.id()).orElseThrow().read());
    assertTrue(store.get(old.id()).orElseThrow().actionRequired());
    assertTrue(store.markRead(latest));
    assertFalse(store.resolve(latest)); // Read state changed the reviewed record too.
    assertTrue(store.resolve(store.get(old.id()).orElseThrow()));
    assertTrue(store.dismiss(store.get(old.id()).orElseThrow()));
    assertEquals(1, store.snapshot().size());
    assertTrue(store.search("", null, null, true).isEmpty());
  }

  @Test
  void markAllReadUsesOnlyUnchangedReviewedOccurrencesAndPreservesRequiredActions() {
    var store = events(5);
    var a = publish(store, "A", true);
    var b = publish(store, "B", false);
    var reviewed = store.view();
    publish(store, "A", true);
    var c = publish(store, "C", false);
    store.markAllRead(reviewed);
    assertFalse(store.get(a.id()).orElseThrow().read());
    assertTrue(store.get(b.id()).orElseThrow().read());
    assertFalse(store.get(c.id()).orElseThrow().read());
    assertEquals(2, store.counts().unread());
    assertEquals(1, store.counts().actionRequired());
    assertEquals(2, reviewed.counts().unread());
    assertTrue(reviewed.entries().stream().noneMatch(EditorEvents.Entry::read));
  }

  @Test
  void evictionAndEqualValuedForeignEntriesCannotAcknowledgeAnotherStore() {
    var store = events(1);
    var other = events(1);
    var a = publish(store, "A", false);
    var foreign = publish(other, "A", false);
    assertEquals(a, foreign);
    assertNotSame(a, foreign);
    assertFalse(store.markRead(foreign));
    assertFalse(store.resolve(foreign));
    assertFalse(store.dismiss(foreign));
    store.markAllRead(other.view());
    assertEquals(1, store.counts().unread());
    var b = publish(store, "B", true);
    assertFalse(store.markRead(a));
    assertFalse(store.resolve(a));
    assertFalse(store.dismiss(a));
    assertEquals(1, store.counts().evicted());
    assertTrue(store.current(b));
  }

  @Test
  void idempotentStateRequestsKeepTheViewAndReadDoesNotResolveAnAction() {
    var store = events(3);
    var entry = publish(store, "A", true);
    store.markRead(entry.id());
    var read = store.view();
    store.markRead(entry.id());
    store.markAllRead();
    assertSame(read, store.view());
    assertEquals(1, store.counts().actionRequired());
    assertFalse(store.dismiss(store.get(entry.id()).orElseThrow()));
    assertSame(read, store.view());
    store.resolve(entry.id());
    var resolved = store.view();
    store.resolve(entry.id());
    assertSame(resolved, store.view());
    store.dismiss(entry.id());
    var dismissed = store.view();
    store.dismiss(entry.id());
    assertSame(dismissed, store.view());
    assertEquals(1, dismissed.counts().retained());
  }

  @Test
  void projectionsReuseNormalizedQueriesAndRebuildForActualHistoryChanges() {
    var store = events(3);
    var a = publish(store, "A", false);
    publish(store, "B", true);
    var projection = new EventProjection();
    var rows = projection.rows(store.view(), " Message a ", null, null, false);
    assertSame(rows, projection.rows(store.view(), "message A", null, null, false));
    assertEquals(1, projection.builds());
    assertEquals(a.id(), rows.getFirst().id());
    store.markRead(a.id());
    assertNotSame(rows, projection.rows(store.view(), "message a", null, null, false));
    assertEquals(2, projection.builds());
    assertFalse(rows.getFirst().read());
  }

  @Test
  void composedProjectionFiltersKeepHistoricalDismissedGroupsAndNeverModifyTheStore() {
    var store = events(4);
    var a = publish(store, "A", false);
    publish(store, "B", true);
    store.dismiss(a.id());
    var view = store.view();
    var projection = new EventProjection();
    assertEquals(
        2,
        projection
            .rows(view, "message", Diagnostic.Severity.ERROR, EditorEvents.Subsystem.PROJECT, false)
            .size());
    assertEquals(
        1,
        projection
            .rows(view, "message", Diagnostic.Severity.ERROR, EditorEvents.Subsystem.PROJECT, true)
            .size());
    assertTrue(
        projection
            .rows(
                view, "message", Diagnostic.Severity.WARNING, EditorEvents.Subsystem.PROJECT, true)
            .isEmpty());
    assertSame(view, store.view());
    assertEquals(2, store.snapshot().size());
  }
}
