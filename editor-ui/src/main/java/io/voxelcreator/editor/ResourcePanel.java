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
  private ResourceClipboard clipboard;
  private boolean focused;

  private UUID deleteId;
  private UUID replacement, renameId;
  private final ImString renameText = new ImString(256);
  private boolean renameFocus;
  private String renameError = "";
  private final List<UUID> visibleRows = new ArrayList<>();
  private UUID rangeAnchor;
  private String dragRoot = "";
  private List<UUID> dragSelection = List.of();
  public final Set<UUID> exportSelection = new LinkedHashSet<>();

  public ResourcePanel(EditorState s, PropertyPanel properties) {
    state = s;
    this.properties = properties;
  }

  public void reset() {
    clipboard = null;

    filter.set("");
    renameId = null;
    rangeAnchor = null;
    exportSelection.clear();
  }

  public void draw() {
    ImGui.begin("Resources");
    ImGui.setWindowFontScale(state.user.appearance.windowScales.getOrDefault("Resources", 1f));
    visibleRows.clear();
    ImGui.inputTextWithHint("##filter", "Filter resources...", filter);
    root("2D Noise", "noise2D");
    root("3D Noise", "noise3D");
    worldRoot();
    ImGui.separator();
    if (ImGui.button("Voxel Manager")) manager.set(true);
    ImGui.textDisabled("Right-click a root to create resources");
    focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
    if (focused && ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      renameId = null;
      renameError = "";
    }
    ImGui.end();
    createDialog();
    deleteDialog();
    manager();
  }

  public boolean focused() {
    return focused;
  }

  public void shortcut(String action) {
    var target = ResourceGraph.find(state.project(), state.selected);
    if (target == null) return;
    switch (action) {
      case "Rename" -> beginRename(target.uuid());
      case "Copy", "Cut" -> copy(target.uuid(), target.root(), action.equals("Cut"));
      case "Paste" -> {
        if (clipboard != null) {
          if (target.resource() instanceof Layer && clipboard.root().equals("operation"))
            paste("operation", target.uuid());
          else
            paste(
                target.root(),
                target.resource() instanceof Folder ? target.uuid() : target.parent());
        }
      }
      case "Duplicate" ->
          duplicate(target.kind(), target.resource(), target.root(), target.parent());
      case "Delete" -> {
        deleteId = target.uuid();
        replacement = null;
      }
      default -> {}
    }
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
    for (var entry : ResourceGraph.children(state.project(), kind, parent)) {
      if (entry.resource() instanceof Folder folder) {
        if (!visited.add(folder.uuid)) continue;
        boolean open =
            ImGui.treeNodeEx(
                folder.id + "###" + folder.uuid,
                ImGuiTreeNodeFlags.OpenOnArrow
                    | (state.multiSelection.contains(folder.uuid)
                        ? ImGuiTreeNodeFlags.Selected
                        : 0));
        visibleRows.add(folder.uuid);
        if (ImGui.isItemClicked() && !ImGui.isItemToggledOpen())
          select("folder", folder.uuid, kind + ":" + parent);
        if (folder.uuid.equals(renameId)) inlineRename(folder);
        rowMenu("folder", folder, kind, parent);
        drag(folder.uuid, kind);
        dropTarget(kind, folder.uuid);
        if (open) {
          folderContents(kind, folder.uuid, visited);
          ImGui.treePop();
        }
      } else if (entry.resource() instanceof Noise noise && matches(noise.id))
        row(kind, noise, kind, parent);
    }
  }

  private boolean matches(String value) {
    return filter.get().isBlank()
        || value.toLowerCase(Locale.ROOT).contains(filter.get().toLowerCase(Locale.ROOT));
  }

  private void row(String kind, Identity resource, String root, UUID parent) {
    ImGui.pushID(resource.uuid.toString());
    visibleRows.add(resource.uuid);
    if (resource.uuid.equals(renameId)) inlineRename(resource);
    else if (ImGui.selectable(resource.id, state.multiSelection.contains(resource.uuid)))
      select(kind, resource.uuid, root + ":" + parent);
    rowMenu(kind, resource, root, parent);
    drag(resource.uuid, root);
    dropBefore(resource.uuid);
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
        visibleRows.add(layer.uuid);
        if (ImGui.isItemClicked() && !ImGui.isItemToggledOpen())
          select("layer", layer.uuid, "world:null");
        if (layer.uuid.equals(renameId)) inlineRename(layer);
        rowMenu("layer", layer, "world", null);
        drag(layer.uuid, "world");
        dropBefore(layer.uuid);
        dropTarget("operation", layer.uuid);
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
      if (ImGui.menuItem("Paste", clipboard != null && clipboard.root().equals(kind)))
        paste(kind, parent);
      ImGui.endPopup();
    }
  }

  private void rowMenu(String kind, Identity r, String root, UUID parent) {
    if (ImGui.beginPopupContextItem("resource-menu" + r.uuid)) {
      if (r instanceof Folder) {
        if (ImGui.menuItem("Create Noise Generator")) create(root, root, r.uuid);
        if (ImGui.menuItem("Create Folder")) create("folder", root, r.uuid);
        if (ImGui.menuItem("Paste", clipboard != null && clipboard.root().equals(root)))
          paste(root, r.uuid);
      }
      if (r instanceof Layer
          && ImGui.menuItem(
              "Paste Placements", clipboard != null && clipboard.root().equals("operation")))
        paste("operation", r.uuid);
      if (ImGui.menuItem("Rename", state.user.input.shortcuts.getOrDefault("Rename", "")))
        beginRename(r.uuid);
      if (ImGui.menuItem("Properties")) state.select(kind, r.uuid);
      if (ImGui.menuItem("Duplicate", state.user.input.shortcuts.getOrDefault("Duplicate", "")))
        duplicate(kind, r, root, parent);
      if (ImGui.menuItem("Copy", state.user.input.shortcuts.getOrDefault("Copy", "")))
        copy(r.uuid, root, false);
      if (ImGui.menuItem("Cut", state.user.input.shortcuts.getOrDefault("Cut", "")))
        copy(r.uuid, root, true);
      if (ImGui.menuItem("Delete", state.user.input.shortcuts.getOrDefault("Delete", ""))) {
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
      if (!state.multiSelection.contains(id)) {
        var e = ResourceGraph.find(state.project(), id);
        state.select(e.kind(), id, e.scope(), false);
      }
      dragRoot = root;
      dragSelection = List.copyOf(state.multiSelection);
      ImGui.setDragDropPayload("resource", id.toString());
      ImGui.text("Move " + dragSelection.size() + " selected resource(s)");
      ImGui.endDragDropSource();
    }
  }

  private void dropTarget(String root, UUID parent) {
    if (ImGui.beginDragDropTarget()) {
      String value = ImGui.acceptDragDropPayload("resource", String.class);
      if (value != null && !dragSelection.isEmpty()) {
        var ids = List.copyOf(dragSelection);
        state.change(p -> ResourceGraph.move(p, ids, root, parent));
        state.refreshDraft();
      }
      ImGui.endDragDropTarget();
    }
  }

  private void beginRename(UUID id) {
    var e = ResourceGraph.find(state.project(), id);
    if (e == null) return;
    renameId = id;
    renameText.set(e.resource().id);
    renameFocus = true;
    renameError = "";
  }

  private void inlineRename(Identity resource) {
    ImGui.pushID("rename" + resource.uuid);
    if (renameFocus) {
      ImGui.setKeyboardFocusHere();
      renameFocus = false;
    }
    boolean entered =
        ImGui.inputText("##inline-id", renameText, ImGuiInputTextFlags.EnterReturnsTrue);
    if (entered) {
      UUID id = resource.uuid;
      String name = renameText.get();
      if (state.change(p -> ResourceGraph.rename(p, id, name))) {
        renameId = null;
        renameError = "";
        state.refreshDraft();
      } else renameError = "ID rejected; correct it or press Escape";
    }
    if (!renameError.isEmpty()) ImGui.textWrapped("! " + renameError);
    ImGui.popID();
  }

  private void select(String kind, UUID id, String scope) {
    if (ImGui.getIO().getKeyShift() && rangeAnchor != null) {
      var anchor = ResourceGraph.find(state.project(), rangeAnchor);
      var target = ResourceGraph.find(state.project(), id);
      if (anchor != null && target != null && anchor.scope().equals(target.scope())) {
        var siblings =
            ResourceGraph.entries(state.project()).stream()
                .filter(e -> e.scope().equals(target.scope()))
                .map(ResourceGraph.Entry::uuid)
                .toList();
        int a = siblings.indexOf(rangeAnchor), b = siblings.indexOf(id);
        state.select(kind, id, scope, false);
        state.multiSelection.clear();
        for (int i = Math.min(a, b); i <= Math.max(a, b); i++)
          state.multiSelection.add(siblings.get(i));
        return;
      }
    }
    state.select(kind, id, scope, ImGui.getIO().getKeyCtrl());
    rangeAnchor = id;
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
    Set<UUID> selection =
        state.multiSelection.contains(uuid) ? Set.copyOf(state.multiSelection) : Set.of(uuid);
    state.change(p -> ResourceGraph.shift(p, selection, direction));
  }

  private void dropBefore(UUID target) {
    if (ImGui.beginDragDropTarget()) {
      String value = ImGui.acceptDragDropPayload("resource", String.class);
      if (value != null && !dragSelection.isEmpty()) {
        var ids = List.copyOf(dragSelection);
        state.change(p -> ResourceGraph.reorderBefore(p, ids, target));
      }
      ImGui.endDragDropTarget();
    }
  }

  private void duplicate(String kind, Identity r, String root, UUID parent) {
    if (r instanceof Folder || state.multiSelection.size() > 1) {
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
    Set<UUID> ids =
        state.multiSelection.isEmpty() && state.selected != null
            ? Set.of(state.selected)
            : Set.copyOf(state.multiSelection);
    return ResourceGraph.selection(state.project(), ids, dependencies);
  }

  public ProjectData exportSelection() {
    return ResourceGraph.selection(state.project(), exportSelection, true);
  }

  public void beginExport() {
    exportSelection.clear();
    exportSelection.addAll(state.multiSelection);
    if (exportSelection.isEmpty() && state.selected != null) exportSelection.add(state.selected);
  }

  public void drawExportSelection() {
    ImGui.textWrapped(
        "Choose resources. Required references and folder ancestors are included automatically.");
    for (var e : ResourceGraph.entries(state.project())) {
      ImGui.pushID("export" + e.uuid());
      var selected = new ImBoolean(exportSelection.contains(e.uuid()));
      if (ImGui.checkbox(e.kind() + " / " + e.resource().id, selected)) {
        if (selected.get()) exportSelection.add(e.uuid());
        else exportSelection.remove(e.uuid());
      }
      ImGui.popID();
    }
    if (!exportSelection.isEmpty()) {
      var included = ResourceGraph.entries(exportSelection());
      ImGui.separator();
      ImGui.text("Package contains " + included.size() + " resources (including dependencies):");
      for (var e : included) ImGui.bulletText(e.kind() + " / " + e.resource().id);
    }
  }

  private void copy(UUID id, String root, boolean cut) {
    try {
      if (!state.multiSelection.contains(id)) {
        var entry = ResourceGraph.find(state.project(), id);
        state.select(entry.kind(), id, entry.scope(), false);
      }
      clipboard = new ResourceClipboard(state.project(), state.multiSelection, cut);
      state.status =
          state.multiSelection.size() + " resource(s) " + (cut ? "cut; paste to move" : "copied");
    } catch (RuntimeException invalid) {
      state.notify("Error", invalid.getMessage());
    }
  }

  private void paste(String root, UUID parent) {
    if (clipboard == null) return;
    ResourceClipboard content = clipboard;
    if (state.change(p -> content.paste(p, root, parent))) {
      if (content.cut()) clipboard = null;
      state.refreshDraft();
    }
  }

  public List<Identity> dependents(UUID id) {
    return ResourceGraph.dependents(
            state.project(), ResourceGraph.subtree(state.project(), List.of(id)))
        .stream()
        .map(ResourceGraph.Entry::resource)
        .toList();
  }

  private void deleteDialog() {
    if (deleteId != null && !ImGui.isPopupOpen("Delete Resource"))
      ImGui.openPopup("Delete Resource");
    if (ImGui.beginPopupModal("Delete Resource", ImGuiWindowFlags.AlwaysAutoResize)) {
      var source = ResourceGraph.find(state.project(), deleteId);
      var deleted = ResourceGraph.subtree(state.project(), List.of(deleteId));
      var deps = ResourceGraph.dependents(state.project(), deleted);
      ImGui.textWrapped(
          "Delete "
              + (source == null ? "resource" : source.resource().id)
              + " and "
              + (deleted.size() - 1)
              + " contained resource(s)?");
      if (!deps.isEmpty()) {
        ImGui.text("External dependents:");
        for (var d : deps) ImGui.bulletText(d.kind() + " / " + d.resource().id);
        if (source != null && !(source.resource() instanceof Folder)) {
          if (ImGui.beginCombo(
              "Replace references with",
              replacement == null
                  ? "Choose compatible resource"
                  : Optional.ofNullable(ResourceGraph.find(state.project(), replacement))
                      .map(e -> e.resource().id)
                      .orElse("Missing"))) {
            for (var e : ResourceGraph.entries(state.project()))
              if (e.kind().equals(source.kind())
                  && !deleted.contains(e.uuid())
                  && ImGui.selectable(e.resource().id)) replacement = e.uuid();
            ImGui.endCombo();
          }
          ImGui.textWrapped("References and deletion commit together as one undoable edit.");
        } else
          ImGui.textWrapped("Edit the external references before deleting this folder subtree.");
      }
      if ((deps.isEmpty() || replacement != null) && ImGui.button("Delete")) {
        UUID id = deleteId, target = replacement;
        if (state.change(p -> ResourceGraph.delete(p, id, target))) {
          state.selected = null;
          state.draft = null;
          state.multiSelection.clear();
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
