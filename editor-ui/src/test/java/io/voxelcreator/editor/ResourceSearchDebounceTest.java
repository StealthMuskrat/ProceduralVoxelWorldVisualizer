package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceSearchDebounceTest {
  private static ResourceSearchIndex.Filter text(String query) {
    return new ResourceSearchIndex.Filter(
        query, "noise2D", "All", "All", null, null, false, ResourceSearchIndex.Mode.ID);
  }

  private static ResourceSearchSession session() {
    return new ResourceSearchSession(1, 150);
  }

  @Test
  void typingBurstRunsOnlyTheFinalQueryAfterTheWholeQuietInterval() {
    var project = ResourceSearchIndexTest.fixture();
    var session = session();
    var original = session.search(project, 0, text(""));
    long version = session.presentationVersion();
    for (int i = 0; i < 100; i++) {
      assertSame(original, session.searchDebounced(project, 0, text("missing-" + i), i));
      assertTrue(session.pending());
      assertFalse(session.matches(text("")));
    }
    assertTrue(session.presentationVersion() > version);
    assertSame(original, session.searchDebounced(project, 0, text("missing-99"), 248));
    assertEquals(1, session.queries());
    assertTrue(session.searchDebounced(project, 0, text("missing-99"), 249).isEmpty());
    assertFalse(session.pending());
    assertTrue(session.matches(text("missing-99")));
    assertEquals(2, session.queries());
    assertEquals(1, session.builds());
  }

  @Test
  void repeatedDrawingDoesNotExtendTheDeadlineOrRebuildResults() {
    var p = ResourceSearchIndexTest.fixture();
    var session = session();
    session.search(p, 0, text(""));
    session.searchDebounced(p, 0, text("consumer"), 0);
    long version = session.presentationVersion();
    for (int i = 1; i < 150; i++) {
      session.searchDebounced(p, 0, text("consumer"), i);
      assertEquals(version, session.presentationVersion());
    }
    var result = session.searchDebounced(p, 0, text("consumer"), 150);
    assertEquals(List.of("consumer"), result.stream().map(ResourceSearchIndex.Hit::label).toList());
    for (int i = 151; i < 300; i++)
      assertSame(result, session.searchDebounced(p, 0, text("consumer"), i));
    assertEquals(2, session.queries());
    assertThrows(UnsupportedOperationException.class, () -> result.clear());
  }

  @Test
  void normalizedEquivalentTextDoesNotRestartTypingAndReturningToDisplayedCancels() {
    var p = ResourceSearchIndexTest.fixture();
    var session = session();
    var all = session.search(p, 0, text(""));
    session.searchDebounced(p, 0, text("Consumer"), 0);
    assertEquals(1, session.searchDebounced(p, 0, text(" CONSUMER "), 150).size());
    var selected = session.searchDebounced(p, 0, text("source"), 200);
    assertSame(selected, session.searchDebounced(p, 0, text("consumer"), 210));
    assertFalse(session.pending());
    assertEquals(2, session.queries());
    assertEquals(all, session.searchDebounced(p, 0, text(""), 211));
    assertFalse(session.pending());
  }

  @Test
  void scopeChangesAndExplicitRequestsBypassAnOutstandingTypedQuery() {
    var p = ResourceSearchIndexTest.fixture();
    var requests =
        List.of(
            new ResourceSearchIndex.Filter(
                "source", "All", "All", "All", null, null, false, ResourceSearchIndex.Mode.ID),
            new ResourceSearchIndex.Filter(
                "source",
                "noise2D",
                "Disabled",
                "All",
                null,
                null,
                false,
                ResourceSearchIndex.Mode.ID),
            new ResourceSearchIndex.Filter(
                "source",
                "noise2D",
                "All",
                "noise2D",
                null,
                null,
                false,
                ResourceSearchIndex.Mode.ID),
            new ResourceSearchIndex.Filter(
                "source",
                "noise2D",
                "All",
                "All",
                p.folders.getFirst().uuid,
                null,
                false,
                ResourceSearchIndex.Mode.ID),
            new ResourceSearchIndex.Filter(
                "source",
                "noise2D",
                "All",
                "All",
                null,
                p.noise2D.getFirst().uuid,
                false,
                ResourceSearchIndex.Mode.ID),
            new ResourceSearchIndex.Filter(
                "source", "noise2D", "All", "All", null, null, true, ResourceSearchIndex.Mode.ID),
            new ResourceSearchIndex.Filter(
                "source",
                "noise2D",
                "All",
                "All",
                null,
                null,
                false,
                ResourceSearchIndex.Mode.BREADCRUMB));
    for (var request : requests) {
      var session = session();
      session.search(p, 0, text(""));
      session.searchDebounced(p, 0, text("missing"), 0);
      var result = session.searchDebounced(p, 0, request, 1);
      assertFalse(session.pending(), request.toString());
      assertEquals(new ResourceSearchIndex(p).search(request), result);
      assertTrue(session.matches(request));
    }
    var session = session();
    session.search(p, 0, text(""));
    session.searchDebounced(p, 0, text("missing"), 0);
    assertEquals(1, session.search(p, 0, text("source")).size());
    assertFalse(session.pending());
  }

  @Test
  void sameRevisionReplacementAndRevisionChangeNeverExposePredecessorHits() {
    var p = ResourceSearchIndexTest.fixture();
    var replacement = ResourceSearchIndexTest.fixture();
    replacement.noise2D.getLast().id = "new-owner";
    for (boolean replace : new boolean[] {false, true}) {
      var session = session();
      var old = session.search(p, 0, text(""));
      session.searchDebounced(p, 0, text("consumer"), 0);
      var result =
          session.searchDebounced(replace ? replacement : p, replace ? 0 : 1, text("consumer"), 1);
      assertFalse(session.pending());
      assertEquals(2, session.builds());
      assertTrue(
          result.stream().noneMatch(hit -> old.stream().anyMatch(previous -> previous == hit)));
      assertEquals(replace ? 0 : 1, result.size());
    }
  }

  @Test
  void hiddenCancellationRestartsTheQuietIntervalAndClearDropsTheIndex() {
    var p = ResourceSearchIndexTest.fixture();
    var session = session();
    session.search(p, 0, text(""));
    session.searchDebounced(p, 0, text("consumer"), 0);
    long version = session.presentationVersion();
    session.cancelPending();
    assertFalse(session.pending());
    assertTrue(session.presentationVersion() > version);
    session.searchDebounced(p, 0, text("consumer"), 1000);
    assertEquals(1, session.queries());
    assertTrue(session.pending());
    session.clear();
    assertEquals(0, session.indexed());
    assertFalse(session.pending());
    assertFalse(session.matches(text("")));
    assertEquals(1, session.searchDebounced(p, 0, text("consumer"), 1001).size());
    assertEquals(2, session.builds());
    assertFalse(session.pending());
  }

  @Test
  void clockRegressionRestartsWaitAndNanoTimeWrapStillCompletes() {
    var p = ResourceSearchIndexTest.fixture();
    var session = session();
    session.search(p, 0, text(""));
    session.searchDebounced(p, 0, text("consumer"), 100);
    session.searchDebounced(p, 0, text("consumer"), 90);
    session.searchDebounced(p, 0, text("consumer"), 239);
    assertTrue(session.pending());
    assertEquals(1, session.searchDebounced(p, 0, text("consumer"), 240).size());
    long start = Long.MAX_VALUE - 75;
    session.searchDebounced(p, 0, text("source"), start);
    assertEquals(1, session.searchDebounced(p, 0, text("source"), start + 150).size());
    assertFalse(session.pending());
    assertEquals(3, session.queries());
  }

  @Test
  void thresholdUsesIndexedRowsAndSmallProjectsRemainImmediate() {
    var p = ResourceSearchIndexTest.fixture();
    int size = new ResourceSearchIndex(p).size();
    for (int threshold : new int[] {size, size + 1}) {
      var session = new ResourceSearchSession(threshold, 150);
      session.search(p, 0, text(""));
      session.searchDebounced(p, 0, text("consumer"), 0);
      assertEquals(threshold == size, session.pending());
      assertEquals(threshold == size ? 1 : 2, session.queries());
    }
    assertEquals(1024, ResourceSearchSession.DEBOUNCE_ROWS);
    assertEquals(150_000_000L, ResourceSearchSession.DEBOUNCE_NANOS);
  }

  @Test
  void productionThresholdDefersAnActuallyLargeIndex() {
    var p = new ProjectData();
    for (int i = 0; i < 1024; i++) {
      var n = new Noise();
      n.id = "field-" + i;
      p.noise2D.add(n);
    }
    var session = new ResourceSearchSession();
    var all = session.search(p, 0, text(""));
    assertEquals(1024, all.size());
    assertSame(all, session.searchDebounced(p, 0, text("field-1023"), 0));
    assertTrue(session.pending());
    assertEquals(
        List.of("field-1023"),
        session.searchDebounced(p, 0, text("field-1023"), 150_000_000L).stream()
            .map(ResourceSearchIndex.Hit::label)
            .toList());
  }
}
