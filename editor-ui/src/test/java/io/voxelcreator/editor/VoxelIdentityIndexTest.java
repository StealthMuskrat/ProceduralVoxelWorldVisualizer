package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class VoxelIdentityIndexTest {
  static ProjectData fixture() {
    var p = new ProjectData();
    var stone = new VoxelType();
    stone.id = "stone";
    var snow = new VoxelType();
    snow.id = "snow";
    p.voxelTypes.addAll(List.of(stone, snow));
    var group = new Membership();
    group.id = "display-group";
    group.voxelTypeUuids.add(stone.uuid);
    p.voxelGroups.add(group);
    var set = new Membership();
    set.id = "solid-set";
    set.voxelTypeUuids.addAll(List.of(stone.uuid, snow.uuid));
    p.voxelSets.add(set);
    var noise = new Noise();
    noise.id = "constant";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", 1.);
    p.noise2D.add(noise);
    var layer = new Layer();
    layer.id = "ground";
    layer.defaults.voxelTypeUuid = stone.uuid;
    var operation = new Operation();
    operation.id = "fill";
    operation.noiseGeneratorUuid = noise.uuid;
    operation.overrides.put("voxelTypeUuid", ProjectIO.JSON.valueToTree(stone.uuid));
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
    return p;
  }

  @Test
  void indexListsMembershipAndWorldDependenciesOncePerContainingResource() {
    var p = fixture();
    var type = new VoxelIdentityIndex(p).type(p.voxelTypes.getFirst().uuid);
    assertEquals("stone", type.id());
    assertEquals(
        List.of("group", "set", "layer", "operation"),
        type.dependents().stream().map(VoxelIdentityIndex.Dependent::kind).toList());
    assertEquals(
        List.of("display-group", "solid-set", "ground", "fill"),
        type.dependents().stream().map(VoxelIdentityIndex.Dependent::id).toList());
    assertEquals(
        4,
        new HashSet<>(type.dependents().stream().map(VoxelIdentityIndex.Dependent::uuid).toList())
            .size());
    assertEquals(
        1, new VoxelIdentityIndex(p).type(p.voxelTypes.getLast().uuid).dependents().size());
  }

  @Test
  void nestedPredicatesAndIntervalsUseRecognizedReferencesRatherThanUuidLikeLabels() {
    var p = fixture();
    UUID snow = p.voxelTypes.getLast().uuid;
    p.noise2D.getFirst().id = snow.toString();
    var op = p.worldGenerator.layers.getFirst().operations.getFirst();
    var predicate = new Predicate();
    predicate.kind = "TYPE_IS";
    predicate.reference = snow;
    op.enabled = false;
    p.worldGenerator.layers.getFirst().enabled = false;
    op.overrides.put("voxelStatePredicate", ProjectIO.JSON.valueToTree(predicate));
    var type = new VoxelIdentityIndex(p).type(snow);
    assertEquals(
        List.of("set", "layer", "operation"),
        type.dependents().stream().map(VoxelIdentityIndex.Dependent::kind).toList());
    assertNull(new VoxelIdentityIndex(p).type(UUID.randomUUID()));
  }

  @Test
  void indexOwnsLabelsAndListsAfterEverySourceValueIsChanged() {
    var p = fixture();
    UUID id = p.voxelTypes.getFirst().uuid;
    var index = new VoxelIdentityIndex(p);
    var type = index.type(id);
    p.voxelTypes.getFirst().id = "renamed";
    p.voxelGroups.getFirst().id = "changed";
    p.voxelGroups.getFirst().voxelTypeUuids.clear();
    p.worldGenerator.layers.clear();
    assertSame(type, index.type(id));
    assertEquals("stone", type.id());
    assertEquals("display-group", type.dependents().getFirst().id());
    assertEquals(4, type.dependents().size());
    assertThrows(UnsupportedOperationException.class, () -> type.dependents().clear());
  }

  @Test
  void cacheKeysIncludeDocumentLifetimeIdentityAndRevisionAndCanReleaseTheIndex() {
    var p = fixture();
    var cache = new VoxelIdentityIndex.Cache();
    var session = new Object();
    var first = cache.get(session, p, 0);
    for (int i = 0; i < 100; i++) assertSame(first, cache.get(session, p, 0));
    assertEquals(1, cache.builds());
    assertNotSame(first, cache.get(new Object(), p, 0));
    var second = cache.get(session, p, 1);
    assertNotSame(first, second);
    assertNotSame(second, cache.get(session, ProjectIO.copy(p), 1));
    cache.clear();
    assertNotSame(first, cache.get(session, p, 0));
    assertEquals(5, cache.builds());
  }

  @Test
  void privateDraftChangesDoNotEnterTheCommittedIndexAndRenameKeepsAllUuidReferences() {
    var p = fixture();
    var before = ProjectIO.JSON.valueToTree(p);
    var uuid = p.voxelTypes.getFirst().uuid;
    var draft = ProjectIO.JSON.convertValue(before.path("voxelTypes").get(0), VoxelType.class);
    draft.id = "private-name";
    var committed = new VoxelIdentityIndex(p).type(uuid);
    assertEquals("stone", committed.id());
    var expected = ProjectIO.copy(p);
    expected.voxelTypes.getFirst().id = "granite";
    ResourceGraph.rename(p, uuid, "granite");
    assertEquals(ProjectIO.JSON.valueToTree(expected), ProjectIO.JSON.valueToTree(p));
    assertEquals(committed.dependents(), new VoxelIdentityIndex(p).type(uuid).dependents());
  }

  @Test
  void largeMembershipListsRemainStableAndDoNotInventEmptyOrUnresolvedTypeRows() {
    var p = fixture();
    UUID uuid = p.voxelTypes.getFirst().uuid;
    for (int i = 0; i < 2048; i++) {
      var group = new Membership();
      group.id = "group-" + i;
      group.voxelTypeUuids.add(uuid);
      p.voxelGroups.add(group);
    }
    var index = new VoxelIdentityIndex(p);
    assertEquals(2052, index.type(uuid).dependents().size());
    assertNull(index.type(null));
    var empty = new VoxelIdentityIndex(new ProjectData());
    assertNull(empty.type(uuid));
  }
}
