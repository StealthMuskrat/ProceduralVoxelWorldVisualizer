package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceChoicesTest {
  @Test
  void normalizedQueriesReuseSnapshotsAndRevisionChangesRefreshLabels() {
    var p = new ProjectData();
    var t = new VoxelType();
    t.id = "Stone";
    p.voxelTypes.add(t);
    var index = new ResourceTreeIndex(p);
    var query = new ResourceChoices();
    var first = query.rows(index, "voxel", "stone");
    assertSame(first, query.rows(index, "voxel", " STONE "));
    assertEquals(1, query.evaluations());
    t.id = "Clay";
    assertEquals("Stone", first.getFirst().id());
    assertTrue(query.rows(new ResourceTreeIndex(p), "voxel", "stone").isEmpty());
    assertEquals(2, query.evaluations());
  }

  @Test
  void equalVisibleIdsAcrossDimensionsRetainSeparateUuidChoices() {
    var p = new ProjectData();
    var a = new Noise();
    a.id = "same###id";
    var b = new Noise();
    b.id = a.id;
    b.dimension = 3;
    p.noise2D.add(a);
    p.noise3D.add(b);
    var index = new ResourceTreeIndex(p);
    var rows = new ResourceChoices().rows(index, "noise", "same");
    assertEquals(List.of(a.uuid, b.uuid), rows.stream().map(ResourceTreeIndex.Node::uuid).toList());
    assertEquals("same###id [2D]", ResourceChoices.label(rows.getFirst()));
    assertEquals("same###id [3D]", ResourceChoices.label(rows.getLast()));
    assertEquals(b.uuid, ResourceChoices.choose(index, "noise", b.uuid, true));
  }

  @Test
  void incompatibleMissingAndDisallowedNullChoicesRejectWithoutMutation() {
    var p = new ProjectData();
    var t = new VoxelType();
    t.id = "stone";
    p.voxelTypes.add(t);
    var index = new ResourceTreeIndex(p);
    assertThrows(
        IllegalArgumentException.class, () -> ResourceChoices.choose(index, "noise", t.uuid, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceChoices.choose(index, "voxel", UUID.randomUUID(), true));
    assertThrows(
        IllegalArgumentException.class, () -> ResourceChoices.choose(index, "voxel", null, false));
    assertNull(ResourceChoices.choose(index, "voxel", null, true));
    assertEquals(t.uuid, ResourceChoices.choose(index, "voxel", t.uuid, false));
  }

  @Test
  void filtersPreserveOrderAndCatalogOwnershipAcrossIndependentQueries() {
    var p = new ProjectData();
    for (String id : List.of("b-stone", "clay", "a-stone")) {
      var t = new VoxelType();
      t.id = id;
      p.voxelTypes.add(t);
    }
    var index = new ResourceTreeIndex(p);
    var a = new ResourceChoices();
    var b = new ResourceChoices();
    assertEquals(
        List.of("b-stone", "a-stone"),
        a.rows(index, "voxel", "stone").stream().map(ResourceTreeIndex.Node::id).toList());
    assertEquals(
        List.of("clay"),
        b.rows(index, "voxel", "clay").stream().map(ResourceTreeIndex.Node::id).toList());
    assertEquals(2, a.rows(index, "voxel", "stone").size());
    assertThrows(UnsupportedOperationException.class, () -> index.all().clear());
  }

  @Test
  void dimensionScopedViewportChoicesKeepUuidAcrossRenameAndRejectForeignDimension() {
    var p = new ProjectData();
    var a = new Noise();
    a.id = "shared";
    p.noise2D.add(a);
    var b = new Noise();
    b.id = "shared";
    b.dimension = 3;
    p.noise3D.add(b);
    var choices = new ResourceChoices();
    var index = new ResourceTreeIndex(p);
    assertEquals(
        List.of(a.uuid),
        choices.rows(index, "noise2D", "shared").stream()
            .map(ResourceTreeIndex.Node::uuid)
            .toList());
    assertEquals(
        List.of(b.uuid),
        choices.rows(index, "noise3D", "shared").stream()
            .map(ResourceTreeIndex.Node::uuid)
            .toList());
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceChoices.choose(index, "noise2D", b.uuid, true));
    a.id = "renamed";
    var renamed = new ResourceTreeIndex(p);
    assertEquals(a.uuid, ResourceChoices.choose(renamed, "noise2D", a.uuid, true));
    assertEquals("renamed", choices.rows(renamed, "noise2D", "renamed").getFirst().id());
    p.noise2D.clear();
    var removed = new ResourceTreeIndex(p);
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceChoices.choose(removed, "noise2D", a.uuid, true));
    assertNull(ResourceChoices.choose(removed, "noise2D", null, true));
  }
}
