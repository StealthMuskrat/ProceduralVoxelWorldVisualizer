package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CurveControlFocusTest {
  private final Curve curve = new Curve();
  private final CurveControlFocus focus = new CurveControlFocus();

  CurveControlFocusTest() {
    curve.points.add(1, new Point(.5, .5));
  }

  @Test
  void pointAndCurveFieldsSubmitOnceAndAcknowledgeOnlyTheirOwnWidget() {
    for (var field : List.of("x", "y", "segmentInterpolation")) {
      focus.request(curve.points.get(1).uuid, field);
      var destination = focus.destination(curve);
      assertEquals(field, destination.key());
      assertTrue(focus.windowRequest());
      assertFalse(focus.windowRequest());
      assertFalse(focus.submit("other", destination));
      focus.acknowledge(field);
      assertNull(focus.acknowledged());
      assertTrue(focus.submit(field, destination));
      assertFalse(focus.submit(field, destination));
      focus.acknowledge("other");
      assertNull(focus.acknowledged());
      focus.acknowledge(field);
      assertEquals(field, focus.acknowledged());
      assertFalse(focus.matches(field));
    }
    for (var field : CurveNavigation.CURVE_FIELDS) {
      focus.request(null, field);
      assertEquals(field, focus.destination(curve).key());
    }
  }

  @Test
  void endpointsRouteToTheirDomainWhileInteriorXRemainsEditable() {
    focus.request(curve.points.getFirst().uuid, "x");
    assertEquals("min", focus.destination(curve).key());
    assertFalse(focus.destination(curve).explanation().isEmpty());
    focus.request(curve.points.getLast().uuid, "x");
    assertEquals("max", focus.destination(curve).key());
    focus.request(curve.points.get(1).uuid, "x");
    assertEquals("x", focus.destination(curve).key());
  }

  @Test
  void linearAndAutoTangentsRevealEditableModesWithoutChangingTheCurve() {
    var p = curve.points.get(1);
    var before = io.voxelcreator.model.ProjectIO.JSON.valueToTree(curve);
    focus.request(p.uuid, "inTangent");
    assertEquals("segmentInterpolation", focus.destination(curve).key());
    assertEquals(before, io.voxelcreator.model.ProjectIO.JSON.valueToTree(curve));
    curve.points.getFirst().segmentInterpolation = "BEZIER";
    focus.request(p.uuid, "outTangent");
    assertEquals("handleMode", focus.destination(curve).key());
    assertEquals("AUTO", p.handleMode);
    assertEquals("handleMode", focus.destination(curve).key());
    p.handleMode = "FREE";
    for (var field : List.of("inTangent", "outTangent", "handleMode")) {
      focus.request(p.uuid, field);
      assertEquals(field, focus.destination(curve).key());
    }
  }

  @Test
  void removedPointCancellationAndReplacementClearPendingFocus() {
    focus.request(curve.points.remove(1).uuid, "y");
    assertNull(focus.destination(curve));
    assertFalse(focus.windowRequest());
    focus.request(null, "min");
    assertTrue(focus.submit("min", focus.destination(curve)));
    focus.request(null, "displayMax");
    assertFalse(focus.matches("min"));
    assertTrue(focus.submit("displayMax", focus.destination(curve)));
    focus.cancel();
    assertNull(focus.destination(curve));
    assertNull(focus.acknowledged());
    assertFalse(focus.windowRequest());
  }

  @Test
  void unknownOrWrongScopeKeysAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> focus.request(null, "x"));
    assertThrows(
        IllegalArgumentException.class, () -> focus.request(curve.points.getFirst().uuid, "min"));
    assertThrows(IllegalArgumentException.class, () -> focus.request(null, "unknown"));
    assertThrows(IllegalArgumentException.class, () -> focus.request(null, null));
  }

  @Test
  void changingConditionalControlsBeforeAcknowledgementCancelsThePendingRequest() {
    var p = curve.points.get(1);
    focus.request(p.uuid, "inTangent");
    assertTrue(focus.submit("segmentInterpolation", focus.destination(curve)));
    p.segmentInterpolation = "BEZIER";
    assertNull(focus.destination(curve));
    assertFalse(focus.matches("segmentInterpolation"));
    assertFalse(focus.windowRequest());
  }
}
