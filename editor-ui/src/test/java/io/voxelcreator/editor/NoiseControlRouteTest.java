package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class NoiseControlRouteTest {
  private final UUID project = UUID.randomUUID(),
      resource = UUID.randomUUID(),
      control = UUID.randomUUID();

  private String route(UUID item, String parameter, String pointer) {
    return DiagnosticNavigation.controlParameter(
        new EditorEvents.Target(project, resource, item, parameter, pointer, false));
  }

  @Test
  void sourceParametersUseAnExactLogicalKeyAcrossHistoricalPositions() {
    for (String root : List.of("noise2D", "noise3D", "noise"))
      assertEquals(
          "algorithm/parameters/value",
          route(null, "value", "/" + root + "/999/algorithm/parameters/value"));
  }

  @Test
  void itemParametersAndVectorComponentsRetainTheirDecodedNames() {
    for (String section : List.of("processing", "coordinateTransforms", "control")) {
      assertEquals(
          "parameters/a/b~c",
          route(control, "a/b~c", "/noise2D/99/" + section + "/999/parameters/a~1b~0c"));
      for (int i = 0; i < 3; i++)
        assertEquals(
            "parameters/value/" + i,
            route(control, "value", "/noise2D/99/" + section + "/999/parameters/value/" + i));
    }
  }

  @Test
  void sharedConstantsRetainAnExactComponentAndCompilerAlias() {
    assertEquals(
        "parameters/value/2", route(control, null, "/noise3D/0/sharedParameters/100/value/2"));
    assertEquals(
        "parameters/value/2", route(control, "value", "/noise3D/0/sharedParameters/100/value/2"));
    assertEquals(
        "parameters/value", route(control, "value", "/noise/uuid/control/uuid/parameters/value"));
    assertEquals("parameters/value", route(control, "value", ""));
  }

  @Test
  void bindingRoutesRemainExactAndAreNeverReinterpretedAsParameters() {
    assertEquals(
        "inputs/a/b", route(control, "inputs/a/b", "/noise2D/0/processing/0/parameters/a/type"));
  }

  @Test
  void unrelatedOrContradictoryPointersCannotOverrideAnExplicitParameter() {
    assertEquals("radius", route(control, "radius", "/runtime/parameters/amplitude"));
    assertEquals(
        "radius", route(control, "radius", "/noise2D/0/processing/0/parameters/amplitude"));
    assertEquals("frequency", route(null, "frequency", "/runtime/algorithm/parameters/frequency"));
    assertEquals(
        "parameters/axis",
        route(control, "parameters/axis", "/noise2D/0/processing/0/inputs/axis/type"));
  }

  @Test
  void existingCanonicalKeysAndUnsupportedLeafPathsRemainStable() {
    assertEquals("algorithm/parameters/value", route(null, "algorithm/parameters/value", ""));
    assertEquals("parameters/warpMode", route(control, "parameters/warpMode", ""));
    assertEquals(
        "parameters/value", route(control, "value", "/noise2D/0/processing/0/parameters/value/7"));
    assertNull(route(control, null, ""));
  }
}
