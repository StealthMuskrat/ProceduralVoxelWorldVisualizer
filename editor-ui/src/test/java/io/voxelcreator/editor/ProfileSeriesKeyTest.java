package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.Profiler;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProfileSeriesKeyTest {
  @Test
  void everyLogicalDimensionContributesEvenWhenDisplayLabelsAreIdentical() {
    var id = UUID.randomUUID();
    var keys =
        List.of(
            new ProfileSeriesKey(Profiler.Kind.FRAME, Profiler.Unit.COUNT, 1, null, null, "same"),
            new ProfileSeriesKey(
                Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 1, null, null, "same"),
            new ProfileSeriesKey(Profiler.Kind.FRAME, Profiler.Unit.BYTES, 1, null, null, "same"),
            new ProfileSeriesKey(Profiler.Kind.FRAME, Profiler.Unit.COUNT, 2, null, null, "same"),
            new ProfileSeriesKey(Profiler.Kind.FRAME, Profiler.Unit.COUNT, 1, id, null, "same"),
            new ProfileSeriesKey(Profiler.Kind.FRAME, Profiler.Unit.COUNT, 1, null, id, "same"),
            new ProfileSeriesKey(Profiler.Kind.FRAME, Profiler.Unit.COUNT, 1, null, null, "Same"));
    assertEquals(7, keys.stream().map(ProfileSeriesKey::widgetId).distinct().count());
  }

  @Test
  void markerNewlineSeparatorAndMalformedUnicodeLabelsHaveDistinctNativeSafeKeys() {
    var labels =
        List.of("###same", "##same", "# #same", "a/b", "a\nb", "a b", "\ud800", "\ud801", "?");
    var ids =
        labels.stream()
            .map(
                label ->
                    new ProfileSeriesKey(
                            Profiler.Kind.FRAME,
                            Profiler.Unit.COUNT,
                            Long.MIN_VALUE,
                            null,
                            null,
                            label)
                        .widgetId())
            .toList();
    assertEquals(labels.size(), new HashSet<>(ids).size());
    assertTrue(ids.stream().noneMatch(id -> id.contains("#") || id.contains("\n")));
  }

  @Test
  void changedSummariesAndProjectionPositionDoNotChangeLogicalWidgetIdentity() {
    var first =
        new Profiler.Series(
            Profiler.Kind.FRAME,
            Profiler.Unit.NANOSECONDS,
            3,
            null,
            null,
            "frame",
            1,
            5,
            5,
            5,
            5,
            5,
            5,
            0,
            0);
    var later =
        new Profiler.Series(
            Profiler.Kind.FRAME,
            Profiler.Unit.NANOSECONDS,
            3,
            null,
            null,
            "frame",
            50,
            1,
            99,
            40,
            35,
            80,
            95,
            1,
            2);
    assertEquals(ProfileSeriesKey.of(first), ProfileSeriesKey.of(later));
    assertEquals(ProfileSeriesKey.of(first).widgetId(), ProfileSeriesKey.of(later).widgetId());
  }
}
