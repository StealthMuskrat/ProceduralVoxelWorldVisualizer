package io.voxelcreator.editor;

import io.voxelcreator.core.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Immutable local authoring metadata. UUID/type/order eligibility comes from the current draft. */
public final class NoiseDraftIndex {
  public enum Section {
    TRANSFORMS,
    PROCESSING,
    PARAMETERS
  }

  public record Row(
      UUID uuid,
      String id,
      String kind,
      String type,
      boolean enabled,
      int position,
      Section section) {
    public String label() {
      return "["
          + position
          + "] "
          + id
          + " / "
          + kind
          + " / "
          + type
          + (enabled ? "" : " (disabled)");
    }
  }

  public record Use(UUID owner, String label, String path) {}

  private final List<Row> transforms, processing, parameters;
  private final Map<UUID, Row> byId;
  private final Map<UUID, List<Use>> users;
  private final Map<String, List<DraftChoicePicker.Choice>> choices =
      new LinkedHashMap<>(16, .75f, true);

  public NoiseDraftIndex(Noise noise) {
    transforms = rows(noise.coordinateTransforms, Section.TRANSFORMS);
    processing = rows(noise.processing, Section.PROCESSING);
    var params = new ArrayList<Row>();
    for (int i = 0; i < noise.sharedParameters.size(); i++) {
      var p = noise.sharedParameters.get(i);
      params.add(new Row(p.uuid, p.id, "Shared Parameter", p.type, true, i, Section.PARAMETERS));
    }
    parameters = List.copyOf(params);
    var ids = new HashMap<UUID, Row>();
    for (var list : List.of(transforms, processing, parameters))
      list.forEach(row -> ids.put(row.uuid(), row));
    byId = Map.copyOf(ids);
    var uses = new HashMap<UUID, List<Use>>();
    for (var list : List.of(noise.coordinateTransforms, noise.processing))
      for (var item : list)
        for (var input : item.inputs.entrySet())
          add(uses, input.getValue(), new Use(item.uuid, item.id, "inputs/" + input.getKey()));
    add(uses, noise.outputBinding, new Use(null, "Output", "outputBinding"));
    for (var pin : noise.pinnedControls)
      if (pin.targetUuid != null)
        uses.computeIfAbsent(pin.targetUuid, ignored -> new ArrayList<>())
            .add(new Use(pin.targetUuid, "Pin: " + pin.label, "pin"));
    var frozen = new HashMap<UUID, List<Use>>();
    uses.forEach((id, values) -> frozen.put(id, List.copyOf(values)));
    users = Map.copyOf(frozen);
  }

  private static List<Row> rows(List<Item> items, Section section) {
    var rows = new ArrayList<Row>();
    for (int i = 0; i < items.size(); i++) {
      var item = items.get(i);
      rows.add(new Row(item.uuid, item.id, item.kind, item.resultType, item.enabled, i, section));
    }
    return List.copyOf(rows);
  }

  private static void add(Map<UUID, List<Use>> users, Binding binding, Use site) {
    if (binding == null) return;
    UUID reference =
        switch (binding.sourceKind) {
          case "Shared Parameter" -> binding.parameterUuid;
          case "Earlier Result", "Earlier Field Prefix" -> binding.itemUuid;
          default -> null;
        };
    if (reference != null) users.computeIfAbsent(reference, ignored -> new ArrayList<>()).add(site);
  }

  public Row get(UUID uuid) {
    return uuid == null ? null : byId.get(uuid);
  }

  public List<Use> users(UUID uuid) {
    return uuid == null ? List.of() : users.getOrDefault(uuid, List.of());
  }

  public List<Row> rows(Section section) {
    return switch (section) {
      case TRANSFORMS -> transforms;
      case PROCESSING -> processing;
      case PARAMETERS -> parameters;
    };
  }

  public List<DraftChoicePicker.Choice> choices(String mode, UUID owner, String type) {
    String key = mode + ":" + owner + ":" + type;
    var cached = choices.get(key);
    if (cached != null) return cached;
    var source = get(owner);
    var result = new ArrayList<DraftChoicePicker.Choice>();
    List<Row> candidates =
        mode.equals("parameter")
            ? parameters
            : source != null && source.section() == Section.TRANSFORMS ? transforms : processing;
    for (var row : candidates) {
      boolean earlier =
          mode.equals("parameter")
              || mode.equals("output")
              || source != null && row.position() < source.position();
      if (row.enabled() && earlier && row.type().equals(type))
        result.add(
            new DraftChoicePicker.Choice(
                row.uuid().toString(),
                row.id(),
                row.kind() + " / " + row.type() + " / [" + row.position() + "]"));
    }
    var owned = List.copyOf(result);
    if (choices.size() >= 128) choices.remove(choices.keySet().iterator().next());
    choices.put(key, owned);
    return owned;
  }

  public static List<String> sources(String type, boolean coordinateStage) {
    var result = new ArrayList<String>(List.of("Constant", "Shared Parameter", "Earlier Result"));
    if (type.equals("Scalar")) {
      result.add("Ambient Value");
      if (!coordinateStage) result.add("Source Result");
      result.add("Noise Generator Reference");
    }
    return List.copyOf(result);
  }

  public static final class Cache {
    private Noise owner;
    private long revision;
    private NoiseDraftIndex index;
    private int builds;

    public NoiseDraftIndex get(Noise draft, long version) {
      if (owner != draft || revision != version || index == null) {
        owner = draft;
        revision = version;
        index = new NoiseDraftIndex(draft);
        builds++;
      }
      return index;
    }

    public int builds() {
      return builds;
    }
  }
}
