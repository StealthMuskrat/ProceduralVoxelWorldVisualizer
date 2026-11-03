package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PinControlsTest {
  private ProjectData fixture() {
    var project = new ProjectData();
    var noise = new Noise();
    noise.id = "pins";
    var item = new Item();
    item.id = "sum";
    item.inputs.put("a", Binding.source());
    item.inputs.put("b", Binding.constant(2));
    noise.processing.add(item);
    noise.outputBinding = Binding.earlier(item.uuid);
    project.noise2D.add(noise);
    return project;
  }

  @Test
  void omittedControlKeepsLegacyWholeItemPinsReadable() throws Exception {
    var project = fixture();
    var noise = project.noise2D.getFirst();
    assertTrue(PinControls.add(noise, noise.processing.getFirst().uuid, "", "Sum"));
    var tree = (ObjectNode) ProjectIO.JSON.valueToTree(project);
    ((ObjectNode) tree.path("noise2D").get(0).path("pinnedControls").get(0)).remove("control");
    var loaded = new ProjectIO().parse(tree.toString());
    assertTrue(loaded.success(), loaded.diagnostics().toString());
    assertEquals("", loaded.project().noise2D.getFirst().pinnedControls.getFirst().control);
  }

  @Test
  void separateControlsOnOneItemRetainTheirOwnIdentityWithoutCopiedValues() {
    var noise = fixture().noise2D.getFirst();
    var item = noise.processing.getFirst();
    assertTrue(PinControls.add(noise, item.uuid, "", "Whole item"));
    assertTrue(PinControls.add(noise, item.uuid, "inputs/a", "Source"));
    assertTrue(PinControls.add(noise, item.uuid, "inputs/b", "Gain"));
    assertFalse(PinControls.add(noise, item.uuid, "inputs/b", "Duplicate label"));
    assertEquals(3, noise.pinnedControls.size());
    assertEquals(List.of("inputs/a", "inputs/b"), PinControls.paths(item));
    for (var pin : noise.pinnedControls) {
      var tree = ProjectIO.JSON.valueToTree(pin);
      assertEquals(
          Set.of("targetUuid", "control", "label", "group"),
          ProjectIO.JSON.convertValue(tree, Map.class).keySet());
      assertFalse(tree.has("value"));
    }
    item.inputs.get("b").value = ProjectIO.JSON.valueToTree(7);
    assertSame(item, PinControls.item(noise, noise.pinnedControls.getLast().targetUuid));
    assertEquals(7, item.inputs.get("b").value.intValue());
  }

  @Test
  void parameterPathsFollowLiveBindingsAndReturnToTheirOriginalLiteral() {
    var item = new Item();
    item.parameters.put("scale", ProjectIO.JSON.valueToTree(.125));
    assertEquals("parameters/scale", PinControls.resolvedPath(item, "parameters/scale"));
    item.inputs.put("scale", Binding.constant(4));
    assertEquals("inputs/scale", PinControls.resolvedPath(item, "parameters/scale"));
    assertEquals(List.of("parameters/scale"), PinControls.paths(item));
    item.inputs.remove("scale");
    assertEquals("parameters/scale", PinControls.resolvedPath(item, "parameters/scale"));
    assertEquals(.125, item.parameters.get("scale").doubleValue());
  }

  @Test
  void invalidPathsAndMissingTargetsProduceIndexedDiagnosticsWithoutMutatingResources() {
    for (String path : Arrays.asList(null, "inputs/", "parameters/", "inputs/missing", "wrong/b")) {
      var project = fixture();
      var noise = project.noise2D.getFirst();
      var pin = new Pin();
      pin.targetUuid = noise.processing.getFirst().uuid;
      pin.control = path;
      noise.pinnedControls.add(pin);
      var before = ProjectIO.JSON.valueToTree(project);
      assertTrue(
          Validator.validate(project).stream()
              .anyMatch(d -> d.jsonPointer().equals("/noise2D/0/pinnedControls/0/control")));
      assertEquals(before, ProjectIO.JSON.valueToTree(project));
    }
    var project = fixture();
    var pin = new Pin();
    project.noise2D.getFirst().pinnedControls.add(pin);
    assertTrue(
        Validator.validate(project).stream()
            .anyMatch(d -> d.jsonPointer().equals("/noise2D/0/pinnedControls/0/targetUuid")));
  }

  @Test
  void copyingRemapsTheOriginalItemAndKeepsControlPathsAndSourceProjectIntact() {
    var project = fixture();
    var noise = project.noise2D.getFirst();
    var item = noise.processing.getFirst();
    PinControls.add(noise, item.uuid, "inputs/b", "Gain");
    var before = ProjectIO.JSON.valueToTree(project);
    var copy = ResourceTransactions.duplicateMapped(project);
    var copied = copy.project().noise2D.getFirst();
    var pin = copied.pinnedControls.getFirst();
    assertEquals(copy.identities().get(item.uuid), pin.targetUuid);
    assertEquals(copied.processing.getFirst().uuid, pin.targetUuid);
    assertEquals("inputs/b", pin.control);
    assertEquals(before, ProjectIO.JSON.valueToTree(project));
    assertTrue(Validator.validate(copy.project()).isEmpty());
  }

  @Test
  void sharedParametersAcceptOnlyTheirValueAndResolveNoItem() {
    var noise = fixture().noise2D.getFirst();
    var parameter = new Parameter();
    parameter.id = "gain";
    parameter.value = ProjectIO.JSON.valueToTree(2);
    noise.sharedParameters.add(parameter);
    PinControls.add(noise, parameter.uuid, "", "Gain");
    var pin = noise.pinnedControls.getFirst();
    pin.control = "value";
    assertNull(PinControls.problem(noise, pin));
    assertNull(PinControls.item(noise, pin.targetUuid));
    pin.control = "inputs/value";
    assertNotNull(PinControls.problem(noise, pin));
  }

  @Test
  void implicitConstantValueCanBePinnedWithoutMaterializingItsDefault() {
    var noise = fixture().noise2D.getFirst();
    var item = new Item();
    item.kind = "CONSTANT";
    item.id = "constant";
    noise.processing.add(item);
    assertEquals(List.of("parameters/value"), PinControls.paths(item));
    assertTrue(PinControls.add(noise, item.uuid, "parameters/value", "Literal"));
    assertEquals("parameters/value", PinControls.resolvedPath(item, "parameters/value"));
    assertTrue(item.parameters.isEmpty());
  }
}
