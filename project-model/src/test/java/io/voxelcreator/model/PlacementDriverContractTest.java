package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class PlacementDriverContractTest {
  @Test
  void samplingDestinationsRequireExplicitFieldsEvenWhenDisabled() throws Exception {
    var p =
        ProjectIO.JSON.readValue(
            Path.of("../fixtures/placement-driver-phases.json").toFile(), ProjectData.class);
    var d = p.worldGenerator.layers.getFirst().defaults.parameterDrivers.getFirst();
    d.noiseGeneratorUuid = null;
    for (boolean enabled : new boolean[] {false, true}) {
      d.enabled = enabled;
      assertTrue(
          Validator.validate(p).stream()
              .anyMatch(
                  x ->
                      x.code().equals("MISSING_DRIVER_FIELD")
                          && x.jsonPointer().endsWith("/noiseGeneratorUuid")));
    }
  }

  @Test
  void publishedPhasesAndListsAreImmutable() {
    assertEquals(WorldSemantics.Phase.SAMPLING, WorldSemantics.parameter("offsetZ").phase);
    assertTrue(WorldSemantics.parameter("probability").unitRange);
    assertFalse(WorldSemantics.parameter("threshold").explicitField);
    assertThrows(UnsupportedOperationException.class, () -> WorldSemantics.PARAMETERS.clear());
    assertThrows(
        UnsupportedOperationException.class, () -> WorldSemantics.parameterRegistry().clear());
  }

  @Test
  void signedAcceptanceMappingsRemainStructurallyValid() throws Exception {
    var p =
        ProjectIO.JSON.readValue(
            Path.of("../fixtures/placement-driver-phases.json").toFile(), ProjectData.class);
    var d = p.worldGenerator.layers.getFirst().defaults.parameterDrivers.get(1);
    d.combination = "ADD";
    d.outputMin = d.outputMax = -.5;
    assertTrue(Validator.validate(p).isEmpty());
  }
}
