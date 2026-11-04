package io.voxelcreator.editor;

import java.nio.file.Path;

/** Live preview is separate from persisted preferences, including periodic workspace saves. */
public final class ThemeEditSession {
  private ThemePalette base, draft;
  private long version;

  public ThemeEditSession(UserConfig user) {
    revert(user);
  }

  public ThemePalette draft() {
    return draft;
  }

  ThemePalette base() {
    return base;
  }

  public boolean current(UserConfig user) {
    try {
      return base.equals(ThemePalette.from(user.appearance));
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  public void edit(ThemePalette palette) {
    draft = java.util.Objects.requireNonNull(palette);
    version++;
  }

  public boolean changed() {
    return !draft.equals(base);
  }

  public void revert(UserConfig user) {
    base = ThemePalette.from(user.appearance);
    draft = base;
    version++;
  }

  long version() {
    return version;
  }

  boolean ownsInput(UserConfig user, long captured, ThemePalette displayed) {
    return captured == version && current(user) && draft.equals(displayed);
  }

  UserConfig.PreferenceRequest request(UserConfig user, ThemePalette displayed) {
    if (!draft.equals(displayed))
      throw new IllegalStateException("Theme preview changed; review it before saving");
    if (!current(user))
      throw new IllegalStateException("Theme changed elsewhere; Revert before saving");
    return user.requestTheme(draft);
  }

  public void apply(UserConfig user, Path path) {
    apply(user, path, draft);
  }

  void apply(UserConfig user, Path path, ThemePalette displayed) {
    if (!draft.equals(displayed))
      throw new IllegalStateException("Theme preview changed; review it before saving");
    if (!current(user))
      throw new IllegalStateException("Theme changed elsewhere; Revert before saving");
    user.applyTheme(draft, path);
    revert(user);
  }
}
