package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class EditorLifecycleTest {
  @Test
  void normalExitQuiescesBeforeReverseOrderCleanupAndCannotRunAgain() {
    var owner = new EditorLifecycle();
    var calls = new ArrayList<String>();
    owner.own(() -> calls.add("state"));
    owner.run(
        () -> {
          calls.add("init");
          owner.own(() -> calls.add("window"));
          owner.own(() -> calls.add("renderer"));
        },
        () -> calls.add("loop"),
        () -> calls.add("finish"),
        () -> calls.add("stop-frames"));
    assertEquals(
        List.of("init", "loop", "finish", "stop-frames", "renderer", "window", "state"), calls);
    assertTrue(owner.closed());
    assertNull(owner.close(null));
    assertThrows(
        IllegalStateException.class,
        () -> owner.run(() -> fail("Reinitialize"), () -> {}, () -> {}, () -> {}));
  }

  @Test
  void initializationFailureSkipsTheLoopAndPreservesTheOriginalFailureThroughEveryRelease() {
    var owner = new EditorLifecycle();
    var calls = new ArrayList<String>();
    var primary = new IllegalStateException("initialize");
    var release = new IllegalArgumentException("release");
    owner.own(() -> calls.add("state"));
    var actual =
        assertThrows(
            IllegalStateException.class,
            () ->
                owner.run(
                    () -> {
                      owner.own(() -> calls.add("window"));
                      owner.own(
                          () -> {
                            calls.add("partial-backend");
                            throw release;
                          });
                      throw primary;
                    },
                    () -> fail("loop"),
                    () -> fail("finish"),
                    () -> calls.add("quiesce")));
    assertSame(primary, actual);
    assertArrayEquals(new Throwable[] {release}, actual.getSuppressed());
    assertEquals(List.of("quiesce", "partial-backend", "window", "state"), calls);
  }

  @Test
  void frameFailureAlsoRetainsQuiesceAndCleanupFailuresWithoutSelfSuppression() {
    var owner = new EditorLifecycle();
    var primary = new AssertionError("frame");
    var stop = new IllegalStateException("stop");
    owner.own(
        () -> {
          throw primary;
        });
    assertSame(
        primary,
        assertThrows(
            AssertionError.class,
            () ->
                owner.run(
                    () -> {},
                    () -> {
                      throw primary;
                    },
                    () -> fail("finish"),
                    () -> {
                      throw stop;
                    })));
    assertArrayEquals(new Throwable[] {stop}, primary.getSuppressed());
    assertTrue(owner.closed());
  }

  @Test
  void finishFailureStillClosesWorkersAfterAnotherResourceFails() {
    var owner = new EditorLifecycle();
    var calls = new ArrayList<String>();
    var save = new IllegalStateException("preferences");
    owner.own(() -> calls.add("workers"));
    owner.own(
        () -> {
          calls.add("viewports");
          throw new IllegalArgumentException("renderer");
        });
    assertSame(
        save,
        assertThrows(
            IllegalStateException.class,
            () ->
                owner.run(
                    () -> {},
                    () -> {},
                    () -> {
                      throw save;
                    },
                    () -> calls.add("stop"))));
    assertEquals(List.of("stop", "viewports", "workers"), calls);
    assertEquals(1, save.getSuppressed().length);
  }

  @Test
  void cleanupIsExactlyOnceEvenWhenAReleaseReentersOrTriesToRegister() {
    var owner = new EditorLifecycle();
    var calls = new ArrayList<String>();
    owner.own(() -> calls.add("last"));
    owner.own(
        () -> {
          calls.add("first");
          assertNull(owner.close(null));
          assertThrows(IllegalStateException.class, () -> owner.own(() -> fail("late")));
        });
    assertNull(owner.close(null));
    assertNull(owner.close(null));
    assertEquals(List.of("first", "last"), calls);
  }

  @Test
  void recursiveLaunchCannotConsumeAnotherInitializationOrFinish() {
    var owner = new EditorLifecycle();
    var calls = new ArrayList<String>();
    owner.own(() -> calls.add("release"));
    owner.run(
        () ->
            assertThrows(
                IllegalStateException.class,
                () -> owner.run(() -> fail("nested"), () -> {}, () -> {}, () -> {})),
        () -> calls.add("loop"),
        () -> calls.add("finish"),
        () -> calls.add("stop"));
    assertEquals(List.of("loop", "finish", "stop", "release"), calls);
  }
}
