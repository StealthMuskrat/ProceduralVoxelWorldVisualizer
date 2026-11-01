package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import org.junit.jupiter.api.Test;

/** Deferred exact local-history and ownership expectations for numeric slider previews. */
class CurveNumericGestureTest {
  private final DialogBounds.Rect bounds = new DialogBounds.Rect(10, 20, 200, 24);
  private final CurveEditSession session = new CurveEditSession(new Curve());
  private final CurveNumericGesture slider = new CurveNumericGesture(session);

  @Test
  void manyAcceptedFramesAreExactlyOneUndoAndRedo() {
    var before = ProjectIO.JSON.valueToTree(session.curve());
    for (int i = 1; i <= 100; i++) {
      double y = 1 + i * .03125;
      slider.beginFrame();
      assertTrue(slider.edit("last:y", bounds, c -> c.points.getLast().y = y));
      slider.observe("last:y", bounds, true, true);
      slider.endFrame();
      assertFalse(session.canUndo());
    }
    assertEquals(4.125, new ScalarCurve(session.curve()).sample(1));
    slider.observe("last:y", bounds, false, false);
    var after = ProjectIO.JSON.valueToTree(session.curve());
    session.undo();
    assertEquals(before, ProjectIO.JSON.valueToTree(session.curve()));
    assertFalse(session.canUndo());
    session.redo();
    assertEquals(after, ProjectIO.JSON.valueToTree(session.curve()));
  }

  @Test
  void cancelledHeldInputPreservesRedoAndRequiresReleaseBeforeAnotherEdit() {
    session.edit(c -> c.points.getLast().y = 2);
    session.undo();
    var before = ProjectIO.JSON.valueToTree(session.curve());
    slider.edit("last:y", bounds, c -> c.points.getLast().y = 3);
    slider.cancel();
    assertEquals(before, ProjectIO.JSON.valueToTree(session.curve()));
    assertTrue(session.canRedo());
    assertFalse(session.canUndo());
    assertFalse(slider.edit("last:y", bounds, c -> c.points.getLast().y = 4));
    slider.observe("last:y", bounds, false, true);
    assertTrue(slider.blocked("last:y"));
    slider.observe("last:y", bounds, false, false);
    assertTrue(slider.edit("last:y", bounds, c -> c.points.getLast().y = 4));
    slider.end();
    assertFalse(session.canRedo());
    session.undo();
    assertEquals(before, ProjectIO.JSON.valueToTree(session.curve()));
  }

  @Test
  void rejectedOrderingKeepsLastValidPreviewAndOneHistoryEntry() {
    var curve = new Curve();
    curve.points.add(1, new Point(.5, .5));
    var edit = new CurveEditSession(curve);
    var drag = new CurveNumericGesture(edit);
    var point = curve.points.get(1).uuid;
    assertTrue(drag.edit("point:x", bounds, c -> CurveEditSession.point(c, point).x = .625));
    assertFalse(drag.edit("point:x", bounds, c -> CurveEditSession.point(c, point).x = 1));
    assertEquals(.625, CurveEditSession.point(edit.curve(), point).x);
    assertFalse(edit.error().isEmpty());
    drag.observe("point:x", bounds, false, false);
    edit.undo();
    assertEquals(.5, CurveEditSession.point(edit.curve(), point).x);
    assertFalse(edit.canUndo());
  }

  @Test
  void externalDiscreteEditsAreNotRolledBackOrFollowedByAStaleHeldSlider() {
    slider.edit("last:y", bounds, c -> c.points.getLast().y = 2);
    session.edit(c -> c.displayMax = 4);
    var newer = ProjectIO.JSON.valueToTree(session.curve());
    slider.beginFrame();
    assertFalse(slider.active());
    assertTrue(slider.blocked("last:y"));
    slider.cancel();
    assertFalse(slider.edit("last:y", bounds, c -> c.points.getLast().y = 8));
    assertEquals(newer, ProjectIO.JSON.valueToTree(session.curve()));
  }

  @Test
  void changedGeometryAndMissingControlsRollbackOnlyTheirOwnedPreview() {
    var before = ProjectIO.JSON.valueToTree(session.curve());
    slider.edit("last:y", bounds, c -> c.points.getLast().y = 2);
    assertFalse(
        slider.edit(
            "last:y", new DialogBounds.Rect(10, 20, 120, 24), c -> c.points.getLast().y = 3));
    assertEquals(before, ProjectIO.JSON.valueToTree(session.curve()));
    slider.observe("last:y", bounds, false, false);
    slider.edit("last:y", bounds, c -> c.points.getLast().y = 2);
    slider.beginFrame();
    slider.endFrame();
    assertEquals(before, ProjectIO.JSON.valueToTree(session.curve()));
    assertFalse(session.canUndo());
  }

  @Test
  void domainAndEndpointAndAlignedSlopesRemainAtomicWithinNumericHistory() {
    assertTrue(
        slider.edit(
            "min",
            bounds,
            c -> {
              c.min = -1;
              c.points.getFirst().x = -1;
            }));
    slider.observe("min", bounds, false, false);
    assertEquals(.5, new ScalarCurve(session.curve()).sample(0));
    session.undo();
    assertEquals(0, session.curve().min);
    session.edit(
        c -> {
          c.points.getFirst().segmentInterpolation = "BEZIER";
          c.points.getLast().handleMode = "ALIGNED";
        });
    slider.edit(
        "last:in",
        bounds,
        c -> {
          c.points.getLast().inTangent = -2;
          c.points.getLast().outTangent = -2;
        });
    slider.edit(
        "last:in",
        bounds,
        c -> {
          c.points.getLast().inTangent = 3;
          c.points.getLast().outTangent = 3;
        });
    slider.observe("last:in", bounds, false, false);
    assertEquals(3, session.curve().points.getLast().outTangent);
    session.undo();
    assertEquals(0, session.curve().points.getLast().inTangent);
    assertEquals(0, session.curve().points.getLast().outTangent);
  }
}
