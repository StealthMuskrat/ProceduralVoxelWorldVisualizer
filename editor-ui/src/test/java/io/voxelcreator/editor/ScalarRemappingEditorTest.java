package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScalarRemappingEditorTest {
  @Test
  void sharedControlSignaturesPreserveTypesAndSupportedModes() {
    var m = AppMetadata.defaults();
    for (var kind : ProcessingExtensions.REMAPPING)
      for (var c : ProcessingExtensions.DETAILS.get(kind).controls()) {
        var spec = CreationRegistry.get("processing." + kind + ".parameter." + c.key());
        spec.validate(spec.builtIn());
        assertEquals(c.label(), m.controls.get(spec.id()).label);
        assertEquals(c.options(), spec.options());
      }
    assertEquals(
        CreationRegistry.Kind.BOOLEAN,
        CreationRegistry.get("processing.REMAP.parameter.clamp").kind());
  }

  @Test
  void futureOnlyProfilesBakeValuesWithoutChangingHistoricalDefinitions() {
    var m = AppMetadata.defaults();
    var old = EditorCreations.processing(m, "BLEND", "old");
    m.creationDefaults.put("processing.BLEND.parameter.weight", ProjectIO.JSON.valueToTree(.75));
    m.creationDefaults.put(
        "processing.FALLOFF.parameter.shape", ProjectIO.JSON.valueToTree("COSINE"));
    var newer = EditorCreations.processing(m, "BLEND", "new");
    assertEquals(.5, old.parameters.get("weight").doubleValue());
    assertEquals(.75, newer.parameters.get("weight").doubleValue());
    assertEquals(
        "COSINE",
        EditorCreations.processing(m, "FALLOFF", "new-falloff")
            .parameters
            .get("shape")
            .textValue());
    assertNotEquals(old.uuid, newer.uuid);
  }

  @Test
  void profileChoicesAndCrossFieldRangesRejectBeforePublication() {
    var m = AppMetadata.defaults();
    m.creationDefaults.put(
        "processing.BLEND.parameter.mode", ProjectIO.JSON.valueToTree("AVERAGE"));
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    m.creationDefaults.put("processing.CLAMP.parameter.min", ProjectIO.JSON.valueToTree(2));
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    m.creationDefaults.put("processing.REMAP.parameter.inputMax", ProjectIO.JSON.valueToTree(-1));
    assertThrows(IllegalArgumentException.class, m::validate);
  }

  @Test
  void conditionalFalloffControlsRetainInactiveValues() {
    var item = ProcessingRegistry.create("FALLOFF", "falloff");
    item.parameters.put("domain", ProjectIO.JSON.valueToTree("INPUT"));
    item.parameters.put("centerZ", ProjectIO.JSON.valueToTree(123));
    var visible =
        ProcessingExtensions.DETAILS.get("FALLOFF").controls().stream()
            .filter(c -> ProcessingExtensions.visible(item, c, 2))
            .map(ProcessingExtensions.Control::key)
            .toList();
    assertFalse(visible.contains("metric"));
    assertFalse(visible.contains("axis"));
    assertFalse(visible.contains("centerY"));
    assertFalse(visible.contains("centerZ"));
    assertTrue(visible.contains("centerX"));
    assertEquals(123, item.parameters.get("centerZ").intValue());
  }

  @Test
  void creationPreservesOriginalPresetBoundsWhileRuntimeAcceptsWiderValues() {
    assertEquals(65536, CreationRegistry.get("processing.TERRACE.parameter.levels").maximum());
    assertEquals(1e-9, CreationRegistry.get("processing.FALLOFF.parameter.extent").minimum());
    var i = ProcessingRegistry.create("TERRACE", "wide");
    i.parameters.put("levels", ProjectIO.JSON.valueToTree(1e8));
    assertDoesNotThrow(() -> ScalarRemapping.compile(i, 2));
  }

  @Test
  void validDraftIsOneUndoAndInvalidDraftKeepsExactControlTarget() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "blend";
                n.algorithm.key = "CONSTANT";
                p.noise2D.add(n);
              }));
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      var draft = (Noise) state.draft;
      var i = ProcessingRegistry.create("BLEND", "blend");
      i.inputs.put("a", Binding.constant(2));
      i.inputs.put("b", Binding.constant(10));
      i.parameters.put("weight", ProjectIO.JSON.valueToTree(.25));
      draft.processing.add(i);
      draft.outputBinding = Binding.earlier(i.uuid);
      assertTrue(state.applyDraft());
      assertEquals(4, new NoiseCompiler(state.project(), 0).compile(n.uuid).sample(0, 0, 0));
      state.document.undo();
      assertEquals(0, new NoiseCompiler(state.project(), 0).compile(n.uuid).sample(0, 0, 0));
      state.document.redo();
      state.refreshDraft();
      var edited = ((Noise) state.draft).processing.getFirst();
      edited.parameters.put("mode", ProjectIO.JSON.valueToTree("invalid"));
      var before = ProjectIO.JSON.valueToTree(state.draft);
      assertFalse(state.applyDraft());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
      assertTrue(state.issues.stream().anyMatch(issue -> "mode".equals(issue.parameterKey())));
    }
  }
}
