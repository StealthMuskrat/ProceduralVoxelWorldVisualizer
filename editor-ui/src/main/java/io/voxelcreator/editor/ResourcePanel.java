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
  private final ResourceTreeIndex.Cache browserCache = new ResourceTreeIndex.Cache();
  private final ResourceTreeView treeView = new ResourceTreeView();
  private final Map<String, Boolean> rootExpansion = new HashMap<>();
  private ResourceTreeIndex browser;
  private Map<UUID, Identity> identities = Map.of();
  private List<ResourceTreeIndex.Row> selectionRows = List.of(), managerSelectionRows = List.of();
  private final ImString managerFilter = new ImString(256), placementFilter = new ImString(256);
  private String managerKey, placementKey;
  private List<ResourceTreeIndex.Node> managerRows = List.of(), placementRows = List.of();
  private boolean placementPending;
  private UUID placementNoise;
  private int submittedRows;
  private boolean smokeBrowserDrawn;
  private int submittedManagerRows;
  private Float probeScroll;
  private final List<UUID> submittedTreeIds = new ArrayList<>();

  private UUID rangeAnchor;
  private String dragRoot = "";
  private List<UUID> dragSelection = List.of();
  public Consumer<UUID> findReferences;
  public final Set<UUID> exportSelection = new LinkedHashSet<>();

  public ResourcePanel(EditorState s, PropertyPanel properties) {
    state = s;
    this.properties = properties;
  }

  public void reset() {
    clipboard = null;
    browserCache.clear();
    treeView.clear();
    rootExpansion.clear();
    browser = null;
    managerFilter.set("");
    placementFilter.set("");
    managerKey = placementKey = null;
    managerRows = placementRows = List.of();
    selectionRows = managerSelectionRows = List.of();
    placementPending = false;
    placementLayer = placementNoise = null;

    filter.set("");
    renameId = null;
    rangeAnchor = null;
    dragRoot = "";
    dragSelection = List.of();
    manager.set(false);
    exportSelection.clear();
  }

  public void draw() {
    ImGui.begin("Resources");
    ImGui.setWindowFontScale(state.user.appearance.windowScales.getOrDefault("Resources", 1f));
    refreshBrowser();
    submittedRows = 0;
    submittedTreeIds.clear();
    ImGui.inputTextWithHint("##filter", "Filter resources...", filter);
    ImGui.beginChild("resource-tree", 0, Math.max(100, ImGui.getContentRegionAvailY() - 100), true);
    root("2D Noise", "noise2D");
    root("3D Noise", "noise3D");
    root("World Generator", "world");
    if (probeScroll != null) {
      ImGui.setScrollY(probeScroll);
      probeScroll = null;
    }
    ImGui.endChild();
    ImGui.separator();
    if (ImGui.button("Voxel Manager")) manager.set(true);
    if (browser.get(state.selected) != null
        && browser.get(state.selected).kind().equals("layer")
        && ImGui.button("Add Noise Generator to Layer")) beginPlacement(state.selected);
    ImGui.textDisabled("Right-click a root to create resources");
    if (!renameError.isEmpty()) ImGui.textWrapped("! " + renameError);
    focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
    if (focused && ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      renameId = null;
      renameError = "";
    }
    ImGui.end();
    createDialog();
    deleteDialog();
    placementDialog();
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

  private void refreshBrowser() {
    var next = browserCache.get(state.project(), state.revision);
    if (next == browser) return;
    browser = next;
    treeView.bind(browser);
    var values = new HashMap<UUID, Identity>();
    ResourceGraph.entries(state.project()).forEach(e -> values.put(e.uuid(), e.resource()));
    identities = Map.copyOf(values);
    managerKey = placementKey = null;
  }

  private void root(String label, String kind) {
    ImGui.setNextItemOpen(
        !filter.get().isBlank() || rootExpansion.getOrDefault(kind, true), ImGuiCond.Always);
    boolean open = ImGui.treeNodeEx(label, ImGuiTreeNodeFlags.DefaultOpen);
    if (filter.get().isBlank()) rootExpansion.put(kind, open);
    rootMenu(kind, null);
    if (!kind.equals("world")) dropTarget(kind, null);
    if (open) {
      var rows = treeView.rows(kind, filter.get());
      selectionRows = rows;
      float height = ImGui.getFrameHeightWithSpacing();
      var clipper = new ImGuiListClipper();
      try {
        clipper.begin(rows.size(), height);
        while (clipper.step())
          for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++)
            treeRow(rows.get(i));
        clipper.end();
      } finally {
        clipper.destroy();
      }
      ImGui.treePop();
    }
  }

  private void treeRow(ResourceTreeIndex.Row row) {
    var node = row.node();
    Identity resource = identities.get(node.uuid());
    if (resource == null) return;
    submittedRows++;
    submittedTreeIds.add(node.uuid());
    ImGui.pushID(node.uuid().toString());
    float indent = Math.min(row.depth(), 32) * ImGui.getTreeNodeToLabelSpacing();
    if (indent > 0) ImGui.indent(indent);
    if (node.uuid().equals(renameId)) inlineRename(resource);
    else if (node.container()) {
      ImGui.setNextItemOpen(row.open(), ImGuiCond.Always);
      boolean expanded =
          ImGui.treeNodeEx(
              (node.enabled() ? "" : "[off] ") + AppMetadata.uiText(node.id()) + "###container",
              ImGuiTreeNodeFlags.OpenOnArrow
                  | ImGuiTreeNodeFlags.NoTreePushOnOpen
                  | ImGuiTreeNodeFlags.FramePadding
                  | (state.multiSelection.contains(node.uuid()) ? ImGuiTreeNodeFlags.Selected : 0));
      if (expanded != row.open() && filter.get().isBlank()) treeView.open(node.uuid(), expanded);
      if (ImGui.isItemClicked() && !ImGui.isItemToggledOpen())
        select(node.kind(), node.uuid(), node.root() + ":" + node.parent());
    } else if (ImGui.selectable(
        (node.enabled() ? "" : "[off] ") + AppMetadata.uiText(node.id()) + "###leaf",
        state.multiSelection.contains(node.uuid()),
        0,
        0,
        ImGui.getFrameHeight()))
      select(node.kind(), node.uuid(), node.root() + ":" + node.parent());
    rowMenu(node.kind(), resource, node.root(), node.parent());
    drag(node.uuid(), node.root());
    if (node.kind().equals("folder")) dropTarget(node.root(), node.uuid());
    else if (node.kind().equals("layer") && dragRoot.equals("operation"))
      dropTarget("operation", node.uuid());
    else dropBefore(node.uuid());
    if (indent > 0) ImGui.unindent(indent);
    ImGui.popID();
  }

  private void row(String kind, Identity resource, String root, UUID parent) {
    ImGui.pushID(resource.uuid.toString());
    if (resource.uuid.equals(renameId)) inlineRename(resource);
    else if (ImGui.selectable(
        AppMetadata.uiText(resource.id),
        state.multiSelection.contains(resource.uuid),
        0,
        0,
        ImGui.getFrameHeight())) select(kind, resource.uuid, root + ":" + parent);
    rowMenu(kind, resource, root, parent);
    drag(resource.uuid, root);
    dropBefore(resource.uuid);
    ImGui.popID();
  }

  private void beginPlacement(UUID layer) {
    placementLayer = layer;
    placementNoise = null;
    placementFilter.set("");
    placementKey = null;
    placementPending = true;
  }

  private void placementDialog() {
    if (placementPending) {
      ImGui.openPopup("Add Noise Generator");
      placementPending = false;
    }
    ImGui.setNextWindowSize(560, 430, ImGuiCond.FirstUseEver);
    if (!ImGui.beginPopupModal("Add Noise Generator")) return;
    ImGui.inputTextWithHint("##placement-filter", "Find noise by ID...", placementFilter);
    if (!Objects.equals(placementKey, placementFilter.get())) {
      placementKey = placementFilter.get();
      placementRows = browser.noises(placementKey);
    }
    // Initial empty query also materializes the complete 2D/3D list.
    if (placementRows.isEmpty() && placementKey.isEmpty()) placementRows = browser.noises("");
    ImGui.text(placementRows.size() + " matching noise generator(s)");
    ImGui.beginChild(
        "placement-options", 0, Math.max(120, ImGui.getContentRegionAvailY() - 48), true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(placementRows.size(), ImGui.getTextLineHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var node = placementRows.get(i);
          if (ImGui.selectable(
              AppMetadata.uiText(node.id())
                  + " ["
                  + (node.kind().equals("noise2D") ? "2D" : "3D")
                  + "]###"
                  + node.uuid(),
              node.uuid().equals(placementNoise))) placementNoise = node.uuid();
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    ImGui.beginDisabled(placementNoise == null || browser.get(placementLayer) == null);
    if (ImGui.button("Add Placement")) {
      UUID layerId = placementLayer, noiseId = placementNoise;
      if (state.change(
          p -> {
            var layer =
                p.worldGenerator.layers.stream()
                    .filter(l -> l.uuid.equals(layerId))
                    .findFirst()
                    .orElseThrow();
            var noise = (Noise) EditorState.find(p, noiseId);
            var op = new Operation();
            op.id =
                ResourceTransactions.unique(
                    noise.id, layer.operations.stream().map(o -> o.id).toList());
            op.noiseGeneratorUuid = noiseId;
            op.interpretation = noise.dimension == 2 ? "HEIGHT_BELOW" : "DENSITY";
            layer.operations.add(op);
          })) {
        state.refreshDraft();
        ImGui.closeCurrentPopup();
      }
    }
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Cancel")) ImGui.closeCurrentPopup();
    ImGui.endPopup();
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
      if (r instanceof Layer && ImGui.menuItem("Add Noise Generator")) beginPlacement(r.uuid);
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
    if (!dragRoot.equals(root)) return;
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
    ImGui.popID();
  }

  private void select(String kind, UUID id, String scope) {
    if (ImGui.getIO().getKeyShift() && rangeAnchor != null) {
      var range = ResourceTreeIndex.range(selectionRows, rangeAnchor, id);
      if (!range.isEmpty()) {
        state.select(kind, id, scope, false);
        state.multiSelection.clear();
        state.multiSelection.addAll(range);
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
                      var n =
                          EditorCreations.noise(
                              state.metadata,
                              createKind.equals("noise2D") ? 2 : 3,
                              id,
                              createParent);
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
                      var t = EditorCreations.voxel(state.metadata, id);
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
    refreshBrowser();
    ImGui.setNextWindowSize(650, 520, ImGuiCond.FirstUseEver);
    ImGui.begin("Voxel Manager", manager);
    ImGui.inputTextWithHint(
        "##voxel-filter", "Filter voxel type, set or group IDs...", managerFilter);
    submittedManagerRows = 0;
    if (ImGui.beginTabBar("voxel-tabs")) {
      managerTab("Voxel Types", "voxel", "Create Voxel Type");
      managerTab("Voxel Sets", "set", "Create Set");
      managerTab("Groups", "group", "Create Group");
      ImGui.endTabBar();
    }
    smokeBrowserDrawn = true;
    ImGui.end();
  }

  private void managerTab(String label, String kind, String createLabel) {
    if (!ImGui.beginTabItem(label)) return;
    if (ImGui.button(createLabel)) create(kind, kind, null);
    String key = kind + ":" + managerFilter.get();
    if (!key.equals(managerKey)) {
      managerKey = key;
      managerRows = browser.kind(kind, managerFilter.get());
      managerSelectionRows =
          managerRows.stream().map(n -> new ResourceTreeIndex.Row(n, 0, false)).toList();
    }
    selectionRows = managerSelectionRows;
    ImGui.text(managerRows.size() + " matching " + label.toLowerCase(Locale.ROOT));
    ImGui.beginChild("manager-rows", 0, Math.max(80, ImGui.getContentRegionAvailY() - 30), true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(managerRows.size(), ImGui.getFrameHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var node = managerRows.get(i);
          submittedManagerRows++;
          if (node.swatch() != null) {
            var c = node.swatch();
            ImGui.colorButton(
                "##swatch" + node.uuid(), new ImVec4(c.r() / 255f, c.g() / 255f, c.b() / 255f, 1));
            ImGui.sameLine();
          }
          row(kind, identities.get(node.uuid()), kind, null);
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    if (findReferences != null
        && browser.get(state.selected) != null
        && browser.get(state.selected).kind().equals(kind)
        && ImGui.button("Find References to Selected")) {
      findReferences.accept(state.selected);
    }
    ImGui.endTabItem();
  }

  void smokeBrowser(boolean show) {
    manager.set(show);
    if (show) {
      smokeBrowserDrawn = false;
      managerFilter.set("");
    } else if (!smokeBrowserDrawn) throw new AssertionError("Voxel browser was not drawn");
  }

  record BrowserStats(
      int indexed,
      long builds,
      long projections,
      int treeRows,
      int managerRows,
      List<UUID> submitted) {}

  BrowserStats browserStats() {
    return new BrowserStats(
        browser.size(),
        browserCache.builds(),
        treeView.projections(),
        submittedRows,
        submittedManagerRows,
        List.copyOf(submittedTreeIds));
  }

  void probeFilter(String tree, String voxels) {
    filter.set(tree);
    managerFilter.set(voxels);
  }

  void probeScroll(float value) {
    probeScroll = value;
  }
}
