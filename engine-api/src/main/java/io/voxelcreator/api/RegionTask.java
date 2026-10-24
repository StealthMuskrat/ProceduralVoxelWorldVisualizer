package io.voxelcreator.api;

import java.util.concurrent.CompletableFuture;

public interface RegionTask {
  boolean cancel();

  TaskProgress progress();

  CompletableFuture<RegionSummary> completion();
}
