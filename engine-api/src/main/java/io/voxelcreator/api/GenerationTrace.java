package io.voxelcreator.api;

import io.voxelcreator.storage.BlockPos;
import java.util.*;

public record GenerationTrace(BlockPos position, int finalType, List<Step> steps) {
  public record GateEvaluation(
      String path, String kind, UUID noiseUuid, Double value, boolean passed) {}

  public record Details(
      Map<String, String> effectiveSettings,
      List<GateEvaluation> gates,
      Boolean gatePassed,
      Boolean predicatePassed,
      double probability,
      double random,
      boolean selected) {
    public Details {
      effectiveSettings = Map.copyOf(effectiveSettings);
      gates = List.copyOf(gates);
    }
  }

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
      Map<String, String> settingsSources,
      Details details) {
    public Step(
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
        Map<String, String> settingsSources) {
      this(
          layerUuid,
          operationUuid,
          noiseUuid,
          layerId,
          operationId,
          field,
          previous,
          result,
          action,
          reason,
          settingsSources,
          null);
    }
  }
}
