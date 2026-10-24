package io.voxelcreator.api;

import io.voxelcreator.storage.RegionBounds;

public record RegionRequest(
    RegionBounds bounds,
    FailurePolicy failurePolicy,
    DeliveryOrder order,
    int maxInFlightChunks,
    GenerationPriority priority) {
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
        || priority == null
        || maxInFlightChunks < 1
        || maxInFlightChunks > 1024)
      throw new IllegalArgumentException(
          "Region request needs bounds and an in-flight budget 1..1024");
  }

  public RegionRequest(
      RegionBounds bounds,
      FailurePolicy failurePolicy,
      DeliveryOrder order,
      int maxInFlightChunks) {
    this(bounds, failurePolicy, order, maxInFlightChunks, GenerationPriority.NORMAL);
  }

  public static RegionRequest of(RegionBounds bounds) {
    return new RegionRequest(bounds, FailurePolicy.CONTINUE, DeliveryOrder.COMPLETION, 8);
  }
}
