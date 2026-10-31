package io.voxelcreator.editor;

import java.util.UUID;

/** Ordinary callback presentation follows its private owner without acquiring Apply authority. */
final class PreferenceSaveAction {
  private final SettingsActionScope scope = new SettingsActionScope();
  private final Object enabled = new Object(), disabled = new Object();
  private final String nativeId = UUID.randomUUID().toString();

  long synchronize(PreferenceActionScope owner, boolean available) {
    return scope.synchronize(owner, available ? enabled : disabled, owner.version());
  }

  boolean current(PreferenceActionScope owner, boolean available) {
    return owner.active() && scope.current(owner, available ? enabled : disabled, owner.version());
  }

  String nativeId() {
    return nativeId;
  }

  void release() {
    scope.release();
  }
}
