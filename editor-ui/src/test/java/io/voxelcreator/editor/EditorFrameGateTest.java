package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EditorFrameGateTest {
  @Test
  void startupResizeCannotDrawUntilPanelsAreReady() {
    var gate = new EditorFrameGate();
    var frames = new AtomicInteger();
    gate.draw(frames::incrementAndGet);
    assertEquals(0, frames.get());
    gate.ready();
    gate.draw(frames::incrementAndGet);
    assertEquals(1, frames.get());
  }

  @Test
  void nestedNativeResizeIsSuppressedButTheNextMainLoopFrameRuns() {
    var gate = new EditorFrameGate();
    gate.ready();
    var frames = new AtomicInteger();
    gate.draw(
        () -> {
          frames.incrementAndGet();
          gate.draw(() -> fail("Nested frame"));
        });
    gate.draw(frames::incrementAndGet);
    assertEquals(2, frames.get());
  }

  @Test
  void frameFailureReleasesTheGateForTheNextAttempt() {
    var gate = new EditorFrameGate();
    gate.ready();
    assertThrows(
        IllegalStateException.class,
        () ->
            gate.draw(
                () -> {
                  throw new IllegalStateException("fixture");
                }));
    var frames = new AtomicInteger();
    gate.draw(frames::incrementAndGet);
    assertEquals(1, frames.get());
  }
}
