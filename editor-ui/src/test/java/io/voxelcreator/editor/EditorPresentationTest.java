package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EditorPresentationTest {
  @Test
  void initializationAndClosureCannotPresentOrResumeReadiness() {
    var owner = new EditorPresentation();
    var count = new AtomicInteger();
    Runnable call = count::incrementAndGet;
    assertThrows(IllegalStateException.class, () -> owner.present(call, call, call, call, call));
    assertEquals(0, count.get());
    owner.ready();
    assertThrows(IllegalStateException.class, () -> owner.present(call, call, call, call, call));
    assertEquals(0, count.get());
    owner.close();
    assertThrows(IllegalStateException.class, owner::ready);
    assertThrows(IllegalStateException.class, () -> owner.present(call, call, call, call, call));
    assertEquals(0, count.get());
  }

  @Test
  void completeHiddenFrameSwapsBeforeShowAndDetachedWindowsWaitUntilAfterVisibility() {
    var owner = new EditorPresentation();
    owner.ready();
    owner.frameReady(800, 600, 1, 1, 100);
    var calls = new ArrayList<String>();
    owner.present(
        () -> calls.add("swap"),
        () -> calls.add("show"),
        () -> calls.add("placement"),
        () -> calls.add("platforms"),
        () -> calls.add("poll"));
    assertEquals(List.of("swap", "show", "placement", "platforms", "poll"), calls);
    assertTrue(owner.visible());
    calls.clear();
    owner.present(
        () -> calls.add("swap"),
        () -> fail("second show"),
        () -> fail("second placement"),
        () -> calls.add("platforms"),
        () -> calls.add("poll"));
    assertEquals(List.of("platforms", "swap", "poll"), calls);
  }

  @Test
  void failedSwapNeverShowsOrPollsAndCannotBeRetried() {
    var owner = new EditorPresentation();
    owner.ready();
    owner.frameReady(800, 600, 1, 1, 100);
    var primary = new IllegalStateException("swap");
    assertSame(
        primary,
        assertThrows(
            IllegalStateException.class,
            () ->
                owner.present(
                    () -> {
                      throw primary;
                    },
                    () -> fail("show"),
                    () -> fail("placement"),
                    () -> fail("platforms"),
                    () -> fail("poll"))));
    assertEquals(EditorPresentation.Phase.FAILED, owner.phase());
    assertThrows(IllegalStateException.class, owner::ready);
    assertThrows(
        IllegalStateException.class,
        () -> owner.present(() -> {}, () -> fail("show"), () -> {}, () -> {}, () -> {}));
  }

  @Test
  void failedPostShowPlacementCannotContinuePlatformPresentation() {
    var owner = new EditorPresentation();
    owner.ready();
    owner.frameReady(800, 600, 1, 1, 100);
    var shown = new AtomicInteger();
    var primary = new IllegalArgumentException("placement");
    assertSame(
        primary,
        assertThrows(
            IllegalArgumentException.class,
            () ->
                owner.present(
                    () -> {},
                    shown::incrementAndGet,
                    () -> {
                      throw primary;
                    },
                    () -> fail("platforms"),
                    () -> fail("poll"))));
    assertEquals(1, shown.get());
    assertEquals(EditorPresentation.Phase.FAILED, owner.phase());
  }

  @Test
  void closingInsideASwapCannotResurrectTheWindow() {
    var owner = new EditorPresentation();
    owner.ready();
    owner.frameReady(800, 600, 1, 1, 100);
    assertThrows(
        IllegalStateException.class,
        () ->
            owner.present(
                owner::close,
                () -> fail("show"),
                () -> fail("placement"),
                () -> fail("platforms"),
                () -> fail("poll")));
    assertEquals(EditorPresentation.Phase.CLOSED, owner.phase());
    assertThrows(IllegalStateException.class, owner::ready);
  }

  @Test
  void recursivePresentationIsRejectedWithoutDuplicatingTheOuterShow() {
    var owner = new EditorPresentation();
    owner.ready();
    owner.frameReady(800, 600, 1, 1, 100);
    var shown = new AtomicInteger();
    owner.present(
        () ->
            assertThrows(
                IllegalStateException.class,
                () ->
                    owner.present(
                        () -> {}, () -> fail("nested show"), () -> {}, () -> {}, () -> {})),
        shown::incrementAndGet,
        () -> {},
        () -> {},
        () -> {});
    assertEquals(1, shown.get());
    assertTrue(owner.visible());
  }

  @Test
  void firstFrameRequiresRealPixelExtentsAndVerticesWithoutOverflowOrCoercion() {
    for (float[] invalid :
        new float[][] {
          {0, 600, 1, 1},
          {800, 600, Float.NaN, 1},
          {800, 600, -1, 1},
          {Float.MAX_VALUE, 600, 2, 1},
          {.1f, 600, 1, 1}
        }) {
      var owner = new EditorPresentation();
      owner.ready();
      owner.frameReady(800, 600, 1, 1, 100);
      assertFalse(owner.frameReady(invalid[0], invalid[1], invalid[2], invalid[3], 100));
      assertTrue(owner.frameReady(800, 600, 2, 2, 100));
    }
    var owner = new EditorPresentation();
    owner.ready();
    owner.frameReady(800, 600, 1, 1, 100);
    assertFalse(owner.frameReady(800, 600, 1, 1, 0));
    assertTrue(owner.frameReady(800, 600, 1, 1, 1));
  }

  @Test
  void unavailableHiddenFramesHaveABoundedFailureWithoutShowing() {
    var owner = new EditorPresentation();
    owner.ready();
    owner.frameReady(800, 600, 1, 1, 100);
    for (int i = 0; i < 7; i++) assertFalse(owner.frameReady(0, 0, 1, 1, 0));
    assertThrows(IllegalStateException.class, () -> owner.frameReady(0, 0, 1, 1, 0));
    assertEquals(EditorPresentation.Phase.FAILED, owner.phase());
    assertThrows(IllegalStateException.class, () -> owner.frameReady(800, 600, 1, 1, 10));
  }
}
