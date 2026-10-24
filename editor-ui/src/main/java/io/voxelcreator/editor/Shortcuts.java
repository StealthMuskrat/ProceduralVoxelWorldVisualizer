package io.voxelcreator.editor;

import java.util.*;
import java.util.function.Predicate;

/** Portable physical-key chords and context-aware command registry, independent of native input. */
public final class Shortcuts {
  private Shortcuts() {}

  public enum Scope {
    GLOBAL,
    DOCUMENT,
    RESOURCES,
    CURVE
  }

  public enum Focus {
    GENERAL,
    RESOURCES,
    CURVE,
    FLYING
  }

  public record Action(String id, String label, Scope scope, String defaultBinding) {}

  public record Context(
      Focus focus, boolean textInput, boolean popup, boolean activeItem, boolean recording) {
    public boolean blocked() {
      return textInput || popup || activeItem || recording || focus == Focus.FLYING;
    }
  }

  public static final int CTRL = 1, SHIFT = 2, ALT = 4, SUPER = 8;
  private static final List<Action> ACTIONS =
      List.of(
          new Action("New", "New Project", Scope.GLOBAL, "Ctrl+N"),
          new Action("Open", "Open Project", Scope.GLOBAL, "Ctrl+O"),
          new Action("Save", "Save", Scope.GLOBAL, "Ctrl+S"),
          new Action("SaveAs", "Save As", Scope.GLOBAL, "Ctrl+Shift+S"),
          new Action("Import", "Import Resources", Scope.GLOBAL, "Ctrl+I"),
          new Action("Export", "Export Resources", Scope.GLOBAL, "Ctrl+E"),
          new Action("Undo", "Undo Project Edit", Scope.DOCUMENT, "Ctrl+Z"),
          new Action("Redo", "Redo Project Edit", Scope.DOCUMENT, "Ctrl+Y"),
          new Action("Find", "Find Resources", Scope.GLOBAL, "Ctrl+F"),
          new Action("FindReferences", "Find References", Scope.GLOBAL, "Ctrl+Shift+F"),
          new Action("Validate", "Validate Project", Scope.GLOBAL, "F7"),
          new Action("Settings", "User Settings", Scope.GLOBAL, "Ctrl+Comma"),
          new Action("Help", "Help", Scope.GLOBAL, "F1"),
          new Action("Profiler", "Profiler", Scope.GLOBAL, "Ctrl+Shift+P"),
          new Action("Logs", "Logs", Scope.GLOBAL, "Ctrl+Shift+L"),
          new Action("Rename", "Rename Resource", Scope.RESOURCES, "F2"),
          new Action("Duplicate", "Duplicate Resource", Scope.RESOURCES, "Ctrl+D"),
          new Action("Copy", "Copy Resources", Scope.RESOURCES, "Ctrl+C"),
          new Action("Cut", "Cut Resources", Scope.RESOURCES, "Ctrl+X"),
          new Action("Paste", "Paste Resources", Scope.RESOURCES, "Ctrl+V"),
          new Action("Delete", "Delete Resource...", Scope.RESOURCES, "Delete"),
          new Action("CurveDelete", "Delete Curve Points", Scope.CURVE, "Delete"),
          new Action("CurveUndo", "Undo Curve Edit", Scope.CURVE, "Ctrl+Z"),
          new Action("CurveRedo", "Redo Curve Edit", Scope.CURVE, "Ctrl+Y"));
  private static final Map<String, Action> BY_ID;
  private static final Map<String, String> KEYS;

  static {
    var actions = new LinkedHashMap<String, Action>();
    for (var action : ACTIONS) actions.put(action.id(), action);
    BY_ID = Collections.unmodifiableMap(actions);
    var keys = new LinkedHashMap<String, String>();
    for (char c = 'A'; c <= 'Z'; c++) add(keys, Character.toString(c));
    for (int i = 0; i < 10; i++) add(keys, Integer.toString(i));
    for (int i = 1; i <= 12; i++) add(keys, "F" + i);
    for (String key :
        List.of(
            "Enter",
            "Tab",
            "Space",
            "Backspace",
            "Insert",
            "Delete",
            "Home",
            "End",
            "PageUp",
            "PageDown",
            "Left",
            "Right",
            "Up",
            "Down",
            "Minus",
            "Equal",
            "LeftBracket",
            "RightBracket",
            "Backslash",
            "Semicolon",
            "Apostrophe",
            "Comma",
            "Period",
            "Slash",
            "GraveAccent",
            "Keypad0",
            "Keypad1",
            "Keypad2",
            "Keypad3",
            "Keypad4",
            "Keypad5",
            "Keypad6",
            "Keypad7",
            "Keypad8",
            "Keypad9",
            "KeypadDecimal",
            "KeypadDivide",
            "KeypadMultiply",
            "KeypadSubtract",
            "KeypadAdd",
            "KeypadEnter",
            "KeypadEqual")) add(keys, key);
    KEYS = Collections.unmodifiableMap(keys);
  }

  private static void add(Map<String, String> map, String key) {
    map.put(key.toLowerCase(Locale.ROOT), key);
  }

  public static List<Action> actions() {
    return ACTIONS;
  }

  public static Collection<String> keys() {
    return KEYS.values();
  }

  public static Action action(String id) {
    var action = BY_ID.get(id);
    if (action == null) throw new IllegalArgumentException("Unknown shortcut action: " + id);
    return action;
  }

  public static Map<String, String> defaults() {
    var result = new LinkedHashMap<String, String>();
    for (var action : ACTIONS) result.put(action.id(), action.defaultBinding());
    return result;
  }

  public record Chord(int modifiers, String key) {
    public Chord {
      if (modifiers < 0 || modifiers > 15 || !KEYS.containsValue(key))
        throw new IllegalArgumentException("Unsupported shortcut key or modifiers");
    }

    public String text() {
      return ((modifiers & CTRL) != 0 ? "Ctrl+" : "")
          + ((modifiers & SHIFT) != 0 ? "Shift+" : "")
          + ((modifiers & ALT) != 0 ? "Alt+" : "")
          + ((modifiers & SUPER) != 0 ? "Super+" : "")
          + key;
    }
  }

  /** Empty means explicitly unbound. Escape is reserved for cancellation/free-fly release. */
  public static Optional<Chord> parse(String text) {
    if (text == null || text.length() > 80)
      throw new IllegalArgumentException("Shortcut must be at most 80 characters");
    if (text.isBlank()) return Optional.empty();
    String[] tokens = text.strip().split("\\+", -1);
    int modifiers = 0;
    for (int i = 0; i < tokens.length - 1; i++) {
      int bit =
          switch (tokens[i].strip().toLowerCase(Locale.ROOT)) {
            case "ctrl" -> CTRL;
            case "shift" -> SHIFT;
            case "alt" -> ALT;
            case "super" -> SUPER;
            default ->
                throw new IllegalArgumentException("Use Ctrl, Shift, Alt or Super before a key");
          };
      if ((modifiers & bit) != 0) throw new IllegalArgumentException("Repeated shortcut modifier");
      modifiers |= bit;
    }
    String key = KEYS.get(tokens[tokens.length - 1].strip().toLowerCase(Locale.ROOT));
    if (key == null)
      throw new IllegalArgumentException(
          "Unsupported key; use a letter, digit, F1-F12 or named key. Escape cancels.");
    return Optional.of(new Chord(modifiers, key));
  }

  public record Problem(String action, String message) {}

  public static List<Problem> problems(Map<String, String> bindings) {
    if (bindings == null) return List.of(new Problem("", "Missing shortcut bindings"));
    if (bindings.size() > ACTIONS.size())
      return List.of(new Problem("", "Too many shortcut bindings"));
    var problems = new ArrayList<Problem>();
    var parsed = new LinkedHashMap<Action, Chord>();
    for (var entry : bindings.entrySet()) {
      var action = BY_ID.get(entry.getKey());
      if (action == null) {
        problems.add(new Problem(Objects.toString(entry.getKey(), ""), "Unknown shortcut action"));
        continue;
      }
      try {
        parse(entry.getValue()).ifPresent(chord -> parsed.put(action, chord));
      } catch (IllegalArgumentException e) {
        problems.add(new Problem(action.id(), e.getMessage()));
      }
    }
    var entries = new ArrayList<>(parsed.entrySet());
    for (int i = 0; i < entries.size(); i++)
      for (int j = i + 1; j < entries.size(); j++) {
        var a = entries.get(i);
        var b = entries.get(j);
        if (a.getValue().equals(b.getValue()) && overlap(a.getKey().scope(), b.getKey().scope())) {
          problems.add(
              new Problem(
                  a.getKey().id(), a.getValue().text() + " conflicts with " + b.getKey().label()));
          problems.add(
              new Problem(
                  b.getKey().id(), b.getValue().text() + " conflicts with " + a.getKey().label()));
        }
      }
    return List.copyOf(problems);
  }

  public static boolean overlap(Scope a, Scope b) {
    return a == Scope.GLOBAL || b == Scope.GLOBAL || a == b || a != Scope.CURVE && b != Scope.CURVE;
  }

  public static Map<String, String> canonical(Map<String, String> bindings) {
    var problems = problems(bindings);
    if (!problems.isEmpty())
      throw new IllegalArgumentException(
          problems.getFirst().action() + ": " + problems.getFirst().message());
    var result = new LinkedHashMap<String, String>();
    for (var action : ACTIONS)
      result.put(
          action.id(), parse(bindings.getOrDefault(action.id(), "")).map(Chord::text).orElse(""));
    return result;
  }

  /** Preserve historical bindings; fill new commands only where their defaults do not conflict. */
  public static Map<String, String> upgrade(Map<String, String> authored) {
    var result = new LinkedHashMap<>(canonical(authored));
    Set<String> legacy = Set.of("Save", "Undo", "Redo", "Find");
    for (var action : ACTIONS)
      if (!authored.containsKey(action.id()) && !legacy.contains(action.id())) {
        result.put(action.id(), action.defaultBinding());
        if (!problems(result).isEmpty()) result.put(action.id(), "");
      }
    return result;
  }

  public static boolean eligible(Scope scope, Focus focus) {
    return focus != Focus.FLYING
        && (scope == Scope.GLOBAL
            || scope == Scope.DOCUMENT && focus != Focus.CURVE
            || scope == Scope.RESOURCES && focus == Focus.RESOURCES
            || scope == Scope.CURVE && focus == Focus.CURVE);
  }

  public static final class Bindings {
    private final Map<Action, Chord> parsed;
    private final Map<String, String> canonical;

    private Bindings(Map<String, String> bindings) {
      canonical = Collections.unmodifiableMap(Shortcuts.canonical(bindings));
      var result = new LinkedHashMap<Action, Chord>();
      for (var action : ACTIONS)
        parse(canonical.get(action.id())).ifPresent(chord -> result.put(action, chord));
      parsed = Collections.unmodifiableMap(result);
    }

    public Map<String, String> values() {
      return canonical;
    }

    public Optional<Action> match(Context context, int modifiers, Predicate<String> pressed) {
      if (context.blocked()) return Optional.empty();
      for (var entry : parsed.entrySet())
        if (eligible(entry.getKey().scope(), context.focus())
            && entry.getValue().modifiers() == modifiers
            && pressed.test(entry.getValue().key())) return Optional.of(entry.getKey());
      return Optional.empty();
    }
  }

  public static Bindings compile(Map<String, String> bindings) {
    return new Bindings(bindings);
  }

  public static Optional<Action> match(
      Map<String, String> bindings, Context context, int modifiers, Predicate<String> pressed) {
    return compile(bindings).match(context, modifiers, pressed);
  }

  public static Map<String, Object> registry() {
    return Map.of(
        "formatVersion",
        1,
        "modifiers",
        List.of("Ctrl", "Shift", "Alt", "Super"),
        "keys",
        List.copyOf(keys()),
        "actions",
        ACTIONS,
        "maximumBindingUtf16Units",
        80,
        "unbound",
        "empty string",
        "reserved",
        "Escape cancels; mouse/free-fly gestures retain their own routing",
        "scope",
        "Global overlaps all; Document and Resources overlap; Curve is separate from"
            + " Document/Resources. Text, popups, active widgets, recording and free-fly suppress"
            + " commands.",
        "migration",
        "Existing authored chords win; conflicting newly introduced defaults remain unbound.");
  }
}
