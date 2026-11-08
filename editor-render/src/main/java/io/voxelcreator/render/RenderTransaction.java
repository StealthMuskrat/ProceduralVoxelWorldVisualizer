package io.voxelcreator.render;

import java.util.Objects;
import java.util.function.Supplier;

/** Restore captured caller state before accepting work, retaining the original operation failure. */
final class RenderTransaction {
  private RenderTransaction() {}

  static <T> T run(Supplier<T> body, Runnable rollback, Runnable... restoration) {
    Objects.requireNonNull(body);
    Objects.requireNonNull(rollback);
    var captured = restoration.clone();
    for (var restore : captured) Objects.requireNonNull(restore);
    T result = null;
    Throwable primary = null;
    try { result = body.get(); }
    catch (RuntimeException | Error failure) { primary = failure; }
    try { RenderCleanup.run(captured); }
    catch (RuntimeException | Error failure) { if (primary == null) primary = failure; }
    if (primary != null) {
      try { rollback.run(); }
      catch (RuntimeException | Error ignored) { /* Keep the body or first restoration primary. */ }
      if (primary instanceof RuntimeException runtime) throw runtime;
      throw (Error) primary;
    }
    return result;
  }
}
