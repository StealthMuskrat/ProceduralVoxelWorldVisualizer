package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred projection contracts; no model candidate or semantic import is rebuilt here. */
class ImportRowsTest {
  private ResourceImport.Row row(String kind, String id, String resolution) {
    return new ResourceImport.Row(
        UUID.randomUUID(),
        kind,
        id,
        resolution,
        true,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "target-" + id);
  }

  private ResourceImport.Plan plan(List<ResourceImport.Row> rows) {
    return new ResourceImport.Plan(new ProjectData(), rows, List.of());
  }

  @Test
  void normalizedQueriesReuseTheExactViewWithoutRebuilding() {
    var source = plan(List.of(row("noise2D", "Shared", "COPY")));
    var cache = new ImportRows();
    var first = cache.view(source, new ImportRows.Filter("  SHARED  ", "All", "All"));
    assertSame(first, cache.view(source, new ImportRows.Filter("shared", "All", "All")));
    assertEquals(1, cache.evaluations());
    assertEquals(1, first.rows().size());
  }

  @Test
  void kindResolutionAndTextAreAnIntersectionAndCountsDescribeTheWholePlan() {
    var copy = row("noise2D", "shared", "COPY");
    var reuse = row("noise3D", "shared", "REUSE");
    var skip = row("voxel", "other", "SKIP (dependent exclusion)");
    var source = plan(List.of(copy, reuse, skip));
    var cache = new ImportRows();
    var all = cache.view(source, new ImportRows.Filter("", "All", "All"));
    assertEquals(List.of(copy, reuse, skip), all.rows());
    var exact = cache.view(source, new ImportRows.Filter("shared", "noise3D", "REUSE"));
    assertEquals(List.of(reuse), exact.rows());
    assertEquals(new ImportRows.Counts(1, 1, 1), exact.counts());
    assertEquals(3, exact.total());
    assertEquals(
        List.of(), cache.view(source, new ImportRows.Filter("shared", "noise2D", "REUSE")).rows());
    assertEquals(
        List.of(skip), cache.view(source, new ImportRows.Filter("", "All", "SKIP")).rows());
  }

  @Test
  void everyDisplayedIdentityCanDisambiguateAResourceWithoutChangingItsRow() {
    var row = row("noise2D", "name", "COPY");
    var source = plan(List.of(row));
    var cache = new ImportRows();
    for (String text :
        List.of(
            "noise2D",
            "name",
            row.uuid().toString(),
            row.existing().toString(),
            row.targetUuid().toString(),
            row.targetId())) {
      var view = cache.view(source, new ImportRows.Filter(text, "All", "All"));
      assertEquals(1, view.rows().size());
      assertSame(row, view.rows().getFirst());
    }
  }

  @Test
  void anotherEqualValuedPlanInvalidatesTheCachedViewAndClearDoesNotResetCounters() {
    var rows = List.of(row("noise2D", "field", "COPY"));
    var first = plan(rows);
    var second = plan(rows);
    var cache = new ImportRows();
    var filter = new ImportRows.Filter("", "All", "All");
    var one = cache.view(first, filter);
    var two = cache.view(second, filter);
    assertNotSame(one, two);
    assertEquals(one, two);
    assertEquals(2, cache.evaluations());
    cache.clear();
    assertNotSame(two, cache.view(second, filter));
    assertEquals(3, cache.evaluations());
  }

  @Test
  void resultsAndOptionCatalogsAreOwnedAndInvalidFiltersReject() {
    var row = row("voxel", "é🌋", "COPY");
    var source = plan(List.of(row));
    var cache = new ImportRows();
    var view = cache.view(source, new ImportRows.Filter("É🌋", "voxel", "COPY"));
    assertEquals(List.of(row), view.rows());
    assertThrows(UnsupportedOperationException.class, () -> view.rows().clear());
    assertThrows(UnsupportedOperationException.class, () -> ImportRows.KINDS.clear());
    assertThrows(UnsupportedOperationException.class, () -> ImportRows.RESOLUTIONS.clear());
    assertThrows(
        IllegalArgumentException.class, () -> new ImportRows.Filter("", "operation", "All"));
    assertThrows(IllegalArgumentException.class, () -> new ImportRows.Filter("", "All", "replace"));
    assertThrows(NullPointerException.class, () -> new ImportRows.Filter(null, "All", "All"));
  }
}
