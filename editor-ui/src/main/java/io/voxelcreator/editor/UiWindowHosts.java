package io.voxelcreator.editor;

import java.util.*;

/** Logical viewport IDs and owner rectangles; no native pointer or preference serialization. */
final class UiWindowHosts {
  private record Host(int viewport, DialogBounds.Rect bounds) {}

  private final Map<String, Host> owners = new HashMap<>();
  private int frame = -1;

  void clear() {
    owners.clear();
    frame = -1;
  }

  void observe(String owner, int viewport, int currentFrame) {
    observe(owner, viewport, currentFrame, null);
  }

  void observe(String owner, int viewport, int currentFrame, DialogBounds.Rect bounds) {
    Objects.requireNonNull(owner);
    if (viewport == 0 || currentFrame < 0)
      throw new IllegalArgumentException("Invalid viewport observation");
    advance(currentFrame);
    owners.put(owner, new Host(viewport, bounds));
  }

  int viewport(String owner, int currentFrame) {
    advance(currentFrame);
    var host = owner == null ? null : owners.get(owner);
    return host == null ? 0 : host.viewport();
  }

  DialogBounds.Rect bounds(String owner, int currentFrame) {
    advance(currentFrame);
    var host = owner == null ? null : owners.get(owner);
    return host == null ? null : host.bounds();
  }

  private void advance(int currentFrame) {
    if (currentFrame < 0) throw new IllegalArgumentException("Invalid UI frame");
    if (currentFrame < frame) owners.clear();
    frame = currentFrame;
  }
}
