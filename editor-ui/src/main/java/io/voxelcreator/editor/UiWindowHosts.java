package io.voxelcreator.editor;

import java.util.*;

/** Logical editor window to current viewport ID; no native pointer or preference serialization. */
final class UiWindowHosts {
  private final Map<String, Integer> owners = new HashMap<>();
  private int frame = -1;

  void clear() {
    owners.clear();
    frame = -1;
  }

  void observe(String owner, int viewport, int currentFrame) {
    Objects.requireNonNull(owner);
    if (viewport == 0 || currentFrame < 0)
      throw new IllegalArgumentException("Invalid viewport observation");
    advance(currentFrame);
    owners.put(owner, viewport);
  }

  int viewport(String owner, int currentFrame) {
    advance(currentFrame);
    return owner == null ? 0 : owners.getOrDefault(owner, 0);
  }

  private void advance(int currentFrame) {
    if (currentFrame < 0) throw new IllegalArgumentException("Invalid UI frame");
    if (currentFrame < frame) owners.clear();
    frame = currentFrame;
  }
}
