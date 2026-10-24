package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NumericDriverResourceTest {
  private static ProjectData project() {
    var p = new ProjectData();
    var a = new Noise();
    a.id = "primary";
    var b = new Noise();
    b.id = "control";
    p.noise2D.addAll(List.of(a, b));
    var layer = new Layer();
    layer.id = "layer";
    var op = new Operation();
    op.id = "placement";
    op.noiseGeneratorUuid = a.uuid;
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    var driver = new NumericDriver();
    driver.enabled = true;
    driver.destination = "baseHeight";
    driver.noiseGeneratorUuid = b.uuid;
    layer.defaults.parameterDrivers.add(driver);
    return p;
  }

  @Test
  void portableDependencyClosureAndCopyRemapNumericSource() {
    var p = project();
    var source = p.worldGenerator.layers.getFirst();
    var payload = ResourceGraph.selection(p, List.of(source.uuid), true);
    assertEquals(2, payload.noise2D.size());
    var plan = ResourceImport.plan(new ProjectData(), payload, Map.of());
    assertTrue(plan.valid(), plan.diagnostics().toString());
    var copy = plan.candidate();
    var driver = copy.worldGenerator.layers.getFirst().defaults.parameterDrivers.getFirst();
    assertNotEquals(
        source.defaults.parameterDrivers.getFirst().noiseGeneratorUuid, driver.noiseGeneratorUuid);
    assertTrue(copy.noise2D.stream().anyMatch(n -> n.uuid.equals(driver.noiseGeneratorUuid)));
    assertTrue(Validator.validate(copy).isEmpty());
  }

  @Test
  void deleteReplacementReachesDefaultsAndPlacementOverridesAndUndo() {
    var p = project();
    var layer = p.worldGenerator.layers.getFirst();
    var op = layer.operations.getFirst();
    op.overrides.put(
        "parameterDrivers", ProjectIO.JSON.valueToTree(layer.defaults.parameterDrivers));
    var original = p.noise2D.getLast().uuid;
    var replacement = p.noise2D.getFirst().uuid;
    var document = new DocumentSession();
    assertTrue(document.edit(next -> ResourceGraph.assign(next, ProjectIO.copy(p))).isEmpty());
    assertTrue(document.edit(next -> ResourceGraph.delete(next, original, replacement)).isEmpty());
    var changed = document.data().worldGenerator.layers.getFirst();
    assertEquals(replacement, changed.defaults.parameterDrivers.getFirst().noiseGeneratorUuid);
    assertEquals(
        replacement,
        SettingsResolver.resolve(changed, changed.operations.getFirst())
            .parameterDrivers
            .getFirst()
            .noiseGeneratorUuid);
    document.undo();
    assertEquals(
        original,
        document
            .data()
            .worldGenerator
            .layers
            .getFirst()
            .defaults
            .parameterDrivers
            .getFirst()
            .noiseGeneratorUuid);
    document.redo();
    assertEquals(
        replacement,
        document
            .data()
            .worldGenerator
            .layers
            .getFirst()
            .defaults
            .parameterDrivers
            .getFirst()
            .noiseGeneratorUuid);
  }

  @Test
  void orderedDriverEditIsOneTransactionAndEmptyOverrideRestoresLiveDefaults() {
    var p = project();
    var layer = p.worldGenerator.layers.getFirst();
    var document = new DocumentSession();
    assertTrue(document.edit(next -> ResourceGraph.assign(next, ProjectIO.copy(p))).isEmpty());
    assertTrue(
        document
            .edit(
                next -> {
                  var d = new NumericDriver();
                  d.outputMin = d.outputMax = -4;
                  next.worldGenerator.layers.getFirst().defaults.parameterDrivers.add(d);
                })
            .isEmpty());
    assertEquals(
        2, document.data().worldGenerator.layers.getFirst().defaults.parameterDrivers.size());
    document.undo();
    assertEquals(
        1, document.data().worldGenerator.layers.getFirst().defaults.parameterDrivers.size());
    document.redo();
    assertEquals(
        -4,
        document
            .data()
            .worldGenerator
            .layers
            .getFirst()
            .defaults
            .parameterDrivers
            .getLast()
            .outputMin);
    var op = layer.operations.getFirst();
    op.overrides.put("parameterDrivers", ProjectIO.JSON.createArrayNode());
    assertTrue(SettingsResolver.resolve(layer, op).parameterDrivers.isEmpty());
    op.overrides.remove("parameterDrivers");
    assertEquals(1, SettingsResolver.resolve(layer, op).parameterDrivers.size());
  }
}
