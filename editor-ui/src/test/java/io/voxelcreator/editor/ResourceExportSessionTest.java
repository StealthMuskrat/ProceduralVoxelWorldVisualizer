package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceExportSessionTest {
  @Test
  void openingExportAfterUndoExcludesUnavailableSelectionWithoutChangingTheDocument() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var type = new VoxelType();
      type.id = "temporary";
      assertTrue(state.change(p -> p.voxelTypes.add(type)));
      state.select("voxel", type.uuid);
      state.document.undo();
      state.revision++;
      state.refreshDraft();
      var before = ProjectIO.JSON.valueToTree(state.project());
      var panel = new ResourcePanel(state, new PropertyPanel(state));
      panel.beginExport();
      assertTrue(panel.exportSelection.isEmpty());
      assertTrue(panel.exportSelection().voxelTypes.isEmpty());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  private static ProjectData fixture() {
    var p = new ProjectData();
    for (String id : List.of("stone", "clay", "unused")) {
      var t = new VoxelType();
      t.id = id;
      p.voxelTypes.add(t);
    }
    var set = new Membership();
    set.id = "pair";
    set.voxelTypeUuids.add(p.voxelTypes.get(0).uuid);
    set.voxelTypeUuids.add(p.voxelTypes.get(1).uuid);
    p.voxelSets.add(set);
    return p;
  }

  @Test
  void unchangedRenderingInputsReuseOnePreparedDependencyPayload() {
    var p = fixture();
    var session = new ResourceExportSession();
    var selected = Set.of(p.voxelSets.getFirst().uuid);
    for (int i = 0; i < 100; i++) session.prepare(p, 3, selected);
    assertEquals(1, session.preparations());
    assertEquals(
        Set.of("stone", "clay", "pair"),
        new HashSet<>(session.included().stream().map(ResourceTreeIndex.Node::id).toList()));
    session.prepare(p, 4, selected);
    assertEquals(2, session.preparations());
    session.prepare(p, 4, Set.of(p.voxelTypes.getLast().uuid));
    assertEquals(3, session.preparations());
    assertEquals("unused", session.included().getFirst().id());
  }

  @Test
  void sourceSelectedSetPreviewAndPayloadCopiesHaveIndependentOwnership() {
    var p = fixture();
    var selected = new HashSet<>(Set.of(p.voxelSets.getFirst().uuid));
    var session = new ResourceExportSession();
    session.prepare(p, 0, selected);
    selected.clear();
    assertEquals(1, session.selected().size());
    var copy = session.resources();
    copy.voxelTypes.clear();
    copy.voxelSets.getFirst().voxelTypeUuids.clear();
    assertEquals(2, session.resources().voxelTypes.size());
    assertEquals(2, p.voxelSets.getFirst().voxelTypeUuids.size());
    assertThrows(UnsupportedOperationException.class, () -> session.included().clear());
  }

  @Test
  void operationExportsRetainCarrierDefaultsReferencedNoiseAndFolderContext() {
    var p = fixture();
    var folder = new Folder();
    folder.id = "fields";
    folder.rootKind = "noise2D";
    p.folders.add(folder);
    var noise = new Noise();
    noise.id = "height";
    noise.parentFolderUuid = folder.uuid;
    p.noise2D.add(noise);
    var layer = new Layer();
    layer.id = "terrain";
    layer.defaults.voxelTypeUuid = p.voxelTypes.getFirst().uuid;
    var op = new Operation();
    op.id = "fill";
    op.noiseGeneratorUuid = noise.uuid;
    op.interpretation = "HEIGHT_BELOW";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    var session = new ResourceExportSession();
    session.prepare(p, 0, Set.of(op.uuid));
    var out = session.resources();
    assertEquals(List.of("fields"), out.folders.stream().map(f -> f.id).toList());
    assertEquals(List.of("height"), out.noise2D.stream().map(n -> n.id).toList());
    assertEquals(List.of("stone"), out.voxelTypes.stream().map(t -> t.id).toList());
    assertEquals(1, out.worldGenerator.layers.getFirst().operations.size());
    assertEquals(op.uuid, out.worldGenerator.layers.getFirst().operations.getFirst().uuid);
    assertTrue(Validator.validate(out).isEmpty());
  }

  @Test
  void invalidSelectionCannotReplacePreviouslyPreparedPayloadAndReplacementInvalidates() {
    var p = fixture();
    var session = new ResourceExportSession();
    var ids = Set.of(p.voxelSets.getFirst().uuid);
    session.prepare(p, 0, ids);
    var before = ProjectIO.JSON.valueToTree(session.resources());
    assertThrows(
        IllegalArgumentException.class, () -> session.prepare(p, 0, Set.of(UUID.randomUUID())));
    assertEquals(before, ProjectIO.JSON.valueToTree(session.resources()));
    session.prepare(ProjectIO.copy(p), 0, ids);
    assertEquals(2, session.preparations());
    session.clear();
    assertThrows(IllegalStateException.class, session::resources);
  }

  @Test
  void emptySelectionAndRepeatedPreviewDoNotMutateTheSource() {
    var p = fixture();
    var before = ProjectIO.JSON.valueToTree(p);
    var session = new ResourceExportSession();
    session.prepare(p, 0, Set.of());
    assertTrue(session.included().isEmpty());
    assertTrue(session.resources().voxelTypes.isEmpty());
    session.prepare(p, 0, Set.of(p.voxelSets.getFirst().uuid));
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }
}
