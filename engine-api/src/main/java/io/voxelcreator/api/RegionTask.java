package io.voxelcreator.api;

import java.util.concurrent.CompletableFuture;

public interface RegionTask {
  /** Request cooperative stream cancellation independently of any completion observer. */
  boolean cancel();

  TaskProgress progress();

  /**
   * Fresh observation future, published after owned request/region-slot cleanup. Mutating,
   * cancelling or timing out this future affects only that observer; use cancel() to stop the
   * stream. Normal stream cancellation returns a summary with cancelled counts. Retain one future
   * when polling it repeatedly.
   */
  CompletableFuture<RegionSummary> completion();
}
