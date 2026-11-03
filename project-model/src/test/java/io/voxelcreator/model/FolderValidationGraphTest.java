package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred hierarchy validation, exact order locations and caller-owned authoring contracts. */
class FolderValidationGraphTest {
  private static Folder folder(ProjectData p, String id, UUID parent, String root) {
    var f = new Folder();
    f.id = id;
    f.parentFolderUuid = parent;
    f.rootKind = root;
    p.folders.add(f);
    return f;
  }

  private static ProjectData chain(int size) {
    var p = new ProjectData();
    UUID parent = null;
    for (int i = 0; i < size; i++) parent = folder(p, "folder-" + i, parent, "noise2D").uuid;
    Collections.reverse(p.folders);
    return p;
  }

  private static List<Diagnostic> issues(ProjectData p, String code) {
    return Validator.validate(p).stream().filter(d -> d.code().equals(code)).toList();
  }

  private static void exact(ProjectData p, Diagnostic d, String pointer, UUID owner, String key) {
    assertEquals(pointer, d.jsonPointer());
    assertEquals(owner, d.sourceUuid());
    assertEquals(key, d.parameterKey());
    assertFalse(ProjectIO.JSON.valueToTree(p).at(pointer).isMissingNode());
  }

  @Test
  void reverseAuthored8192FolderChainIsValidWithoutChangingValuesOrOrders() {
    var p = chain(8192);
    for (int i = 0; i + 1 < p.folders.size(); i++)
      p.folders.get(i + 1).childOrder.add(p.folders.get(i).uuid);
    p.project.rootOrder.put("noise2D", new ArrayList<>(List.of(p.folders.getLast().uuid)));
    var before = ProjectIO.JSON.valueToTree(p);
    assertTrue(Validator.validate(p).isEmpty());
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }

  @Test
  void manySharedAncestorsAndRepeatedNamesInSeparateScopesRemainValid() {
    var p = chain(1024);
    var parent = p.folders.getFirst();
    for (int i = 0; i < 2048; i++) {
      var branch = folder(p, "branch-" + i, parent.uuid, "noise2D");
      var leaf = folder(p, "same-name", branch.uuid, "noise2D");
      branch.childOrder.add(leaf.uuid);
    }
    assertTrue(Validator.validate(p).isEmpty());
  }

  @Test
  void deepCycleHasOneCompleteRouteAndExactClosingParentAuthority() {
    var p = chain(2048);
    p.folders.getLast().parentFolderUuid = p.folders.getFirst().uuid;
    var before = ProjectIO.JSON.valueToTree(p);
    var ds = issues(p, "FOLDER_CYCLE");
    assertEquals(1, ds.size());
    var route = new ArrayList<String>();
    p.folders.forEach(f -> route.add(f.uuid.toString()));
    route.add(p.folders.getFirst().uuid.toString());
    assertEquals("Folder ancestry cycle " + String.join(" -> ", route), ds.getFirst().message());
    exact(
        p,
        ds.getFirst(),
        "/folders/2047/parentFolderUuid",
        p.folders.getLast().uuid,
        "parentFolderUuid");
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }

  @Test
  void traversalPrefixIsExcludedFromCycleRouteAndSharedIncomingBranchesDoNotRepeatIt() {
    var p = new ProjectData();
    var prefix = folder(p, "prefix", null, "noise2D");
    var a = folder(p, "a", null, "noise2D");
    var b = folder(p, "b", a.uuid, "noise2D");
    prefix.parentFolderUuid = a.uuid;
    a.parentFolderUuid = b.uuid;
    folder(p, "other-prefix", b.uuid, "noise2D");
    var ds = issues(p, "FOLDER_CYCLE");
    assertEquals(1, ds.size());
    assertEquals(
        "Folder ancestry cycle " + a.uuid + " -> " + b.uuid + " -> " + a.uuid,
        ds.getFirst().message());
    exact(p, ds.getFirst(), "/folders/2/parentFolderUuid", b.uuid, "parentFolderUuid");
  }

  @Test
  void disconnectedCyclesRetainAuthoredComponentOrderAcrossProtectedRoots() {
    var p = new ProjectData();
    var a = folder(p, "a", null, "world");
    var b = folder(p, "b", a.uuid, "world");
    a.parentFolderUuid = b.uuid;
    var c = folder(p, "c", null, "noise3D");
    var d = folder(p, "d", c.uuid, "noise3D");
    c.parentFolderUuid = d.uuid;
    var ds = issues(p, "FOLDER_CYCLE");
    assertEquals(2, ds.size());
    exact(p, ds.get(0), "/folders/1/parentFolderUuid", b.uuid, "parentFolderUuid");
    exact(p, ds.get(1), "/folders/3/parentFolderUuid", d.uuid, "parentFolderUuid");
  }

  @Test
  void selfParentReportsItsOwnFieldWithoutRecursion() {
    var p = new ProjectData();
    var f = folder(p, "self", null, "world");
    f.parentFolderUuid = f.uuid;
    var d = issues(p, "FOLDER_CYCLE").getFirst();
    assertEquals("Folder ancestry cycle " + f.uuid + " -> " + f.uuid, d.message());
    exact(p, d, "/folders/0/parentFolderUuid", f.uuid, "parentFolderUuid");
  }

  @Test
  void unresolvedParentKeepsPreciseLocationAndNeverInventsAnAncestryNode() {
    var p = new ProjectData();
    var f = folder(p, "missing", UUID.randomUUID(), "noise2D");
    var ds = Validator.validate(p);
    assertEquals(1, ds.size());
    assertEquals("INVALID_REFERENCE", ds.getFirst().code());
    exact(p, ds.getFirst(), "/folders/0/parentFolderUuid", f.uuid, "parentFolderUuid");
  }

  @Test
  void crossRootAndUnknownRootFolderErrorsResolveTheActualAuthoredField() {
    var p = new ProjectData();
    var a = folder(p, "a", null, "noise2D");
    var b = folder(p, "b", a.uuid, "world");
    var unknown = folder(p, "unknown", null, "bad~/root");
    exact(
        p,
        issues(p, "INVALID_REFERENCE").getFirst(),
        "/folders/1/parentFolderUuid",
        b.uuid,
        "parentFolderUuid");
    exact(p, issues(p, "INVALID_ROOT").getFirst(), "/folders/2/rootKind", unknown.uuid, "rootKind");
  }

  @Test
  void noiseParentErrorsUseAuthoredDimensionOrderAndSurviveLaterReordering() {
    var p = new ProjectData();
    var a = new Noise();
    a.id = "a";
    a.parentFolderUuid = UUID.randomUUID();
    var b = new Noise();
    b.id = "b";
    b.dimension = 3;
    b.parentFolderUuid = UUID.randomUUID();
    p.noise2D.add(a);
    p.noise3D.add(b);
    var ds = issues(p, "INVALID_REFERENCE");
    assertEquals(2, ds.size());
    exact(p, ds.get(0), "/noise2D/0/parentFolderUuid", a.uuid, "parentFolderUuid");
    exact(p, ds.get(1), "/noise3D/0/parentFolderUuid", b.uuid, "parentFolderUuid");
    p.noise2D.addFirst(new Noise());
    assertEquals(a.uuid, ds.getFirst().sourceUuid());
  }

  @Test
  void childOrderRejectsDuplicateUnknownNestedAndCrossRootEntriesAtTheirActualIndices() {
    var p = new ProjectData();
    var parent = folder(p, "parent", null, "noise2D");
    var child = folder(p, "child", parent.uuid, "noise2D");
    var nested = folder(p, "nested", child.uuid, "noise2D");
    var world = folder(p, "world", null, "world");
    parent.childOrder.addAll(
        List.of(child.uuid, child.uuid, UUID.randomUUID(), nested.uuid, world.uuid));
    var ds = issues(p, "INVALID_ORDER");
    assertEquals(4, ds.size());
    for (int i = 0; i < ds.size(); i++)
      exact(p, ds.get(i), "/folders/0/childOrder/" + (i + 1), parent.uuid, "childOrder");
  }

  @Test
  void rootOrderRejectsDuplicateNestedCrossRootAndUnknownEntriesAtExactIndices() {
    var p = new ProjectData();
    var root = folder(p, "root", null, "noise2D");
    var nested = folder(p, "nested", root.uuid, "noise2D");
    var world = folder(p, "world", null, "world");
    p.project.rootOrder.put(
        "noise2D",
        new ArrayList<>(List.of(root.uuid, root.uuid, nested.uuid, world.uuid, UUID.randomUUID())));
    p.project.rootOrder.put("bad~/root", new ArrayList<>());
    var ds = issues(p, "INVALID_ORDER");
    assertEquals(5, ds.size());
    for (int i = 0; i < 4; i++)
      exact(p, ds.get(i), "/project/rootOrder/noise2D/" + (i + 1), null, "rootOrder");
    exact(p, ds.getLast(), "/project/rootOrder/bad~0~1root", null, "bad~/root");
  }

  @Test
  void sparseMixedOrdersAndWorldLayerChildrenStayIndependentOfExecutionOrder() {
    var p = new ProjectData();
    var world = folder(p, "world", null, "world");
    var first = new Layer();
    first.id = "first";
    first.parentFolderUuid = world.uuid;
    var second = new Layer();
    second.id = "second";
    second.parentFolderUuid = world.uuid;
    p.worldGenerator.layers.addAll(List.of(first, second));
    world.childOrder.add(second.uuid);
    p.project.rootOrder.put("world", new ArrayList<>(List.of(world.uuid)));
    var before = ProjectIO.JSON.valueToTree(p);
    assertTrue(Validator.validate(p).isEmpty());
    assertEquals(
        List.of(first.uuid, second.uuid),
        p.worldGenerator.layers.stream().map(l -> l.uuid).toList());
    assertEquals(before, ProjectIO.JSON.valueToTree(p));
  }

  @Test
  void nullOrderMembersRetainSpecificOrderErrorsRatherThanGenericStructureFailures() {
    var p = new ProjectData();
    var f = folder(p, "folder", null, "noise2D");
    f.childOrder.add(null);
    p.project.rootOrder.put("noise2D", new ArrayList<>(Arrays.asList((UUID) null)));
    var ds = Validator.validate(p);
    assertEquals(2, ds.size());
    assertTrue(ds.stream().allMatch(d -> d.code().equals("INVALID_ORDER")));
    exact(p, ds.get(0), "/folders/0/childOrder/0", f.uuid, "childOrder");
    exact(p, ds.get(1), "/project/rootOrder/noise2D/0", null, "rootOrder");
  }

  @Test
  void missingAndDuplicateFolderIdentitiesStillRejectBeforeAnyDocumentPublication() {
    var p = new ProjectData();
    var a = folder(p, "a", null, "noise2D");
    var b = folder(p, "b", null, "noise2D");
    b.uuid = a.uuid;
    var missing = folder(p, "missing", null, "noise2D");
    missing.uuid = null;
    var codes = Validator.validate(p).stream().map(Diagnostic::code).toList();
    assertTrue(codes.contains("DUPLICATE_UUID"));
    assertTrue(codes.contains("MISSING_UUID"));
    var document = new DocumentSession();
    var original = document.data();
    assertFalse(document.edit(target -> ResourceGraph.assign(target, p)).isEmpty());
    assertSame(original, document.data());
    assertFalse(document.canUndo());
  }
}
