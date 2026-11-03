package io.voxelcreator.editor;

import java.util.concurrent.*;

/** UI-side terminal observation; a normal worker exit grants no result publication authority. */
final class BackgroundFailure {
  private BackgroundFailure() {}

  record Completion(Throwable failure) {}

  static Completion completed(Future<?> future) {
    if (future == null) return null;
    try {
      if (!future.isDone()) return null;
      future.get();
      return new Completion(null);
    } catch (ExecutionException failure) {
      return new Completion(failure.getCause() == null ? failure : failure.getCause());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Completion(interrupted);
    } catch (Throwable failure) {
      return new Completion(failure);
    }
  }

  static String describe(Throwable failure) {
    String detail;
    try {
      detail = failure.getMessage();
    } catch (Throwable unreadable) {
      detail = null;
    }
    if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
    if (detail.isBlank()) detail = failure.getClass().getName();
    return StatusBarText.clip(detail, 2048);
  }
}
