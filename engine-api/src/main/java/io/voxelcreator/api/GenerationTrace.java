package io.voxelcreator.api;

import io.voxelcreator.storage.BlockPos;
import java.util.*;

public record GenerationTrace(BlockPos position, int finalType, List<Step> steps) {
  public record GateEvaluation(
      String path, String kind, UUID noiseUuid, Double value, boolean passed, Double random) {
    public GateEvaluation(String path, String kind, UUID noiseUuid, Double value, boolean passed) {
      this(path, kind, noiseUuid, value, passed, null);
    }
  }

  public record SampleCoordinates(double x, double y, double z) {}

  public record DriverEvaluation(
      String property,
      UUID noiseUuid,
      double sample,
      double mapped,
      double constant,
      double effective) {}

  public record Details(
      Map<String, String> effectiveSettings,
      List<GateEvaluation> gates,
      Boolean gatePassed,
      Boolean predicatePassed,
      double probability,
      double random,
      boolean selected,
      SampleCoordinates sampleCoordinates,
      Integer matchedInterval,
      int candidateType,
      List<DriverEvaluation> drivers) {
    public Details {
      effectiveSettings = Map.copyOf(effectiveSettings);
      gates = List.copyOf(gates);
      drivers = List.copyOf(drivers);
    }

    public Details(
        Map<String, String> effectiveSettings,
        List<GateEvaluation> gates,
        Boolean gatePassed,
        Boolean predicatePassed,
        double probability,
        double random,
        boolean selected) {
      this(
          effectiveSettings,
          gates,
          gatePassed,
          predicatePassed,
          probability,
          random,
          selected,
          null,
          null,
          0,
          List.of());
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
