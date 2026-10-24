package io.voxelcreator.api;

import io.voxelcreator.storage.RegionBounds;

public record RegionRequest(
    RegionBounds bounds, FailurePolicy failurePolicy, DeliveryOrder order, int maxInFlightChunks) {
  public enum FailurePolicy {
    CONTINUE,
    FAIL_FAST
  }

  public enum DeliveryOrder {
    COMPLETION,
    COORDINATE
  }

  public RegionRequest {
    if (bounds == null
        || failurePolicy == null
        || order == null
        || maxInFlightChunks < 1
        || maxInFlightChunks > 1024)
      throw new IllegalArgumentException(
          "Region request needs bounds and an in-flight budget 1..1024");
  }

  public static RegionRequest of(RegionBounds bounds) {
    return new RegionRequest(bounds, FailurePolicy.CONTINUE, DeliveryOrder.COMPLETION, 8);
  }
}
