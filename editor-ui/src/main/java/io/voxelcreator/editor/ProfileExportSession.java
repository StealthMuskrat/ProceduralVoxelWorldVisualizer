package io.voxelcreator.editor;

import io.voxelcreator.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;

/** An explicit review owns one immutable capture, independent of live collection and filtering. */
final class ProfileExportSession {
  @FunctionalInterface
  interface Writer {
    void write(Path path, Profiler.Snapshot snapshot, boolean csv) throws IOException;
  }

  private final Profiler.Snapshot snapshot;
  private final Writer writer;
  private boolean consumed, writing;

  ProfileExportSession(Profiler.Snapshot snapshot) {
    this(
        snapshot,
        (path, value, csv) -> {
          if (csv) ProfileIO.writeCSV(path, value);
          else ProfileIO.writeJSON(path, value);
        });
  }

  ProfileExportSession(Profiler.Snapshot snapshot, Writer writer) {
    this.snapshot = Objects.requireNonNull(snapshot);
    this.writer = Objects.requireNonNull(writer);
  }

  Profiler.Snapshot snapshot() {
    return snapshot;
  }

  boolean available() {
    return !consumed && !writing;
  }

  void cancel() {
    if (writing) throw new IllegalStateException("Capture export is in progress");
    consumed = true;
  }

  Path write(String text, boolean csv, Path activeProject) throws IOException {
    if (!available()) throw new IOException("Prepare a capture export before writing");
    if (text == null || text.isBlank()) throw new IOException("Choose an export path");
    final Path path;
    try {
      path = Path.of(text).toAbsolutePath();
    } catch (InvalidPathException invalid) {
      throw new IOException("Invalid capture path: " + invalid.getReason(), invalid);
    }
    ExportPaths.rejectProjectTarget(path, activeProject, "capture file");
    writing = true;
    try {
      writer.write(path, snapshot, csv);
      consumed = true;
      return path;
    } finally {
      writing = false;
    }
  }
}
