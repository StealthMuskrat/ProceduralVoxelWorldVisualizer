package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Deferred stale Resume admission, independent of native UI and GPU state. */
class RenderPressureEpisodeTest {
  @Test
  void criticalPauseKeepsItsIdentityUntilExplicitResumeAndRejectsAnEarlierEpisode() {
    var p = new RenderPressure();
    for (int level = 1; level <= 5; level++) {
      assertTrue(p.observe(level * 1_000_000_000L, 1, 1, 10, true));
      assertEquals(level, p.level());
      assertEquals(level < 5 ? 0 : 1, p.pauseVersion());
    }
    assertFalse(p.observe(20_000_000_000L, 0, 0, 10, false));
    assertFalse(p.observe(21_000_000_000L, 1, 1, 10, true));
    assertTrue(p.paused());
    assertEquals(1, p.pauseVersion());
    assertFalse(p.resume(2, 22_000_000_000L));
    assertTrue(p.resume(1, 22_000_000_000L));
    assertFalse(p.resume(1, 22_000_000_001L));
    assertEquals(3, p.level());
    assertFalse(p.paused());
    p.observe(24_000_000_000L, 1, 1, 10, true);
    p.observe(25_000_000_000L, 1, 1, 10, true);
    assertEquals(5, p.level());
    assertEquals(2, p.pauseVersion());
    assertFalse(p.resume(1, 26_000_000_000L));
    assertTrue(p.paused());
    assertTrue(p.resume(2, 26_000_000_000L));
  }

  @Test
  void repeatedManualPauseIsOneEpisodeAndAnUnpausedControllerCannotAdmitResume() {
    var p = new RenderPressure();
    assertFalse(p.resume(0, 1));
    p.pause();
    long first = p.pauseVersion();
    p.pause();
    assertEquals(first, p.pauseVersion());
    assertTrue(p.resume(first, 2));
    assertEquals(0, p.level());
    p.pause();
    assertEquals(first + 1, p.pauseVersion());
    assertFalse(p.resume(first, 3));
    assertTrue(p.paused());
    // Legacy explicit resume retains its policy and also ends this episode.
    p.resume(4);
    assertFalse(p.paused());
    p.pause();
    assertEquals(first + 2, p.pauseVersion());
  }
}
