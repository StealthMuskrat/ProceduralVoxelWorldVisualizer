package io.voxelcreator.editor;

import java.nio.file.Path;
import java.util.*;

/**
 * Private preference draft with conflict-aware reset and publication after successful persistence.
 */
public final class ShortcutEditSession {
  private Map<String, String> base;
  private final Map<String, String> draft = new LinkedHashMap<>();

  public ShortcutEditSession(Map<String, String> bindings) {
    revert(bindings);
  }

  public Map<String, String> values() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(draft));
  }

  public void edit(String action, String value) {
    Shortcuts.action(action);
    draft.put(action, Objects.requireNonNull(value));
  }

  public void unbind(String action) {
    edit(action, "");
  }

  public void reset(String action) {
    edit(action, Shortcuts.action(action).defaultBinding());
  }

  public void resetAll() {
    draft.clear();
    draft.putAll(Shortcuts.defaults());
  }

  public boolean changed() {
    return !draft.equals(base);
  }

  public List<Shortcuts.Problem> problems() {
    return Shortcuts.problems(draft);
  }

  public void revert(Map<String, String> bindings) {
    base = Shortcuts.canonical(bindings);
    draft.clear();
    draft.putAll(base);
  }

  public void apply(UserConfig user, Path path) {
    if (!base.equals(Shortcuts.canonical(user.input.shortcuts)))
      throw new IllegalStateException(
          "Bindings changed elsewhere; Revert to load the current bindings");
    user.applyShortcuts(draft, path);
    revert(user.input.shortcuts);
  }
}
