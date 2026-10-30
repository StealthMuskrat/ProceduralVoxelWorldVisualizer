package io.voxelcreator.editor;

import java.util.Objects;

/** Presentation begins only after a complete ready frame; callback recursion cannot show twice. */
final class EditorPresentation {
  enum Phase {
    INITIALIZING,
    READY_HIDDEN,
    VISIBLE,
    FAILED,
    CLOSED
  }

  private Phase phase = Phase.INITIALIZING;
  private boolean presenting, firstFrameApproved;
  private int deferred;

  void ready() {
    if (phase != Phase.INITIALIZING)
      throw new IllegalStateException("Editor readiness already consumed");
    phase = Phase.READY_HIDDEN;
  }

  void present(
      Runnable swap, Runnable show, Runnable afterShow, Runnable platforms, Runnable poll) {
    Objects.requireNonNull(swap);
    Objects.requireNonNull(show);
    Objects.requireNonNull(afterShow);
    Objects.requireNonNull(platforms);
    Objects.requireNonNull(poll);
    if (presenting
        || phase != Phase.READY_HIDDEN && phase != Phase.VISIBLE
        || phase == Phase.READY_HIDDEN && !firstFrameApproved)
      throw new IllegalStateException("Editor cannot present in " + phase);
    presenting = true;
    try {
      if (phase == Phase.READY_HIDDEN) {
        swap.run();
        require(Phase.READY_HIDDEN);
        show.run();
        require(Phase.READY_HIDDEN);
        phase = Phase.VISIBLE;
        afterShow.run();
        require(Phase.VISIBLE);
        platforms.run();
      } else {
        platforms.run();
        require(Phase.VISIBLE);
        swap.run();
      }
      require(Phase.VISIBLE);
      poll.run();
      require(Phase.VISIBLE);
    } catch (RuntimeException | Error failure) {
      if (phase != Phase.CLOSED) phase = Phase.FAILED;
      throw failure;
    } finally {
      presenting = false;
    }
  }

  boolean frameReady(float width, float height, float scaleX, float scaleY, int vertices) {
    if (phase != Phase.READY_HIDDEN)
      throw new IllegalStateException("First-frame readiness already consumed");
    double w = (double) width * scaleX, h = (double) height * scaleY;
    boolean complete =
        Float.isFinite(width)
            && Float.isFinite(height)
            && Float.isFinite(scaleX)
            && Float.isFinite(scaleY)
            && width > 0
            && height > 0
            && scaleX > 0
            && scaleY > 0
            && w >= 1
            && h >= 1
            && w <= Integer.MAX_VALUE
            && h <= Integer.MAX_VALUE
            && vertices > 0;
    if (!complete && ++deferred >= 8) {
      phase = Phase.FAILED;
      throw new IllegalStateException("The hidden editor could not produce a complete first frame");
    }
    firstFrameApproved = complete;
    return complete;
  }

  private void require(Phase expected) {
    if (phase != expected)
      throw new IllegalStateException("Editor presentation changed during callback");
  }

  Phase phase() {
    return phase;
  }

  boolean visible() {
    return phase == Phase.VISIBLE;
  }

  void close() {
    phase = Phase.CLOSED;
  }
}
