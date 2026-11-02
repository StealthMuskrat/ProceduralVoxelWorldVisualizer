package io.voxelcreator.model;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Per-writer package-private persistence boundary; production replacement is always atomic. */
interface ProjectSaveFiles {
  default Path temporary(Path parent) throws IOException {
    return Files.createTempFile(parent, ".voxel-save-", ".tmp");
  }

  default void write(Path path, String contents) throws IOException {
    Files.writeString(path, contents);
  }

  default void force(Path path) throws IOException {
    try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  default void replace(Path temporary, Path target) throws IOException {
    Files.move(
        temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  default void delete(Path temporary) throws IOException {
    Files.deleteIfExists(temporary);
  }
}
