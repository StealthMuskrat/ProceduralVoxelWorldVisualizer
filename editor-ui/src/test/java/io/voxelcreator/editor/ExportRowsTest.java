package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred catalog filtering definitions independent of export preparation/file I/O. */
class ExportRowsTest {
  private ProjectData project() {
    var p = new ProjectData();
    for (String id : List.of("shared", "é🌋", "third")) {
      var n = new Noise();
      n.id = id;
      p.noise2D.add(n);
    }
    return p;
  }

  @Test
  void normalizedQueriesReuseTheViewAndUnfilteredSelectionChangesDoNotRebuildIt() {
    var p = project();
    var index = new ResourceTreeIndex(p);
    var cache = new ExportRows();
    var one = cache.rows(index, " SHARED ", false, Set.of());
    assertSame(one, cache.rows(index, "shared", false, Set.of(p.noise2D.getFirst().uuid)));
    assertEquals(1, cache.evaluations());
    assertEquals("shared", one.getFirst().id());
  }

  @Test
  void selectedOnlyOwnsItsKeyAndNeverSubstitutesForMissingUuids() {
    var p = project();
    var index = new ResourceTreeIndex(p);
    var cache = new ExportRows();
    var selected = new HashSet<>(Set.of(p.noise2D.getFirst().uuid));
    var one = cache.rows(index, "", true, selected);
    selected.clear();
    selected.add(p.noise2D.getLast().uuid);
    var two = cache.rows(index, "", true, selected);
    assertEquals("shared", one.getFirst().id());
    assertEquals("third", two.getFirst().id());
    assertNotSame(one, two);
    assertTrue(cache.rows(index, "", true, Set.of(UUID.randomUUID())).isEmpty());
    assertEquals(3, cache.evaluations());
  }

  @Test
  void replacementCatalogWithTheSameUuidInvalidatesLabelsAndClearReleasesOwnership() {
    var p = project();
    var cache = new ExportRows();
    var first = new ResourceTreeIndex(p);
    var old = cache.rows(first, "", false, Set.of());
    p.noise2D.getFirst().id = "changed";
    var next = new ResourceTreeIndex(p);
    var current = cache.rows(next, "", false, Set.of());
    assertEquals("shared", old.getFirst().id());
    assertEquals("changed", current.getFirst().id());
    assertEquals(old.getFirst().uuid(), current.getFirst().uuid());
    cache.clear();
    assertNotSame(current, cache.rows(next, "", false, Set.of()));
    assertEquals(3, cache.evaluations());
  }

  @Test
  void idKindUuidAndUnicodeQueriesIntersectSelectedOnlyWithoutChangingNodeIdentity() {
    var p = project();
    var index = new ResourceTreeIndex(p);
    var cache = new ExportRows();
    var id = p.noise2D.get(1).uuid;
    for (String text : List.of("É🌋", "noise2D", id.toString().toUpperCase(Locale.ROOT))) {
      var rows = cache.rows(index, text, true, Set.of(id));
      assertEquals(1, rows.size());
      assertSame(index.get(id), rows.getFirst());
    }
    assertTrue(cache.rows(index, "shared", true, Set.of(id)).isEmpty());
  }

  @Test
  void returnedRowsAreImmutableAndNullInputsCannotRetargetTheExistingView() {
    var index = new ResourceTreeIndex(project());
    var cache = new ExportRows();
    var rows = cache.rows(index, "", false, Set.of());
    assertThrows(UnsupportedOperationException.class, rows::clear);
    assertThrows(NullPointerException.class, () -> cache.rows(null, "", false, Set.of()));
    assertThrows(NullPointerException.class, () -> cache.rows(index, null, false, Set.of()));
    assertSame(rows, cache.rows(index, "", false, Set.of()));
    assertEquals(1, cache.evaluations());
  }
}
