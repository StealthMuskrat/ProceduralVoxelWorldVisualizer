package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** UUID navigation never reinterprets a historical array index against a newer document. */
public final class DiagnosticNavigation {
  private DiagnosticNavigation() {}

  private static final Set<String> ITEMS = Set.of("processing", "coordinateTransforms", "control");
  private static final Set<String> BINDING_FIELDS =
      Set.of(
          "type",
          "sourceKind",
          "value",
          "parameterUuid",
          "itemUuid",
          "noiseGeneratorUuid",
          "ambientKey",
          "projection",
          "slice");

  /** Preserve the named binding from structural diagnostics without replaying array indices. */
  static String parameter(Diagnostic diagnostic) {
    String parameter = diagnostic.parameterKey();
    if (canonical(parameter)) return parameter;
    String[] tokens = DiagnosticPaths.tokens(diagnostic.jsonPointer());
    if (inputPath(tokens, diagnostic.sourceUuid(), diagnostic.itemUuid())) {
      String name = decode(tokens[6]);
      // Older structural rows carry either the port name or its serialized binding leaf.
      boolean legacyLeaf =
          tokens.length >= 8
              && (BINDING_FIELDS.contains(tokens[7]) || tokens[7].equals("constant"))
              && Objects.equals(parameter, tokens[7]);
      if (parameter == null || parameter.equals(name) || legacyLeaf) return "inputs/" + name;
    }
    return parameter;
  }

  /** Canonical logical control key; indices locate no current object and are never replayed. */
  static String controlParameter(EditorEvents.Target target) {
    String parameter = target.parameter();
    if (canonical(parameter)) return parameter;
    String[] tokens = DiagnosticPaths.tokens(target.pointer());
    // Only a noise resource's immediate collections can contribute a control leaf.
    if (tokens.length >= 6 && DiagnosticPaths.noisePath(tokens, target.resource())) {
      if (target.control() == null
          && tokens[3].equals("algorithm")
          && tokens[4].equals("parameters")
          && tokens.length == 6) {
        String key = decode(tokens[5]);
        if (parameter == null || parameter.equals(key)) return "algorithm/parameters/" + key;
      }
      if (DiagnosticPaths.controlPath(tokens, target.resource(), target.control())
          && ITEMS.contains(tokens[3])
          && tokens.length >= 7
          && tokens[5].equals("parameters")
          && valuePath(tokens, 7)) {
        String key = decode(tokens[6]);
        if (parameter == null || parameter.equals(key))
          return "parameters/" + key + component(tokens, 7);
      }
      if (DiagnosticPaths.controlPath(tokens, target.resource(), target.control())
          && tokens[3].equals("sharedParameters")
          && tokens[5].equals("value")
          && valuePath(tokens, 6)
          && (parameter == null || parameter.equals("value")))
        return "parameters/value" + component(tokens, 6);
    }
    return target.control() != null && Objects.equals(parameter, "value")
        ? "parameters/value"
        : parameter;
  }

  private static boolean canonical(String parameter) {
    return parameter != null
        && (parameter.startsWith("inputs/")
            || parameter.startsWith("parameters/")
            || parameter.startsWith("algorithm/parameters/"));
  }

  private static boolean inputPath(String[] tokens, UUID resource, UUID control) {
    return tokens.length >= 7
        && DiagnosticPaths.controlPath(tokens, resource, control)
        && ITEMS.contains(tokens[3])
        && tokens[5].equals("inputs");
  }

  private static boolean valuePath(String[] tokens, int end) {
    return tokens.length == end || !component(tokens, end).isEmpty();
  }

  private static String component(String[] tokens, int index) {
    return tokens.length == index + 1 && Set.of("0", "1", "2").contains(tokens[index])
        ? "/" + tokens[index]
        : "";
  }

  private static String decode(String token) {
    return token.replace("~1", "/").replace("~0", "~");
  }

  static boolean inputTarget(EditorEvents.Target target, UUID item, String name) {
    return target != null
        && Objects.equals(target.control(), item)
        && Objects.equals(target.parameter(), "inputs/" + name);
  }

  /** Resolve only the exact named binding's serialized leaf, never a historical array index. */
  static String bindingField(EditorEvents.Target target, String name, Binding binding) {
    String[] tokens = DiagnosticPaths.tokens(target.pointer());
    String field = null;
    if (Objects.equals(target.parameter(), "inputs/" + name)
        && inputPath(tokens, target.resource(), target.control())
        && tokens.length >= 8
        && decode(tokens[6]).equals(name)
        && BINDING_FIELDS.contains(tokens[7])
        && (tokens.length == 8 || tokens[7].equals("value") && valuePath(tokens, 8)))
      field = tokens[7] + component(tokens, 8);
    if (field == null)
      field =
          switch (binding.sourceKind) {
            case "Constant" -> "value";
            case "Shared Parameter" -> "parameterUuid";
            case "Earlier Result", "Earlier Field Prefix" -> "itemUuid";
            case "Noise Generator Reference", "Noise Generator Field" -> "noiseGeneratorUuid";
            case "Ambient Value" -> "ambientKey";
            default -> "sourceKind";
          };
    if (field.equals("value") && !Set.of("Scalar", "Boolean").contains(binding.type))
      field = "value/0";
    return field;
  }

  public static boolean available(
      ProjectData project, UUID selected, Object draft, EditorEvents.Target target) {
    if (target == null || !Objects.equals(project.project.uuid, target.project())) return false;
    if (target.settings()) return ProjectSettingsSession.field(target) != null;
    if (target.resource() == null) return false;
    var entry = ResourceGraph.find(project, target.resource());
    if (entry == null) return false;
    if (target.control() == null) return true;
    Object object =
        Objects.equals(selected, target.resource()) && draft != null ? draft : entry.resource();
    if (!(object instanceof Noise n)) return false;
    UUID control = target.control();
    if (n.sharedParameters.stream().anyMatch(p -> control.equals(p.uuid))) return true;
    for (var items : List.of(n.processing, n.coordinateTransforms))
      for (var item : items) {
        if (control.equals(item.uuid)) return true;
        var curve = item.parameters.get("curve");
        if (curve != null)
          for (var point : curve.path("points"))
            if (control.toString().equals(point.path("uuid").asText())) return true;
      }
    return false;
  }
}
