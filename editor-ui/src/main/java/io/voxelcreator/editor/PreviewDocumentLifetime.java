package io.voxelcreator.editor;

import java.util.Objects;

/** UI-thread document boundary; a failed retirement cannot mark stale previews synchronized. */
final class PreviewDocumentLifetime {
  private Object document;

  PreviewDocumentLifetime(Object initial) {
    document = Objects.requireNonNull(initial);
  }

  boolean synchronize(Object current, Runnable retire) {
    Objects.requireNonNull(current);
    Objects.requireNonNull(retire);
    if (document == current) return false;
    retire.run();
    document = current;
    return true;
  }
}
