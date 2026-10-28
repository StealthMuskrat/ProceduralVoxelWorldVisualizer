package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/**
 * One draft's exact pin objects own selection; transient picker keys never encode array indices.
 */
final class PinBrowser {
  private Noise owner;
  private long version = Long.MIN_VALUE, nextToken;
  private Pin selected;
  private String group = "all";
  private List<Pin> snapshot = List.of();
  private final Map<Pin, String> tokens = new IdentityHashMap<>();
  private Map<String, Pin> visible = Map.of();
  private List<DraftChoicePicker.Choice> groups = List.of(), rows = List.of();
  private Map<UUID, String> types = Map.of();

  boolean synchronize(Noise noise, long currentVersion) {
    boolean replaced = owner != noise;
    if (!replaced && version == currentVersion) return false;
    int previous = replaced ? 0 : snapshot.indexOf(selected);
    if (replaced) {
      owner = noise;
      selected = null;
      group = "all";
      tokens.clear();
    }
    snapshot = List.copyOf(noise.pinnedControls);
    var live = Collections.newSetFromMap(new IdentityHashMap<Pin, Boolean>());
    live.addAll(snapshot);
    tokens.keySet().removeIf(pin -> !live.contains(pin));
    for (var pin : snapshot) tokens.computeIfAbsent(pin, ignored -> "pin:" + nextToken++);
    boolean changed = !live.contains(selected);
    if (changed)
      selected =
          snapshot.isEmpty() ? null : snapshot.get(Math.clamp(previous, 0, snapshot.size() - 1));
    var typeMap = new HashMap<UUID, String>();
    noise.sharedParameters.forEach(p -> typeMap.put(p.uuid, p.type));
    for (var items : List.of(noise.processing, noise.coordinateTransforms))
      items.forEach(
          item -> typeMap.put(item.uuid, item.kind + (item.enabled ? "" : " (disabled)")));
    types = Map.copyOf(typeMap);
    var groupRows = new LinkedHashMap<String, DraftChoicePicker.Choice>();
    groupRows.put("all", new DraftChoicePicker.Choice("all", "All groups", "Authored order"));
    for (var pin : snapshot) {
      String key = groupKey(pin);
      groupRows.putIfAbsent(
          key,
          new DraftChoicePicker.Choice(
              key, key.equals("empty") ? "Ungrouped" : pin.group, "Pin group"));
    }
    groups = List.copyOf(groupRows.values());
    if (!groupRows.containsKey(group)) group = "all";
    refreshRows();
    version = currentVersion;
    return replaced || changed;
  }

  private static String groupKey(Pin pin) {
    return pin.group == null || pin.group.isBlank() ? "empty" : "group:" + pin.group;
  }

  private void refreshRows() {
    var byKey = new LinkedHashMap<String, Pin>();
    var choices = new ArrayList<DraftChoicePicker.Choice>();
    for (var pin : snapshot) {
      if (!group.equals("all") && !group.equals(groupKey(pin))) continue;
      String key = tokens.get(pin);
      byKey.put(key, pin);
      choices.add(
          new DraftChoicePicker.Choice(
              key,
              Objects.requireNonNullElse(pin.label, ""),
              (groupKey(pin).equals("empty") ? "Ungrouped" : pin.group)
                  + " / "
                  + (pin.targetUuid == null
                      ? "Missing control"
                      : types.getOrDefault(pin.targetUuid, "Missing control"))));
    }
    visible = Collections.unmodifiableMap(byKey);
    rows = List.copyOf(choices);
  }

  List<DraftChoicePicker.Choice> groups() {
    return groups;
  }

  List<DraftChoicePicker.Choice> rows() {
    return rows;
  }

  String group() {
    return group;
  }

  Pin selected() {
    return selected;
  }

  String token() {
    return tokens.get(selected);
  }

  String visibleToken() {
    return visible.containsKey(token()) ? token() : null;
  }

  boolean showGroup(String key) {
    if (groups.stream().noneMatch(row -> row.key().equals(key))) return false;
    if (!group.equals(key)) {
      group = key;
      refreshRows();
    }
    return true;
  }

  boolean choose(String token) {
    var pin = visible.get(token);
    if (pin == null || owner == null || !owner.pinnedControls.contains(pin)) return false;
    selected = pin;
    return true;
  }

  /** A control UUID can reveal a pin only when it identifies exactly one current pin. */
  boolean selectTarget(UUID target) {
    if (target == null) {
      showGroup("all");
      return false;
    }
    var matches = snapshot.stream().filter(pin -> Objects.equals(pin.targetUuid, target)).toList();
    if (matches.size() != 1) {
      showGroup("all");
      return false;
    }
    selected = matches.getFirst();
    showGroup(groupKey(selected));
    return true;
  }

  boolean move(int direction) {
    if (direction != -1 && direction != 1) throw new IllegalArgumentException("Pin move direction");
    int index = owner == null ? -1 : owner.pinnedControls.indexOf(selected);
    if (index < 0 || index + direction < 0 || index + direction >= owner.pinnedControls.size())
      return false;
    Collections.swap(owner.pinnedControls, index, index + direction);
    version = Long.MIN_VALUE;
    return true;
  }

  boolean remove() {
    int index = owner == null ? -1 : owner.pinnedControls.indexOf(selected);
    if (index < 0) return false;
    owner.pinnedControls.remove(index);
    selected =
        owner.pinnedControls.isEmpty()
            ? null
            : owner.pinnedControls.get(Math.min(index, owner.pinnedControls.size() - 1));
    version = Long.MIN_VALUE;
    return true;
  }

  static String navigation(EditorEvents.Target target) {
    if (target == null) return null;
    String key = Objects.requireNonNullElse(target.parameter(), "");
    if (Set.of("pin", "pin/label", "pin/group").contains(key))
      return key.equals("pin/group") ? "pin/group" : "pin/label";
    if (target.control() == null && key.equals("pinnedControls")) return "pins";
    String[] path = Objects.requireNonNullElse(target.pointer(), "").split("/", -1);
    if (target.control() == null
        && path.length >= 4
        && Set.of("noise2D", "noise3D", "noise").contains(path[1])
        && path[3].equals("pinnedControls")
        && Set.of("", "label", "group", "targetUuid").contains(key)) return "pins";
    return null;
  }
}
