package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import org.junit.jupiter.api.Test;

/** Deferred definitions for oversized provisional expression input and displayed-buffer staging. */
class ExpressionBufferBoundsTest {
  @Test
  void oversizedFeedbackAndStageRejectWithoutChangingEitherDraftOrDocument() {
    try (var state = SearchNavigationTest.state()) {
      var noise = (Noise) state.draft;
      var item = noise.processing.getFirst();
      var session = new ExpressionEditSession(state, noise, item);
      var before = ProjectIO.JSON.valueToTree(state.project());
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      var raw = "x".repeat(ExpressionEditSession.MAX_CHARACTERS + 1);
      session.edit(raw);
      assertThrows(IllegalArgumentException.class, () -> session.feedback(state));
      assertThrows(IllegalArgumentException.class, () -> session.stage(state, raw));
      assertEquals(raw, session.text());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      session.edit("x+6");
      assertTrue(session.feedback(state).valid());
      session.stage(state, "x+6");
      assertEquals("x+6", item.parameters.get("expression").asText());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void aRejectedBufferCannotStageThePreviousTextAndCanBeCorrected() {
    try (var state = SearchNavigationTest.state()) {
      var noise = (Noise) state.draft;
      var item = noise.processing.getFirst();
      var session = new ExpressionEditSession(state, noise, item);
      String old = session.text();
      String excessive = "x".repeat(ExpressionEditSession.MAX_BUFFER_CHARACTERS + 1);
      assertThrows(IllegalArgumentException.class, () -> session.edit(excessive));
      assertEquals(old, session.text());
      assertThrows(IllegalStateException.class, () -> session.stage(state, excessive));
      session.edit("x+7");
      session.stage(state, "x+7");
      assertEquals("x+7", item.parameters.get("expression").asText());
    }
  }

  @Test
  void unicodeLimitsUseUtf16AndTheHardBufferBoundaryIsInclusive() {
    try (var state = SearchNavigationTest.state()) {
      var noise = (Noise) state.draft;
      var item = noise.processing.getFirst();
      var session = new ExpressionEditSession(state, noise, item);
      String edge = "😀".repeat(ExpressionEditSession.MAX_BUFFER_CHARACTERS / 2);
      session.edit(edge);
      assertEquals(edge, session.text());
      assertThrows(IllegalArgumentException.class, () -> session.edit(edge + "x"));
      assertThrows(IllegalArgumentException.class, () -> session.feedback(state));
      session.cancel();
      assertThrows(IllegalStateException.class, () -> session.edit("x"));
    }
  }
}
