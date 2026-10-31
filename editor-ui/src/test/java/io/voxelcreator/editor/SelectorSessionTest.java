package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred selector opening, reviewed-value and consumed callback ownership contracts. */
class SelectorSessionTest {
  private final Object document = new Object(), project = new Object(), owner = new Object();

  private SelectorSession.Context context(String query, Object source) {
    return new SelectorSession.Context(
        document, project, 3, owner, 7, "alpha", "local", "None", source, query);
  }

  @Test
  void consecutiveReflowAndEqualRebuiltValuesRetainTheReviewedRows() {
    var session = new SelectorSession();
    session.open();
    var first = session.observe(4, context("beta", List.of("alpha", "beta")));
    var same = session.observe(5, context("beta", new ArrayList<>(List.of("alpha", "beta"))));
    assertEquals(first.version(), same.version());
    assertTrue(session.consume(first, 5, context("beta", List.of("alpha", "beta"))));
    assertFalse(session.consume(first, 5, first.context()));
  }

  @Test
  void rawQueryAndMutableChoiceChangesRetireHeldRowsWithoutAdoptingTheirIndex() {
    var values = new ArrayList<>(List.of("alpha", "beta"));
    var session = new SelectorSession();
    session.open();
    var first = session.observe(4, context("beta", values));
    var query = session.observe(5, context(" BETA ", values));
    assertNotEquals(first.version(), query.version());
    assertFalse(session.consume(first, 5, query.context()));
    values.set(1, "gamma");
    assertEquals(List.of("alpha", "beta"), first.context().source());
    assertFalse(session.consume(query, 5, context(" BETA ", values)));
    var refreshed = session.observe(6, context(" BETA ", values));
    assertNotEquals(query.version(), refreshed.version());
    assertTrue(session.consume(refreshed, 6, context(" BETA ", values)));
  }

  @Test
  void ownerCurrentNullabilityKindAndDocumentChangesRequireExplicitReopening() {
    var baseline = context("", List.of("alpha"));
    for (var next :
        List.of(
            new SelectorSession.Context(
                new Object(), project, 3, owner, 7, "alpha", "local", "None", List.of("alpha"), ""),
            new SelectorSession.Context(
                document,
                new Object(),
                3,
                owner,
                7,
                "alpha",
                "local",
                "None",
                List.of("alpha"),
                ""),
            new SelectorSession.Context(
                document, project, 4, owner, 7, "alpha", "local", "None", List.of("alpha"), ""),
            new SelectorSession.Context(
                document,
                project,
                3,
                new Object(),
                7,
                "alpha",
                "local",
                "None",
                List.of("alpha"),
                ""),
            new SelectorSession.Context(
                document, project, 3, owner, 8, "alpha", "local", "None", List.of("alpha"), ""),
            new SelectorSession.Context(
                document, project, 3, owner, 7, "beta", "local", "None", List.of("alpha"), ""),
            new SelectorSession.Context(
                document, project, 3, owner, 7, "alpha", "other", "None", List.of("alpha"), ""),
            new SelectorSession.Context(
                document, project, 3, owner, 7, "alpha", "local", null, List.of("alpha"), ""))) {
      var session = new SelectorSession();
      session.open();
      session.observe(4, baseline);
      assertNull(session.observe(5, next));
      assertFalse(session.active());
      session.open();
      assertNotNull(session.observe(6, next));
    }
  }

  @Test
  void aSkippedNativeFrameRetiresTheOpeningButAnotherExplicitOpeningIsAdmitted() {
    var session = new SelectorSession();
    var values = context("beta", List.of("beta"));
    session.open();
    var first = session.observe(10, values);
    assertNull(session.observe(12, values));
    assertFalse(session.consume(first, 12, values));
    session.open();
    var returned = session.observe(13, values);
    assertNotEquals(first.version(), returned.version());
    assertTrue(session.consume(returned, 13, values));
    assertTrue(session.failed(returned, "hidden retry"));
    assertFalse(session.retry(values, 15));
    session.open();
    session.observe(16, values);
    assertNull(session.observe(1, values));
  }

  @Test
  void failureRetryGetsAnotherGestureAndCannotOverrideAnExplicitSuccessor() {
    var session = new SelectorSession();
    var values = context("beta", List.of("beta"));
    session.open();
    var first = session.observe(4, values);
    assertFalse(session.failed(first, "not consumed"));
    assertTrue(session.consume(first, 4, values));
    assertTrue(session.failed(first, "boundary"));
    assertTrue(session.retry(values, 5));
    assertEquals("boundary", session.feedback());
    var retry = session.observe(5, values);
    assertNotEquals(first.version(), retry.version());
    assertFalse(session.consume(first, 5, values));
    assertTrue(session.consume(retry, 5, values));
    session.open();
    session.complete(retry);
    assertFalse(session.failed(retry, "obsolete"));
    assertEquals("", session.feedback());
    var successor = session.observe(6, values);
    assertTrue(session.consume(successor, 6, values));
    assertTrue(session.failed(successor, "changed query"));
    assertFalse(session.retry(context("different", List.of("beta")), 7));
    assertFalse(session.active());
    assertEquals("", session.feedback());
    session.open();
    var done = session.observe(8, values);
    assertTrue(session.consume(done, 8, values));
    session.complete(done);
    assertFalse(session.failed(done, "completed"));
  }
}
