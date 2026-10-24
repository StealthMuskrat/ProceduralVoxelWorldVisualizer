package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class CurveGestureTest {
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
