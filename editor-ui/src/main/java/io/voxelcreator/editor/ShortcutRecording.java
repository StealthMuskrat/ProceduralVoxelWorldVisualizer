package io.voxelcreator.editor;

import java.util.*;

/**
 * One recording owns its opening private version and active map; frame suppression outlives cancel.
 */
final class ShortcutRecording {
  record Captured(String action, String chord) {}

  private final UserConfig user;
  private final ShortcutCapture capture = new ShortcutCapture();
  private ShortcutEditSession owner;
  private Map<String, String> active;
  private long version;
  private long epoch;
  private boolean consumed;

  ShortcutRecording(UserConfig user) {
    this.user = Objects.requireNonNull(user);
  }

  /** The host calls once at the start of every UI frame, including when Settings is hidden. */
  void beginFrame() {
    consumed = capture.active();
  }

  boolean start(ShortcutEditSession session, String action) {
    Shortcuts.action(action);
    if (capture.active() || !session.current(user)) return false;
    owner = session;
    version = session.version();
    active = new LinkedHashMap<>(user.input.shortcuts);
    capture.start(action);
    epoch++;
    consumed = true;
    return true;
  }

  boolean active() {
    return capture.active();
  }

  String action() {
    return capture.action();
  }

  boolean suppressed() {
    return consumed || capture.active();
  }

  void cancel() {
    if (capture.active() || owner != null) epoch++;
    consumed |= capture.active();
    capture.cancel();
    owner = null;
    active = null;
  }

  long epoch() {
    return epoch;
  }

  Optional<Captured> poll(
      ShortcutEditSession session,
      boolean available,
      boolean held,
      boolean escape,
      Optional<Shortcuts.Chord> pressed) {
    if (!capture.active()) return Optional.empty();
    consumed = true;
    if (!available
        || owner != session
        || version != session.version()
        || !session.current(user)
        || !active.equals(user.input.shortcuts)) {
      cancel();
      return Optional.empty();
    }
    String target = capture.action();
    var chord = capture.poll(held, escape, pressed);
    if (chord.isPresent()) {
      session.edit(target, chord.get());
      var accepted = new Captured(target, chord.get());
      cancel();
      return Optional.of(accepted);
    }
    if (!capture.active()) cancel();
    return Optional.empty();
  }
}
