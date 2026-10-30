package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TooltipTextTest {
  private static Function<String, TooltipText.Size> cells(int columns) {
    return text -> {
      int lines = 1, column = 0, widest = 0;
      for (int codepoint : text.codePoints().toArray()) {
        if (codepoint == '\n') {
          lines++;
          column = 0;
          continue;
        }
        if (column == columns) {
          lines++;
          column = 0;
        }
        widest = Math.max(widest, ++column);
      }
      return new TooltipText.Size(widest * 5, lines * 10);
    };
  }

  @Test
  void completeTextRetainsIdentityAndExactHashPercentUnicodeAndNewlines() {
    String source = "100% ### snow 雪 😀\nnext line";
    var result = TooltipText.fit(source, 400, 100, cells(80));
    assertSame(source, result.text());
    assertFalse(result.truncated());
    assertEquals(new TooltipText.Size(85, 20), result.size());
  }

  @Test
  void independentCellBudgetEndsWithAnExplicitMarkerAndFitsBothAxes() {
    String source = "0123456789".repeat(30);
    var result = TooltipText.fit(source, 50, 30, cells(10));
    assertEquals("012345678901234567890123456...", result.text());
    assertEquals(new TooltipText.Size(50, 30), result.size());
    assertTrue(result.truncated());
    assertEquals(300, source.length());
  }

  @Test
  void characterCapNeverCutsASurrogatePairOrMeasuresTheCompleteHugeSource() {
    String source = "x".repeat(4095) + "😀" + "z".repeat(100000);
    var calls = new AtomicInteger();
    var result =
        TooltipText.fit(
            source,
            50000,
            100,
            value -> {
              calls.incrementAndGet();
              assertTrue(value.length() <= TooltipText.MAX_CHARACTERS + 3);
              assertTrue(value.chars().noneMatch(c -> Character.isSurrogate((char) c)));
              return new TooltipText.Size(value.length(), 10);
            });
    assertEquals("x".repeat(4095) + "...", result.text());
    assertTrue(result.truncated());
    assertTrue(calls.get() <= 15);
  }

  @Test
  void supplementaryCodepointsSurviveGeometricTruncation() {
    var result = TooltipText.fit("😀😀😀😀😀", 20, 20, cells(4));
    assertEquals("😀😀😀😀😀", result.text());
    assertFalse(result.truncated());
    var limited = TooltipText.fit("😀".repeat(100), 20, 20, cells(4));
    assertEquals("😀".repeat(5) + "...", limited.text());
    assertTrue(limited.truncated());
  }

  @Test
  void noRoomForTheMarkerReturnsAnEmptyPreviewWithoutInventingText() {
    var result = TooltipText.fit("full source", 5, 5, cells(1));
    assertEquals("", result.text());
    assertTrue(result.truncated());
    assertEquals(new TooltipText.Size(0, 0), result.size());
  }

  @Test
  void invalidBudgetsAndNativeMeasurementsRejectWithoutCoercion() {
    for (float value : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY})
      assertThrows(IllegalArgumentException.class, () -> TooltipText.fit("x", value, 10, cells(1)));
    assertThrows(IllegalArgumentException.class, () -> new TooltipText.Size(Float.NaN, 1));
    assertThrows(IllegalArgumentException.class, () -> new TooltipText.Size(1, -1));
    assertThrows(NullPointerException.class, () -> TooltipText.fit("x", 10, 10, text -> null));
  }

  @Test
  void explicitLineBreaksCountAgainstTheVisibleBudget() {
    var result = TooltipText.fit("one\ntwo\nthree\nfour", 100, 20, cells(20));
    assertEquals("one\ntwo...", result.text());
    assertEquals(20, result.size().height());
    assertTrue(result.truncated());
  }
}
