package io.voxelcreator.editor;

import java.util.*;

/**
 * Capture waits for the initiating held keys/modifiers to release; Escape cancels without an edit.
 */
public final class ShortcutCapture {
  private String action;
  private boolean waiting;

  public void start(String action) {
    Shortcuts.action(action);
    this.action = action;
    waiting = true;
  }

  public boolean active() {
    return action != null;
  }

  public String action() {
    return action;
  }

  public void cancel() {
    action = null;
    waiting = false;
  }

  public Optional<String> poll(boolean held, boolean escape, Optional<Shortcuts.Chord> pressed) {
    if (!active()) return Optional.empty();
    if (escape) {
      cancel();
      return Optional.empty();
    }
    if (waiting) {
      if (!held) waiting = false;
      return Optional.empty();
    }
    if (pressed.isPresent()) {
      String chord = pressed.get().text();
      cancel();
      return Optional.of(chord);
    }
    return Optional.empty();
  }
}
