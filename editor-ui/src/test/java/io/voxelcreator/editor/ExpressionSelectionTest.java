package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ExpressionSelectionTest {
  @Test
  void convertsAsciiMultilineAndUnicodeToByteOffsets() {
    assertEquals(
        new ExpressionSelection.Range(2, 9), ExpressionSelection.utf8Range("x+unknown", 2, 9));
    assertEquals(
        new ExpressionSelection.Range(4, 11), ExpressionSelection.utf8Range("é+\nunknown", 3, 10));
    assertEquals(new ExpressionSelection.Range(3, 6), ExpressionSelection.utf8Range("é+界", 2, 3));
    assertEquals(
        new ExpressionSelection.Range(5, 12), ExpressionSelection.utf8Range("😀+unknown", 3, 10));
  }

  @Test
  void preservesEmptyEndOfInputAndClampsInvalidSpans() {
    assertEquals(new ExpressionSelection.Range(4, 4), ExpressionSelection.utf8Range("sin(", 4, 4));
    assertEquals(new ExpressionSelection.Range(0, 0), ExpressionSelection.utf8Range("", -8, 9));
    assertEquals(new ExpressionSelection.Range(0, 4), ExpressionSelection.utf8Range("é+x", -3, 99));
    assertEquals(new ExpressionSelection.Range(4, 4), ExpressionSelection.utf8Range("é+x", 99, -8));
  }

  @Test
  void neverSplitsSurrogatePairs() {
    assertEquals(new ExpressionSelection.Range(0, 4), ExpressionSelection.utf8Range("😀x", 1, 2));
    assertEquals(new ExpressionSelection.Range(0, 4), ExpressionSelection.utf8Range("😀x", 0, 1));
    assertEquals(new ExpressionSelection.Range(0, 0), ExpressionSelection.utf8Range("😀x", 1, 1));
    assertEquals(new ExpressionSelection.Range(4, 5), ExpressionSelection.utf8Range("😀x", 2, 3));
  }

  @Test
  void exactOwnedSourceIsConsumedOnce() {
    var p = new Object();
    var d = new Object();
    var i = new Object();
    var selection = new ExpressionSelection();
    selection.request(p, d, i, "é+unknown", 2, 9);
    assertTrue(selection.pending());
    assertEquals(
        new ExpressionSelection.Range(3, 10), selection.take(p, d, i, new String("é+unknown")));
    assertNull(selection.take(p, d, i, "é+unknown"));
    assertFalse(selection.pending());
  }

  @Test
  void changedTextOrAnyReplacementOwnerDiscardsTheRequest() {
    var p = new Object();
    var d = new Object();
    var i = new Object();
    var selection = new ExpressionSelection();
    for (int change = 0; change < 4; change++) {
      selection.request(p, d, i, "x+unknown", 2, 9);
      assertNull(
          selection.take(
              change == 0 ? new Object() : p,
              change == 1 ? new Object() : d,
              change == 2 ? new Object() : i,
              change == 3 ? "x+bias" : "x+unknown"));
      assertNull(selection.take(p, d, i, "x+unknown"));
    }
  }

  @Test
  void replacementAndCancellationDoNotRetainEarlierRanges() {
    var owner = new Object();
    var selection = new ExpressionSelection();
    selection.request(owner, owner, owner, "unknown+x", 0, 7);
    selection.request(owner, owner, owner, "x+bad", 2, 5);
    assertEquals(new ExpressionSelection.Range(2, 5), selection.take(owner, owner, owner, "x+bad"));
    selection.request(owner, owner, owner, "x+bad", 2, 5);
    selection.cancel();
    assertNull(selection.take(owner, owner, owner, "x+bad"));
    selection.request(
        owner, owner, owner, "x".repeat(ExpressionEditSession.MAX_CHARACTERS + 1), 0, 1);
    assertNull(
        selection.take(owner, owner, owner, "x".repeat(ExpressionEditSession.MAX_CHARACTERS + 1)));
  }

  @Test
  void changedDraftTextCancelsEvenWhenTheNativeBufferStillContainsTheOldText() {
    var owner = new Object();
    var selection = new ExpressionSelection();
    selection.request(owner, owner, owner, "x+bad", 2, 5);
    selection.synchronize(owner, owner, owner, "x+good");
    assertNull(selection.take(owner, owner, owner, "x+bad"));
    selection.request(owner, owner, owner, "x+bad", 2, 5);
    selection.synchronize(owner, owner, owner, "x+bad");
    assertEquals(new ExpressionSelection.Range(2, 5), selection.take(owner, owner, owner, "x+bad"));
  }
}
