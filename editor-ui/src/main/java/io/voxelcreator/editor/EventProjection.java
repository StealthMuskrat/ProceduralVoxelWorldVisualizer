package io.voxelcreator.editor;

import io.voxelcreator.model.Diagnostic;
import java.util.*;

/** One cached immutable filter projection per tool; selection remains an event ID. */
final class EventProjection {
  private EditorEvents.View owner;
  private String query;
  private Diagnostic.Severity severity;
  private EditorEvents.Subsystem subsystem;
  private boolean notices;
  private List<EditorEvents.Entry> rows = List.of();
  private long builds;

  List<EditorEvents.Entry> rows(
      EditorEvents.View view,
      String raw,
      Diagnostic.Severity severity,
      EditorEvents.Subsystem subsystem,
      boolean notices) {
    String normalized = Objects.requireNonNullElse(raw, "").strip().toLowerCase(Locale.ROOT);
    if (owner != view
        || !normalized.equals(query)
        || this.severity != severity
        || this.subsystem != subsystem
        || this.notices != notices) {
      rows = view.search(normalized, severity, subsystem, notices);
      owner = view;
      query = normalized;
      this.severity = severity;
      this.subsystem = subsystem;
      this.notices = notices;
      builds++;
    }
    return rows;
  }

  long builds() {
    return builds;
  }
}
