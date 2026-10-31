package io.voxelcreator.editor;

import java.util.Objects;

/** Owned immutable active values and explicit private resets scope preference footer activation. */
final class PreferenceActionScope {
  private final SettingsActionScope scope = new SettingsActionScope();
  private Object active, source = new Object(), draft = new Object();

  long synchronize(Object values) {
    Objects.requireNonNull(values);
    if (!Objects.equals(active, values)) {
      active = values;
      source = new Object();
    }
    return scope.synchronize(source, draft);
  }

  boolean current(Object values) {
    return Objects.equals(active, values) && scope.current(source, draft);
  }

  void resetDraft() {
    draft = new Object();
    scope.release();
  }

  long version() {
    return scope.version();
  }
}
