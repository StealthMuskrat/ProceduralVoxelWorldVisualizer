package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import org.junit.jupiter.api.Test;

class NoiseCreationChoiceTest {
  private static void set(AppMetadata metadata, String key, Object value) {
    metadata.creationDefaults.put(key, ProjectIO.JSON.valueToTree(value));
  }

  @Test
  void optionalChoicePreservesTheExistingDefaultFactoryAndEmptyAuthoringDefinition() {
    var metadata = AppMetadata.defaults();
    for (int dimension : new int[] {2, 3}) {
      var old = EditorCreations.noise(metadata, dimension, "field", null);
      var optional = EditorCreations.noise(metadata, dimension, "field", null, null);
      optional.uuid = old.uuid;
      assertEquals(ProjectIO.JSON.valueToTree(old), ProjectIO.JSON.valueToTree(optional));
      assertEquals("OPEN_SIMPLEX_2", optional.algorithm.key);
      assertEquals(0, optional.seedOffset);
      assertTrue(optional.coordinateTransforms.isEmpty());
      assertTrue(optional.processing.isEmpty());
      assertTrue(optional.sharedParameters.isEmpty());
      assertTrue(optional.pinnedControls.isEmpty());
      assertEquals("Source Result", optional.outputBinding.sourceKind);
    }
  }

  @Test
  void explicitAlgorithmUsesItsOwnDimensionDefaultsAndDoesNotMutateOrAliasMetadata() {
    var metadata = AppMetadata.defaults();
    set(metadata, "noise2D.algorithm", "GRADIENT_Y");
    set(metadata, "noise3D.algorithm", "GRADIENT_Y");
    set(metadata, "noise2D.algorithm.CONSTANT.value", .625);
    set(metadata, "noise3D.algorithm.CONSTANT.value", -.25);
    set(metadata, "noise2D.seedOffset", 17L);
    var before = ProjectIO.JSON.valueToTree(metadata);
    UUID parent = UUID.randomUUID();
    var two = EditorCreations.noise(metadata, 2, "two", parent, "CONSTANT");
    var three = EditorCreations.noise(metadata, 3, "three", null, "CONSTANT");
    assertEquals(Map.of("value", .625), two.algorithm.parameters);
    assertEquals(Map.of("value", -.25), three.algorithm.parameters);
    assertEquals(parent, two.parentFolderUuid);
    assertEquals(17, two.seedOffset);
    assertEquals(0, three.seedOffset);
    assertEquals(before, ProjectIO.JSON.valueToTree(metadata));
    two.algorithm.parameters.put("value", 9.);
    assertEquals(before, ProjectIO.JSON.valueToTree(metadata));
    assertEquals(-.25, three.algorithm.parameters.get("value").doubleValue());
  }

  @Test
  void explicitGradientChoiceUsesTheCanonicalDimensionSemanticsIndependentlyOfDefaultSource() {
    var metadata = AppMetadata.defaults();
    set(metadata, "noise2D.algorithm", "CONSTANT");
    set(metadata, "noise3D.algorithm", "CONSTANT");
    var p = new ProjectData();
    var two = EditorCreations.noise(metadata, 2, "two", null, "GRADIENT_Y");
    var three = EditorCreations.noise(metadata, 3, "three", null, "GRADIENT_Y");
    p.noise2D.add(two);
    p.noise3D.add(three);
    var compiled = new CompiledProject(p);
    assertTrue(two.algorithm.parameters.isEmpty());
    assertTrue(three.algorithm.parameters.isEmpty());
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++) {
        assertEquals(z, compiled.noise2D(two.uuid, 91).sample(x, z));
        assertEquals(-x, compiled.noise3D(three.uuid, 91).sample(x, -x, z));
      }
  }

  @Test
  void invalidAlgorithmAndDimensionRejectWithoutChangingCreationPreferences() {
    var metadata = AppMetadata.defaults();
    var before = ProjectIO.JSON.valueToTree(metadata);
    for (String key : List.of("", "not-an-algorithm"))
      assertThrows(
          IllegalArgumentException.class,
          () -> EditorCreations.noise(metadata, 2, "field", null, key));
    assertThrows(
        IllegalArgumentException.class,
        () -> EditorCreations.noise(metadata, 4, "field", null, "CONSTANT"));
    assertEquals(before, ProjectIO.JSON.valueToTree(metadata));
  }

  @Test
  void guardedCreationOwnsTheChosenDefinitionAcrossLaterMetadataChangesAndOneUndoRedo() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var metadata = state.metadata;
      set(metadata, "noise2D.algorithm", "GRADIENT_Y");
      set(metadata, "noise2D.algorithm.CONSTANT.value", .625);
      assertTrue(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "source";
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", .5);
                p.noise2D.add(n);
              }));
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var before = ProjectIO.JSON.valueToTree(state.project());
      ((Noise) state.draft).seedOffset = 17;
      var prepared = EditorCreations.noise(metadata, 2, "created", null, "CONSTANT");
      var edits = new ResourceEditSession(state);
      assertEquals(
          ResourceEditSession.Result.NEEDS_DECISION,
          edits.request("Create chosen noise", ResourceBrowserEdits.insert("noise2D", prepared)));
      set(metadata, "noise2D.algorithm.CONSTANT.value", .875);
      prepared.algorithm.parameters.put("value", 9.);
      assertEquals(
          ResourceEditSession.Result.APPLIED, edits.decide(SearchNavigation.Decision.APPLY));
      assertEquals(
          .625, state.project().noise2D.getLast().algorithm.parameters.get("value").doubleValue());
      assertEquals(17, state.project().noise2D.getFirst().seedOffset);
      assertEquals(prepared.uuid, state.selected);
      var after = ProjectIO.JSON.valueToTree(state.project());
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(after, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(
          .625, new CompiledProject(state.project()).noise2D(state.selected, 0).sample(-8, 7));
    }
  }
}
