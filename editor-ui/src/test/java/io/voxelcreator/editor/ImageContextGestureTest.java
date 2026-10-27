package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ImageContextGestureTest {
  @Test
  void aShortReleaseInsideHasInclusiveDistanceAndDurationBounds() {
    var g = new ImageContextGesture();
    g.begin(100, 200, 0);
    assertTrue(g.release(103, 200, .35, true));
    assertFalse(g.active());
    assertFalse(g.release(100, 200, .1, true));
  }

  @Test
  void longPressOutsideReleaseAndNegativeDurationCannotOpenMenus() {
    var g = new ImageContextGesture();
    g.begin(0, 0, 0);
    assertFalse(g.release(0, 0, Math.nextUp(.35), true));
    g.begin(0, 0, 0);
    assertFalse(g.release(0, 0, .1, false));
    g.begin(0, 0, 1);
    assertFalse(g.release(0, 0, 0, true));
  }

  @Test
  void draggingOutAndBackDoesNotBecomeAClick() {
    var g = new ImageContextGesture();
    g.begin(10, 20, 0);
    g.move(14, 20);
    g.move(10, 20);
    assertFalse(g.release(10, 20, .1, true));
    g.begin(10, 20, 0);
    assertFalse(g.release(13, 23, .1, true));
  }

  @Test
  void cancellationKeepsAReleaseInactiveUntilAFreshPress() {
    var g = new ImageContextGesture();
    g.begin(0, 0, 0);
    g.reset();
    g.move(20, 30);
    assertFalse(g.release(0, 0, .1, true));
    g.begin(0, 0, .2);
    assertTrue(g.release(0, 0, .3, true));
  }

  @Test
  void anAlreadyOwnedPressCannotBeRestartedToEraseMovementOrElapsedTime() {
    var g = new ImageContextGesture();
    g.begin(0, 0, 0);
    g.move(10, 10);
    g.begin(10, 10, .9);
    assertFalse(g.release(10, 10, 1, true));
    g.begin(0, 0, 0);
    g.begin(0, 0, .9);
    assertFalse(g.release(0, 0, 1, true));
  }
}
