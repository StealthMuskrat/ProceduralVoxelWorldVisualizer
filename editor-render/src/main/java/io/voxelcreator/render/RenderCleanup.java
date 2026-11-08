package io.voxelcreator.render;

/** Attempt captured native retirement callbacks independently after their caller detaches owners. */
final class RenderCleanup {
  private RenderCleanup() {}

  static void run(Runnable... callbacks) {
    Throwable first = null;
    for (var callback : callbacks) {
      try {
        callback.run();
      } catch (RuntimeException | Error failure) {
        if (first == null) first = failure;
      }
    }
    if (first instanceof RuntimeException runtime) throw runtime;
    if (first instanceof Error error) throw error;
  }
}
