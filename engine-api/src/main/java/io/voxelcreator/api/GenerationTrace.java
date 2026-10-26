package io.voxelcreator.api;

import io.voxelcreator.core.NoiseTrace;
import io.voxelcreator.storage.BlockPos;
import java.util.*;

public record GenerationTrace(BlockPos position, int finalType, List<Step> steps) {
  public GenerationTrace {
    Objects.requireNonNull(position);
    steps = List.copyOf(steps);
  }

  /**
   * Resource-relative property keys never retain an authored array index as navigation identity.
   */
  public record SettingSource(UUID resourceUuid, String property, boolean override) {}

  public record Comparison(
      String property, double left, String operator, double right, boolean passed) {}

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

  public record ParameterEvaluation(
      int row,
      String destination,
      String combination,
      UUID noiseUuid,
      double sample,
      double mapped,
      double previous,
      double effective,
      String phase,
      SampleCoordinates sampleCoordinates) {
    public ParameterEvaluation(
        int row,
        String destination,
        String combination,
        UUID noiseUuid,
        double sample,
        double mapped,
        double previous,
        double effective) {
      this(
          row,
          destination,
          combination,
          noiseUuid,
          sample,
          mapped,
          previous,
          effective,
          "INTERPRETATION",
          null);
    }
  }

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
      List<DriverEvaluation> drivers,
      List<ParameterEvaluation> parameters,
      Map<String, SettingSource> settingTargets,
      List<Comparison> comparisons,
      NoiseTrace primaryField) {
    public Details {
      effectiveSettings = Map.copyOf(effectiveSettings);
      gates = List.copyOf(gates);
      drivers = List.copyOf(drivers);
      parameters = List.copyOf(parameters);
      settingTargets = Map.copyOf(settingTargets);
      comparisons = List.copyOf(comparisons);
    }

    public Details(
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
        List<DriverEvaluation> drivers,
        List<ParameterEvaluation> parameters) {
      this(
          effectiveSettings,
          gates,
          gatePassed,
          predicatePassed,
          probability,
          random,
          selected,
          sampleCoordinates,
          matchedInterval,
          candidateType,
          drivers,
          parameters,
          Map.of(),
          List.of(),
          null);
    }

    public Details(
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
      this(
          effectiveSettings,
          gates,
          gatePassed,
          predicatePassed,
          probability,
          random,
          selected,
          sampleCoordinates,
          matchedInterval,
          candidateType,
          drivers,
          List.of());
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
    public Step {
      settingsSources = Map.copyOf(settingsSources);
    }

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
