package io.voxelcreator.editor;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded UI-side notifications after their caller has settled publication ownership. */
final class PreviewNotifications {
  private PreviewNotifications() {}

  static Throwable attempt(Runnable... callbacks) {
    Throwable first = null;
    for (var callback : callbacks) {
      try {
        callback.run();
      } catch (RuntimeException | Error failure) {
        if (first == null) first = failure;
      }
    }
    return first;
  }

  static void report(Throwable failure, BooleanSupplier current, Consumer<String> report) {
    if (failure == null || !current.getAsBoolean()) return;
    String detail = BackgroundFailure.describe(failure);
    if (!current.getAsBoolean()) return;
    try {
      report.accept(detail);
    } catch (RuntimeException | Error ignored) {
      // Diagnostic delivery cannot change the caller's already settled owner or outcome.
    }
  }
}
