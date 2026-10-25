package io.voxelcreator.model;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/**
 * Project-session clipboard. Copies snapshot definitions; cuts move the live identities at paste.
 */
public final class ResourceClipboard {
  private final UUID project;
  private final List<UUID> selected;
  private final ProjectData snapshot;
  private final String root;
  private final boolean cut;

  public ResourceClipboard(ProjectData source, Collection<UUID> selected, boolean cut) {
    this.project = source.project.uuid;
    if (selected.isEmpty()) throw new IllegalArgumentException("Nothing selected");
    var entries =
        selected.stream()
            .map(id -> Objects.requireNonNull(ResourceGraph.find(source, id)))
            .toList();
    String scope = entries.getFirst().scope();
    if (entries.stream().anyMatch(e -> !scope.equals(e.scope())))
      throw new IllegalArgumentException("Clipboard accepts only siblings");
    this.root = entries.getFirst().root();
    var selectedIds = Set.copyOf(selected);
    this.selected =
        ResourceGraph.children(source, root, entries.getFirst().parent()).stream()
            .map(ResourceGraph.Entry::uuid)
            .filter(selectedIds::contains)
            .toList();
    this.cut = cut;
    snapshot = ResourceGraph.selection(source, selected, false);
  }

  public boolean cut() {
    return cut;
  }

  public String root() {
    return root;
  }

  public void paste(ProjectData destination, String root, UUID parent) {
    if (!destination.project.uuid.equals(project))
      throw new IllegalArgumentException("Clipboard belongs to another project session");
    if (!this.root.equals(root))
      throw new IllegalArgumentException("Paste requires a compatible root");
    ResourceGraph.validateParent(destination, root, parent);
    if (cut) {
      ResourceGraph.move(destination, selected, root, parent);
      return;
    }
    var copy = ResourceTransactions.duplicateMapped(snapshot);
    ProjectData payload = copy.project();
    var roots = new LinkedHashSet<UUID>();
    selected.forEach(id -> roots.add(copy.identities().get(id)));
    for (var n : payload.noise2D) if (roots.contains(n.uuid)) n.parentFolderUuid = parent;
    for (var n : payload.noise3D) if (roots.contains(n.uuid)) n.parentFolderUuid = parent;
    for (var f : payload.folders) if (roots.contains(f.uuid)) f.parentFolderUuid = parent;
    for (var layer : payload.worldGenerator.layers)
      if (roots.contains(layer.uuid)) layer.parentFolderUuid = parent;
    if (root.equals("operation")) {
      Layer target =
          destination.worldGenerator.layers.stream()
              .filter(l -> l.uuid.equals(parent))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Choose a destination layer"));
      for (var l : payload.worldGenerator.layers)
        for (var op : l.operations) {
          op.id =
              ResourceTransactions.unique(
                  op.id, target.operations.stream().map(o -> o.id).toList());
          target.operations.add(op);
        }
      return;
    }
    ResourceGraph.append(destination, payload);
    if (ResourceGraph.ORGANIZATIONAL_ROOTS.contains(root))
      ResourceGraph.appendOrder(
          destination, root, parent, this.selected.stream().map(copy.identities()::get).toList());
  }
}
