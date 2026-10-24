package io.voxelcreator.api;

import io.voxelcreator.storage.BlockPos;
import java.util.*;

public record GenerationTrace(BlockPos position, int finalType, List<Step> steps) {
  public record Step(
      UUID layerUuid,
      UUID operationUuid,
      UUID noiseUuid,
      String layerId,
      String operationId,
      double field,
      int previous,
      int result,
      String action,
      String reason,
      Map<String, String> settingsSources) {}
}
