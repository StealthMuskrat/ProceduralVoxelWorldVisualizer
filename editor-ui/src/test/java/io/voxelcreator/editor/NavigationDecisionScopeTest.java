package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NavigationDecisionScopeTest {
  @Test
  void inactiveDialogsCannotCaptureOrDeliverADecision() {
    var scope = new NavigationDecisionScope();
    assertThrows(IllegalStateException.class, () -> scope.capture(1));
    scope.open();
    var ticket = scope.capture(1);
    scope.close();
    assertFalse(scope.active());
    assertFalse(scope.owns(ticket));
    assertFalse(scope.consume(ticket, 1));
  }

  @Test
  void repeatedDrawingCannotDeliverTwoActionsInTheSameNativeFrame() {
    var scope = new NavigationDecisionScope();
    scope.open();
    var ticket = scope.capture(10);
    assertTrue(scope.consume(ticket, 10));
    assertFalse(scope.consume(ticket, 10));
    assertFalse(scope.consume(scope.capture(10), 10));
    assertTrue(scope.consume(scope.capture(11), 11));
  }

  @Test
  void oldFramesAndForeignDialogsCannotBorrowAnOpening() {
    var scope = new NavigationDecisionScope();
    scope.open();
    var ticket = scope.capture(10);
    var other = new NavigationDecisionScope();
    other.open();
    assertFalse(scope.consume(ticket, 11));
    assertFalse(other.consume(ticket, 10));
    assertTrue(scope.consume(ticket, 10));
  }

  @Test
  void aSuccessorOpeningInvalidatesItsPredecessorsCompletionAuthority() {
    var scope = new NavigationDecisionScope();
    scope.open();
    var old = scope.capture(10);
    assertTrue(scope.consume(old, 10));
    scope.open();
    assertFalse(scope.owns(old));
    assertFalse(scope.consume(old, 10));
    assertFalse(scope.consume(scope.capture(10), 10));
    assertTrue(scope.consume(scope.capture(11), 11));
  }

  @Test
  void anExplicitClosePreventsAnOlderCallbackFromRestoringItsMessage() {
    var scope = new NavigationDecisionScope();
    scope.open();
    var ticket = scope.capture(10);
    assertTrue(scope.consume(ticket, 10));
    scope.close();
    assertFalse(scope.owns(ticket));
    scope.open();
    assertTrue(scope.active());
    assertTrue(scope.version() > ticket.version());
    assertFalse(scope.owns(ticket));
  }

  @Test
  void aThrowingCallbackHasAlreadyConsumedItsCurrentFrameDelivery() {
    var scope = new NavigationDecisionScope();
    scope.open();
    var ticket = scope.capture(10);
    assertThrows(
        IllegalStateException.class,
        () -> {
          assertTrue(scope.consume(ticket, 10));
          throw new IllegalStateException("callback boundary");
        });
    assertFalse(scope.consume(ticket, 10));
    assertTrue(scope.owns(ticket));
    assertTrue(scope.consume(scope.capture(11), 11));
  }
}
