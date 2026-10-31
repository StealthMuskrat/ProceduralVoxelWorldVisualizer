package io.voxelcreator.editor;

import java.util.Objects;
import java.util.UUID;

/** Owned immutable active values and explicit private resets scope preference footer activation. */
final class PreferenceActionScope {
  private final SettingsActionScope scope = new SettingsActionScope();
  private final String nativeId = UUID.randomUUID().toString();
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

  boolean active() {
    return scope.current(source, draft);
  }

  String nativeId() {
    return nativeId;
  }

  long version() {
    return scope.version();
  }
}
