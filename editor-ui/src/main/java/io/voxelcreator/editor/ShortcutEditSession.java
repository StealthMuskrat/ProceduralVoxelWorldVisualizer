package io.voxelcreator.editor;

import java.nio.file.Path;
import java.util.*;

/**
 * Private preference draft with conflict-aware reset and publication after successful persistence.
 */
public final class ShortcutEditSession {
  static final int MAX_BUFFER_CHARACTERS = 256;
  private Map<String, String> base;
  private final Map<String, String> draft = new LinkedHashMap<>();
  private Map<String, String> snapshot, checkedActive;
  private List<Shortcuts.Problem> cachedProblems;
  private long version, validations;
  private boolean activeCurrent;

  public ShortcutEditSession(Map<String, String> bindings) {
    revert(bindings);
  }

  public Map<String, String> values() {
    if (snapshot == null) snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(draft));
    return snapshot;
  }

  public void edit(String action, String value) {
    Shortcuts.action(action);
    if (Objects.requireNonNull(value).length() > MAX_BUFFER_CHARACTERS)
      throw new IllegalArgumentException("Shortcut input text limit " + MAX_BUFFER_CHARACTERS);
    if (Objects.equals(draft.get(action), value)) return;
    draft.put(action, value);
    invalidate();
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
    invalidate();
  }

  public boolean changed() {
    return !draft.equals(base);
  }

  public List<Shortcuts.Problem> problems() {
    if (cachedProblems == null) {
      cachedProblems = Shortcuts.problems(draft);
      validations++;
    }
    return cachedProblems;
  }

  public void revert(Map<String, String> bindings) {
    base = Shortcuts.canonical(bindings);
    draft.clear();
    draft.putAll(base);
    checkedActive = null;
    invalidate();
  }

  private void invalidate() {
    version++;
    snapshot = null;
    cachedProblems = null;
  }

  long version() {
    return version;
  }

  long validations() {
    return validations;
  }

  boolean current(UserConfig user) {
    if (user == null || user.input == null || user.input.shortcuts == null) return false;
    if (checkedActive != null && checkedActive.equals(user.input.shortcuts)) return activeCurrent;
    checkedActive = new LinkedHashMap<>(user.input.shortcuts);
    try {
      activeCurrent = base.equals(Shortcuts.canonical(checkedActive));
    } catch (RuntimeException invalid) {
      activeCurrent = false;
    }
    return activeCurrent;
  }

  void requireDisplayed(Map<String, String> displayed) {
    if (!draft.keySet().equals(displayed.keySet()))
      throw new IllegalStateException("All binding fields are required before applying");
    for (var entry : draft.entrySet())
      if (!Objects.equals(entry.getValue(), displayed.get(entry.getKey())))
        throw new IllegalStateException(
            "Displayed binding for "
                + entry.getKey()
                + " differs from the private draft; correct it before applying");
  }

  void apply(UserConfig user, Path path, Map<String, String> displayed) {
    requireDisplayed(displayed);
    apply(user, path);
  }

  public void apply(UserConfig user, Path path) {
    if (!current(user))
      throw new IllegalStateException(
          "Bindings changed elsewhere; Revert to load the current bindings");
    user.applyShortcuts(draft, path);
    revert(user.input.shortcuts);
  }
}
