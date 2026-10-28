package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Owned browser command inputs; UUID resolution always uses the transaction's candidate. */
final class ResourceBrowserEdits {
  private ResourceBrowserEdits() {}

  record Selection(List<UUID> ids, UUID active) {
    Selection {
      ids = List.copyOf(ids);
    }

    static Selection one(UUID id) {
      return new Selection(List.of(id), id);
    }
  }

  @FunctionalInterface
  interface Edit {
    Selection apply(ProjectData candidate);
  }

  static Edit rename(UUID id, String name) {
    return p -> {
      ResourceGraph.rename(p, id, name);
      return null;
    };
  }

  static Edit move(Collection<UUID> ids, String root, UUID parent) {
    var owned = List.copyOf(ids);
    return p -> {
      ResourceGraph.move(p, owned, root, parent);
      return null;
    };
  }

  static Edit before(Collection<UUID> ids, UUID target) {
    var owned = List.copyOf(ids);
    return p -> {
      ResourceGraph.reorderBefore(p, owned, target);
      return null;
    };
  }

  static Edit shift(Collection<UUID> ids, int direction, boolean execution) {
    var owned = Set.copyOf(ids);
    return p -> {
      if (execution) ResourceGraph.shiftExecution(p, owned, direction);
      else ResourceGraph.shift(p, owned, direction);
      return null;
    };
  }

  static Edit delete(UUID id, UUID replacement) {
    return p -> {
      ResourceGraph.delete(p, id, replacement);
      return null;
    };
  }

  static Edit paste(ResourceClipboard clipboard, String root, UUID parent) {
    Objects.requireNonNull(clipboard);
    return p -> {
      clipboard.paste(p, root, parent);
      return null;
    };
  }

  static Edit duplicate(Collection<UUID> ids) {
    var owned = List.copyOf(ids);
    return p -> {
      if (owned.isEmpty()) throw new IllegalArgumentException("Nothing selected");
      var source =
          Objects.requireNonNull(ResourceGraph.find(p, owned.getFirst()), "Missing source");
      var previous = new HashSet<UUID>();
      ResourceGraph.entries(p).forEach(e -> previous.add(e.uuid()));
      new ResourceClipboard(p, owned, false).paste(p, source.root(), source.parent());
      var added =
          ResourceGraph.children(p, source.root(), source.parent()).stream()
              .map(ResourceGraph.Entry::uuid)
              .filter(id -> !previous.contains(id))
              .toList();
      if (added.isEmpty())
        throw new IllegalStateException("Duplicate created no sibling resources");
      return new Selection(added, added.getLast());
    };
  }

  static Edit insert(String kind, Identity resource) {
    var tree = ProjectIO.JSON.valueToTree(resource);
    var type = resource.getClass();
    return p -> {
      Identity made = ProjectIO.JSON.convertValue(tree, type);
      switch (kind) {
        case "noise2D", "noise3D" -> {
          var noise = (Noise) made;
          ResourceGraph.validateParent(p, kind, noise.parentFolderUuid);
          (kind.equals("noise2D") ? p.noise2D : p.noise3D).add(noise);
        }
        case "layer" -> {
          var layer = (Layer) made;
          ResourceGraph.validateParent(p, "world", layer.parentFolderUuid);
          p.worldGenerator.layers.add(layer);
        }
        case "folder" -> {
          var folder = (Folder) made;
          ResourceGraph.validateParent(p, folder.rootKind, folder.parentFolderUuid);
          p.folders.add(folder);
        }
        case "voxel" -> p.voxelTypes.add((VoxelType) made);
        case "set" -> p.voxelSets.add((Membership) made);
        case "group" -> p.voxelGroups.add((Membership) made);
        default -> throw new IllegalArgumentException("Unsupported creation kind " + kind);
      }
      return Selection.one(made.uuid);
    };
  }

  static Edit placement(UUID layerId, UUID noiseId) {
    return p -> {
      var layer = (Layer) Objects.requireNonNull(EditorState.find(p, layerId), "Missing layer");
      var noise = (Noise) Objects.requireNonNull(EditorState.find(p, noiseId), "Missing noise");
      var operation = new Operation();
      operation.id =
          ResourceTransactions.unique(noise.id, layer.operations.stream().map(o -> o.id).toList());
      operation.noiseGeneratorUuid = noiseId;
      operation.interpretation = noise.dimension == 2 ? "HEIGHT_BELOW" : "DENSITY";
      layer.operations.add(operation);
      return null;
    };
  }
}
