package io.voxelcreator.api;

import io.voxelcreator.storage.*;
import java.util.*;

public record RegionSummary(
    RegionBounds requested,
    Optional<RegionBounds> effective,
    TaskProgress progress,
    List<ChunkFailure> failures) {
  public record ChunkFailure(ChunkPos chunk, String phase, String message) {}
}
