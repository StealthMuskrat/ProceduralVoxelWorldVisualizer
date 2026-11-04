package io.voxelcreator.storage;

import java.io.IOException;
import java.nio.file.*;

/** Per-writer publication boundary; production never falls back to ordinary replacement. */
@FunctionalInterface
interface ArchivePublication {
  void replace(Path temporary, Path destination) throws IOException;

  static void atomic(Path temporary, Path destination) throws IOException {
    Files.move(
        temporary,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
  }
}
