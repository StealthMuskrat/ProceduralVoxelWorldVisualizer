package io.voxelcreator.api;

import io.voxelcreator.storage.*;
import java.util.*;

/** Immutable terminal region value; failure-list ownership is detached from its caller. */
public record RegionSummary(
    RegionBounds requested,
    Optional<RegionBounds> effective,
    TaskProgress progress,
    List<ChunkFailure> failures) {
  public RegionSummary {
    Objects.requireNonNull(requested);
    Objects.requireNonNull(effective);
    Objects.requireNonNull(progress);
    failures = List.copyOf(failures);
  }

  /**
   * Engine-produced details share one readable description, at most 2048 UTF-16 units, with
   * diagnostics.
   */
  public record ChunkFailure(ChunkPos chunk, String phase, String message) {}
}
