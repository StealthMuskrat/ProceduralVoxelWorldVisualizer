package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FieldOperationsEditorTest {
  @Test
  void dimensionAwareFactoriesUseMatchingGradientTypesAndKeepAllFieldSelectors() {
    var metadata = AppMetadata.defaults();
    for (int d : new int[] {2, 3})
      for (var key : ProcessingExtensions.DETAILS.keySet()) {
        if (!ProcessingExtensions.supports(key, d)) continue;
        var item = EditorCreations.processing(metadata, key, "new-item", d);
        assertEquals(ProcessingExtensions.resultType(key, d), item.resultType);
        if (ProcessingExtensions.CALCULUS.contains(key))
          assertEquals("Source Field", item.inputs.get("field").sourceKind);
        for (var control : ProcessingExtensions.DETAILS.get(key).controls())
          assertTrue(item.parameters.containsKey(control.key()));
      }
  }

  @Test
  void creationDefaultsBakeFutureControlsWithoutMutatingExistingItems() {
    var metadata = AppMetadata.defaults();
    var old = EditorCreations.processing(metadata, "MAX_FILTER", "old", 2);
    metadata.creationDefaults.put(
        "processing.MAX_FILTER.parameter.strength", ProjectIO.JSON.valueToTree(.25));
    var created = EditorCreations.processing(metadata, "MAX_FILTER", "new", 2);
    assertEquals(1, old.parameters.get("strength").doubleValue());
    assertEquals(.25, created.parameters.get("strength").doubleValue());
    assertNotEquals(old.uuid, created.uuid);
  }

  @Test
  void crossFieldDefaultFailuresRejectBeforePublication() {
    var metadata = AppMetadata.defaults();
    metadata.creationDefaults.put(
        "processing.TORUS.parameter.minorRadius", ProjectIO.JSON.valueToTree(20));
    assertThrows(IllegalArgumentException.class, metadata::validate);
    metadata.creationDefaults.clear();
    metadata.creationDefaults.put(
        "processing.ROUNDED_BOX.parameter.roundRadius", ProjectIO.JSON.valueToTree(17));
    assertThrows(IllegalArgumentException.class, metadata::validate);
    metadata.creationDefaults.clear();
    metadata.creationDefaults.put(
        "processing.LAPLACIAN.parameter.spacing", ProjectIO.JSON.valueToTree(Double.MIN_VALUE));
    assertThrows(IllegalArgumentException.class, metadata::validate);
  }

  @Test
  void canonicalExtensionControlSignaturesHavePresentationAndStrictChoices() {
    var metadata = AppMetadata.defaults();
    for (var entry : ProcessingExtensions.DETAILS.entrySet())
      for (var control : entry.getValue().controls()) {
        String id = "processing." + entry.getKey() + ".parameter." + control.key();
        var spec = CreationRegistry.get(id);
        spec.validate(spec.builtIn());
        assertEquals(control.label(), metadata.controls.get(id).label);
      }
    metadata.creationDefaults.put(
        "processing.PLANE.parameter.direction", ProjectIO.JSON.valueToTree("AUTO"));
    assertThrows(IllegalArgumentException.class, metadata::validate);
  }

  @Test
  void typedDraftIndexAdvertisesGradientComponentsWithoutCoercion() {
    var n = new Noise();
    n.dimension = 2;
    var gradient = ProcessingRegistry.create("GRADIENT", "gradient", 2);
    var length = ProcessingRegistry.create("LENGTH", "length", 2);
    n.processing.addAll(List.of(gradient, length));
    var index = new NoiseDraftIndex(n);
    assertTrue(
        index.choices("earlier", length.uuid, "Vector2").stream()
            .anyMatch(c -> c.key().equals(gradient.uuid.toString())));
    assertTrue(index.choices("earlier", length.uuid, "Scalar").isEmpty());
    assertTrue(
        index.choices("output", null, "Scalar").stream()
            .noneMatch(c -> c.key().equals(gradient.uuid.toString())));
  }

  @Test
  void propertyApplyAndUndoPublishTheSameCanonicalFieldProgram() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "shapes";
                n.algorithm.key = "CONSTANT";
                p.noise2D.add(n);
              }));
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      var draft = (Noise) state.draft;
      var plane = EditorCreations.processing(state.metadata, "PLANE", "plane", 2);
      plane.parameters.put("offset", ProjectIO.JSON.valueToTree(3));
      draft.processing.add(plane);
      draft.outputBinding = Binding.earlier(plane.uuid);
      assertEquals(0, new NoiseCompiler(state.project(), 0).compile(n.uuid).sample(5, 0, 0));
      assertTrue(state.applyDraft());
      assertEquals(2, new NoiseCompiler(state.project(), 0).compile(n.uuid).sample(5, 0, 0));
      state.document.undo();
      assertEquals(0, new NoiseCompiler(state.project(), 0).compile(n.uuid).sample(5, 0, 0));
      state.document.redo();
      assertEquals(2, new NoiseCompiler(state.project(), 0).compile(n.uuid).sample(5, 0, 0));
    }
  }
}
