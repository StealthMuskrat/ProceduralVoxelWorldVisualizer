package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CurveModesTest {
  @Test
  void canonicalVocabularyIsImmutableAndContainsEveryHistoricalDefault() {
    assertEquals(List.of("STEP", "LINEAR", "SMOOTH", "BEZIER"), CurveModes.INTERPOLATIONS);
    assertEquals(List.of("CLAMP", "EXTEND", "REPEAT", "MIRROR"), CurveModes.OUTSIDE);
    assertEquals(List.of("AUTO", "ALIGNED", "FREE"), CurveModes.HANDLES);
    var curve = new ProjectData.Curve();
    var point = curve.points.getFirst();
    assertTrue(CurveModes.INTERPOLATIONS.contains(point.segmentInterpolation));
    assertTrue(CurveModes.HANDLES.contains(point.handleMode));
    assertTrue(CurveModes.OUTSIDE.contains(curve.outsideDomain));
    assertThrows(UnsupportedOperationException.class, () -> CurveModes.INTERPOLATIONS.add("OTHER"));
    assertThrows(
        UnsupportedOperationException.class, () -> CurveModes.registry().put("formatVersion", 9));
  }

  @Test
  void registryOwnsEveryOrderedChoiceAndItsBehaviorHelp() {
    var r = ProjectIO.JSON.valueToTree(CurveModes.registry());
    assertEquals(1, r.path("formatVersion").asInt());
    assertEquals(2, r.path("projectFormat").asInt());
    for (var entry :
        Map.of(
                "interpolation",
                CurveModes.INTERPOLATIONS,
                "outside",
                CurveModes.OUTSIDE,
                "handles",
                CurveModes.HANDLES)
            .entrySet()) {
      assertEquals(entry.getValue().size(), r.path(entry.getKey()).size());
      for (int i = 0; i < entry.getValue().size(); i++) {
        String key = entry.getValue().get(i);
        assertEquals(key, r.path(entry.getKey()).get(i).path("key").asText());
        assertFalse(CurveModes.help(key).isBlank());
        assertFalse(CurveModes.help(key).startsWith("Unknown"));
      }
    }
    ((com.fasterxml.jackson.databind.node.ObjectNode) r).put("projectFormat", 99);
    assertEquals(
        2, ProjectIO.JSON.valueToTree(CurveModes.registry()).path("projectFormat").asInt());
  }
}
