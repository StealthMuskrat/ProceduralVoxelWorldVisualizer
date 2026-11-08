package io.voxelcreator.editor;

import java.util.function.Consumer;

/** Settle fixed-preview fallback before captured resource cleanup or foreign fault descriptions. */
final class ScenePreviewFallback {
  private ScenePreviewFallback() {}

  interface Owner {
    boolean eligible();
    void settle();
    boolean current();
  }

  /** True only while this fallback still owns the viewport after all admitted notifications. */
  static boolean fail(
      Owner owner, Runnable releaseGpu, Runnable closeScene, Throwable primary,
      Consumer<String> reportPrimary, Consumer<String> reportCleanup) {
    if (!owner.eligible()) return false;
    owner.settle();
    var cleanup = PreviewNotifications.attempt(releaseGpu, closeScene);
    PreviewNotifications.report(primary, owner::current, reportPrimary);
    PreviewNotifications.report(cleanup, owner::current, reportCleanup);
    return owner.current();
  }
}
