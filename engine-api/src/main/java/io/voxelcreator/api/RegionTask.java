package io.voxelcreator.api;

import java.util.concurrent.CompletableFuture;

public interface RegionTask {
  /** Request cooperative stream cancellation independently of any completion observer. */
  boolean cancel();

  /**
   * One coherent immutable count snapshot. Counts partition the effective chunk total, including
   * during delivery and cancellation; retaining a snapshot never follows later task changes.
   * Cancellation is reflected when the coordinator observes it. Poll again for newer progress.
   */
  TaskProgress progress();

  /**
   * Fresh observation future, published after owned request/region-slot cleanup. Mutating,
   * cancelling or timing out this future affects only that observer; use cancel() to stop the
   * stream. Normal stream cancellation returns a summary with cancelled counts. Retain one future
   * when polling it repeatedly.
   */
  CompletableFuture<RegionSummary> completion();
}
