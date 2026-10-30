package io.voxelcreator.editor;

import java.util.Objects;

/** Presentation identity only; existing settings sessions retain validation and publication. */
final class SettingsActionScope {
  private Object source, draft;
  private long sourceRevision, version;
  private boolean active;

  long synchronize(Object source, Object draft) {
    return synchronize(source, draft, 0);
  }

  long synchronize(Object source, Object draft, long revision) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(draft);
    if (!current(source, draft, revision)) {
      this.source = source;
      this.draft = draft;
      sourceRevision = revision;
      active = true;
      version++;
    }
    return version;
  }

  boolean current(Object source, Object draft) {
    return current(source, draft, 0);
  }

  boolean current(Object source, Object draft, long revision) {
    return active && this.source == source && this.draft == draft && sourceRevision == revision;
  }

  void release() {
    active = false;
    source = draft = null;
  }

  long version() {
    return version;
  }
}
