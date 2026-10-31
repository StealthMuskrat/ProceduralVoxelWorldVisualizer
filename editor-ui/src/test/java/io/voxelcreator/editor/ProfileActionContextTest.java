package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.Profiler;
import org.junit.jupiter.api.Test;

/** Deferred controller identity against actual bounded capture collection; no native context. */
class ProfileActionContextTest {
  @Test
  void samplesAndDisplaySnapshotsDoNotReplaceContextButReplacementStopAndClearDo() {
    try (var profiler = new Profiler()) {
      var empty = ProfileActionContext.from(profiler);
      assertNull(empty.capture());
      assertFalse(empty.recording());
      var first = profiler.start(new Profiler.Options(60000, 2048, 128, 1));
      var opening = ProfileActionContext.from(profiler);
      var scope = profiler.scope(7, null, null);
      for (int i = 0; i < 17; i++)
        scope.gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, i, "sample");
      assertNotSame(first.snapshot(), first.snapshot());
      assertEquals(opening, ProfileActionContext.from(profiler));
      var second = profiler.start(new Profiler.Options(60000, 2048, 128, 1));
      assertNotEquals(opening, ProfileActionContext.from(profiler));
      assertSame(first, opening.capture());
      assertSame(second, ProfileActionContext.from(profiler).capture());
      var running = ProfileActionContext.from(profiler);
      profiler.stop();
      assertNotEquals(running, ProfileActionContext.from(profiler));
      assertSame(second, ProfileActionContext.from(profiler).capture());
      assertFalse(ProfileActionContext.from(profiler).recording());
      profiler.clear();
      assertEquals(empty, ProfileActionContext.from(profiler));
    }
  }

  @Test
  void reachingSampleLimitChangesControllerEligibilityWithoutReplacingTheCapture() {
    try (var profiler = new Profiler()) {
      var capture = profiler.start(new Profiler.Options(60000, 64, 128, 1));
      var opening = ProfileActionContext.from(profiler);
      var scope = profiler.scope(7, null, null);
      for (int i = 0; i < 64; i++)
        scope.gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, i, "bounded");
      var limited = ProfileActionContext.from(profiler);
      assertSame(capture, limited.capture());
      assertFalse(limited.recording());
      assertNotEquals(opening, limited);
      assertEquals(Profiler.State.SAMPLE_LIMIT, capture.snapshot().state());
      assertEquals(64, capture.snapshot().samples().size());
      assertEquals(limited, ProfileActionContext.from(profiler));
    }
  }
}
