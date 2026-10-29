package io.voxelcreator.editor;

import java.util.Objects;

/** UI-thread preview ownership. Native style replacements yield until an explicit resume/revert. */
final class ThemePreview implements AutoCloseable {
  interface Style {
    Object owner();

    Object apply(ThemePalette palette);
  }

  private final UserConfig user;
  private final ThemeEditSession session;
  private final Style style;
  private Object installed;
  private ThemePalette shown;
  private boolean paused, closed;

  ThemePreview(UserConfig user, ThemeEditSession session, Style style) {
    this.user = Objects.requireNonNull(user);
    this.session = Objects.requireNonNull(session);
    this.style = Objects.requireNonNull(style);
  }

  boolean paused() {
    return paused;
  }

  boolean closed() {
    return closed;
  }

  void refresh() {
    if (closed || paused) return;
    if (!session.current(user) || installed != null && style.owner() != installed) {
      paused = true;
      release();
      return;
    }
    if (installed == null || !session.draft().equals(shown)) {
      installed = style.apply(session.draft());
      shown = session.draft();
    }
  }

  void resume() {
    if (closed) throw new IllegalStateException("Theme preview is closed");
    if (!session.current(user))
      throw new IllegalStateException("Theme changed elsewhere; Revert before previewing");
    paused = false;
    installed = null;
    refresh();
  }

  void revert() {
    if (closed) throw new IllegalStateException("Theme preview is closed");
    session.revert(user);
    resume();
  }

  private void release() {
    if (installed != null && style.owner() == installed) {
      ThemePalette active;
      try {
        active = ThemePalette.from(user.appearance);
      } catch (RuntimeException invalid) {
        active = session.base();
      }
      style.apply(active);
    }
    installed = null;
    shown = null;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    release();
  }
}
