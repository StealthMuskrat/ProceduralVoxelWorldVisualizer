package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Logical world-setting routes; collection indices never acquire current row identity. */
final class WorldSettingNavigation {
  record Route(String property, String control, String explanation) {}

  private static final Set<String> DRIVER =
      Set.of(
          "enabled",
          "noiseGeneratorUuid",
          "mapping",
          "inputMin",
          "inputMax",
          "outputMin",
          "outputMax");
  private static final Set<String> GATE =
      Set.of(
          "kind",
          "noiseGeneratorUuid",
          "comparison",
          "threshold",
          "max",
          "includeMin",
          "includeMax",
          "probability",
          "seedOffset");

  static Route resolve(Object draft, Layer containing, EditorEvents.Target target) {
    if (!(draft instanceof Layer || draft instanceof Operation)
        || target == null
        || target.settings()
        || target.control() != null
        || target.resource() == null
        || !target.resource().equals(((Identity) draft).uuid)) return null;
    String parameter = Objects.requireNonNullElse(target.parameter(), "");
    String logical = null;
    for (String prefix : List.of("settings/", "defaults/", "overrides/"))
      if (parameter.startsWith(prefix)) logical = parameter.substring(prefix.length());
    boolean qualified = logical != null;
    if (!qualified && SettingsResolver.PROPERTIES.contains(parameter)) logical = parameter;
    String[] pointer = DiagnosticPaths.tokens(target.pointer());
    int root = settingsRoot(pointer, draft, containing);
    if (!qualified
        && root >= 0
        && (logical == null && parameter.isEmpty()
            || parameter.equals(pointer[pointer.length - 1])
            || logical != null && logical.equals(pointer[root]))) {
      logical = String.join("/", Arrays.copyOfRange(pointer, root, pointer.length));
    }
    if (logical == null) {
      if (parameter.equals("enabled")) return new Route(null, "enabled", "");
      if (draft instanceof Operation
          && Set.of("interpretation", "noiseGeneratorUuid").contains(parameter))
        return new Route(null, parameter, "");
      return null;
    }
    String[] parts = logical.split("/", -1);
    String property = parts[0];
    if (!SettingsResolver.PROPERTIES.contains(property)) return null;
    if (draft instanceof Operation op && !op.overrides.containsKey(property))
      return new Route(
          property,
          "override/" + property,
          "This value is inherited. Enable its override explicitly to edit this placement.");
    Settings settings;
    try {
      settings =
          draft instanceof Layer layer
              ? layer.defaults
              : SettingsResolver.resolve(Objects.requireNonNull(containing), (Operation) draft);
    } catch (RuntimeException invalid) {
      // Malformed override values still have an explicit reset/override control.
      return new Route(
          property,
          "override/" + property,
          "The stored override cannot be edited. Disable its override to restore inheritance.");
    }
    String leaf = parts.length == 2 ? parts[1] : "";
    String key = "settings/" + property;
    if (property.equals("voxelTypeUuid")
        && settings != null
        && settings.typeSource.equals("INTERVALS"))
      return new Route(
          property,
          draft instanceof Operation op && !op.overrides.containsKey("typeSource")
              ? "override/typeSource"
              : "settings/typeSource",
          "Voxel types are selected by intervals. Type Source controls this choice; inherited"
              + " settings need an explicit override.");
    if ((property.equals("probabilityDriver") || property.equals("strengthDriver"))
        && DRIVER.contains(leaf)) return new Route(property, key + "/" + leaf, "");
    if (property.equals("voxelStatePredicate") && Set.of("kind", "reference").contains(leaf)) {
      var predicate = settings.voxelStatePredicate;
      boolean reference =
          Set.of("TYPE_IS", "TYPE_IS_NOT", "IN_SET", "NOT_IN_SET").contains(predicate.kind);
      return new Route(
          property,
          key + "/" + (leaf.equals("reference") && !reference ? "kind" : leaf),
          leaf.equals("reference") && !reference
              ? "Choose a reference-based condition before selecting its resource."
              : "");
    }
    if (property.equals("fieldGates") && GATE.contains(leaf)) {
      String kind = settings.fieldGates.kind;
      boolean visible =
          switch (leaf) {
            case "kind" -> true;
            case "noiseGeneratorUuid", "threshold" -> Set.of("COMPARE", "INTERVAL").contains(kind);
            case "comparison" -> kind.equals("COMPARE");
            case "max", "includeMin", "includeMax" -> kind.equals("INTERVAL");
            default -> kind.equals("PROBABILITY");
          };
      return new Route(
          property,
          key + "/" + (visible ? leaf : "kind"),
          visible
              ? ""
              : "This gate kind does not expose the requested field. Choose its kind explicitly.");
    }
    return new Route(
        property,
        key,
        parts.length > 1
            ? "The collection or unsupported leaf is shown without selecting a historical row."
            : "");
  }

  private static int settingsRoot(String[] pointer, Object draft, Layer containing) {
    if (pointer.length < 6
        || !pointer[0].isEmpty()
        || !pointer[1].equals("worldGenerator")
        || !pointer[2].equals("layers")) return -1;
    if (draft instanceof Layer layer
        && DiagnosticPaths.identity(pointer[3], layer.uuid)
        && pointer[4].equals("defaults")) return 5;
    if (draft instanceof Operation operation
        && containing != null
        && pointer.length >= 8
        && DiagnosticPaths.identity(pointer[3], containing.uuid)
        && pointer[4].equals("operations")
        && DiagnosticPaths.identity(pointer[5], operation.uuid)
        && pointer[6].equals("overrides")) return 7;
    return -1;
  }
}
