package io.voxelcreator.editor;

import java.util.*;

/** UI-thread ownership: quiesce before reverse-order teardown, retaining the original failure. */
final class EditorLifecycle {
  private final Deque<Runnable> releases = new ArrayDeque<>();
  private boolean closed, running;

  void own(Runnable release) {
    if (closed) throw new IllegalStateException("Editor resources are closed");
    releases.push(Objects.requireNonNull(release));
  }

  void run(Runnable initialize, Runnable loop, Runnable finish, Runnable quiesce) {
    if (closed || running) throw new IllegalStateException("Editor lifecycle already consumed");
    running = true;
    Throwable failure = null;
    try {
      initialize.run();
      loop.run();
      finish.run();
    } catch (Throwable problem) {
      failure = problem;
    }
    failure = attempt(quiesce, failure);
    failure = close(failure);
    running = false;
    if (failure instanceof Error error) throw error;
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure != null) throw new IllegalStateException("Editor lifecycle failed", failure);
  }

  Throwable close(Throwable failure) {
    if (closed) return failure;
    closed = true;
    while (!releases.isEmpty()) failure = attempt(releases.pop(), failure);
    return failure;
  }

  private static Throwable attempt(Runnable action, Throwable failure) {
    try {
      action.run();
    } catch (Throwable problem) {
      if (failure == null) return problem;
      if (problem != failure) failure.addSuppressed(problem);
    }
    return failure;
  }

  boolean closed() {
    return closed;
  }
}
