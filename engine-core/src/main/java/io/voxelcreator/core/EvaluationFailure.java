package io.voxelcreator.core;

import java.util.UUID;

public final class EvaluationFailure extends RuntimeException {
  public final UUID resourceUuid, itemUuid;
  public final double x, y, z;

  public EvaluationFailure(UUID r, UUID i, double x, double y, double z, String message) {
    super(message + " [resource=" + r + ", item=" + i + ", xyz=" + x + "," + y + "," + z + "]");
    resourceUuid = r;
    itemUuid = i;
    this.x = x;
    this.y = y;
    this.z = z;
  }
}
