package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceTreeIndexTest {
  private static Noise noise(String id, UUID parent) {
    var n = new Noise();
    n.id = id;
    n.parentFolderUuid = parent;
    return n;
  }

  private static Folder folder(String id, UUID parent) {
    var f = new Folder();
    f.id = id;
    f.rootKind = "noise2D";
    f.parentFolderUuid = parent;
    return f;
  }

  private static List<String> labels(List<ResourceTreeIndex.Row> rows) {
    return rows.stream().map(r -> r.node().id()).toList();
  }

  private static ProjectData fixture() {
    var p = new ProjectData();
    var outer = folder("outer", null);
    var inner = folder("inner", outer.uuid);
    p.folders.addAll(List.of(outer, inner));
    var a = noise("alpha", outer.uuid);
    var b = noise("needle", inner.uuid);
    var c = noise("omega", outer.uuid);
    var root = noise("root", null);
    p.noise2D.addAll(List.of(a, b, c, root));
    outer.childOrder.addAll(List.of(c.uuid, inner.uuid, a.uuid));
    return p;
  }

  @Test
  void canonicalSiblingOrderAndExplicitExpansionMatchAuthoredChildOrder() {
    var p = fixture();
    var index = new ResourceTreeIndex(p);
    assertEquals(List.of("outer", "root"), labels(index.project("noise2D", Map.of(), "")));
    var open = new HashMap<UUID, Boolean>();
    p.folders.forEach(f -> open.put(f.uuid, true));
    var rows = index.project("noise2D", open, "");
    assertEquals(List.of("outer", "omega", "inner", "needle", "alpha", "root"), labels(rows));
    assertEquals(
        List.of(0, 1, 1, 2, 1, 0), rows.stream().map(ResourceTreeIndex.Row::depth).toList());
    assertEquals(
        List.of("outer", "omega", "inner", "alpha", "root"),
        labels(index.project("noise2D", Map.of(p.folders.getFirst().uuid, true), "")));
  }

  @Test
  void descendantSearchRevealsAncestorsAndMatchingContainersRevealContents() {
    var index = new ResourceTreeIndex(fixture());
    assertEquals(
        List.of("outer", "inner", "needle"),
        labels(index.project("noise2D", Map.of(), " NEEDLE ")));
    assertEquals(
        List.of("outer", "omega", "inner", "needle", "alpha"),
        labels(index.project("noise2D", Map.of(), "outer")));
    assertTrue(index.project("noise2D", Map.of(), "missing").isEmpty());
  }

  @Test
  void searchDoesNotOverwriteExpansionAndViewReusesOnlyMatchingInputs() {
    var p = fixture();
    var view = new ResourceTreeView();
    view.bind(new ResourceTreeIndex(p));
    var original = view.rows("noise2D", "");
    assertSame(original, view.rows("noise2D", "  "));
    assertEquals(1, view.projections());
    assertEquals(3, view.rows("noise2D", "needle").size());
    assertEquals(labels(original), labels(view.rows("noise2D", "")));
    view.open(p.folders.getFirst().uuid, true);
    assertEquals(5, view.rows("noise2D", "").size());
    long count = view.projections();
    view.open(p.folders.getFirst().uuid, true);
    view.rows("noise2D", "");
    assertEquals(count, view.projections());
  }

  @Test
  void layersDefaultOpenAndOperationSearchKeepsItsOwningLayer() {
    var p = new ProjectData();
    var layer = new Layer();
    layer.id = "terrain";
    var a = new Operation();
    a.id = "fill";
    var b = new Operation();
    b.id = "cut";
    b.enabled = false;
    layer.operations.addAll(List.of(a, b));
    p.worldGenerator.layers.add(layer);
    var index = new ResourceTreeIndex(p);
    assertEquals(List.of("terrain", "fill", "cut"), labels(index.project("world", Map.of(), "")));
    assertFalse(index.get(b.uuid).enabled());
    assertEquals(List.of("terrain"), labels(index.project("world", Map.of(layer.uuid, false), "")));
    assertEquals(
        List.of("terrain", "cut"),
        labels(index.project("world", Map.of(layer.uuid, false), "cut")));
    assertEquals(
        List.of("terrain", "fill", "cut"), labels(index.project("world", Map.of(), "terrain")));
  }

  @Test
  void shiftRangesFollowDisplayedSiblingOrderAndExcludeFilteredHiddenAndOtherParents() {
    var p = fixture();
    var index = new ResourceTreeIndex(p);
    var rows = index.project("noise2D", Map.of(), "outer");
    UUID omega = p.noise2D.get(2).uuid,
        alpha = p.noise2D.get(0).uuid,
        inner = p.folders.get(1).uuid,
        needle = p.noise2D.get(1).uuid;
    assertEquals(List.of(omega, inner, alpha), ResourceTreeIndex.range(rows, omega, alpha));
    assertEquals(List.of(omega, inner, alpha), ResourceTreeIndex.range(rows, alpha, omega));
    assertTrue(ResourceTreeIndex.range(rows, omega, needle).isEmpty());
    assertTrue(
        ResourceTreeIndex.range(index.project("noise2D", Map.of(), "alpha"), omega, alpha)
            .isEmpty());
    assertTrue(
        ResourceTreeIndex.range(index.project("noise2D", Map.of(), ""), omega, alpha).isEmpty());
  }

  @Test
  void metadataSnapshotsOwnLabelsColorsAndOrdersWithoutHoldingMutableResources() {
    var p = fixture();
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var index = new ResourceTreeIndex(p);
    var labels = labels(index.project("noise2D", Map.of(), "outer"));
    p.noise2D.get(0).id = "renamed";
    p.folders.getFirst().childOrder.clear();
    type.color.r = 0;
    assertEquals(labels, labels(index.project("noise2D", Map.of(), "outer")));
    assertEquals(200, index.get(type.uuid).swatch().r());
    assertThrows(UnsupportedOperationException.class, () -> index.kind("voxel", "").clear());
  }

  @Test
  void cacheInvalidatesOnRevisionAndProjectReplacementButNotQueriesOrNavigation() {
    var p = fixture();
    var cache = new ResourceTreeIndex.Cache();
    var first = cache.get(p, 0);
    for (int i = 0; i < 100; i++) assertSame(first, cache.get(p, 0));
    assertEquals(1, cache.builds());
    p.noise2D.get(0).id = "changed";
    assertNotSame(first, cache.get(p, 1));
    assertEquals("changed", cache.get(p, 1).get(p.noise2D.get(0).uuid).id());
    assertNotSame(cache.get(p, 1), cache.get(ProjectIO.copy(p), 1));
    assertEquals(3, cache.builds());
    cache.clear();
    assertNotSame(first, cache.get(p, 0));
  }

  @Test
  void committedRenameUndoRedoRefreshIndexWithoutChangingIdentities() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var n = noise("original", null);
      assertTrue(state.change(p -> p.noise2D.add(n)));
      var cache = new ResourceTreeIndex.Cache();
      var before = cache.get(state.project(), state.revision);
      assertTrue(state.change(p -> ResourceGraph.rename(p, n.uuid, "renamed")));
      assertEquals("renamed", cache.get(state.project(), state.revision).get(n.uuid).id());
      state.document.undo();
      state.revision++;
      assertEquals("original", cache.get(state.project(), state.revision).get(n.uuid).id());
      state.document.redo();
      state.revision++;
      assertEquals("renamed", cache.get(state.project(), state.revision).get(n.uuid).id());
      assertEquals("original", before.get(n.uuid).id());
    }
  }

  @Test
  void deeplyNestedProjectionUsesBoundedCallStackAndRetainsEveryAncestor() {
    var p = new ProjectData();
    UUID parent = null;
    for (int i = 0; i < 12000; i++) {
      var f = folder("level-" + i, parent);
      p.folders.add(f);
      parent = f.uuid;
    }
    p.noise2D.add(noise("deep-needle", parent));
    var rows = new ResourceTreeIndex(p).project("noise2D", Map.of(), "deep-needle");
    assertEquals(12001, rows.size());
    assertEquals(12000, rows.getLast().depth());
  }

  @Test
  void wideListsHaveExactFilterMembershipAcrossKindsAndLocaleChanges() {
    var p = new ProjectData();
    for (int i = 0; i < 25000; i++) {
      var v = new VoxelType();
      v.id = "item-" + i;
      p.voxelTypes.add(v);
    }
    var set = new Membership();
    set.id = "item-set";
    p.voxelSets.add(set);
    var index = new ResourceTreeIndex(p);
    assertEquals(25000, index.kind("voxel", "").size());
    assertEquals(1, index.kind("set", "").size());
    var expected =
        p.voxelTypes.stream().filter(v -> v.id.contains("123")).map(v -> v.uuid).toList();
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      var starts =
          p.voxelTypes.stream().filter(v -> v.id.startsWith("item-123")).map(v -> v.uuid).toList();
      assertEquals(
          starts,
          index.kind("voxel", "ITEM-123").stream().map(ResourceTreeIndex.Node::uuid).toList());
    } finally {
      Locale.setDefault(previous);
    }
    assertEquals(
        expected, index.kind("voxel", "123").stream().map(ResourceTreeIndex.Node::uuid).toList());
  }

  @Test
  void bindingFreshSnapshotDropsDeletedExpansionAndRejectsInvalidContainers() {
    var p = fixture();
    var view = new ResourceTreeView();
    view.bind(new ResourceTreeIndex(p));
    view.open(p.folders.getFirst().uuid, true);
    assertThrows(IllegalArgumentException.class, () -> view.open(p.noise2D.getFirst().uuid, true));
    view.bind(new ResourceTreeIndex(new ProjectData()));
    assertTrue(view.rows("noise2D", "").isEmpty());
    view.clear();
    view.bind(new ResourceTreeIndex(p));
    assertEquals(List.of("outer", "root"), labels(view.rows("noise2D", "")));
  }

  @Test
  void projectingAndFilteringPreserveDocumentDraftHistoryAndNumericalSourceIdentity() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var n = noise("field", null);
      assertTrue(state.change(p -> p.noise2D.add(n)));
      state.select("noise2D", n.uuid);
      var before = ProjectIO.JSON.valueToTree(state.project());
      Object draft = state.draft;
      long revision = state.revision, history = state.document.historyBytes();
      boolean dirty = state.document.dirty();
      var source = PreviewIdentity.of(state.project(), 0, n.uuid);
      var index = new ResourceTreeIndex(state.project());
      var view = new ResourceTreeView();
      view.bind(index);
      for (String query : List.of("", "field", "missing", "FIELD")) {
        view.rows("noise2D", query);
        index.noises(query);
        index.kind("voxel", query);
      }
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertSame(draft, state.draft);
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertEquals(dirty, state.document.dirty());
      assertEquals(source, PreviewIdentity.of(state.project(), 0, n.uuid));
    }
  }
}
