package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NoiseDraftIndexTest {
  static Noise noise() {
    var n = new Noise();
    n.id = "field";
    for (String type : NoiseAuthoring.TYPES) {
      var constant = ProcessingRegistry.create("CONSTANT", type);
      constant.resultType = type;
      constant.parameters.put("value", NoiseAuthoring.defaultValue(type));
      n.processing.add(constant);
      var parameter = new Parameter();
      parameter.id = "parameter-" + type;
      parameter.type = type;
      parameter.value = NoiseAuthoring.defaultValue(type);
      n.sharedParameters.add(parameter);
    }
    var add = ProcessingRegistry.create("ADD", "last");
    n.processing.add(add);
    return n;
  }

  @Test
  void earlierResultsRequireSameTypeEnabledAndStrictlyEarlierPosition() {
    var n = noise();
    var target = n.processing.getLast();
    var index = new NoiseDraftIndex(n);
    for (String type : NoiseAuthoring.TYPES) {
      var values = index.choices("earlier", target.uuid, type);
      assertEquals(1, values.size());
      assertEquals(type, index.get(UUID.fromString(values.getFirst().key())).type());
    }
    assertTrue(index.choices("earlier", n.processing.getFirst().uuid, "Scalar").isEmpty());
    n.processing.getFirst().enabled = false;
    assertTrue(new NoiseDraftIndex(n).choices("earlier", target.uuid, "Scalar").isEmpty());
  }

  @Test
  void coordinateResultsNeverLeakIntoPostSourceChoices() {
    var n = noise();
    var transform = new Item();
    transform.id = "transform";
    transform.kind = "TRANSLATE";
    transform.resultType = "Coordinates2D";
    n.coordinateTransforms.add(transform);
    var after = new Item();
    after.id = "after";
    after.kind = "TRANSLATE";
    after.resultType = "Coordinates2D";
    n.coordinateTransforms.add(after);
    var index = new NoiseDraftIndex(n);
    assertEquals(
        List.of(transform.uuid.toString()),
        index.choices("earlier", after.uuid, "Coordinates2D").stream()
            .map(DraftChoicePicker.Choice::key)
            .toList());
    assertEquals(1, index.choices("earlier", n.processing.getLast().uuid, "Coordinates2D").size());
    assertFalse(
        index.choices("earlier", n.processing.getLast().uuid, "Coordinates2D").stream()
            .anyMatch(c -> c.key().equals(transform.uuid.toString())));
  }

  @Test
  void parametersAreTypeFilteredAndOutputIsEnabledScalarOnly() {
    var n = noise();
    var index = new NoiseDraftIndex(n);
    for (String type : NoiseAuthoring.TYPES)
      assertEquals(1, index.choices("parameter", null, type).size());
    assertEquals(2, index.choices("output", null, "Scalar").size());
    n.processing.getLast().enabled = false;
    assertEquals(1, new NoiseDraftIndex(n).choices("output", null, "Scalar").size());
  }

  @Test
  void prefixChoicesUseScalarEarlierResultsRatherThanSampledNumericValues() {
    var n = noise();
    var sampler = ProcessingRegistry.create("OFFSET_SAMPLE", "sample");
    n.processing.add(sampler);
    var index = new NoiseDraftIndex(n);
    assertEquals(2, index.choices("prefix", sampler.uuid, "Scalar").size());
    assertTrue(index.choices("prefix", n.processing.getFirst().uuid, "Scalar").isEmpty());
  }

  @Test
  void usersIdentifyOutputInputsAndPinsWithoutStringFalsePositives() {
    var n = noise();
    var source = n.processing.getFirst();
    var target = n.processing.getLast();
    target.inputs.put("a", Binding.earlier(source.uuid));
    n.outputBinding = Binding.earlier(source.uuid);
    target.parameters.put("expression", ProjectIO.JSON.valueToTree(source.uuid.toString()));
    var pin = new Pin();
    pin.targetUuid = source.uuid;
    pin.label = "frequent";
    n.pinnedControls.add(pin);
    var index = new NoiseDraftIndex(n);
    var users = index.users(source.uuid);
    assertEquals(3, users.size());
    assertTrue(
        users.stream().anyMatch(u -> u.path().equals("inputs/a") && u.owner().equals(target.uuid)));
    assertTrue(users.stream().anyMatch(u -> u.path().equals("outputBinding") && u.owner() == null));
  }

  @Test
  void indexesOwnMetadataAndCachedChoicesAreImmutable() {
    var n = noise();
    var first = n.processing.getFirst();
    var index = new NoiseDraftIndex(n);
    var choices = index.choices("earlier", n.processing.getLast().uuid, "Scalar");
    first.id = "changed";
    first.enabled = false;
    n.processing.clear();
    assertEquals("Scalar", index.get(first.uuid).id());
    assertTrue(index.get(first.uuid).enabled());
    assertSame(
        choices,
        index.choices(
            "earlier", index.rows(NoiseDraftIndex.Section.PROCESSING).getLast().uuid(), "Scalar"));
    assertThrows(UnsupportedOperationException.class, () -> choices.clear());
  }

  @Test
  void cacheTracksDraftIdentityAndExplicitEditVersion() {
    var n = noise();
    var cache = new NoiseDraftIndex.Cache();
    var first = cache.get(n, 0);
    assertSame(first, cache.get(n, 0));
    assertEquals(1, cache.builds());
    assertNotSame(first, cache.get(n, 1));
    assertNotSame(first, cache.get(NoiseAuthoring.copy(n), 1));
    assertEquals(3, cache.builds());
  }

  @Test
  void sourceMenusRespectValueTypesAndCoordinateStageBoundaries() {
    for (String type : NoiseAuthoring.TYPES) {
      var processing = NoiseDraftIndex.sources(type, false);
      var coordinates = NoiseDraftIndex.sources(type, true);
      assertTrue(processing.containsAll(List.of("Constant", "Shared Parameter", "Earlier Result")));
      assertFalse(coordinates.contains("Source Result"));
      assertEquals(type.equals("Scalar"), processing.contains("Noise Generator Reference"));
      assertEquals(type.equals("Scalar"), processing.contains("Ambient Value"));
    }
  }

  @Test
  void currentDraftValuesDoNotChangeCommittedChoicesUntilParentApply() {
    var committed = noise();
    var draft = NoiseAuthoring.copy(committed);
    var id = draft.processing.getFirst().uuid;
    draft.processing.getFirst().id = "private";
    assertEquals("private", new NoiseDraftIndex(draft).get(id).id());
    assertEquals("Scalar", new NoiseDraftIndex(committed).get(id).id());
  }

  @Test
  void pinPresentationLeavesComputationIdentityUnchangedAndValuesStayCanonical() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "field";
    n.algorithm.key = "CONSTANT";
    p.noise2D.add(n);
    var parameter = new Parameter();
    parameter.id = "p";
    parameter.value = ProjectIO.JSON.valueToTree(2);
    n.sharedParameters.add(parameter);
    String before = PreviewIdentity.of(p, 0, n.uuid);
    var pin = new Pin();
    pin.targetUuid = parameter.uuid;
    pin.label = "alias";
    pin.group = "group";
    n.pinnedControls.add(pin);
    assertEquals(before, PreviewIdentity.of(p, 0, n.uuid));
    pin.label = "different";
    pin.group = "other";
    assertEquals(before, PreviewIdentity.of(p, 0, n.uuid));
    parameter.value = ProjectIO.JSON.valueToTree(4);
    assertNotEquals(before, PreviewIdentity.of(p, 0, n.uuid));
  }
}
