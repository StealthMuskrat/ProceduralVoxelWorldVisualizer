package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.Curve;
import io.voxelcreator.model.ProjectIO;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred lifecycle definitions; no native context is needed for publication ownership. */
class CurveApplySessionTest {
  @Test
  void acceptedStagingClosesBeforeFollowUpAndCannotRepeat() {
    var stages = new AtomicInteger();
    var followUps = new AtomicInteger();
    var reference = new AtomicReference<CurveApplySession>();
    var publication =
        new CurveApplySession(
            c -> stages.incrementAndGet(),
            () -> {
              assertTrue(reference.get().closed());
              assertThrows(IllegalStateException.class, () -> reference.get().apply(new Curve()));
              followUps.incrementAndGet();
            });
    reference.set(publication);
    assertTrue(publication.apply(new Curve()));
    assertTrue(publication.closed());
    assertThrows(IllegalStateException.class, () -> publication.apply(new Curve()));
    publication.cancel();
    assertEquals(1, stages.get());
    assertEquals(1, followUps.get());
    assertEquals("", publication.error());
    assertNull(publication.followUpFailure());
  }

  @Test
  void followUpFailureRetainsSuccessAndItsExactFailureWithoutRestaging() {
    var failure = new IllegalStateException();
    var stages = new AtomicInteger();
    var publication =
        new CurveApplySession(
            c -> stages.incrementAndGet(),
            () -> {
              throw failure;
            });
    assertTrue(publication.apply(new Curve()));
    assertTrue(publication.closed());
    assertSame(failure, publication.followUpFailure());
    assertEquals("", publication.error());
    assertThrows(IllegalStateException.class, () -> publication.apply(new Curve()));
    assertEquals(1, stages.get());
  }

  @Test
  void rejectedStageKeepsCandidateCorrectableAndClearsErrorAfterRetry() {
    var attempts = new AtomicInteger();
    var followUps = new AtomicInteger();
    var curve = new Curve();
    curve.points.getLast().y = 3;
    var before = ProjectIO.JSON.valueToTree(curve);
    var publication =
        new CurveApplySession(
            c -> {
              if (attempts.incrementAndGet() == 1) throw new IllegalStateException();
            },
            followUps::incrementAndGet);
    assertFalse(publication.apply(curve));
    assertFalse(publication.closed());
    assertTrue(publication.error().contains("IllegalStateException"));
    assertEquals(0, followUps.get());
    assertEquals(before, ProjectIO.JSON.valueToTree(curve));
    assertTrue(publication.apply(curve));
    assertEquals(2, attempts.get());
    assertEquals(1, followUps.get());
    assertEquals("", publication.error());
  }

  @Test
  void invalidCurveNeverCallsStagingAndCanBeRepaired() {
    var stages = new AtomicInteger();
    var publication = new CurveApplySession(c -> stages.incrementAndGet(), () -> {});
    var curve = new Curve();
    curve.points.getLast().x = 0;
    assertFalse(publication.apply(curve));
    assertFalse(publication.closed());
    assertEquals(0, stages.get());
    curve.points.getLast().x = 1;
    assertTrue(publication.apply(curve));
    assertEquals(1, stages.get());
  }

  @Test
  void stagingReceivesAnOwnedCopyIncludingPoints() {
    var curve = new Curve();
    var before = ProjectIO.JSON.valueToTree(curve);
    var retained = new AtomicReference<Curve>();
    var publication =
        new CurveApplySession(
            c -> {
              assertNotSame(curve, c);
              assertNotSame(curve.points.getFirst(), c.points.getFirst());
              c.points.getLast().y = 9;
              retained.set(c);
            },
            () -> {});
    assertTrue(publication.apply(curve));
    retained.get().points.clear();
    assertEquals(before, ProjectIO.JSON.valueToTree(curve));
  }

  @Test
  void reentrantStageIsRejectedWithoutASecondPublication() {
    var reference = new AtomicReference<CurveApplySession>();
    var attempts = new AtomicInteger();
    var publication =
        new CurveApplySession(
            c -> {
              attempts.incrementAndGet();
              assertThrows(IllegalStateException.class, () -> reference.get().apply(c));
            },
            () -> {});
    reference.set(publication);
    assertTrue(publication.apply(new Curve()));
    assertEquals(1, attempts.get());
  }

  @Test
  void cancelledSessionsNeverStageAndErrorTextIsBounded() {
    var attempts = new AtomicInteger();
    var publication =
        new CurveApplySession(
            c -> {
              attempts.incrementAndGet();
              throw new IllegalArgumentException("x".repeat(10000));
            },
            () -> fail("Rejected edit ran follow-up"));
    assertFalse(publication.apply(new Curve()));
    assertEquals(2048, publication.error().length());
    publication.cancel();
    publication.cancel();
    assertTrue(publication.closed());
    assertThrows(IllegalStateException.class, () -> publication.apply(new Curve()));
    assertEquals(1, attempts.get());
  }
}
