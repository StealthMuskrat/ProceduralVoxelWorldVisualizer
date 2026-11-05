package io.voxelcreator.editor;

import io.voxelcreator.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;

/** Canonical capture encoding on a worker; the UI alone authorizes final atomic replacement. */
final class ProfileExportFiles {
  interface Storage {
    default void write(Path temporary, Profiler.Snapshot snapshot, boolean csv) throws IOException {
      // Reuse the engine's canonical, forced atomic writer into this private reserved sibling.
      if (csv) ProfileIO.writeCSV(temporary, snapshot); else ProfileIO.writeJSON(temporary, snapshot);
    }

    default void replace(Path temporary, Path destination) throws IOException {
      try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        throw new IOException("This location does not support safe capture replacement; choose another location", unsupported);
      }
    }

    default void delete(Path temporary) throws IOException { Files.deleteIfExists(temporary); }
  }

  private final Storage storage;

  ProfileExportFiles() { this(new Storage() {}); }

  ProfileExportFiles(Storage storage) { this.storage = Objects.requireNonNull(storage); }

  final class Prepared implements AutoCloseable {
    private final Path target, temporary;
    private boolean closed;

    private Prepared(Path target, Path temporary) { this.target = target; this.temporary = temporary; }

    synchronized void publish(Path activeProject) throws IOException {
      if (closed) throw new IllegalStateException("Capture staging already consumed");
      ExportPaths.rejectProjectTarget(target, activeProject, "capture file");
      storage.replace(temporary, target);
      closed = true;
    }

    @Override
    public synchronized void close() throws IOException {
      if (closed) return;
      closed = true; // Cleanup ownership is terminal, even if deletion fails.
      storage.delete(temporary);
    }
  }

  Prepared prepare(Path destination, Profiler.Snapshot snapshot, boolean csv, Path activeProject)
      throws IOException {
    Path target = Objects.requireNonNull(destination).toAbsolutePath();
    Objects.requireNonNull(snapshot);
    if (target.getParent() == null || target.getFileName() == null) throw new IOException("Choose a capture file path");
    ExportPaths.rejectProjectTarget(target, activeProject, "capture file");
    Files.createDirectories(target.getParent());
    ExportPaths.rejectProjectTarget(target, activeProject, "capture file");
    Path temporary = Files.createTempFile(target.getParent(), ".profile-export-", ".tmp");
    try {
      storage.write(temporary, snapshot, csv);
      return new Prepared(target, temporary);
    } catch (IOException | RuntimeException | Error failure) {
      try { storage.delete(temporary); }
      catch (IOException | RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
      throw failure;
    }
  }
}
