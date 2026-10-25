package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.ChunkPos;
import java.util.*;
import org.junit.jupiter.api.Test;

class HierarchyEditorTest {
  @Test
  void organizationalOrderAndParentChangesKeepComputationIdentityButExecutionDoesNot() {
    var p = HierarchyReferenceProbe.fixture();
    String original = PreviewIdentity.of(p, 2, null);
    HierarchyReferenceProbe.organize(p);
    assertEquals(original, PreviewIdentity.of(p, 2, null));
    ResourceGraph.shiftExecution(p, List.of(p.worldGenerator.layers.getLast().uuid), -1);
    assertNotEquals(original, PreviewIdentity.of(p, 2, null));
  }

  @Test
  void cachedTreeUsesRootOrderAndStageNumbersFollowCanonicalExecutionArray() {
    var p = HierarchyReferenceProbe.fixture();
    HierarchyReferenceProbe.organize(p);
    var index = new ResourceTreeIndex(p);
    assertEquals(
        List.of("constant", "fields"),
        index.project("noise3D", Map.of(), "").stream().map(r -> r.node().id()).toList());
    var rows =
        index.project(
            "world", Map.of(p.folders.get(0).uuid, true, p.folders.get(1).uuid, true), "");
    assertEquals(
        List.of("second", "clear", "everywhere", "first", "set", "everywhere"),
        rows.stream().map(r -> r.node().id()).toList());
    assertEquals(2, index.stage(p.worldGenerator.layers.getLast().uuid));
    ResourceGraph.shiftExecution(p, List.of(p.worldGenerator.layers.getLast().uuid), -1);
    assertEquals(1, new ResourceTreeIndex(p).stage(p.worldGenerator.layers.getFirst().uuid));
  }

  @Test
  void findWorldDirectoryScopesAndBreadcrumbsIncludeFolderLayerAndOperation() {
    var p = HierarchyReferenceProbe.fixture();
    HierarchyReferenceProbe.organize(p);
    var index = new ResourceSearchIndex(p);
    var rows =
        index.search(
            new ResourceSearchIndex.Filter(
                "",
                "operation",
                "All",
                "world",
                p.folders.getFirst().uuid,
                null,
                false,
                ResourceSearchIndex.Mode.ANY_TEXT));
    assertEquals(1, rows.size());
    assertEquals("world / first / set / everywhere", rows.getFirst().breadcrumb());
  }

  @Test
  void historicalFormatTwoWithoutNewFieldsKeepsLegacyOrderAndNullLayerParents() throws Exception {
    var p = HierarchyReferenceProbe.fixture();
    var json = (ObjectNode) ProjectIO.JSON.valueToTree(p);
    ((ObjectNode) json.path("project")).remove("rootOrder");
    json.path("worldGenerator")
        .path("layers")
        .forEach(layer -> ((ObjectNode) layer).remove("parentFolderUuid"));
    var loaded = ProjectIO.JSON.treeToValue(json, ProjectData.class);
    assertTrue(loaded.project.rootOrder.isEmpty());
    assertTrue(loaded.worldGenerator.layers.stream().allMatch(l -> l.parentFolderUuid == null));
    assertTrue(Validator.validate(loaded).isEmpty());
  }

  @Test
  void organizationIsOneDocumentEditWithUndoRedoAndDoesNotBakePlacementOverrides() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(state.change(p -> ResourceGraph.assign(p, HierarchyReferenceProbe.fixture())));
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertTrue(state.change(HierarchyReferenceProbe::organize));
      assertTrue(
          state
              .project()
              .worldGenerator
              .layers
              .getFirst()
              .operations
              .getFirst()
              .overrides
              .isEmpty());
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertNotNull(state.project().worldGenerator.layers.getFirst().parentFolderUuid);
    }
  }

  @Test
  void independentWorldExpectationsHoldAcrossOrganizationRootsAndExecutionChanges() {
    var p = HierarchyReferenceProbe.fixture();
    HierarchyReferenceProbe.organize(p);
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4, 8})
        try (var world =
            engine.openWorld(
                new CompiledProject(p),
                new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          assertTrue(world.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty());
        }
      ResourceGraph.shiftExecution(p, List.of(p.worldGenerator.layers.getLast().uuid), -1);
      try (var world =
          engine.openWorld(
              new CompiledProject(p),
              new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
        var chunk = world.generateChunk(new ChunkPos(0, 0, 0));
        for (int x = 0; x < 4; x++)
          for (int y = 0; y < 4; y++)
            for (int z = 0; z < 4; z++) assertNotEquals(0, chunk.getVoxelTypeId(x, y, z));
      }
    }
  }
}
