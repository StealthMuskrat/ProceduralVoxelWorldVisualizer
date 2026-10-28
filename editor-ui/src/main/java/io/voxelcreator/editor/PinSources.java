package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Presentation of canonical binding identities; no field evaluation or copied pin values. */
final class PinSources {
  static String input(Noise noise, ProjectData project, Binding binding) {
    if (binding == null) return "Missing binding";
    String source = Objects.requireNonNullElse(binding.sourceKind, "Missing source");
    String name =
        switch (source) {
          case "Shared Parameter" ->
              noise.sharedParameters.stream()
                  .filter(p -> Objects.equals(p.uuid, binding.parameterUuid))
                  .map(p -> p.id)
                  .findFirst()
                  .orElse("Missing parameter");
          case "Earlier Result", "Earlier Field Prefix" -> {
            String found = null;
            for (var items : List.of(noise.processing, noise.coordinateTransforms))
              for (var item : items)
                if (Objects.equals(item.uuid, binding.itemUuid)) found = item.id;
            yield found == null ? "Missing earlier item" : found;
          }
          case "Ambient Value" ->
              Objects.requireNonNullElse(binding.ambientKey, "Missing ambient value");
          case "Noise Generator Field", "Noise Generator Reference" -> {
            Noise found = Objects.equals(noise.uuid, binding.noiseGeneratorUuid) ? noise : null;
            if (found == null && binding.noiseGeneratorUuid != null)
              for (var resources : List.of(project.noise2D, project.noise3D))
                for (var resource : resources)
                  if (binding.noiseGeneratorUuid.equals(resource.uuid)) found = resource;
            yield found == null
                ? "Missing noise generator"
                : found.id + " (" + found.dimension + "D)";
          }
          case "Constant" -> binding.value == null ? "Missing value" : binding.value.toString();
          default -> "";
        };
    String label =
        source
            + (name.isEmpty() ? "" : ": " + name)
            + " / "
            + Objects.requireNonNullElse(binding.type, "Missing type");
    if (label.length() <= 240) return label;
    int end =
        Character.isHighSurrogate(label.charAt(238)) && Character.isLowSurrogate(label.charAt(239))
            ? 238
            : 239;
    return label.substring(0, end) + "…";
  }

  static String control(NoiseDraftIndex.Row row, Item item) {
    if (row.section() == NoiseDraftIndex.Section.PARAMETERS) return "value";
    if (item == null) return null;
    if (item.kind.equals("CURVE")) return "curve";
    if (item.kind.equals("CONSTANT")) return "parameters/value";
    if (item.inputs.containsKey("field")) return "inputs/field";
    if (item.inputs.containsKey("a")) return "inputs/a";
    return item.inputs.isEmpty() ? null : "inputs/" + item.inputs.keySet().iterator().next();
  }
}
