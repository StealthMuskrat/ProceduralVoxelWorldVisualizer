package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.ProjectIO;
import java.util.*;
import java.util.regex.Pattern;

/** Private profile text has no fallback publication; vectors are parsed as complete values. */
final class CreationValueInputs {
  static final int MAX_NUMBER_CHARACTERS = 128;
  static final int MAX_TEXT_CHARACTERS = 32768;
  private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");
  private static final Pattern DECIMAL =
      Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
  private final AppMetadata draft;
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  private static final class Entry {
    final List<String> raw;
    final boolean present;
    JsonNode expected;
    String error = "";

    Entry(JsonNode value, boolean present, CreationRegistry.Spec spec) {
      raw = new ArrayList<>(strings(value, spec));
      expected = value.deepCopy();
      this.present = present;
    }
  }

  CreationValueInputs(AppMetadata draft) {
    this.draft = Objects.requireNonNull(draft);
  }

  private static CreationRegistry.Spec spec(String id) {
    var spec = CreationRegistry.get(id);
    if (spec.kind() == CreationRegistry.Kind.BOOLEAN
        || spec.kind() == CreationRegistry.Kind.CHOICE
        || spec.kind() == CreationRegistry.Kind.INTEGER && !spec.options().isEmpty())
      throw new IllegalArgumentException("This creation control has no raw value fields: " + id);
    return spec;
  }

  static int limit(CreationRegistry.Spec spec) {
    return spec.kind() == CreationRegistry.Kind.TEXT ? MAX_TEXT_CHARACTERS : MAX_NUMBER_CHARACTERS;
  }

  private Entry entry(String id) {
    var spec = spec(id);
    return entries.computeIfAbsent(
        id,
        key ->
            new Entry(
                CreationRegistry.value(draft, id), draft.creationDefaults.containsKey(id), spec));
  }

  private boolean current(String id, Entry entry) {
    return entry.present == draft.creationDefaults.containsKey(id)
        && entry.expected.equals(CreationRegistry.value(draft, id));
  }

  List<String> text(String id) {
    return List.copyOf(entry(id).raw);
  }

  Set<String> ids() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(entries.keySet()));
  }

  void edit(String id, int component, String value) {
    var entry = entry(id);
    if (component < 0 || component >= entry.raw.size())
      throw new IllegalArgumentException("Creation component is outside this value");
    var next = new ArrayList<>(entry.raw);
    next.set(component, value);
    editAll(id, next);
  }

  void editAll(String id, List<String> values) {
    var spec = spec(id);
    var entry = entry(id);
    if (values.size() != entry.raw.size())
      throw new IllegalArgumentException("All creation value components are required");
    if (!current(id, entry))
      throw new IllegalStateException(
          "Creation value changed elsewhere; Reload Value before editing");
    if (!entry.present) throw new IllegalStateException("Enable the creation override first");
    for (var value : values)
      if (Objects.requireNonNull(value).length() > limit(spec))
        throw new IllegalArgumentException("Creation input text limit " + limit(spec));
    var next = List.copyOf(values);
    entry.raw.clear();
    entry.raw.addAll(next);
    try {
      String value = entry.raw.getFirst();
      JsonNode parsed;
      if (spec.kind() == CreationRegistry.Kind.TEXT) parsed = ProjectIO.JSON.valueToTree(value);
      else if (spec.kind() == CreationRegistry.Kind.LONG
          || spec.kind() == CreationRegistry.Kind.INTEGER) {
        if (!INTEGER.matcher(value).matches())
          throw new IllegalArgumentException("An exact signed decimal integer is required");
        parsed = ProjectIO.JSON.valueToTree(Long.parseLong(value));
      } else if (spec.kind() == CreationRegistry.Kind.NUMBER)
        parsed = ProjectIO.JSON.valueToTree(number(value));
      else {
        var vector = ProjectIO.JSON.createArrayNode();
        for (var raw : entry.raw) vector.add(number(raw));
        parsed = vector;
      }
      spec.validate(parsed);
      draft.creationDefaults.put(id, parsed);
      entry.expected = parsed.deepCopy();
      entry.error = "";
    } catch (RuntimeException invalid) {
      var message = invalid.getMessage();
      entry.error = message == null || message.isBlank() ? invalid.toString() : message;
    }
  }

  private static double number(String raw) {
    if (!DECIMAL.matcher(raw).matches())
      throw new IllegalArgumentException("A finite decimal number is required");
    double value = Double.parseDouble(raw);
    if (!Double.isFinite(value))
      throw new IllegalArgumentException("A finite decimal number is required");
    return value;
  }

  String error(String id) {
    var entry = entries.get(id);
    if (entry == null) return "";
    return current(id, entry)
        ? entry.error
        : "Creation value changed elsewhere; Reload Value before applying";
  }

  Map<String, String> errors() {
    var errors = new LinkedHashMap<String, String>();
    for (var id : entries.keySet()) {
      var error = error(id);
      if (!error.isEmpty()) errors.put(id, error);
    }
    return Collections.unmodifiableMap(errors);
  }

  boolean changed() {
    for (var entry : entries.entrySet())
      if (!error(entry.getKey()).isEmpty()
          || !entry.getValue().raw.equals(strings(entry.getValue().expected, spec(entry.getKey()))))
        return true;
    return false;
  }

  void requireValid() {
    var errors = errors();
    if (!errors.isEmpty()) {
      var first = errors.entrySet().iterator().next();
      throw new IllegalStateException(first.getKey() + ": " + first.getValue());
    }
  }

  void requireDisplayed(Map<String, List<String>> displayed) {
    if (!displayed.keySet().equals(entries.keySet()))
      throw new IllegalStateException("All retained creation input fields are required");
    for (var entry : entries.entrySet())
      if (!entry.getValue().raw.equals(displayed.get(entry.getKey())))
        throw new IllegalStateException(
            "Displayed creation input differs from the private value: " + entry.getKey());
    requireValid();
  }

  void override(String id, boolean enabled) {
    var spec = CreationRegistry.get(id);
    if (enabled && draft.creationDefaults.containsKey(id)) return;
    if (enabled) draft.creationDefaults.put(id, spec.builtIn());
    else if (!enabled) draft.creationDefaults.remove(id);
    entries.remove(id);
  }

  void replace(String id, JsonNode value) {
    CreationRegistry.get(id).validate(value);
    draft.creationDefaults.put(id, value.deepCopy());
    entries.remove(id);
  }

  void reload(String id) {
    CreationRegistry.get(id);
    entries.remove(id);
  }

  private static List<String> strings(JsonNode value, CreationRegistry.Spec spec) {
    if (spec.kind() == CreationRegistry.Kind.VECTOR2
        || spec.kind() == CreationRegistry.Kind.VECTOR3) {
      var result = new ArrayList<String>();
      for (var component : value) result.add(Double.toString(component.doubleValue()));
      return List.copyOf(result);
    }
    if (spec.kind() == CreationRegistry.Kind.NUMBER)
      return List.of(Double.toString(value.doubleValue()));
    if (spec.kind() == CreationRegistry.Kind.INTEGER)
      return List.of(Long.toString(value.longValue()));
    return List.of(value.asText());
  }
}
