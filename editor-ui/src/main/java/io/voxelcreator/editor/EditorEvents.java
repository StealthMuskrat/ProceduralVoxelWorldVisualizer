package io.voxelcreator.editor;

import io.voxelcreator.model.Diagnostic;
import java.time.*;
import java.util.*;

/**
 * Thread-safe bounded grouped history. Worker publication never allocates a UI callback per event.
 */
public final class EditorEvents {
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
          clip(d.parameterKey(), 256),
          clip(p, 2048),
          d.sourceUuid() == null && (p.startsWith("/project") || p.equals("/formatVersion")));
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

  private final int capacity;
  private final Clock clock;
  private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();
  private long nextId = 1, evicted;

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
    code = clip(Objects.requireNonNullElse(code, "UNSPECIFIED"), 96);
    message = Objects.requireNonNullElse(message, "No details supplied");
    String fingerprint;
    try {
      fingerprint =
          HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
    message = clip(message, 8192);
    if (target != null)
      target =
          new Target(
              target.project,
              target.resource,
              target.control,
              clip(target.parameter, 256),
              clip(target.pointer, 2048),
              target.settings);
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
    return next;
  }

  public synchronized List<Entry> snapshot() {
    var result = new ArrayList<>(entries.values());
    Collections.reverse(result);
    return List.copyOf(result);
  }

  public List<Entry> search(
      String query, Diagnostic.Severity severity, Subsystem subsystem, boolean noticesOnly) {
    String text = Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
    return snapshot().stream()
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

  public synchronized Optional<Entry> get(long id) {
    return entries.values().stream().filter(e -> e.id == id).findFirst();
  }

  public synchronized void markRead(long id) {
    update(id, true, null, null);
  }

  public synchronized void resolve(long id) {
    update(id, null, false, null);
  }

  /** Dismissal never removes historical details or silently resolves an outstanding action. */
  public synchronized boolean dismiss(long id) {
    var e = get(id);
    if (e.isEmpty() || e.get().actionRequired) return false;
    update(id, true, null, true);
    return true;
  }

  public synchronized void markAllRead() {
    entries.replaceAll(
        (key, e) ->
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
                true,
                e.actionRequired,
                e.dismissed));
  }

  private void update(long id, Boolean read, Boolean required, Boolean dismissed) {
    for (var pair : entries.entrySet()) {
      Entry e = pair.getValue();
      if (e.id != id) continue;
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
      return;
    }
  }

  public synchronized Counts counts() {
    return new Counts(
        entries.size(),
        (int) entries.values().stream().filter(e -> !e.dismissed && !e.read).count(),
        (int) entries.values().stream().filter(e -> e.actionRequired).count(),
        evicted);
  }

  private static String clip(String value, int limit) {
    return value == null
        ? null
        : value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
  }
}
