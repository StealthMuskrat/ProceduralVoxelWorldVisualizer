package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.GenerationTrace;
import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.BlockPos;
import java.util.*;
import org.junit.jupiter.api.Test;

class TraceObservationSelectionTest {
  private TraceSnapshot trace() {
    var p = new ProjectData();
    TraceWorkflowProbe.fixture(p);
    var layer = p.worldGenerator.layers.getFirst();
    var op = layer.operations.getFirst();
    var step =
        new GenerationTrace.Step(
            layer.uuid,
            op.uuid,
            p.noise2D.getFirst().uuid,
            layer.id,
            op.id,
            22,
            0,
            1,
            "SET",
            "reason 🌋\n".repeat(1000),
            Map.of());
    return new TraceSnapshot(
        p, 3, new GenerationTrace(new BlockPos(2, 0, 3), 1, List.of(step, step)));
  }

  @Test
  void normalizedQueriesReuseTheirProjectionWithoutChangingTheSnapshot() {
    var trace = trace();
    var selection = new TraceObservationSelection();
    var rows = selection.observe(trace, 0, " Outcome ");
    assertSame(rows, selection.observe(trace, 0, "outcome"));
    assertEquals(1, selection.builds());
    assertEquals(List.of(0), rows);
    assertThrows(UnsupportedOperationException.class, () -> rows.clear());
    assertTrue(trace.rows(0).getFirst().value().contains("🌋"));
  }

  @Test
  void changedFiltersInvalidateThePreviousSelectedReviewInsteadOfFallingBackToIt() {
    var trace = trace();
    var selection = new TraceObservationSelection();
    selection.observe(trace, 0, "Outcome");
    var first = selection.review();
    selection.observe(trace, 0, "no-match");
    assertNull(selection.review());
    assertFalse(selection.current(first));
    selection.observe(trace, 0, "Primary field");
    assertEquals("Primary field", selection.review().value().label());
    assertFalse(selection.current(first));
  }

  @Test
  void anotherStepOrSnapshotCannotReuseAnEqualLookingObservationReview() {
    var trace = trace();
    var selection = new TraceObservationSelection();
    selection.observe(trace, 0, "Outcome");
    var first = selection.review();
    selection.observe(trace, 1, "Outcome");
    assertFalse(selection.current(first));
    assertNotSame(first.value(), selection.review().value());
    var second = selection.review();
    selection.observe(trace(), 1, "Outcome");
    assertFalse(selection.current(second));
    selection.clear();
    assertNull(selection.review());
  }

  @Test
  void explicitSelectionOwnsTheFullValueAndRejectsUnknownProjectionIndices() {
    var trace = trace();
    var selection = new TraceObservationSelection();
    var rows = selection.observe(trace, 0, "");
    selection.select(0);
    var review = selection.review();
    assertTrue(review.value().value().length() > 4096);
    assertTrue(selection.current(review));
    assertThrows(IllegalArgumentException.class, () -> selection.select(-1));
    assertThrows(IllegalArgumentException.class, () -> selection.select(rows.size()));
    assertTrue(selection.current(review));
    selection.select(1);
    assertFalse(selection.current(review));
    assertEquals("Layer", selection.review().value().label());
  }
}
