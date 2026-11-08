package io.voxelcreator.editor;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Render-thread commit observation; a completed upload survives previous-texture retirement faults. */
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
}
