package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CurvePointEditingTest {
  @Test
  void insertionSamplesCanonicalCurveAndOneUndoRetainsTheNewIdentity() {
    var original = new Curve();
    original.points.getLast().y = 3;
    var s = new CurveEditSession(original);
    UUID id = s.insert(.47, true, false, .1, .1);
    assertNotNull(id);
    var p = CurveEditSession.point(s.curve(), id);
    assertEquals(.5, p.x);
    assertEquals(1.5, p.y);
    for (int i = 0; i <= 100; i++)
      assertEquals(3 * i / 100., new ScalarCurve(s.curve()).sample(i / 100.), 1e-14);
    s.undo();
    assertEquals(2, s.curve().points.size());
    assertFalse(s.canUndo());
    s.redo();
    assertEquals(id, s.curve().points.get(1).uuid);
    assertEquals(2, original.points.size());
  }

  @Test
  void insertionInheritsStepAndOnlyExplicitYSnapChangesItsValue() {
    var c = new Curve();
    c.points.getFirst().segmentInterpolation = "STEP";
    c.points.getFirst().y = -.23;
    var s = new CurveEditSession(c);
    UUID id = s.insert(.4, false, false, .1, .1);
    assertEquals("STEP", CurveEditSession.point(s.curve(), id).segmentInterpolation);
    assertEquals(-.23, new ScalarCurve(s.curve()).sample(.9));
    s.undo();
    UUID snapped = s.insert(.4, false, true, .1, .1);
    assertEquals(-.2, CurveEditSession.point(s.curve(), snapped).y);
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(s.curve());
    assertNull(s.insert(.4, false, false, 1, 1));
    assertNull(s.insert(.99, true, false, 1, 1));
    assertNull(s.insert(.6, true, false, Double.POSITIVE_INFINITY, 1));
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(s.curve()));
  }

  @Test
  void groupDeletionPreservesEndpointsAndUndoRestoresExactIdentities() {
    var c = new Curve();
    c.points.add(1, new Point(.3, .4));
    c.points.add(2, new Point(.7, .8));
    var ids = c.points.stream().map(p -> p.uuid).toList();
    var s = new CurveEditSession(c);
    assertTrue(s.removeInterior(new HashSet<>(ids)));
    assertEquals(
        List.of(ids.getFirst(), ids.getLast()),
        s.curve().points.stream().map(p -> p.uuid).toList());
    s.undo();
    assertEquals(ids, s.curve().points.stream().map(p -> p.uuid).toList());
    assertTrue(s.removeInterior(Set.of(ids.getFirst(), ids.getLast())));
    assertFalse(s.canUndo());
    assertTrue(s.canRedo());
    s.redo();
    assertEquals(2, s.curve().points.size());
  }

  @Test
  void relativeAlignedTangentsHaveNoClickJumpAndUseCumulativeBaseline() {
    var c = curve("ALIGNED");
    var s = new CurveEditSession(c);
    UUID id = c.points.get(1).uuid;
    assertTrue(s.moveTangent(id, 1, 0, 1. / 6));
    s.endGesture();
    assertFalse(s.canUndo());
    assertTrue(s.moveTangent(id, 1, .05, 1. / 6));
    assertTrue(s.moveTangent(id, 1, .1, 1. / 6));
    s.endGesture();
    assertEquals(1.6, s.curve().points.get(1).outTangent, 1e-14);
    assertEquals(1.6, s.curve().points.get(1).inTangent, 1e-14);
    s.undo();
    assertEquals(1, s.curve().points.get(1).outTangent);
    assertFalse(s.canUndo());
    s.redo();
    assertEquals(1.6, s.curve().points.get(1).outTangent, 1e-14);
  }

  @Test
  void freeAndAutoModesAndDegenerateHandleFailuresRemainIsolated() {
    var c = curve("FREE");
    var s = new CurveEditSession(c);
    UUID id = c.points.get(1).uuid;
    assertTrue(s.moveTangent(id, -1, .1, -1. / 6));
    assertEquals(.4, s.curve().points.get(1).inTangent, 1e-14);
    assertEquals(1, s.curve().points.get(1).outTangent);
    s.cancelGesture();
    assertFalse(s.moveTangent(id, 1, 1, 0));
    assertFalse(s.moveTangent(id, 0, 1, 1));
    assertFalse(s.moveTangent(id, 1, Double.NaN, 1));
    s.cancelGesture();
    assertFalse(s.canUndo());
    var auto = new CurveEditSession(curve("AUTO"));
    assertFalse(auto.moveTangent(auto.curve().points.get(1).uuid, 1, .1, 1. / 6));
    auto.endGesture();
    assertFalse(auto.canUndo());
  }

  private static Curve curve(String mode) {
    var c = new Curve();
    c.points.add(1, new Point(.5, .5));
    for (var p : c.points) {
      p.segmentInterpolation = "BEZIER";
      p.handleMode = mode;
      p.inTangent = p.outTangent = 1;
    }
    return c;
  }
}
