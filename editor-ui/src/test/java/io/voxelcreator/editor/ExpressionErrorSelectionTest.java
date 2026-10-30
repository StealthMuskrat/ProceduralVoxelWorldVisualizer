package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ExpressionErrorSelectionTest {
  @Test
  void clippedFramesKeepAnExactRequestUntilOneNativeAcknowledgement() {
    var state = new ExpressionErrorSelection();
    var owner = new Object();
    state.context(owner, owner, owner, "é+unknown");
    state.request("é+unknown", 2, 9);
    for (int i = 0; i < 30; i++) {
      state.context(owner, owner, owner, new String("é+unknown"));
      assertTrue(state.pending());
      assertNull(state.applied());
    }
    var range = new ExpressionSelection.Range(3, 10);
    assertEquals(range, state.take("é+unknown"));
    assertEquals(range, state.applied());
    assertFalse(state.pending());
    assertNull(state.take("é+unknown"));
    assertEquals(range, state.applied());
  }

  @Test
  void changingDisplayedSourceClearsBothPendingFocusAndAnAcknowledgedRange() {
    var state = new ExpressionErrorSelection();
    var owner = new Object();
    state.context(owner, owner, owner, "x+bad");
    state.request("x+bad", 2, 5);
    state.context(owner, owner, owner, "x+good");
    assertFalse(state.pending());
    assertNull(state.take("x+bad"));
    assertNull(state.applied());
    state.request("x+good", 2, 6);
    state.take("x+good");
    assertNotNull(state.applied());
    state.context(owner, owner, owner, "x+1");
    assertNull(state.applied());
  }

  @Test
  void anyEqualValueReplacementOwnerInvalidatesThePendingRange() {
    var p = new Object();
    var d = new Object();
    var item = new Object();
    for (int changed = 0; changed < 3; changed++) {
      var state = new ExpressionErrorSelection();
      state.context(p, d, item, "x+bad");
      state.request("x+bad", 2, 5);
      state.context(
          changed == 0 ? new Object() : p,
          changed == 1 ? new Object() : d,
          changed == 2 ? new Object() : item,
          "x+bad");
      assertFalse(state.pending());
      assertNull(state.take("x+bad"));
      assertNull(state.applied());
    }
  }

  @Test
  void parserFeedbackCannotSelectAnEarlierOrNormalizedDisplayedBuffer() {
    var state = new ExpressionErrorSelection();
    var owner = new Object();
    state.context(owner, owner, owner, "x+bad");
    for (String displayed : new String[] {"x +bad", "x+bad\n", "x+good"}) {
      state.request(displayed, 2, 5);
      assertFalse(state.pending());
      assertNull(state.applied());
    }
    state.context(owner, null, owner, "x+bad");
    state.request("x+bad", 2, 5);
    assertFalse(state.pending());
  }

  @Test
  void nativeTextMismatchConsumesOnlyThatRequestAndExplicitRetryStillWorks() {
    var state = new ExpressionErrorSelection();
    var owner = new Object();
    state.context(owner, owner, owner, "x+bad");
    state.request("x+bad", 2, 5);
    assertNull(state.take("x+good"));
    assertFalse(state.pending());
    assertNull(state.applied());
    state.request("x+bad", 2, 5);
    assertEquals(new ExpressionSelection.Range(2, 5), state.take("x+bad"));
    state.cancel();
    assertNull(state.applied());
    assertFalse(state.pending());
    state.request("x+bad", 2, 5);
    assertTrue(state.pending());
  }

  @Test
  void unicodePairAndEndCaretRangesStayByteAccurateWithinTheParserBound() {
    var state = new ExpressionErrorSelection();
    var owner = new Object();
    state.context(owner, owner, owner, "é+😀");
    state.request("é+😀", 3, 4);
    assertEquals(new ExpressionSelection.Range(3, 7), state.take("é+😀"));
    state.request("é+😀", 99, 99);
    assertEquals(new ExpressionSelection.Range(7, 7), state.take("é+😀"));
    var excessive = "x".repeat(ExpressionEditSession.MAX_CHARACTERS + 1);
    state.context(owner, owner, owner, excessive);
    state.request(excessive, 0, 1);
    assertFalse(state.pending());
    assertNull(state.applied());
  }
}
