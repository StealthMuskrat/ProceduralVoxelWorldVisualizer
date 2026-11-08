package io.voxelcreator.editor;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Render-thread commit observation and isolated optional upload measurements. */
final class VolumePreviewHandoff {
  private VolumePreviewHandoff() {}

  /** Returns a recoverable retirement fault only after publishing matching preview metadata. */
  static RuntimeException advance(IntSupplier upload, LongSupplier version, Runnable publish) {
    long before = version.getAsLong();
    Throwable failure = null;
    try {
      upload.getAsInt();
    } catch (RuntimeException | Error observed) {
      failure = observed;
    }
    boolean committed = version.getAsLong() != before;
    if (committed) {
      try {
        publish.run();
      } catch (RuntimeException | Error publication) {
        if (failure == null) throw publication;
        if (publication != failure) failure.addSuppressed(publication);
        rethrow(failure);
      }
    }
    if (failure instanceof Error error) throw error;
    if (failure instanceof RuntimeException runtime) {
      if (!committed) throw runtime;
      return runtime;
    }
    return null;
  }

  private static void rethrow(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }

  /** Optional measurements are attempted independently and cannot replace the upload outcome. */
  static Throwable observe(Runnable... measurements) {
    Throwable first = null;
    for (var measurement : measurements) {
      try {
        measurement.run();
      } catch (RuntimeException | Error failure) {
        if (first == null) first = failure;
      }
    }
    return first;
  }

  /** Reports at most one observation fault without granting it preview publication authority. */
  static void reportObservation(Throwable failure, BooleanSupplier current, Consumer<String> report) {
    if (failure == null || !current.getAsBoolean()) return;
    String detail = BackgroundFailure.describe(failure);
    if (!current.getAsBoolean()) return;
    try {
      report.accept(detail);
    } catch (RuntimeException | Error ignored) {
      // Profiling and its diagnostics remain optional even when notification storage is unavailable.
    }
  }
}
