package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred independent hierarchy expectations, with no native window or export candidate. */
class ExportTreeTest {
  private Folder folder(String id, String root, UUID parent) {
    var f = new Folder();
    f.id = id;
    f.rootKind = root;
    f.parentFolderUuid = parent;
    return f;
  }

  private Noise noise(String id, UUID parent) {
    var n = new Noise();
    n.id = id;
    n.parentFolderUuid = parent;
    return n;
  }

  private ProjectData fixture() {
    var p = new ProjectData();
    var outer = folder("outer", "noise2D", null);
    var inner = folder("inner", "noise2D", outer.uuid);
    var world = folder("world-folder", "world", null);
    p.folders.addAll(List.of(outer, inner, world));
    var alpha = noise("alpha", outer.uuid);
    var needle = noise("needle", inner.uuid);
    var root = noise("root", null);
    p.noise2D.addAll(List.of(alpha, needle, root));
    outer.childOrder.addAll(List.of(alpha.uuid, inner.uuid));
    p.project.rootOrder.put("noise2D", new ArrayList<>(List.of(root.uuid, outer.uuid)));
    var layer = new Layer();
    layer.id = "terrain";
    layer.parentFolderUuid = world.uuid;
    var op = new Operation();
    op.id = "fill";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var set = new Membership();
    set.id = "set";
    set.voxelTypeUuids.add(type.uuid);
    p.voxelSets.add(set);
    return p;
  }

  private List<String> labels(List<ExportTree.Row> rows) {
    return rows.stream().map(r -> r.node().id()).toList();
  }

  @Test
  void foldersAndWorldLayersUseAuthoredOrderAndActualDepthWithoutChangingExecution() {
    var p = fixture();
    var index = new ResourceTreeIndex(p);
    var tree = new ExportTree();
    assertEquals(
        List.of("root", "outer", "world-folder", "stone", "set"),
        labels(tree.choices(index, "", false, Set.of())));
    tree.open(p.folders.getFirst().uuid, true);
    tree.open(p.folders.get(1).uuid, true);
    tree.open(p.folders.get(2).uuid, true);
    var rows = tree.choices(index, "", false, Set.of());
    assertEquals(
        List.of(
            "root",
            "outer",
            "alpha",
            "inner",
            "needle",
            "world-folder",
            "terrain",
            "fill",
            "stone",
            "set"),
        labels(rows));
    assertEquals(
        List.of(0, 0, 1, 1, 2, 0, 1, 2, 0, 0), rows.stream().map(ExportTree.Row::depth).toList());
    assertTrue(rows.get(6).open());
    assertEquals(1, index.stage(p.worldGenerator.layers.getFirst().uuid));
    assertEquals("terrain", p.worldGenerator.layers.getFirst().id);
  }

  @Test
  void descendantSearchRevealsAncestryAndClearingItRestoresTheStoredCollapse() {
    var p = fixture();
    var index = new ResourceTreeIndex(p);
    var tree = new ExportTree();
    var before = tree.choices(index, "", false, Set.of());
    var filtered = tree.choices(index, " NEEDLE ", false, Set.of());
    assertEquals(List.of("outer", "inner", "needle"), labels(filtered));
    assertTrue(filtered.get(0).context());
    assertTrue(filtered.get(1).context());
    assertFalse(filtered.get(2).context());
    assertTrue(filtered.get(0).open() && filtered.get(1).open());
    assertTrue(filtered.get(0).forced() && filtered.get(1).forced());
    assertFalse(filtered.get(2).forced());
    assertEquals(labels(before), labels(tree.choices(index, "", false, Set.of())));
  }

  @Test
  void matchingContainerRevealsItsDescendantsAndKindUuidQueriesKeepParents() {
    var p = fixture();
    var index = new ResourceTreeIndex(p);
    var tree = new ExportTree();
    assertEquals(
        List.of("outer", "alpha", "inner", "needle"),
        labels(tree.choices(index, "outer", false, Set.of())));
    assertEquals(
        List.of("outer", "alpha", "inner", "needle"),
        labels(tree.choices(index, p.folders.getFirst().uuid.toString(), false, Set.of())));
    assertEquals(
        List.of("world-folder", "terrain", "fill"),
        labels(tree.choices(index, "operation", false, Set.of())));
    assertTrue(tree.choices(index, "absent", false, Set.of()).isEmpty());
  }

  @Test
  void selectedOnlyShowsExactSelectedRowsWithContextAndNeverSubstitutesMissingIds() {
    var p = fixture();
    var index = new ResourceTreeIndex(p);
    var tree = new ExportTree();
    var ids = new HashSet<>(Set.of(p.noise2D.get(1).uuid));
    assertEquals(List.of("outer", "inner", "needle"), labels(tree.choices(index, "", true, ids)));
    ids.clear();
    ids.add(p.folders.getFirst().uuid);
    assertEquals(List.of("outer"), labels(tree.choices(index, "", true, ids)));
    assertTrue(tree.choices(index, "needle", true, ids).isEmpty());
    assertTrue(tree.choices(index, "", true, Set.of(UUID.randomUUID())).isEmpty());
  }

  @Test
  void includedProjectionCannotAddSiblingsOrInventResourcesOutsideItsPayload() {
    var p = fixture();
    var index = new ResourceTreeIndex(p);
    var tree = new ExportTree();
    var payload =
        List.of(
            index.get(p.folders.getFirst().uuid),
            index.get(p.folders.get(1).uuid),
            index.get(p.noise2D.get(1).uuid));
    assertEquals(List.of("outer"), labels(tree.included(index, payload)));
    tree.open(p.folders.getFirst().uuid, true);
    tree.open(p.folders.get(1).uuid, true);
    assertEquals(List.of("outer", "inner", "needle"), labels(tree.included(index, payload)));
    assertEquals(
        List.of("needle"), labels(tree.included(index, List.of(index.get(p.noise2D.get(1).uuid)))));
    assertEquals(
        2, tree.included(index, List.of(index.get(p.noise2D.get(1).uuid))).getFirst().depth());
  }

  @Test
  void unchangedInputsAndRepeatedExpansionReuseResultsWhileReplacementPrunesOwnership() {
    var p = fixture();
    var index = new ResourceTreeIndex(p);
    var tree = new ExportTree();
    var first = tree.choices(index, "", false, Set.of());
    tree.open(p.folders.getFirst().uuid, false);
    assertSame(first, tree.choices(index, "  ", false, Set.of(p.noise2D.getFirst().uuid)));
    assertEquals(1, tree.projections());
    assertEquals(1, tree.builds());
    tree.open(p.folders.getFirst().uuid, true);
    var expanded = tree.choices(index, "", false, Set.of());
    tree.open(p.folders.getFirst().uuid, true);
    assertSame(expanded, tree.choices(index, "", false, Set.of()));
    p.noise2D.getFirst().id = "renamed";
    var replacement = new ResourceTreeIndex(p);
    var rows = tree.choices(replacement, "", false, Set.of());
    assertTrue(labels(rows).contains("renamed"));
    assertTrue(labels(expanded).contains("alpha"));
    assertEquals(2, tree.builds());
    tree.clear();
    assertNotSame(rows, tree.choices(replacement, "", false, Set.of()));
    assertEquals(3, tree.builds());
  }

  @Test
  void returnedRowsAreImmutableAndForeignMetadataOrLeafExpansionRejects() {
    var p = fixture();
    var index = new ResourceTreeIndex(p);
    var tree = new ExportTree();
    var rows = tree.choices(index, "needle", false, Set.of());
    assertThrows(UnsupportedOperationException.class, rows::clear);
    assertThrows(IllegalArgumentException.class, () -> tree.open(p.noise2D.getFirst().uuid, true));
    assertThrows(IllegalArgumentException.class, () -> tree.open(UUID.randomUUID(), true));
    var actual = index.get(p.noise2D.getFirst().uuid);
    var fake =
        new ResourceTreeIndex.Node(
            actual.uuid(),
            "foreign",
            actual.kind(),
            actual.root(),
            actual.parent(),
            actual.enabled(),
            actual.swatch());
    assertThrows(IllegalArgumentException.class, () -> tree.included(index, List.of(fake)));
    assertEquals("alpha", p.noise2D.getFirst().id);
  }

  @Test
  void deepHierarchySearchAndCollapseRemainIterativeAndCached() {
    var p = new ProjectData();
    UUID parent = null;
    for (int i = 0; i < 12000; i++) {
      var f = folder("level-" + i, "noise2D", parent);
      p.folders.add(f);
      parent = f.uuid;
    }
    var leaf = noise("needle", parent);
    p.noise2D.add(leaf);
    var index = new ResourceTreeIndex(p);
    var tree = new ExportTree();
    assertEquals(1, tree.choices(index, "", false, Set.of()).size());
    var rows = tree.choices(index, "needle", false, Set.of());
    assertEquals(12001, rows.size());
    assertEquals(12000, rows.getLast().depth());
    assertEquals(leaf.uuid, rows.getLast().node().uuid());
    assertSame(rows, tree.choices(index, "NEEDLE", false, Set.of()));
    assertEquals(2, tree.projections());
    assertEquals(1, tree.builds());
  }
}
