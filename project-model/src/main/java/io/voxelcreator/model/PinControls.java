package io.voxelcreator.model;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Canonical pin identities resolve live authored controls; no values are stored in a pin. */
public final class PinControls {
  public static final String PATH_PATTERN = "^(?:|value|(?:inputs|parameters)/[\\s\\S]+)$";

  private PinControls() {}

  public static Item item(Noise noise, UUID target) {
    for (var items : List.of(noise.coordinateTransforms, noise.processing))
      for (var item : items) if (Objects.equals(item.uuid, target)) return item;
    return null;
  }

  public static List<String> paths(Item item) {
    var paths = new ArrayList<String>();
    item.parameters.keySet().forEach(key -> paths.add("parameters/" + key));
    if ("CONSTANT".equals(item.kind) && !item.parameters.containsKey("value"))
      paths.add("parameters/value");
    item.inputs.keySet().stream()
        .filter(key -> !item.parameters.containsKey(key))
        .forEach(key -> paths.add("inputs/" + key));
    return List.copyOf(paths);
  }

  /** A parameter control follows its binding when Bind is enabled, then its literal on reset. */
  public static String resolvedPath(Item item, String control) {
    if (item == null || control == null || control.isEmpty()) return null;
    if (control.startsWith("parameters/")) {
      String key = control.substring("parameters/".length());
      if (!key.isEmpty() && item.inputs.containsKey(key)) return "inputs/" + key;
      if (!key.isEmpty() && item.parameters.containsKey(key)) return control;
      if (key.equals("value") && "CONSTANT".equals(item.kind)) return control;
    } else if (control.startsWith("inputs/")) {
      String key = control.substring("inputs/".length());
      if (!key.isEmpty() && item.inputs.containsKey(key)) return control;
    }
    return null;
  }

  public static String problem(Noise noise, Pin pin) {
    boolean shared =
        pin != null
            && noise.sharedParameters.stream()
                .anyMatch(parameter -> Objects.equals(parameter.uuid, pin.targetUuid));
    return problem(pin, shared, pin == null ? null : item(noise, pin.targetUuid));
  }

  static String problem(Pin pin, boolean sharedParameter, Item item) {
    if (pin == null || pin.targetUuid == null) return "Choose an existing pin target";
    if (pin.control == null) return "Pin control must be a string; use empty for a whole-item pin";
    if (sharedParameter)
      return pin.control.isEmpty() || pin.control.equals("value")
          ? null
          : "Shared parameter pins target value";
    if (item == null) return "Unknown pinned control target";
    if (pin.control.isEmpty()) return null;
    return resolvedPath(item, pin.control) == null
        ? "Pinned control is missing; unpin it or choose an existing input or parameter"
        : null;
  }

  public static boolean add(Noise noise, UUID target, String control, String label) {
    var pin = new Pin();
    pin.targetUuid = target;
    pin.control = Objects.requireNonNull(control);
    pin.label = Objects.requireNonNull(label);
    String problem = problem(noise, pin);
    if (problem != null) throw new IllegalArgumentException(problem);
    if (noise.pinnedControls.stream()
        .anyMatch(
            existing ->
                Objects.equals(existing.targetUuid, target)
                    && Objects.equals(existing.control, control))) return false;
    noise.pinnedControls.add(pin);
    return true;
  }
}
