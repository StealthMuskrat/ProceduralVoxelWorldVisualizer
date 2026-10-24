package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.function.*;

public final class ResourcePanel {
  private final EditorState state;
  private final PropertyPanel properties;
  private final ImString filter = new ImString(256), name = new ImString(256);
  private String createKind = "noise2D", createRoot = "noise2D";
  private UUID createParent, placementLayer;
  private boolean createPending = false;
  private ProjectData clipboard;
  private boolean cut = false;
  private String clipboardRoot = "";
  private UUID deleteId;
  private UUID replacement;

  public ResourcePanel(EditorState s, PropertyPanel properties) {
    state = s;
    this.properties = properties;
  }

  public void reset() {
    clipboard = null;
    cut = false;
    filter.set("");
  }

  public void draw() {
    ImGui.begin("Resources");
    ImGui.setWindowFontScale(state.user.appearance.windowScales.getOrDefault("Resources", 1f));
    ImGui.inputTextWithHint("##filter", "Filter resources...", filter);
    root("2D Noise", "noise2D");
    root("3D Noise", "noise3D");
    worldRoot();
    ImGui.separator();
    if (ImGui.button("Voxel Manager")) manager.set(true);
    ImGui.textDisabled("Right-click a root to create resources");
    if (ImGui.isKeyPressed(ImGuiKey.F2) && state.selected != null) {
      state.refreshDraft();
      state.status = "Edit ID in Properties, then Apply";
    }
    ImGui.end();
    createDialog();
    deleteDialog();
    manager();
  }

  private void root(String label, String kind) {
    boolean open = ImGui.treeNodeEx(label, ImGuiTreeNodeFlags.DefaultOpen);
    rootMenu(kind, null);
    dropTarget(kind, null);
    if (open) {
      folderContents(kind, null, new HashSet<>());
      ImGui.treePop();
    }
  }

  private void folderContents(String kind, UUID parent, Set<UUID> visited) {
    var folders =
        state.project().folders.stream()
            .filter(x -> x.rootKind.equals(kind) && Objects.equals(x.parentFolderUuid, parent))
            .toList();
    for (var folder : folders) {
      if (!visited.add(folder.uuid)) continue;
      boolean open =
          ImGui.treeNodeEx(
              folder.id + "###" + folder.uuid,
              ImGuiTreeNodeFlags.OpenOnArrow
                  | (state.multiSelection.contains(folder.uuid) ? ImGuiTreeNodeFlags.Selected : 0));
      if (ImGui.isItemClicked())
        state.select("folder", folder.uuid, kind + parent, ImGui.getIO().getKeyCtrl());
      rowMenu("folder", folder, kind, parent);
      drag(folder.uuid, kind);
      rootMenu(kind, folder.uuid);
      dropTarget(kind, folder.uuid);
      if (open) {
        folderContents(kind, folder.uuid, visited);
        ImGui.treePop();
      }
    }
    List<Noise> list = kind.equals("noise2D") ? state.project().noise2D : state.project().noise3D;
    for (var noise : list)
      if (Objects.equals(noise.parentFolderUuid, parent) && matches(noise.id))
        row(kind, noise, kind, parent);
  }

  private boolean matches(String value) {
    return filter.get().isBlank()
        || value.toLowerCase(Locale.ROOT).contains(filter.get().toLowerCase(Locale.ROOT));
  }

  private void row(String kind, Identity resource, String root, UUID parent) {
    ImGui.pushID(resource.uuid.toString());
    if (ImGui.selectable(resource.id, state.multiSelection.contains(resource.uuid)))
      state.select(kind, resource.uuid, root + parent, ImGui.getIO().getKeyCtrl());
    rowMenu(kind, resource, root, parent);
    drag(resource.uuid, root);
    ImGui.popID();
  }

  private void worldRoot() {
    boolean open = ImGui.treeNodeEx("World Generator", ImGuiTreeNodeFlags.DefaultOpen);
    rootMenu("world", null);
    if (open) {
      for (var layer : state.project().worldGenerator.layers) {
        if (!matches(layer.id) && !filter.get().isBlank()) continue;
        boolean expanded =
            ImGui.treeNodeEx(
                (layer.enabled ? "" : "[off] ") + layer.id + "###" + layer.uuid,
                ImGuiTreeNodeFlags.OpenOnArrow
                    | ImGuiTreeNodeFlags.DefaultOpen
                    | (state.selected != null && state.selected.equals(layer.uuid)
                        ? ImGuiTreeNodeFlags.Selected
                        : 0));
        if (ImGui.isItemClicked())
          state.select("layer", layer.uuid, "world", ImGui.getIO().getKeyCtrl());
        rowMenu("layer", layer, "world", null);
        if (expanded) {
          for (var op : layer.operations) row("operation", op, "operation", layer.uuid);
          ImGui.pushID(layer.uuid.toString());
          if (ImGui.smallButton("Add Noise Generator")) {
            placementLayer = layer.uuid;
            ImGui.openPopup("add-placement");
          }
          if (ImGui.beginPopup("add-placement")) {
            for (var list : List.of(state.project().noise2D, state.project().noise3D))
              for (var n : list)
                if (ImGui.menuItem(n.id + " [" + n.dimension + "D]")) {
                  UUID layerId = layer.uuid, noiseId = n.uuid;
                  state.change(
                      p -> {
                        var target =
                            p.worldGenerator.layers.stream()
                                .filter(l -> l.uuid.equals(layerId))
                                .findFirst()
                                .orElseThrow();
                        var op = new Operation();
                        op.id =
                            ResourceTransactions.unique(
                                n.id, target.operations.stream().map(o -> o.id).toList());
                        op.noiseGeneratorUuid = noiseId;
                        op.interpretation = n.dimension == 2 ? "HEIGHT_BELOW" : "DENSITY";
                        target.operations.add(op);
                      });
                }
            ImGui.endPopup();
          }
          ImGui.popID();
          ImGui.treePop();
        }
      }
      ImGui.treePop();
    }
  }

  private void rootMenu(String kind, UUID parent) {
    if (ImGui.beginPopupContextItem("root-menu" + kind + parent)) {
      if (ImGui.menuItem(kind.equals("world") ? "Create Layer" : "Create Noise Generator"))
        create(kind.equals("world") ? "layer" : kind, kind, parent);
      if (!kind.equals("world") && ImGui.menuItem("Create Folder")) create("folder", kind, parent);
      if (ImGui.menuItem("Paste", clipboard != null)) paste(kind, parent);
      ImGui.endPopup();
    }
  }

  private void rowMenu(String kind, Identity r, String root, UUID parent) {
    if (ImGui.beginPopupContextItem("resource-menu" + r.uuid)) {
      if (ImGui.menuItem("Rename / Properties")) state.select(kind, r.uuid);
      if (ImGui.menuItem("Duplicate")) duplicate(kind, r, root, parent);
      if (ImGui.menuItem("Copy")) copy(r.uuid, root, false);
      if (ImGui.menuItem("Cut")) copy(r.uuid, root, true);
      if (ImGui.menuItem("Delete")) {
        deleteId = r.uuid;
        replacement = null;
        ImGui.openPopup("Delete Resource");
      }
      if (ImGui.menuItem("Move Up")) reorder(r.uuid, -1);
      if (ImGui.menuItem("Move Down")) reorder(r.uuid, 1);
      if (ImGui.menuItem("Move to Root") && r instanceof Noise)
        state.change(p -> ((Noise) EditorState.find(p, r.uuid)).parentFolderUuid = null);
      ImGui.endPopup();
    }
  }

  private void drag(UUID id, String root) {
    if (ImGui.beginDragDropSource()) {
      ImGui.setDragDropPayload("resource", id.toString());
      ImGui.text("Move resource");
      ImGui.endDragDropSource();
    }
  }

  private void dropTarget(String root, UUID parent) {
    if (ImGui.beginDragDropTarget()) {
      String value = ImGui.acceptDragDropPayload("resource", String.class);
      if (value != null) {
        UUID id = UUID.fromString(value);
        state.change(
            p -> {
              Object obj = EditorState.find(p, id);
              if (obj instanceof Noise n) {
                if (!root.equals("noise" + n.dimension + "D"))
                  throw new IllegalArgumentException("Cannot move noise across dimension roots");
                n.parentFolderUuid = parent;
              } else if (obj instanceof Folder folder) {
                if (!folder.rootKind.equals(root))
                  throw new IllegalArgumentException("Cannot move folders across roots");
                folder.parentFolderUuid = parent;
              }
            });
      }
      ImGui.endDragDropTarget();
    }
  }

  private void create(String kind, String root, UUID parent) {
    createKind = kind;
    createRoot = root;
    createParent = parent;
    name.set("");
    createPending = true;
  }

  private void createDialog() {
    if (createPending) {
      ImGui.openPopup("Create Resource");
      createPending = false;
    }
    if (ImGui.beginPopupModal("Create Resource", ImGuiWindowFlags.AlwaysAutoResize)) {
      ImGui.text("New " + createKind);
      ImGui.inputText("ID", name);
      if (ImGui.button("Create")) {
        UUID[] made = {null};
        boolean ok =
            state.change(
                p -> {
                  String id = name.get();
                  switch (createKind) {
                    case "noise2D", "noise3D" -> {
                      var n = new Noise();
                      n.id = id;
                      n.dimension = createKind.equals("noise2D") ? 2 : 3;
                      n.parentFolderUuid = createParent;
                      (n.dimension == 2 ? p.noise2D : p.noise3D).add(n);
                      made[0] = n.uuid;
                    }
                    case "layer" -> {
                      var l = new Layer();
                      l.id = id;
                      p.worldGenerator.layers.add(l);
                      made[0] = l.uuid;
                    }
                    case "folder" -> {
                      var folder = new Folder();
                      folder.id = id;
                      folder.rootKind = createRoot;
                      folder.parentFolderUuid = createParent;
                      p.folders.add(folder);
                      made[0] = folder.uuid;
                    }
                    case "voxel" -> {
                      var t = new VoxelType();
                      t.id = id;
                      p.voxelTypes.add(t);
                      made[0] = t.uuid;
                    }
                    case "set", "group" -> {
                      var m = new Membership();
                      m.id = id;
                      (createKind.equals("set") ? p.voxelSets : p.voxelGroups).add(m);
                      made[0] = m.uuid;
                    }
                    default -> throw new IllegalArgumentException();
                  }
                });
        if (ok) {
          state.select(createKind, made[0]);
          ImGui.closeCurrentPopup();
        }
      }
      ImGui.sameLine();
      if (ImGui.button("Cancel")) ImGui.closeCurrentPopup();
      ImGui.endPopup();
    }
  }

  private void reorder(UUID uuid, int direction) {
    state.change(
        p -> {
          for (var list : EditorState.lists(p))
            for (int i = 0; i < list.size(); i++)
              if (list.get(i) instanceof Identity r && r.uuid.equals(uuid)) {
                int to = i + direction;
                if (to >= 0 && to < list.size()) Collections.swap(list, i, to);
                return;
              }
        });
  }

  private void duplicate(String kind, Identity r, String root, UUID parent) {
    if (r instanceof Folder) {
      copy(r.uuid, root, false);
      paste(root, parent);
      return;
    }
    UUID[] made = {null};
    state.change(
        p -> {
          for (var list : EditorState.lists(p))
            if (list.stream().anyMatch(o -> o instanceof Identity i && i.uuid.equals(r.uuid))) {
              Identity copy = ResourceTransactions.duplicate(r, r.getClass());
              copy.id =
                  ResourceTransactions.unique(
                      copy.id,
                      list.stream()
                          .filter(o -> o instanceof Identity)
                          .map(o -> ((Identity) o).id)
                          .toList());
              @SuppressWarnings("unchecked")
              List<Object> target = (List<Object>) (List<?>) list;
              target.add(copy);
              made[0] = copy.uuid;
              return;
            }
        });
    if (made[0] != null) state.select(kind, made[0]);
  }

  public ProjectData selection(boolean dependencies) {
    var p = state.project();
    var subset = new ProjectData();
    Object obj = EditorState.find(p, state.selected);
    if (obj instanceof Noise n) (n.dimension == 2 ? subset.noise2D : subset.noise3D).add(n);
    else if (obj instanceof Layer l) subset.worldGenerator.layers.add(l);
    else if (obj instanceof VoxelType t) subset.voxelTypes.add(t);
    else if (obj instanceof Membership m) {
      if (p.voxelSets.contains(m)) subset.voxelSets.add(m);
      else subset.voxelGroups.add(m);
    } else if (obj instanceof Folder f) {
      collectFolder(p, subset, f.uuid);
    }
    if (dependencies) {
      Set<UUID> added = new HashSet<>();
      for (int pass = 0; pass < 32; pass++) {
        String json = ProjectIO.JSON.valueToTree(subset).toString();
        boolean changed = false;
        for (var list :
            List.of(p.noise2D, p.noise3D, p.voxelTypes, p.voxelSets, p.voxelGroups, p.folders))
          for (var r : list)
            if (json.contains(r.uuid.toString()) && !contains(subset, r.uuid)) {
              if (r instanceof Noise n) (n.dimension == 2 ? subset.noise2D : subset.noise3D).add(n);
              else if (r instanceof VoxelType t) subset.voxelTypes.add(t);
              else if (r instanceof Membership m)
                (p.voxelSets.contains(m) ? subset.voxelSets : subset.voxelGroups).add(m);
              else if (r instanceof Folder f) subset.folders.add(f);
              changed = true;
            }
        if (!changed) break;
      }
    }
    return ProjectIO.copy(subset);
  }

  private boolean contains(ProjectData p, UUID uuid) {
    return EditorState.find(p, uuid) != null;
  }

  private void collectFolder(ProjectData p, ProjectData subset, UUID id) {
    var folder = p.folders.stream().filter(f -> f.uuid.equals(id)).findFirst().orElseThrow();
    subset.folders.add(folder);
    for (var f : p.folders) if (id.equals(f.parentFolderUuid)) collectFolder(p, subset, f.uuid);
    for (var list : List.of(p.noise2D, p.noise3D))
      for (var n : list)
        if (id.equals(n.parentFolderUuid))
          (n.dimension == 2 ? subset.noise2D : subset.noise3D).add(n);
  }

  private void copy(UUID id, String root, boolean cut) {
    state.selected = id;
    clipboard = selection(false);
    clipboardRoot = root;
    this.cut = cut;
    state.status = cut ? "Resource cut; paste to move" : "Resource copied";
  }

  private void paste(String root, UUID parent) {
    if (clipboard == null) return;
    if (!root.equals(clipboardRoot)) {
      state.notify("Warning", "Paste requires a compatible root");
      return;
    }
    ProjectData payload = ProjectIO.copy(clipboard);
    boolean move = cut;
    boolean ok =
        state.change(
            p -> {
              if (move) {
                for (var list : EditorState.lists(payload))
                  for (var obj : list) if (obj instanceof Identity r) remove(p, r.uuid);
              } else payloadResetParents(payload);
              for (var list : List.of(payload.noise2D, payload.noise3D))
                for (var n : list)
                  if (n.parentFolderUuid == null
                      || payload.folders.stream().noneMatch(f -> f.uuid.equals(n.parentFolderUuid)))
                    n.parentFolderUuid = parent;
              for (var folder : payload.folders)
                if (payload.folders.stream().noneMatch(f -> f.uuid.equals(folder.parentFolderUuid)))
                  folder.parentFolderUuid = parent;
              if (move) mergeExisting(p, payload);
              else ResourceTransactions.mergeCopies(p, payload);
            });
    if (ok && move) {
      clipboard = null;
      cut = false;
    }
  }

  private void payloadResetParents(ProjectData p) {}

  private void mergeExisting(ProjectData into, ProjectData from) {
    into.noise2D.addAll(from.noise2D);
    into.noise3D.addAll(from.noise3D);
    into.folders.addAll(from.folders);
    into.worldGenerator.layers.addAll(from.worldGenerator.layers);
  }

  private static void remove(ProjectData p, UUID id) {
    for (var list : EditorState.lists(p))
      list.removeIf(o -> o instanceof Identity r && r.uuid.equals(id));
  }

  public List<Identity> dependents(UUID uuid) {
    var result = new ArrayList<Identity>();
    for (var list : EditorState.lists(state.project()))
      for (var o : list)
        if (o instanceof Identity r
            && !r.uuid.equals(uuid)
            && references(ProjectIO.JSON.valueToTree(r), uuid.toString())) result.add(r);
    return result;
  }

  private static boolean references(JsonNode node, String uuid) {
    if (node.isObject()) {
      var it = node.fields();
      while (it.hasNext()) {
        var e = it.next();
        if (!e.getKey().equals("uuid") && references(e.getValue(), uuid)) return true;
      }
      return false;
    }
    if (node.isArray()) {
      for (var c : node) if (references(c, uuid)) return true;
      return false;
    }
    return node.isTextual() && node.asText().equals(uuid);
  }

  private void deleteDialog() {
    if (deleteId != null && !ImGui.isPopupOpen("Delete Resource"))
      ImGui.openPopup("Delete Resource");
    if (ImGui.beginPopupModal("Delete Resource", ImGuiWindowFlags.AlwaysAutoResize)) {
      var deps = dependents(deleteId);
      if (deps.isEmpty()) ImGui.text("Delete this resource? This can be undone.");
      else {
        ImGui.text("Referenced by:");
        for (var d : deps) ImGui.bulletText(d.id);
        ImGui.textWrapped(
            "Deletion is blocked until these references are explicitly changed in Properties.");
      }
      if (deps.isEmpty() && ImGui.button("Delete")) {
        UUID id = deleteId;
        if (state.change(
            p -> {
              Object obj = EditorState.find(p, id);
              if (obj instanceof Folder) {
                var subtree = new ProjectData();
                collectFolder(p, subtree, id);
                for (var list : EditorState.lists(subtree))
                  for (var o : list) if (o instanceof Identity r) remove(p, r.uuid);
              } else remove(p, id);
            })) {
          state.selected = null;
          state.draft = null;
          deleteId = null;
          ImGui.closeCurrentPopup();
        }
      }
      ImGui.sameLine();
      if (ImGui.button("Cancel")) {
        deleteId = null;
        ImGui.closeCurrentPopup();
      }
      ImGui.endPopup();
    }
  }

  private final ImBoolean manager = new ImBoolean(false);

  public void showManager() {
    manager.set(true);
  }

  private void manager() {
    if (!manager.get()) return;
    ImGui.setNextWindowSize(600, 500, ImGuiCond.FirstUseEver);
    ImGui.begin("Voxel Manager", manager);
    if (ImGui.beginTabBar("voxel-tabs")) {
      if (ImGui.beginTabItem("Voxel Types")) {
        if (ImGui.button("Create Voxel Type")) create("voxel", "voxel", null);
        for (var t : state.project().voxelTypes) {
          ImGui.colorButton(
              "##" + t.uuid, new ImVec4(t.color.r / 255f, t.color.g / 255f, t.color.b / 255f, 1));
          ImGui.sameLine();
          row("voxel", t, "voxel", null);
        }
        ImGui.endTabItem();
      }
      if (ImGui.beginTabItem("Voxel Sets")) {
        if (ImGui.button("Create Set")) create("set", "set", null);
        for (var set : state.project().voxelSets) row("set", set, "set", null);
        ImGui.endTabItem();
      }
      if (ImGui.beginTabItem("Groups")) {
        if (ImGui.button("Create Group")) create("group", "group", null);
        for (var g : state.project().voxelGroups) row("group", g, "group", null);
        ImGui.endTabItem();
      }
      ImGui.endTabBar();
    }
    ImGui.end();
  }
}
