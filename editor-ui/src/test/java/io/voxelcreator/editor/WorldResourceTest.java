package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorldResourceTest {
  private static ProjectData project() {
    var p = new ProjectData();
    var noise = new Noise();
    noise.id = "main";
    p.noise2D.add(noise);
    var driver = new Noise();
    driver.id = "driver";
    p.noise2D.add(driver);
    var a = new VoxelType();
    a.id = "a";
    var b = new VoxelType();
    b.id = "b";
    p.voxelTypes.addAll(List.of(a, b));
    var layer = new Layer();
    layer.id = "source";
    layer.defaults.action = "SET";
    layer.defaults.typeSource = "INTERVALS";
    var interval = new TypeInterval();
    interval.voxelTypeUuid = a.uuid;
    layer.defaults.typeIntervals.add(interval);
    layer.defaults.strengthDriver.enabled = true;
    layer.defaults.strengthDriver.noiseGeneratorUuid = driver.uuid;
    var op = new Operation();
    op.id = "use";
    op.interpretation = "TYPE_SELECTION";
    op.noiseGeneratorUuid = noise.uuid;
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    return p;
  }

  @Test
  void portableClosureAndCopyRemapDriverAndIntervalDependencies() {
    var p = project();
    var source = p.worldGenerator.layers.getFirst();
    var payload = ResourceGraph.selection(p, List.of(source.uuid), true);
    assertEquals(2, payload.noise2D.size());
    assertEquals(1, payload.voxelTypes.size());
    var copy = ResourceImport.plan(new ProjectData(), payload, Map.of());
    assertTrue(copy.valid(), copy.diagnostics().toString());
    var result = copy.candidate();
    var layer = result.worldGenerator.layers.getFirst();
    assertNotEquals(source.uuid, layer.uuid);
    assertTrue(
        result.noise2D.stream()
            .anyMatch(n -> n.uuid.equals(layer.defaults.strengthDriver.noiseGeneratorUuid)));
    assertEquals(
        result.voxelTypes.getFirst().uuid, layer.defaults.typeIntervals.getFirst().voxelTypeUuid);
    assertEquals(List.of(), Validator.validate(result));
  }

  @Test
  void replacementAndUndoReachNestedNewSettingsAtomically() {
    var p = project();
    var original = p.voxelTypes.getFirst().uuid;
    var replacement = p.voxelTypes.getLast().uuid;
    var document = new DocumentSession();
    assertTrue(document.edit(next -> ResourceGraph.assign(next, ProjectIO.copy(p))).isEmpty());
    assertTrue(document.edit(next -> ResourceGraph.delete(next, original, replacement)).isEmpty());
    assertEquals(
        replacement,
        document
            .data()
            .worldGenerator
            .layers
            .getFirst()
            .defaults
            .typeIntervals
            .getFirst()
            .voxelTypeUuid);
    document.undo();
    assertEquals(
        original,
        document
            .data()
            .worldGenerator
            .layers
            .getFirst()
            .defaults
            .typeIntervals
            .getFirst()
            .voxelTypeUuid);
    var driver = p.noise2D.getLast().uuid;
    assertFalse(ResourceGraph.dependents(document.data(), Set.of(driver)).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceGraph.delete(ProjectIO.copy(document.data()), driver, null));
  }

  @Test
  void movingAPlacementPreservesOverridesAndResolvesDestinationDefaults() {
    var p = project();
    var source = p.worldGenerator.layers.getFirst();
    var op = source.operations.getFirst();
    var destination = new Layer();
    destination.id = "destination";
    destination.defaults.axisX = "Z";
    destination.defaults.strength = .4;
    destination.defaults.typeSource = "INTERVALS";
    destination.defaults.typeIntervals = SettingsResolver.resolve(source, op).typeIntervals;
    p.worldGenerator.layers.add(destination);
    op.overrides.put("axisX", ProjectIO.JSON.valueToTree("-Y"));
    op.overrides.put(
        "samplingTransforms", ProjectIO.JSON.valueToTree(List.of(new SamplingTransform())));
    new ResourceClipboard(p, List.of(op.uuid), true).paste(p, "operation", destination.uuid);
    assertTrue(source.operations.isEmpty());
    var resolved = SettingsResolver.resolve(destination, destination.operations.getFirst());
    assertEquals("-Y", resolved.axisX);
    assertEquals(.4, resolved.strength);
    assertEquals(1, resolved.samplingTransforms.size());
    op.overrides.remove("axisX");
    assertEquals("Z", SettingsResolver.resolve(destination, op).axisX);
  }
}
