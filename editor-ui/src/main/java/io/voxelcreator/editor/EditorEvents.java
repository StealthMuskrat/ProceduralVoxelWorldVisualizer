package io.voxelcreator.editor;

import io.voxelcreator.model.Diagnostic;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;

/**
 * Thread-safe bounded grouped history. Worker publication never allocates a UI callback per event.
 */
public final class EditorEvents {
  private static final int CODE_LIMIT = 96, MESSAGE_LIMIT = 8192;
  private static final int PARAMETER_LIMIT = 256, POINTER_LIMIT = 2048;

  public enum Subsystem {
    APPLICATION,
    PROJECT,
    GENERATION,
    RENDERING,
    IMPORT_EXPORT,
    SCHEDULER,
    ENGINE
  }

  public record Target(
      UUID project,
      UUID resource,
      UUID control,
      String parameter,
      String pointer,
      boolean settings) {
    public static Target diagnostic(UUID project, Diagnostic d) {
      String p = Objects.requireNonNullElse(d.jsonPointer(), "");
      return new Target(
          project,
          d.sourceUuid(),
          d.itemUuid(),
          DiagnosticNavigation.parameter(d),
          p,
          d.sourceUuid() == null && (p.startsWith("/project") || p.equals("/formatVersion")));
    }

    /** Retained history may reveal the resource, but never a fabricated truncated control key. */
    private Target retained() {
      if (parameter != null && parameter.length() > PARAMETER_LIMIT
          || pointer != null && pointer.length() > POINTER_LIMIT)
        return new Target(project, resource, null, null, "", false);
      return this;
    }
  }

  public record Entry(
      long id,
      String code,
      Diagnostic.Severity severity,
      Subsystem subsystem,
      String message,
      Target target,
      Instant first,
      Instant last,
      long count,
      boolean read,
      boolean actionRequired,
      boolean dismissed) {
    public String details() {
      return severity
          + " / "
          + subsystem
          + " / "
          + code
          + "\n"
          + message
          + "\nOccurrences: "
          + count
          + "\nFirst: "
          + first
          + "\nLast: "
          + last
          + "\n"
          + (target == null
              ? ""
              : "Project: "
                  + target.project
                  + "\nResource: "
                  + target.resource
                  + "\nControl: "
                  + target.control
                  + "\nParameter: "
                  + target.parameter
                  + "\nPath: "
                  + target.pointer
                  + "\n")
          + "Read: "
          + read
          + " / Action required: "
          + actionRequired;
    }
  }

  private record Key(
      String code,
      Diagnostic.Severity severity,
      Subsystem subsystem,
      String message,
      Target target,
      String fingerprint) {}

  public record Counts(int retained, int unread, int actionRequired, long evicted) {}

  /** One immutable history/count view, reused until a real publication or state change. */
  public record View(List<Entry> entries, Counts counts) {
    public View {
      entries = List.copyOf(entries);
      Objects.requireNonNull(counts);
    }

    public List<Entry> search(
        String query, Diagnostic.Severity severity, Subsystem subsystem, boolean noticesOnly) {
      String text = Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
      return entries.stream()
          .filter(e -> !noticesOnly || !e.dismissed)
          .filter(e -> severity == null || e.severity == severity)
          .filter(e -> subsystem == null || e.subsystem == subsystem)
          .filter(
              e ->
                  text.isEmpty()
                      || (e.code + " " + e.message + " " + e.subsystem + " " + e.target)
                          .toLowerCase(Locale.ROOT)
                          .contains(text))
          .toList();
    }
  }

  private final int capacity;
  private final Clock clock;
  private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();
  private long nextId = 1, evicted;
  private View cachedView;

  public EditorEvents() {
    this(1000, Clock.systemUTC());
  }

  public EditorEvents(int capacity, Clock clock) {
    if (capacity < 1 || capacity > 10000)
      throw new IllegalArgumentException("Event capacity 1..10000");
    this.capacity = capacity;
    this.clock = Objects.requireNonNull(clock);
  }

  public synchronized Entry publish(
      String code,
      Diagnostic.Severity severity,
      Subsystem subsystem,
      String message,
      Target target,
      boolean actionRequired) {
    Objects.requireNonNull(severity);
    Objects.requireNonNull(subsystem);
    code = Objects.requireNonNullElse(code, "UNSPECIFIED");
    message = Objects.requireNonNullElse(message, "No details supplied");
    String fingerprint = fingerprint(code, message, target);
    code = clip(code, CODE_LIMIT);
    message = clip(message, MESSAGE_LIMIT);
    if (target != null) target = target.retained();
    Key key = new Key(code, severity, subsystem, message, target, fingerprint);
    Entry old = entries.remove(key);
    Instant now = clock.instant();
    Entry next =
        new Entry(
            old == null ? nextId++ : old.id,
            code,
            severity,
            subsystem,
            message,
            target,
            old == null ? now : old.first,
            now,
            old == null ? 1 : old.count == Long.MAX_VALUE ? Long.MAX_VALUE : old.count + 1,
            false,
            actionRequired || (old != null && old.actionRequired),
            false);
    entries.put(key, next);
    while (entries.size() > capacity) {
      entries.remove(entries.keySet().iterator().next());
      if (evicted < Long.MAX_VALUE) evicted++;
    }
    cachedView = null;
    return next;
  }

  public synchronized List<Entry> snapshot() {
    return view().entries();
  }

  public synchronized View view() {
    if (cachedView == null) {
      var result = new ArrayList<>(entries.values());
      Collections.reverse(result);
      cachedView =
          new View(
              result,
              new Counts(
                  entries.size(),
                  (int) entries.values().stream().filter(e -> !e.dismissed && !e.read).count(),
                  (int) entries.values().stream().filter(e -> e.actionRequired).count(),
                  evicted));
    }
    return cachedView;
  }

  public List<Entry> search(
      String query, Diagnostic.Severity severity, Subsystem subsystem, boolean noticesOnly) {
    return view().search(query, severity, subsystem, noticesOnly);
  }

  public synchronized Optional<Entry> get(long id) {
    return entries.values().stream().filter(e -> e.id == id).findFirst();
  }

  public synchronized void markRead(long id) {
    update(id, true, null, null);
  }

  public synchronized void resolve(long id) {
    update(id, null, false, null);
  }

  public synchronized boolean current(Entry shown) {
    return shown != null && get(shown.id()).orElse(null) == shown;
  }

  public synchronized boolean markRead(Entry shown) {
    if (!current(shown)) return false;
    update(shown.id(), true, null, null);
    return true;
  }

  public synchronized boolean resolve(Entry shown) {
    if (!current(shown)) return false;
    update(shown.id(), null, false, null);
    return true;
  }

  public synchronized boolean dismiss(Entry shown) {
    if (!current(shown) || shown.actionRequired()) return false;
    update(shown.id(), true, null, true);
    return true;
  }

  /** Dismissal never removes historical details or silently resolves an outstanding action. */
  public synchronized boolean dismiss(long id) {
    var e = get(id);
    if (e.isEmpty() || e.get().actionRequired) return false;
    update(id, true, null, true);
    return true;
  }

  public synchronized void markAllRead() {
    markAllRead(view());
  }

  /** New or changed occurrences after the displayed view remain unread. */
  public synchronized void markAllRead(View shown) {
    var reviewed = new HashMap<Long, Entry>();
    shown.entries().forEach(e -> reviewed.put(e.id(), e));
    entries.replaceAll(
        (key, e) -> {
          if (reviewed.get(e.id()) != e || e.read()) return e;
          cachedView = null;
          return new Entry(
              e.id,
              e.code,
              e.severity,
              e.subsystem,
              e.message,
              e.target,
              e.first,
              e.last,
              e.count,
              true,
              e.actionRequired,
              e.dismissed);
        });
  }

  private void update(long id, Boolean read, Boolean required, Boolean dismissed) {
    for (var pair : entries.entrySet()) {
      Entry e = pair.getValue();
      if (e.id != id) continue;
      if ((read == null || read == e.read)
          && (required == null || required == e.actionRequired)
          && (dismissed == null || dismissed == e.dismissed)) return;
      pair.setValue(
          new Entry(
              e.id,
              e.code,
              e.severity,
              e.subsystem,
              e.message,
              e.target,
              e.first,
              e.last,
              e.count,
              read == null ? e.read : read,
              required == null ? e.actionRequired : required,
              dismissed == null ? e.dismissed : dismissed));
      cachedView = null;
      return;
    }
  }

  public synchronized Counts counts() {
    return view().counts();
  }

  private static String fingerprint(String code, String message, Target target) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      part(digest, code);
      part(digest, message);
      digest.update((byte) (target == null ? 0 : 1));
      if (target != null) {
        part(digest, target.project == null ? null : target.project.toString());
        part(digest, target.resource == null ? null : target.resource.toString());
        part(digest, target.control == null ? null : target.control.toString());
        part(digest, target.parameter);
        part(digest, target.pointer);
        digest.update((byte) (target.settings ? 1 : 0));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /** Length framing preserves field boundaries/nulls; raw UTF-16 units preserve lone surrogates. */
  private static void part(MessageDigest digest, String value) {
    int length = value == null ? -1 : value.length();
    for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (length >>> shift));
    if (value == null) return;
    for (int i = 0; i < value.length(); i++) {
      char unit = value.charAt(i);
      digest.update((byte) (unit >>> 8));
      digest.update((byte) unit);
    }
  }

  private static String clip(String value, int limit) {
    if (value == null || value.length() <= limit) return value;
    int end = limit - 1;
    if (end > 0
        && Character.isHighSurrogate(value.charAt(end - 1))
        && Character.isLowSurrogate(value.charAt(end))) end--;
    return value.substring(0, end) + "…";
  }
}
