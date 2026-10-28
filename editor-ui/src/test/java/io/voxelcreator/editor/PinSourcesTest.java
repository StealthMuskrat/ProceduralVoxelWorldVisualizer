package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PinSourcesTest {
  @Test
  void canonicalBindingDescriptionsFollowCurrentUuidsAndRenamesWithoutCopyingValues() {
    var project = new ProjectData();
    var noise = new Noise();
    project.noise2D.add(noise);
    var parameter = new Parameter();
    parameter.id = "gain";
    parameter.value = ProjectIO.JSON.valueToTree(2);
    noise.sharedParameters.add(parameter);
    var binding = new Binding();
    binding.sourceKind = "Shared Parameter";
    binding.parameterUuid = parameter.uuid;
    var before = ProjectIO.JSON.valueToTree(binding);
    assertEquals("Shared Parameter: gain / Scalar", PinSources.input(noise, project, binding));
    parameter.id = "renamed";
    assertEquals("Shared Parameter: renamed / Scalar", PinSources.input(noise, project, binding));
    assertEquals(before, ProjectIO.JSON.valueToTree(binding));
    binding.sourceKind = "Ambient Value";
    binding.ambientKey = "x";
    assertEquals("Ambient Value: x / Scalar", PinSources.input(noise, project, binding));
    binding.sourceKind = "Noise Generator Field";
    binding.noiseGeneratorUuid = noise.uuid;
    noise.id = "field";
    assertEquals(
        "Noise Generator Field: field (2D) / Scalar", PinSources.input(noise, project, binding));
    binding.noiseGeneratorUuid = UUID.randomUUID();
    assertTrue(PinSources.input(noise, project, binding).contains("Missing noise generator"));
    assertEquals("Missing binding", PinSources.input(noise, project, null));
  }

  @Test
  void sourceRoutesUseCanonicalTypedValuesCurvesAndExactNamedBindings() {
    var noise = new Noise();
    var parameter = new Parameter();
    parameter.id = "p";
    noise.sharedParameters.add(parameter);
    var constant = ProcessingRegistry.create("CONSTANT", "value");
    var curve = ProcessingRegistry.create("CURVE", "curve");
    var expression = ProcessingRegistry.create("EXPRESSION", "expression");
    expression.inputs.put("a/b~c", Binding.constant(2));
    noise.processing.addAll(List.of(constant, curve, expression));
    var index = new NoiseDraftIndex(noise);
    assertEquals("value", PinSources.control(index.get(parameter.uuid), null));
    assertEquals("parameters/value", PinSources.control(index.get(constant.uuid), constant));
    assertEquals("curve", PinSources.control(index.get(curve.uuid), curve));
    assertEquals("inputs/a/b~c", PinSources.control(index.get(expression.uuid), expression));
  }

  @Test
  void metadataOrderFilteringAndUnpinningPreserveComputationWhileCanonicalValueChangesIt() {
    var project = new ProjectData();
    var noise = new Noise();
    noise.id = "field";
    project.noise2D.add(noise);
    var parameter = new Parameter();
    parameter.id = "gain";
    parameter.value = ProjectIO.JSON.valueToTree(2);
    noise.sharedParameters.add(parameter);
    var add = ProcessingRegistry.create("ADD", "add");
    var shared = new Binding();
    shared.sourceKind = "Shared Parameter";
    shared.parameterUuid = parameter.uuid;
    add.inputs.put("a", shared);
    add.inputs.put("b", Binding.constant(1));
    noise.processing.add(add);
    noise.outputBinding = Binding.earlier(add.uuid);
    for (var id : List.of(parameter.uuid, add.uuid)) {
      var pin = new Pin();
      pin.targetUuid = id;
      noise.pinnedControls.add(pin);
    }
    var browser = new PinBrowser();
    browser.synchronize(noise, 0);
    String identity = PreviewIdentity.of(project, 0, noise.uuid);
    assertEquals(3, new NoiseCompiler(project, 0).compile(noise.uuid).sample(0, 0, 0));
    noise.pinnedControls.getFirst().label = "alias";
    browser.move(1);
    browser.synchronize(noise, 1);
    browser.showGroup("empty");
    browser.remove();
    browser.synchronize(noise, 2);
    assertEquals(identity, PreviewIdentity.of(project, 0, noise.uuid));
    parameter.value = ProjectIO.JSON.valueToTree(5);
    assertNotEquals(identity, PreviewIdentity.of(project, 0, noise.uuid));
    assertEquals(6, new NoiseCompiler(project, 0).compile(noise.uuid).sample(0, 0, 0));
  }
}
