package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.util.*;

/** Independent, transient selection over execution order, never the Resources/Properties owner. */
final class WorldEditorSelection {
  record Row(UUID uuid, UUID layer, String id, String source, boolean enabled, int stage) {
    boolean layerRow() {
      return uuid.equals(layer);
    }

    String scope() {
      return layerRow() ? "layers" : layer.toString();
    }
  }

  record Ticket(Object document, long selection, UUID resource) {}

  private Object document;
  private ProjectData source;
  private long revision = -1, selectionVersion;
  private List<Row> rows = List.of();
  private String filterQuery;
  private List<Row> projection = List.of();
  private Map<UUID, Row> index = Map.of();
  private final Set<UUID> selected = new LinkedHashSet<>();
  private UUID active, anchor;
  private String scope;

  boolean synchronize(EditorState state) {
    Object token = state.document.sessionToken();
    boolean replaced = document != token;
    if (!replaced && source == state.project() && revision == state.revision) return false;
    if (replaced) clear();
    document = token;
    source = state.project();
    revision = state.revision;
    var names = new HashMap<UUID, String>();
    source.noise2D.forEach(n -> names.put(n.uuid, n.id));
    source.noise3D.forEach(n -> names.put(n.uuid, n.id));
    var next = new ArrayList<Row>();
    var lookup = new LinkedHashMap<UUID, Row>();
    int layerStage = 0;
    for (var layer : source.worldGenerator.layers) {
      next.add(new Row(layer.uuid, layer.uuid, layer.id, "", layer.enabled, ++layerStage));
      int operationStage = 0;
      for (var operation : layer.operations)
        next.add(
            new Row(
                operation.uuid,
                layer.uuid,
                operation.id,
                names.getOrDefault(operation.noiseGeneratorUuid, "Missing noise resource"),
                layer.enabled && operation.enabled,
                ++operationStage));
    }
    next.forEach(row -> lookup.put(row.uuid(), row));
    rows = List.copyOf(next);
    filterQuery = null;
    index = Collections.unmodifiableMap(lookup);
    var before = List.copyOf(selected);
    selected.removeIf(id -> !index.containsKey(id) || !index.get(id).scope().equals(scope));
    if (!selected.contains(active)) active = selected.stream().reduce((a, b) -> b).orElse(null);
    if (anchor != null && (!index.containsKey(anchor) || !index.get(anchor).scope().equals(scope)))
      anchor = null;
    if (!before.equals(List.copyOf(selected))) selectionVersion++;
    return replaced;
  }

  List<Row> rows(String filter) {
    String query = Objects.requireNonNullElse(filter, "").strip().toLowerCase(Locale.ROOT);
    if (query.equals(filterQuery)) return projection;
    filterQuery = query;
    if (query.isEmpty()) return projection = rows;
    var matchingLayers = new HashSet<UUID>();
    for (var row : rows)
      if (row.layerRow() && matches(row, query)) matchingLayers.add(row.uuid());
    var included = new HashSet<UUID>(matchingLayers);
    for (var row : rows)
      if (matches(row, query) || matchingLayers.contains(row.layer())) {
        included.add(row.uuid());
        included.add(row.layer());
      }
    return projection = rows.stream().filter(row -> included.contains(row.uuid())).toList();
  }

  private static boolean matches(Row row, String query) {
    return (row.id() + " " + row.source() + " " + row.uuid()).toLowerCase(Locale.ROOT).contains(query);
  }

  void select(UUID id, boolean toggle, boolean range, List<Row> displayed) {
    Row row = index.get(id);
    if (row == null || displayed.stream().noneMatch(r -> r.uuid().equals(id))) return;
    var before = List.copyOf(selected);
    UUID previous = active;
    boolean sameScope = row.scope().equals(scope);
    if (!sameScope || (!toggle && !range)) selected.clear();
    scope = row.scope();
    var siblings = displayed.stream().filter(r -> r.scope().equals(scope)).map(Row::uuid).toList();
    int first = sameScope && range ? siblings.indexOf(anchor) : -1;
    int last = siblings.indexOf(id);
    if (first >= 0 && last >= 0) {
      if (!toggle) selected.clear();
      selected.addAll(siblings.subList(Math.min(first, last), Math.max(first, last) + 1));
    } else {
      if (!sameScope || !toggle) selected.clear();
      if (toggle && selected.contains(id)) selected.remove(id);
      else selected.add(id);
      anchor = id;
    }
    active = selected.contains(id) ? id : selected.stream().reduce((a, b) -> b).orElse(null);
    if (!before.equals(List.copyOf(selected)) || !Objects.equals(previous, active)) selectionVersion++;
  }

  Set<UUID> selected() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(selected));
  }

  Row single() {
    return selected.size() == 1 ? index.get(active) : null;
  }

  Ticket ticket() {
    Row row = single();
    return row == null ? null : new Ticket(document, selectionVersion, row.uuid());
  }

  boolean current(EditorState state, Ticket ticket) {
    synchronize(state);
    return ticket != null
        && document == ticket.document()
        && selectionVersion == ticket.selection()
        && single() != null
        && single().uuid().equals(ticket.resource());
  }

  long version() {
    return selectionVersion;
  }

  void clear() {
    selected.clear();
    active = anchor = null;
    scope = null;
    selectionVersion++;
  }
}
