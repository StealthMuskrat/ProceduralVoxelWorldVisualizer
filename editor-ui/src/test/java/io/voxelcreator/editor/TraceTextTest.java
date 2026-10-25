package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TraceTextTest {
  @Test
  void emptyAndExactBoundaryValuesRemainComplete() {
    assertEquals(1, TraceText.count(""));
    assertEquals("", TraceText.page("", 0));
    String text = "x".repeat(4096);
    assertEquals(1, TraceText.count(text));
    assertEquals(text, TraceText.page(text, 0));
  }

  @Test
  void longUnicodeAndMultilineValuesReconstructExactlyWithoutSplitPairs() {
    String text = ("x".repeat(4095) + "🌋\n").repeat(12);
    var copy = new StringBuilder();
    for (int i = 0; i < TraceText.count(text); i++) {
      String page = TraceText.page(text, i);
      assertTrue(page.length() <= 4097);
      assertFalse(Character.isLowSurrogate(page.charAt(0)));
      assertFalse(Character.isHighSurrogate(page.charAt(page.length() - 1)));
      copy.append(page);
    }
    assertEquals(text, copy.toString());
  }

  @Test
  void pageLengthDoesNotGrowWithTheCompleteSettingsPayload() {
    String text = "row: threshold=0.25\n".repeat(100000);
    for (int i : new int[] {0, TraceText.count(text) / 2, TraceText.count(text) - 1})
      assertTrue(TraceText.page(text, i).length() <= 4097);
    assertTrue(TraceText.count(text) > 400);
  }

  @Test
  void invalidPageRequestsRejectWithoutImplicitlyClampingOrWrapping() {
    assertThrows(IllegalArgumentException.class, () -> TraceText.page("value", -1));
    assertThrows(IllegalArgumentException.class, () -> TraceText.page("value", 1));
  }
}
