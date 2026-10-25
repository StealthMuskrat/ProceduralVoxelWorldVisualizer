package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BookmarkTransactionsTest {
  static UUID add(DocumentSession document, Tab tab, String name, Coordinates coordinates) {
    var draft = Draft.add(document.data(), tab, coordinates);
    draft.name(name);
    assertTrue(document.edit(draft::applyTo).isEmpty());
    return draft.uuid();
  }

  @Test void captureUsesContainingCellsAndIgnoresUnused2DY() {
    assertEquals(new Coordinates(-1, null, -3), BookmarkTransactions.capture(Tab.NOISE_2D, -.1, Double.NaN, -2.1));
    assertEquals(new Coordinates(-1, -2, 3), BookmarkTransactions.capture(Tab.WORLD, -.1, -1.9, 3.9));
    assertEquals(new Coordinates(0, 0, 0), BookmarkTransactions.capture(Tab.NOISE_3D, -0., 0, .9));
  }

  @Test void captureChecksFiniteValuesAndIntegerBoundariesBeforeNarrowing() {
    assertEquals(Integer.MAX_VALUE, BookmarkTransactions.capture(Tab.WORLD, 2147483647.9, 0, 0).x());
    assertEquals(Integer.MIN_VALUE, BookmarkTransactions.capture(Tab.WORLD, -2147483648., 0, 0).x());
    for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2147483648., -2147483648.1})
      assertThrows(IllegalArgumentException.class, () -> BookmarkTransactions.capture(Tab.WORLD, invalid, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> BookmarkTransactions.capture(Tab.NOISE_3D, 0, Double.NaN, 0));
  }

  @Test void threeCollectionsAllowDuplicateNamesAndRemainIndependent() {
    var document = new DocumentSession();
    for (var tab : Tab.values()) add(document, tab, "duplicate", new Coordinates(-3, tab.threeDimensional() ? -5 : null, 7));
    var ids = new HashSet<UUID>();
    for (var tab : Tab.values()) {
      var list = BookmarkTransactions.list(document.data(), tab);
      assertEquals(1, list.size());
      ids.add(list.getFirst().uuid);
      assertEquals(tab.threeDimensional(), list.getFirst().y != null);
    }
    assertEquals(3, ids.size());
    assertTrue(Validator.validate(document.data()).isEmpty());
  }

  @Test void invalidCoordinateShapesRejectBeforeMutating() {
    var document = new DocumentSession();
    assertThrows(IllegalArgumentException.class, () -> Draft.add(document.data(), Tab.NOISE_2D, new Coordinates(1, 2, 3)));
    assertThrows(IllegalArgumentException.class, () -> Draft.add(document.data(), Tab.WORLD, new Coordinates(1, null, 3)));
    UUID id = add(document, Tab.NOISE_2D, "point", new Coordinates(1, null, 3));
    var before = ProjectIO.JSON.valueToTree(document.data());
    assertThrows(IllegalArgumentException.class, () -> BookmarkTransactions.update(document.data(), Tab.NOISE_2D, id, new Coordinates(1, 2, 3)));
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
  }

  @Test void cancelingPrivateAddAndRenameDraftsHasNoDocumentEffects() {
    var document = new DocumentSession();
    UUID id = add(document, Tab.WORLD, "point", new Coordinates(1, 2, 3));
    var before = ProjectIO.JSON.valueToTree(document.data());
    long history = document.historyBytes();
    var add = Draft.add(document.data(), Tab.WORLD, new Coordinates(-3, -5, -7));
    add.name("discarded");
    add.coordinates(new Coordinates(8, 9, 10));
    var rename = Draft.rename(document.data(), Tab.WORLD, id);
    rename.name("discarded too");
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    assertEquals(history, document.historyBytes());
  }

  @Test void addIsOneUndoActionAndRedoPreservesItsUuid() {
    var document = new DocumentSession();
    var coordinates = new Coordinates(-3, 5, -7);
    UUID id = add(document, Tab.NOISE_3D, "point", coordinates);
    assertTrue(document.dirty());
    document.undo();
    assertTrue(document.data().bookmarks.noise3D.isEmpty());
    assertFalse(document.canUndo());
    assertFalse(document.dirty());
    document.redo();
    assertEquals(id, document.data().bookmarks.noise3D.getFirst().uuid);
    assertEquals(coordinates, BookmarkTransactions.coordinates(document.data().bookmarks.noise3D.getFirst()));
  }

  @Test void renamePreservesCoordinatesAndCanUndoWithoutChangingOtherTabs() {
    var document = new DocumentSession();
    UUID id = add(document, Tab.WORLD, "original", new Coordinates(-3, 5, -7));
    var rename = Draft.rename(document.data(), Tab.WORLD, id);
    rename.name("renamed");
    assertThrows(IllegalStateException.class, () -> rename.coordinates(new Coordinates(0, 0, 0)));
    assertTrue(document.edit(rename::applyTo).isEmpty());
    assertEquals("renamed", BookmarkTransactions.find(document.data(), Tab.WORLD, id).name);
    assertEquals(new Coordinates(-3, 5, -7), BookmarkTransactions.coordinates(BookmarkTransactions.find(document.data(), Tab.WORLD, id)));
    document.undo();
    assertEquals("original", BookmarkTransactions.find(document.data(), Tab.WORLD, id).name);
    assertTrue(document.data().bookmarks.noise2D.isEmpty());
  }

  @Test void renameRejectsChangedOrDeletedTargetWithoutHistory() {
    var document = new DocumentSession();
    UUID id = add(document, Tab.WORLD, "original", new Coordinates(1, 2, 3));
    var draft = Draft.rename(document.data(), Tab.WORLD, id);
    draft.name("rejected");
    document.edit(p -> BookmarkTransactions.update(p, Tab.WORLD, id, new Coordinates(9, 8, 7)));
    var before = ProjectIO.JSON.valueToTree(document.data());
    long history = document.historyBytes();
    assertThrows(IllegalStateException.class, () -> document.edit(draft::applyTo));
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    assertEquals(history, document.historyBytes());
    document.edit(p -> BookmarkTransactions.delete(p, Tab.WORLD, id));
    assertThrows(IllegalStateException.class, () -> document.edit(draft::applyTo));
  }

  @Test void renameCannotFollowATargetMovedToAnotherTab() {
    var p = new ProjectData();
    var b = new Bookmark(); b.name = "point"; b.y = 1;
    p.bookmarks.world.add(b);
    var draft = Draft.rename(p, Tab.WORLD, b.uuid);
    p.bookmarks.world.clear(); p.bookmarks.noise3D.add(b);
    assertThrows(IllegalStateException.class, () -> draft.applyTo(p));
  }

  @Test void unrelatedEditsAreMergedInsteadOfBeingOverwritten() {
    var document = new DocumentSession();
    UUID id = add(document, Tab.WORLD, "point", new Coordinates(1, 2, 3));
    var draft = Draft.rename(document.data(), Tab.WORLD, id);
    draft.name("renamed");
    document.edit(p -> p.project.seed = 77);
    add(document, Tab.NOISE_2D, "other", new Coordinates(-1, null, 3));
    assertTrue(document.edit(draft::applyTo).isEmpty());
    assertEquals(77, document.data().project.seed);
    assertEquals(1, document.data().bookmarks.noise2D.size());
  }

  @Test void foreignProjectAndWorldIdentitiesRejectDrafts() {
    var p = new ProjectData();
    var draft = Draft.add(p, Tab.WORLD, new Coordinates(1, 2, 3));
    draft.name("point");
    var foreign = ProjectIO.copy(p); foreign.project.uuid = UUID.randomUUID();
    var changedProject = foreign;
    assertThrows(IllegalStateException.class, () -> draft.applyTo(changedProject));
    var changedWorld = ProjectIO.copy(p); changedWorld.worldGenerator.uuid = UUID.randomUUID();
    assertThrows(IllegalStateException.class, () -> draft.applyTo(changedWorld));
    assertTrue(p.bookmarks.world.isEmpty());
  }

  @Test void repeatedAddIsRejectedAndInvalidNamesDoNotPublish() {
    var document = new DocumentSession();
    var draft = Draft.add(document.data(), Tab.WORLD, new Coordinates(1, 2, 3));
    for (String invalid : Arrays.asList(null, "", "  \t")) {
      draft.name(invalid);
      assertThrows(IllegalArgumentException.class, () -> document.edit(draft::applyTo));
      assertFalse(document.dirty()); assertFalse(document.canUndo());
    }
    draft.name("valid"); assertTrue(document.edit(draft::applyTo).isEmpty());
    assertThrows(IllegalStateException.class, () -> document.edit(draft::applyTo));
    assertEquals(1, document.data().bookmarks.world.size());
  }

  @Test void updateAndDeleteAreScopedAndUndoable() {
    var document = new DocumentSession();
    UUID id = add(document, Tab.NOISE_2D, "point", new Coordinates(1, null, 3));
    assertThrows(IllegalStateException.class, () -> BookmarkTransactions.delete(document.data(), Tab.WORLD, id));
    assertTrue(document.edit(p -> BookmarkTransactions.update(p, Tab.NOISE_2D, id, new Coordinates(-4, null, -6))).isEmpty());
    document.edit(p -> BookmarkTransactions.delete(p, Tab.NOISE_2D, id));
    document.undo();
    assertEquals(new Coordinates(-4, null, -6), BookmarkTransactions.coordinates(BookmarkTransactions.find(document.data(), Tab.NOISE_2D, id)));
    document.undo();
    assertEquals(new Coordinates(1, null, 3), BookmarkTransactions.coordinates(BookmarkTransactions.find(document.data(), Tab.NOISE_2D, id)));
  }

  @Test void validationReportsPreciseIndexedPointers() {
    var p = new ProjectData();
    var b = new Bookmark(); b.name = ""; b.y = 3; p.bookmarks.noise2D.add(b);
    var issues = Validator.validate(p);
    assertTrue(issues.stream().anyMatch(d -> d.jsonPointer().equals("/bookmarks/noise2D/0/name")));
    assertTrue(issues.stream().anyMatch(d -> d.jsonPointer().equals("/bookmarks/noise2D/0/y")));
    p.bookmarks.noise2D.clear(); b.name = "point"; b.y = null; p.bookmarks.world.add(b);
    assertTrue(Validator.validate(p).stream().anyMatch(d -> d.jsonPointer().equals("/bookmarks/world/0/y")));
  }

  @Test void legacy2DYMayBeAbsentAnd3DYIsRequired() throws Exception {
    var p = new ProjectData();
    var b = new Bookmark(); b.name = "point"; p.bookmarks.noise2D.add(b);
    var tree = ProjectIO.JSON.valueToTree(p);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("bookmarks").path("noise2D").get(0)).remove("y");
    var io = new ProjectIO();
    assertTrue(io.parse(tree.toString()).success());
    p.bookmarks.noise2D.clear(); p.bookmarks.world.add(b);
    assertFalse(io.parse(ProjectIO.JSON.writeValueAsString(p)).success());
  }
  @Test void missingBookmarkFieldsAreRejectedInsteadOfTakingPrimitiveDefaults() {
    var p = new ProjectData(); var b = new Bookmark(); b.name = "point"; p.bookmarks.noise2D.add(b);
    for (String field : List.of("uuid", "name", "x", "z")) {
      var tree = ProjectIO.JSON.valueToTree(p);
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("bookmarks").path("noise2D").get(0)).remove(field);
      var result = new ProjectIO().parse(tree.toString());
      assertFalse(result.success());
      assertEquals("/bookmarks/noise2D/0/" + field, result.diagnostics().getFirst().jsonPointer());
    }
  }

  @Test void nonintegerAndOutOfRangeBookmarkCoordinatesRejectBeforePublication() {
    var p = new ProjectData(); var b = new Bookmark(); b.name = "point"; b.y = 0; p.bookmarks.world.add(b);
    for (var invalid : List.of(ProjectIO.JSON.valueToTree(2147483648L), ProjectIO.JSON.valueToTree(1.5), ProjectIO.JSON.valueToTree("1"))) {
      var tree = ProjectIO.JSON.valueToTree(p);
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("bookmarks").path("world").get(0)).set("y", invalid);
      var result = new ProjectIO().parse(tree.toString());
      assertFalse(result.success());
      assertEquals("/bookmarks/world/0/y", result.diagnostics().getFirst().jsonPointer());
    }
  }

  @Test void bookmarkUuidsAreGloballyUniqueDespiteDuplicateNamesBeingAllowed() {
    var p = new ProjectData(); var a = new Bookmark(); a.name = "duplicate"; p.bookmarks.noise2D.add(a);
    var b = new Bookmark(); b.uuid = a.uuid; b.name = "duplicate"; b.y = 0; p.bookmarks.world.add(b);
    assertTrue(Validator.validate(p).stream().anyMatch(d -> d.code().equals("DUPLICATE_UUID")
        && d.jsonPointer().equals("/bookmarks/world/0/uuid")));
  }

}
