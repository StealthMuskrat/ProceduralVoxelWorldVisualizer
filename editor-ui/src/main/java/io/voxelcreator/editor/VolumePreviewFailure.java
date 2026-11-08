package io.voxelcreator.editor;

import java.util.function.Supplier;

/** UI failure settlement precedes backend cancellation and potentially reentrant fault reporting. */
final class VolumePreviewFailure {
  private VolumePreviewFailure() {}

  interface Owner {
    void settle();
    boolean current();
    void feedback(String detail);
    void report(String code, String detail);
  }

  static void fail(Owner owner, Runnable cancel, Supplier<String> reason) {
    fail(owner, cancel, reason, "VOLUME_UPLOAD_FAILED");
  }

  static void fail(Owner owner, Runnable cancel, Supplier<String> reason, String code) {
    owner.settle();
    Throwable cleanup = null;
    try {
      cancel.run();
    } catch (RuntimeException | Error failure) {
      cleanup = failure;
    }
    if (!owner.current()) return;
    String detail = reason.get();
    if (!owner.current()) return;
    owner.feedback(detail);
    report(owner, code, detail);
    if (cleanup != null && owner.current()) {
      String cleanupDetail = BackgroundFailure.describe(cleanup);
      report(owner, "VOLUME_UPLOAD_CLEANUP_FAILED", cleanupDetail);
    }
  }

  private static void report(Owner owner, String code, String detail) {
    if (!owner.current()) return;
    try {
      owner.report(code, detail);
    } catch (RuntimeException | Error ignored) {
      // The settled viewport keeps its primary feedback even if diagnostic delivery is unavailable.
    }
  }
}
