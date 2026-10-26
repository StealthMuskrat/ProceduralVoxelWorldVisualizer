package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class CurveGestureTest {
  @Test
  void cancellationRestoresBaselineAndPreservesRedoAcrossRepeatedBegin() {
    var c = new Curve();
    c.points.add(1, new Point(.5, .5));
    var s = new CurveEditSession(c);
    var ids = Set.of(c.points.get(1).uuid);
    s.move(ids, .1, .2, false, false, .1, .1);
    s.endGesture();
    s.undo();
    assertTrue(s.canRedo());
    s.beginGesture();
    s.move(ids, .05, .1, false, false, .1, .1);
    s.beginGesture();
    s.move(ids, .1, .3, false, false, .1, .1);
    assertEquals(.6, s.curve().points.get(1).x, 1e-14);
    s.cancelGesture();
    assertEquals(.5, s.curve().points.get(1).x);
    assertEquals(.5, s.curve().points.get(1).y);
    assertFalse(s.canUndo());
    assertTrue(s.canRedo());
    s.redo();
    assertEquals(.7, s.curve().points.get(1).y, 1e-14);
  }

  @Test
  void cancellingTangentAndRejectedMovesRestoresExactCurveWithoutHistory() {
    var c = new Curve();
    c.points.getFirst().handleMode = "ALIGNED";
    var s = new CurveEditSession(c);
    var id = c.points.getFirst().uuid;
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(s.curve());
    assertTrue(s.tangent(id, 1, 7));
    assertEquals(7, s.curve().points.getFirst().inTangent);
    s.cancelGesture();
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(s.curve()));
    assertFalse(s.canUndo());
    assertFalse(s.move(Set.of(id), Double.NaN, Double.NaN, false, false, 1, 1));
    s.cancelGesture();
    assertEquals("", s.error());
    assertFalse(s.canUndo());
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(s.curve()));
  }

  @Test
  void discreteEditsEndPointerGestureBeforePublishingSeparateHistory() {
    var c = new Curve();
    c.points.add(1, new Point(.5, .5));
    var s = new CurveEditSession(c);
    var id = c.points.get(1).uuid;
    s.move(Set.of(id), .1, .2, false, false, 1, 1);
    assertTrue(s.edit(value -> value.displayMax = 2));
    s.undo();
    assertEquals(c.displayMax, s.curve().displayMax);
    assertEquals(.7, s.curve().points.get(1).y, 1e-14);
    s.undo();
    assertEquals(.5, s.curve().points.get(1).y);
    assertFalse(s.canUndo());
    s.redo();
    s.redo();
    assertEquals(2, s.curve().displayMax);
  }

  @Test
  void cumulativeGroupSnappingProducesOneUndoEntry() {
    var c = new Curve();
    c.points.add(1, new Point(.2, .2));
    c.points.add(2, new Point(.6, .6));
    var s = new CurveEditSession(c);
    var ids = Set.of(c.points.get(1).uuid, c.points.get(2).uuid);
    s.beginGesture();
    assertTrue(s.move(ids, .04, .04, true, true, .1, .1));
    assertEquals(.2, s.curve().points.get(1).x, 1e-14);
    assertTrue(s.move(ids, .06, .06, true, true, .1, .1));
    assertEquals(.3, s.curve().points.get(1).x, 1e-14);
    assertEquals(.7, s.curve().points.get(2).x, 1e-14);
    s.endGesture();
    s.undo();
    assertEquals(.2, s.curve().points.get(1).x);
    assertFalse(s.canUndo());
    s.redo();
    assertEquals(.7, s.curve().points.get(2).x, 1e-14);
    assertEquals(.2, c.points.get(1).x);
  }

  @Test
  void invalidCrossingRetainsLastValidGestureAndEndpointsStayLocked() {
    var c = new Curve();
    c.points.add(1, new Point(.5, .5));
    var s = new CurveEditSession(c);
    s.beginGesture();
    var ids = Set.of(c.points.getFirst().uuid, c.points.get(1).uuid);
    assertTrue(s.move(ids, .1, .2, false, false, .1, .1));
    assertEquals(0, s.curve().points.getFirst().x);
    assertEquals(.6, s.curve().points.get(1).x);
    assertFalse(s.move(ids, .8, .2, false, false, .1, .1));
    assertEquals(.6, s.curve().points.get(1).x);
    assertEquals(.2, s.curve().points.getFirst().y);
    s.endGesture();
    s.undo();
    assertEquals(.5, s.curve().points.get(1).x);
  }

  @Test
  void tangentModeAndLocalCancelIsolationAreEnforced() {
    var c = new Curve();
    var s = new CurveEditSession(c);
    UUID id = c.points.getFirst().uuid;
    s.beginGesture();
    assertFalse(s.tangent(id, 1, 3));
    s.endGesture();
    s.edit(
        curve -> {
          var point = CurveEditSession.point(curve, id);
          point.handleMode = "ALIGNED";
        });
    s.beginGesture();
    assertTrue(s.tangent(id, 1, 3));
    assertEquals(3, s.curve().points.getFirst().inTangent);
    s.endGesture();
    s.undo();
    assertEquals(0, s.curve().points.getFirst().outTangent);
    assertEquals("AUTO", c.points.getFirst().handleMode);
  }
}
