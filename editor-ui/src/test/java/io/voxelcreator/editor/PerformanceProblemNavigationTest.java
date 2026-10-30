package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.editor.PerformancePreferences.Field;
import java.util.*;
import org.junit.jupiter.api.Test;

class PerformanceProblemNavigationTest {
  private Map<Field, String> text() {
    return new EnumMap<>(new PerformanceEditSession(new UserConfig()).text());
  }

  @Test
  void matchingRawTextIsNotParsedOrNormalizedByNavigation() {
    var raw = text();
    raw.put(Field.RENDER_DISTANCE, "-");
    assertNull(PerformanceProblemNavigation.first(raw, raw, Map.of()));
    assertEquals("-", raw.get(Field.RENDER_DISTANCE));
  }

  @Test
  void oversizedDisplayedTextPrecedesAnOwnedValidationProblem() {
    var raw = text();
    var shown = new EnumMap<>(raw);
    shown.put(Field.PREVIEW_CELLS, "9".repeat(129));
    assertEquals(
        Field.PREVIEW_CELLS,
        PerformanceProblemNavigation.first(
            raw, shown, Map.of(Field.DEFAULT_EXTENT, "Extent exceeds maximum")));
    assertNotEquals(shown.get(Field.PREVIEW_CELLS), raw.get(Field.PREVIEW_CELLS));
    assertEquals(129, shown.get(Field.PREVIEW_CELLS).length());
  }

  @Test
  void enumOrderDeterminesTheDestinationRatherThanProblemInsertionOrder() {
    var raw = text();
    var problems = new LinkedHashMap<Field, String>();
    problems.put(Field.STREAM_CHUNKS, "Invalid chunk count");
    problems.put(Field.WORKERS, "Invalid workers");
    assertEquals(Field.WORKERS, PerformanceProblemNavigation.first(raw, raw, problems));
  }

  @Test
  void coupledExtentFeedbackIsRevealedWithoutRepairingEitherField() {
    var session = new PerformanceEditSession(new UserConfig());
    session.edit(Field.MAX_DIMENSION, "16");
    session.edit(Field.DEFAULT_EXTENT, "32");
    var before = session.text();
    assertEquals(
        Field.DEFAULT_EXTENT,
        PerformanceProblemNavigation.first(before, before, session.feedback().problems()));
    assertSame(before, session.text());
    assertEquals("16", session.text().get(Field.MAX_DIMENSION));
    assertEquals("32", session.text().get(Field.DEFAULT_EXTENT));
  }
}
