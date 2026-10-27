package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** UUID navigation never reinterprets a historical array index against a newer document. */
public final class DiagnosticNavigation {
  private DiagnosticNavigation() {}

  /** Preserve the named binding from structural diagnostics without replaying array indices. */
  static String parameter(Diagnostic diagnostic) {
    String pointer = Objects.requireNonNullElse(diagnostic.jsonPointer(), "");
    String[] tokens = pointer.split("/", -1);
    for (int i = 1; i + 1 < tokens.length; i++) {
      if (diagnostic.itemUuid() != null
          && i >= 3
          && Set.of("processing", "coordinateTransforms", "control").contains(tokens[i - 2])
          && tokens[i].equals("inputs"))
        return "inputs/" + tokens[i + 1].replace("~1", "/").replace("~0", "~");
    }
    return diagnostic.parameterKey();
  }

  static boolean inputTarget(EditorEvents.Target target, UUID item, String name) {
    return target != null
        && Objects.equals(target.control(), item)
        && Objects.equals(target.parameter(), "inputs/" + name);
  }

  /** Resolve only the exact named binding's serialized leaf, never a historical array index. */
  static String bindingField(EditorEvents.Target target, String name, Binding binding) {
    String pointer = Objects.requireNonNullElse(target.pointer(), "");
    String[] tokens = pointer.split("/", -1);
    String field = null;
    for (int i = 1; i + 2 < tokens.length; i++)
      if (i >= 3
          && Set.of("processing", "coordinateTransforms", "control").contains(tokens[i - 2])
          && tokens[i].equals("inputs")
          && tokens[i + 1].replace("~1", "/").replace("~0", "~").equals(name)) {
        String leaf = tokens[i + 2];
        if (Set.of(
                "type",
                "sourceKind",
                "value",
                "parameterUuid",
                "itemUuid",
                "noiseGeneratorUuid",
                "ambientKey",
                "projection",
                "slice")
            .contains(leaf)) {
          field = leaf;
          if (leaf.equals("value") && i + 3 < tokens.length && tokens[i + 3].matches("[012]"))
            field += "/" + tokens[i + 3];
        }
        break;
      }
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
    if (target.settings()) return true;
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
