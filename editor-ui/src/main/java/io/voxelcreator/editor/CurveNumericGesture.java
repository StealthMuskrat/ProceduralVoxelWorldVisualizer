package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData.Curve;
import java.util.*;
import java.util.function.Consumer;

/** One numeric slider preview; newer session edits and retired held input retain ownership. */
final class CurveNumericGesture {
  private final CurveEditSession session;
  private final Set<String> blocked = new HashSet<>();
  private String key;
  private Curve lastCurve;
  private DialogBounds.Rect geometry;
  private long lastVersion;
  private boolean seen;

  CurveNumericGesture(CurveEditSession session) {
    this.session = Objects.requireNonNull(session);
  }

  boolean active() {
    return key != null;
  }

  boolean blocked(String field) {
    return blocked.contains(field);
  }

  void beginFrame() {
    if (active() && !current()) retireExternal();
    seen = false;
  }

  boolean edit(String field, DialogBounds.Rect bounds, Consumer<Curve> change) {
    Objects.requireNonNull(field);
    Objects.requireNonNull(bounds);
    if (active() && !current()) retireExternal();
    if (blocked(field)) return false;
    if (Objects.equals(key, field) && !geometry.equals(bounds)) {
      cancel();
      return false;
    }
    if (active() && !Objects.equals(key, field)) end();
    if (!active()) {
      key = field;
      geometry = bounds;
      session.beginGesture();
      remember();
    }
    boolean accepted = session.gestureEdit(change);
    remember();
    return accepted;
  }

  void observe(String field, DialogBounds.Rect bounds, boolean nativeActive, boolean held) {
    if (!nativeActive && !held) blocked.remove(field);
    if (Objects.equals(key, field)) {
      seen = true;
      if (!geometry.equals(bounds)) cancel();
      else if (!nativeActive) finish(false);
    }
  }

  void endFrame() {
    if (active() && !seen) cancel();
  }

  void end() {
    finish(true);
  }

  private void finish(boolean blockInput) {
    if (active() && blockInput) blocked.add(key);
    if (active() && current()) session.endGesture();
    retire();
  }

  void cancel() {
    if (!active()) return;
    blocked.add(key);
    if (current()) session.cancelGesture();
    retire();
  }

  private boolean current() {
    return session.curve() == lastCurve && session.interactionVersion() == lastVersion;
  }

  private void remember() {
    lastCurve = session.curve();
    lastVersion = session.interactionVersion();
  }

  private void retireExternal() {
    blocked.add(key);
    retire();
  }

  private void retire() {
    key = null;
    lastCurve = null;
    geometry = null;
    seen = false;
  }
}
