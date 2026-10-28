package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class TransformDriverContractTest {
  private ProjectData fixture() throws Exception {
    return ProjectIO.JSON.readValue(
        Path.of("../fixtures/transform-drivers.json").toFile(), ProjectData.class);
  }

  @Test
  void historicalDriversDefaultToNoTransformAndNewReferencesRoundTrip() throws Exception {
    var old =
        ProjectIO.JSON.readValue(
            Path.of("../fixtures/placement-driver-phases.json").toFile(), ProjectData.class);
    assertEquals(
        -1,
        old.worldGenerator.layers.getFirst().defaults.parameterDrivers.getFirst().transformIndex);
    var p = fixture();
    var result = new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(p));
    assertTrue(result.success(), result.diagnostics().toString());
    assertEquals(
        1,
        result
            .project()
            .worldGenerator
            .layers
            .getFirst()
            .defaults
            .parameterDrivers
            .getLast()
            .transformIndex);
  }

  @Test
  void targetsMustExistAndReferencesRemainRequiredEvenWhenDisabled() throws Exception {
    var p = fixture();
    var d = p.worldGenerator.layers.getFirst().defaults.parameterDrivers.getFirst();
    d.enabled = false;
    for (int index : new int[] {-1, 2, 63, 64, Integer.MAX_VALUE}) {
      d.transformIndex = index;
      assertTrue(
          Validator.validate(p).stream()
              .anyMatch(x -> x.code().equals("INVALID_DRIVER_TRANSFORM")));
    }
    d.transformIndex = 0;
    d.noiseGeneratorUuid = null;
    assertTrue(
        Validator.validate(p).stream().anyMatch(x -> x.code().equals("MISSING_DRIVER_FIELD")));
  }

  @Test
  void pivotsApplyOnlyToScaleOrRotateAndPlacementTargetsRejectRowIndices() throws Exception {
    var p = fixture();
    var d = p.worldGenerator.layers.getFirst().defaults.parameterDrivers.getLast();
    d.transformIndex = 0;
    assertTrue(
        Validator.validate(p).stream().anyMatch(x -> x.code().equals("INVALID_DRIVER_TRANSFORM")));
    d.destination = "offsetX";
    assertTrue(
        Validator.validate(p).stream().anyMatch(x -> x.code().equals("INVALID_DRIVER_TRANSFORM")));
    d.transformIndex = -1;
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void transformIndicesRejectNullStringsFractionsAndMissingValues() throws Exception {
    var tree = ProjectIO.JSON.valueToTree(fixture());
    var d =
        (ObjectNode)
            tree.path("worldGenerator")
                .path("layers")
                .get(0)
                .path("defaults")
                .path("parameterDrivers")
                .get(0);
    for (var value :
        new com.fasterxml.jackson.databind.JsonNode[] {
          com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.nullNode(),
          ProjectIO.JSON.valueToTree("0"),
          ProjectIO.JSON.valueToTree(.5)
        }) {
      d.set("transformIndex", value);
      assertFalse(new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(tree)).success());
    }
    d.remove("transformIndex");
    assertFalse(new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(tree)).success());
  }

  @Test
  void effectiveTransformOverridesAreValidatedAgainstInheritedDrivers() throws Exception {
    var p = fixture();
    var op = p.worldGenerator.layers.getFirst().operations.getFirst();
    op.overrides.put("samplingTransforms", ProjectIO.JSON.createArrayNode());
    assertTrue(
        Validator.validate(p).stream().anyMatch(x -> x.code().equals("INVALID_DRIVER_TRANSFORM")));
    op.overrides.put("parameterDrivers", ProjectIO.JSON.createArrayNode());
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void componentSlotsAreIndependentAndRegistryMetadataIsOwned() throws Exception {
    var p = fixture();
    var settings = p.worldGenerator.layers.getFirst().defaults;
    assertEquals(1, WorldSemantics.parameter("transformX").value(settings, 1));
    assertNotEquals(
        WorldSemantics.parameter("transformX").slot(0),
        WorldSemantics.parameter("transformX").slot(1));
    assertNotEquals(
        WorldSemantics.parameter("transformX").slot(0),
        WorldSemantics.parameter("transformPivotX").slot(0));
    var definition =
        (java.util.Map<?, ?>) WorldSemantics.parameterRegistry().get("transformPivotX");
    assertEquals("pivotX", definition.get("component"));
    assertEquals(true, definition.get("transformIndexRequired"));
    assertThrows(UnsupportedOperationException.class, definition::clear);
  }
}
