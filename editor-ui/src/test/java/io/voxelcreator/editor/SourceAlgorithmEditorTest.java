package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceAlgorithmEditorTest {
  @Test
  void everyCreationSignatureDerivesFromTheCanonicalSourceRegistry() {
    for (int dimension : new int[] {2, 3})
      for (String key : NoiseAlgorithms.KEYS)
        for (var control : NoiseAlgorithmRegistry.parameters(key, dimension)) {
          var spec =
              CreationRegistry.get(
                  "noise" + dimension + "D.algorithm." + key + "." + control.key());
          assertEquals(control.defaultValue(), spec.builtIn().doubleValue());
          assertEquals(control.presetMinimum(), spec.minimum());
          assertEquals(control.presetMaximum(), spec.maximum());
          assertEquals(control.choices(), spec.options());
          assertEquals(
              control.integer() ? CreationRegistry.Kind.INTEGER : CreationRegistry.Kind.NUMBER,
              spec.kind());
        }
  }

  @Test
  void namedModesPersistAsNumericIndicesAndMetadataAliasesDoNotChangeComputation() {
    var metadata = AppMetadata.defaults();
    String id = "noise2D.algorithm.PERLIN.fractal";
    metadata.controls.get(id).options.put("PING_PONG", "Folded");
    metadata.creationDefaults.put("noise2D.algorithm", ProjectIO.JSON.valueToTree("PERLIN"));
    metadata.creationDefaults.put(id, ProjectIO.JSON.valueToTree(3));
    var n = EditorCreations.noise(metadata, 2, "field", null);
    assertEquals(3, n.algorithm.parameters.get("fractal"));
    assertEquals("Folded", metadata.option(id, "PING_PONG"));
    assertEquals(CreationRegistry.Kind.INTEGER, CreationRegistry.get(id).kind());
  }

  @Test
  void profileValidationRejectsTextEnumsFractionsAndUnknownNamedChoices() {
    var m = AppMetadata.defaults();
    String id = "noise2D.algorithm.PERLIN.fractal";
    m.creationDefaults.put(id, ProjectIO.JSON.valueToTree("PING_PONG"));
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.put(id, ProjectIO.JSON.valueToTree(1.5));
    assertThrows(IllegalArgumentException.class, m::validate);
    m.creationDefaults.clear();
    m.controls.get(id).options.put("UNKNOWN", "Not a mode");
    assertThrows(IllegalArgumentException.class, m::validate);
  }

  @Test
  void narrowerCreationRangesDoNotConstrainExistingValidSignedSourceValues() {
    var n = new Noise();
    n.id = "field";
    n.algorithm.key = "PERLIN";
    n.algorithm.parameters.put("gain", -.5);
    var p = new ProjectData();
    p.noise2D.add(n);
    assertTrue(NoiseCompiler.validate(p).isEmpty());
    var m = AppMetadata.defaults();
    m.creationDefaults.put("noise2D.algorithm.PERLIN.gain", ProjectIO.JSON.valueToTree(-.5));
    assertThrows(IllegalArgumentException.class, m::validate);
    assertEquals(-.5, n.algorithm.parameters.get("gain"));
  }

  @Test
  void sourceCreationWritesOnlySupportedDimensionalSettings() {
    var m = AppMetadata.defaults();
    m.creationDefaults.put("noise2D.algorithm", ProjectIO.JSON.valueToTree("WHITE_HASH"));
    var hash = EditorCreations.noise(m, 2, "hash", null);
    assertEquals(Set.of("frequency"), hash.algorithm.parameters.keySet());
    m.creationDefaults.put("noise2D.algorithm", ProjectIO.JSON.valueToTree("BOX_SDF"));
    var box = EditorCreations.noise(m, 2, "box", null);
    assertEquals(Set.of("extentX", "extentZ"), box.algorithm.parameters.keySet());
    assertFalse(CreationRegistry.CONTROLS.containsKey("noise2D.algorithm.BOX_SDF.extentY"));
  }

  @Test
  void profileChangesAffectFutureSourcesWithoutRewritingExistingProjects() {
    var m = AppMetadata.defaults();
    m.creationDefaults.put("noise2D.algorithm", ProjectIO.JSON.valueToTree("PERLIN"));
    var existing = EditorCreations.noise(m, 2, "existing", null);
    var before = ProjectIO.JSON.valueToTree(existing);
    m.creationDefaults.put("noise2D.algorithm.PERLIN.frequency", ProjectIO.JSON.valueToTree(.25));
    var next = EditorCreations.noise(m, 2, "next", null);
    assertEquals(before, ProjectIO.JSON.valueToTree(existing));
    assertEquals(.25, next.algorithm.parameters.get("frequency"));
  }

  @Test
  void sourceDraftApplyRejectAndUndoKeepOneCanonicalDefinition() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "source";
                p.noise2D.add(n);
              }));
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      var draft = (Noise) state.draft;
      draft.algorithm.parameters.put("frequency", 0.);
      var committed = ProjectIO.JSON.valueToTree(state.project());
      assertFalse(state.applyDraft());
      assertSame(draft, state.draft);
      assertEquals(committed, ProjectIO.JSON.valueToTree(state.project()));
      draft.algorithm.parameters.put("frequency", .125);
      assertTrue(state.applyDraft());
      state.document.undo();
      assertEquals(.01, state.project().noise2D.getFirst().algorithm.parameters.get("frequency"));
      state.document.redo();
      assertEquals(.125, state.project().noise2D.getFirst().algorithm.parameters.get("frequency"));
    }
  }

  @Test
  void sourceDiagnosticsRetainTheInvalidOwnerAndLogicalParameter() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "source";
                p.noise2D.add(n);
              }));
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      ((Noise) state.draft).algorithm.parameters.put("octaves", 1.5);
      assertFalse(state.applyDraft());
      var issue = state.issues.getFirst();
      assertEquals(n.uuid, issue.sourceUuid());
      assertEquals("algorithm/parameters/octaves", issue.parameterKey());
      var target = EditorEvents.Target.diagnostic(state.project().project.uuid, issue);
      assertTrue(state.canNavigate(target));
    }
  }
}
