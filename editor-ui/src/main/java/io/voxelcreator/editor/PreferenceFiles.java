package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectIO;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Objects;

/** Bounded configuration I/O with forced sibling content and required atomic replacement. */
final class PreferenceFiles {
  static final int MAX_BYTES = 8 * 1024 * 1024;

  interface Storage {
    void write(Path temporary, byte[] bytes) throws IOException;

    void replace(Path temporary, Path destination) throws IOException;

    default void delete(Path temporary) throws IOException {
      Files.deleteIfExists(temporary);
    }
  }

  private final Storage storage;

  PreferenceFiles() {
    this(
        new Storage() {
          public void write(Path temporary, byte[] bytes) throws IOException {
            try (var channel =
                FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
              var buffer = ByteBuffer.wrap(bytes);
              while (buffer.hasRemaining()) channel.write(buffer);
              channel.force(true);
            }
          }

          public void replace(Path temporary, Path destination) throws IOException {
            try {
              Files.move(
                  temporary,
                  destination,
                  StandardCopyOption.REPLACE_EXISTING,
                  StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
              throw new IOException(
                  "This location does not support safe configuration replacement; choose a location"
                      + " with atomic file replacement",
                  unsupported);
            }
          }
        });
  }

  PreferenceFiles(Storage storage) {
    this.storage = Objects.requireNonNull(storage);
  }

  static byte[] read(Path path) throws IOException {
    try (var input = Files.newInputStream(path)) {
      return read(input);
    }
  }

  static byte[] read(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) throw new IOException("Configuration exceeds 8 MiB");
    return bytes;
  }

  void write(Path path, Object snapshot, String prefix) throws IOException {
    // Serialize before creating directories or changing destination files.
    byte[] bytes = ProjectIO.JSON.writeValueAsBytes(Objects.requireNonNull(snapshot));
    if (bytes.length > MAX_BYTES) throw new IOException("Configuration exceeds 8 MiB");
    Path target = Objects.requireNonNull(path).toAbsolutePath();
    if (target.getFileName() == null || target.getParent() == null)
      throw new IOException("Choose a configuration file path");
    Files.createDirectories(target.getParent());
    Path temporary = Files.createTempFile(target.getParent(), prefix, ".tmp");
    Throwable failure = null;
    try {
      storage.write(temporary, bytes);
      storage.replace(temporary, target);
    } catch (IOException | RuntimeException | Error rejected) {
      failure = rejected;
      throw rejected;
    } finally {
      try {
        storage.delete(temporary);
      } catch (IOException cleanup) {
        if (failure != null) failure.addSuppressed(cleanup);
        else throw cleanup;
      }
    }
  }
}
