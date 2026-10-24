package io.voxelcreator.api;

import io.voxelcreator.storage.VoxelChunk;
import java.lang.ref.Cleaner;
import java.util.*;

/** Owns the engine's collection reservation. Closing releases admission; snapshots remain valid. */
public final class RegionResult implements AutoCloseable {
  private static final Cleaner CLEANER = Cleaner.create();
  private final List<VoxelChunk> chunks;
  private final RegionSummary summary;
  private final Cleaner.Cleanable lease;

  public RegionResult(List<VoxelChunk> chunks, RegionSummary summary) {
    this(chunks, summary, () -> {});
  }

  RegionResult(List<VoxelChunk> chunks, RegionSummary summary, Runnable release) {
    this.chunks = List.copyOf(chunks);
    this.summary = Objects.requireNonNull(summary);
    lease = CLEANER.register(this, release);
  }

  public List<VoxelChunk> chunks() {
    return chunks;
  }

  public RegionSummary summary() {
    return summary;
  }

  public void close() {
    lease.clean();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RegionResult result
        && chunks.equals(result.chunks)
        && summary.equals(result.summary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(chunks, summary);
  }

  @Override
  public String toString() {
    return "RegionResult[chunks=" + chunks + ", summary=" + summary + "]";
  }
}
