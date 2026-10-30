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
  final FloatingWindowLayout windowLayout = new FloatingWindowLayout(320, 640);
  final FloatingWindowLayout managerLayout = new FloatingWindowLayout(650, 520);
  final PanelDockControls docking = new PanelDockControls("Resources");
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
  private boolean createOpen, placementOpen;
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
  private final ResourceExportPanel exportPanel;
  private final ResourceSelectionSession selection;
  private final ResourceEditSession edits;
  private boolean editPrompt;
  private String editMessage = "";
  private Runnable editCommitted;
  private final Map<String, float[]> editButtons = new HashMap<>();
  private ProjectData dragProject;
  private long dragRevision;
  private boolean dragActive;
  private boolean selectionPrompt;
  private UUID pendingAnchor;
  private String selectionMessage = "";
  private final Map<String, float[]> selectionButtons = new HashMap<>();
  private final Map<UUID, float[]> rowCenters = new HashMap<>();

  public ResourcePanel(EditorState s, PropertyPanel properties) {
    state = s;
    this.properties = properties;
    exportPanel = new ResourceExportPanel(s, exportSelection);
    selection = new ResourceSelectionSession(s);
    edits = new ResourceEditSession(s);
  }

  public void reset() {
    edits.clear();
    editPrompt = false;
    editMessage = "";
    editCommitted = null;
    editButtons.clear();
    dragProject = null;
    dragActive = false;
    selection.clear();
    selectionPrompt = false;
    pendingAnchor = null;
    selectionMessage = "";
    selectionButtons.clear();
    rowCenters.clear();
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
    createOpen = placementOpen = false;
    placementLayer = placementNoise = null;

    filter.set("");
    renameId = null;
    rangeAnchor = null;
    dragRoot = "";
    dragSelection = List.of();
    manager.set(false);
    exportSelection.clear();
    exportPanel.reset();
  }

  public void draw() {
    rowCenters.clear();
    submittedRows = 0;
    submittedTreeIds.clear();
    refreshBrowser();
    windowLayout.prepareExisting();
    docking.prepare();
    boolean visible = ImGui.begin("Resources");
    UiScale.window(state.user, "Resources");
    windowLayout.observe();
    DialogLayout.observe(state, "Resources");
    if (visible) {
      docking.draw();
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      ImGui.inputTextWithHint("##filter", "Filter resources...", filter);
      ImGui.beginChild(
          "resource-tree",
          0,
          Math.max(
              1,
              ImGui.getContentRegionAvailY()
                  - 2 * ImGui.getFrameHeightWithSpacing()
                  - 2 * ImGui.getTextLineHeightWithSpacing()),
          true);
      root("2D Noise", "noise2D");
      root("3D Noise", "noise3D");
      root("World Generator", "world");
      if (probeScroll != null) {
        ImGui.setScrollY(probeScroll);
        probeScroll = null;
      }
      ImGui.endChild();
      ImGui.separator();
      if (DialogLayout.button("Voxel Manager")) manager.set(true);
      if (browser.get(state.selected) != null
          && browser.get(state.selected).kind().equals("layer")
          && DialogLayout.button("Add Noise Generator to Layer")) beginPlacement(state.selected);
      ImGui.textWrapped("Right-click a root to create resources");
      if (!renameError.isEmpty()) ImGui.textWrapped("! " + renameError);
      focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
      if (focused && ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
        renameId = null;
        renameError = "";
      }
    } else {
      focused = false;
      dragActive = false;
      dragProject = null;
      dragSelection = List.of();
    }
    ImGui.end();
    createDialog();
    deleteDialog();
    placementDialog();
    manager();
    selectionDialog();
    editDialog();
    if (!ImGui.isMouseDown(0)) {
      dragActive = false;
      dragProject = null;
      dragSelection = List.of();
    }
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
        beginDelete(target.uuid());
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
    dropTarget(kind, null);
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
              (node.enabled() ? "" : "[off] ")
                  + (node.kind().equals("layer") ? "[run " + browser.stage(node.uuid()) + "] " : "")
                  + AppMetadata.uiText(node.id())
                  + "###container",
              ImGuiTreeNodeFlags.OpenOnArrow
                  | ImGuiTreeNodeFlags.NoTreePushOnOpen
                  | ImGuiTreeNodeFlags.FramePadding
                  | (state.multiSelection.contains(node.uuid()) ? ImGuiTreeNodeFlags.Selected : 0));
      if (expanded != row.open() && filter.get().isBlank()) treeView.open(node.uuid(), expanded);
      if (ImGui.isItemClicked() && !ImGui.isItemToggledOpen()) select(node.uuid());
    } else if (ImGui.selectable(
        (node.enabled() ? "" : "[off] ") + AppMetadata.uiText(node.id()) + "###leaf",
        state.multiSelection.contains(node.uuid()),
        0,
        0,
        ImGui.getFrameHeight())) select(node.uuid());
    rememberRow(node.uuid());
    rowMenu(node.kind(), resource, node.root(), node.parent());
    drag(node.uuid(), node.root());
    if (node.kind().equals("folder")) {
      if (ImGui.getIO().getKeyShift()) dropBefore(node.uuid());
      else dropTarget(node.root(), node.uuid());
    } else if (node.kind().equals("layer") && dragRoot.equals("operation"))
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
        ImGui.getFrameHeight())) select(resource.uuid);
    rememberRow(resource.uuid);
    rowMenu(kind, resource, root, parent);
    drag(resource.uuid, root);
    dropBefore(resource.uuid);
    ImGui.popID();
  }

  private void beginPlacement(UUID layer) {
    if (!actionAvailable()) return;
    placementLayer = layer;
    placementNoise = null;
    placementFilter.set("");
    placementKey = null;
    placementPending = true;
    placementOpen = true;
  }

  private void placementDialog() {
    if (placementPending) {
      dialog("Add Noise Generator").open("Add Noise Generator");
      placementPending = false;
    }
    if (!dialog("Add Noise Generator").begin("Add Noise Generator")) return;
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
    if (DialogLayout.button("Add Placement")) {
      UUID layerId = placementLayer, noiseId = placementNoise;
      var result =
          requestEdit("Add Placement", ResourceBrowserEdits.placement(layerId, noiseId), () -> {});
      if (acceptedOrPending(result)) {
        placementOpen = false;
        ImGui.closeCurrentPopup();
      }
    }
    ImGui.endDisabled();
    DialogLayout.sameLine("Cancel");
    if (DialogLayout.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      placementOpen = false;
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
  }

  private void rootMenu(String kind, UUID parent) {
    if (ImGui.beginPopupContextItem("root-menu" + kind + parent)) {
      if (ImGui.menuItem(kind.equals("world") ? "Create Layer" : "Create Noise Generator"))
        create(kind.equals("world") ? "layer" : kind, kind, parent);
      if (ImGui.menuItem("Create Folder")) create("folder", kind, parent);
      if (ImGui.menuItem("Paste", clipboard != null && clipboard.root().equals(kind)))
        paste(kind, parent);
      ImGui.endPopup();
    }
  }

  private void rowMenu(String kind, Identity r, String root, UUID parent) {
    if (ImGui.beginPopupContextItem("resource-menu" + r.uuid)) {
      if (r instanceof Folder) {
        if (ImGui.menuItem(root.equals("world") ? "Create Layer" : "Create Noise Generator"))
          create(root.equals("world") ? "layer" : root, root, r.uuid);
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
      if (ImGui.menuItem("Properties", "", false, !properties.searchBlocked() && !edits.pending()))
        acceptSelection(selection.click(r.uuid, false), r.uuid);
      if (ImGui.menuItem("Duplicate", state.user.input.shortcuts.getOrDefault("Duplicate", "")))
        duplicate(kind, r, root, parent);
      if (ImGui.menuItem("Copy", state.user.input.shortcuts.getOrDefault("Copy", "")))
        copy(r.uuid, root, false);
      if (ImGui.menuItem("Cut", state.user.input.shortcuts.getOrDefault("Cut", "")))
        copy(r.uuid, root, true);
      if (ImGui.menuItem("Delete", state.user.input.shortcuts.getOrDefault("Delete", ""))) {
        beginDelete(r.uuid);
      }
      if (ImGui.menuItem(root.equals("operation") ? "Run Placement Earlier" : "Move Up in Tree"))
        reorder(r.uuid, -1);
      if (ImGui.menuItem(root.equals("operation") ? "Run Placement Later" : "Move Down in Tree"))
        reorder(r.uuid, 1);
      if (r instanceof Layer) {
        var selected =
            state.multiSelection.contains(r.uuid)
                ? Set.copyOf(state.multiSelection)
                : Set.of(r.uuid);
        boolean layersOnly =
            selected.stream()
                .allMatch(
                    id -> {
                      var entry = ResourceGraph.find(state.project(), id);
                      return entry != null && entry.resource() instanceof Layer;
                    });
        if (ImGui.menuItem("Run Layer Earlier", "", false, layersOnly))
          requestEdit(
              "Run Layer Earlier", ResourceBrowserEdits.shift(selected, -1, true), () -> {});
        if (ImGui.menuItem("Run Layer Later", "", false, layersOnly))
          requestEdit("Run Layer Later", ResourceBrowserEdits.shift(selected, 1, true), () -> {});
        ImGui.textDisabled("Tree organization does not change stage order");
      }
      if (parent != null
          && ResourceGraph.ORGANIZATIONAL_ROOTS.contains(root)
          && ImGui.menuItem("Move to Root"))
        requestEdit(
            "Move to Root", ResourceBrowserEdits.move(List.of(r.uuid), root, null), () -> {});
      ImGui.endPopup();
    }
  }

  private void drag(UUID id, String root) {
    if (ImGui.beginDragDropSource()) {
      if (!dragActive) {
        dragActive = true;
        dragProject = state.project();
        dragRevision = state.revision;
        dragRoot = root;
        dragSelection =
            state.multiSelection.contains(id) ? List.copyOf(state.multiSelection) : List.of(id);
      }
      ImGui.setDragDropPayload("resource", id.toString());
      ImGui.text("Move " + dragSelection.size() + " selected resource(s)");
      if (ResourceGraph.ORGANIZATIONAL_ROOTS.contains(root))
        ImGui.text("Folders: drop inside; hold Shift to place before. Stage order is unchanged.");
      ImGui.endDragDropSource();
    }
  }

  private void dropTarget(String root, UUID parent) {
    if (!dragRoot.equals(root)) return;
    if (ImGui.beginDragDropTarget()) {
      String value = ImGui.acceptDragDropPayload("resource", String.class);
      if (value != null && !dragSelection.isEmpty()) {
        var ids = List.copyOf(dragSelection);
        if (currentDrag())
          requestEdit("Move Resources", ResourceBrowserEdits.move(ids, root, parent), () -> {});
      }
      ImGui.endDragDropTarget();
    }
  }

  private void beginRename(UUID id) {
    if (!actionAvailable()) return;
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
      var result =
          requestEdit(
              "Rename " + resource.id + " to " + name,
              ResourceBrowserEdits.rename(id, name),
              () -> {
                renameId = null;
                renameError = "";
              });
      if (result == ResourceEditSession.Result.REJECTED)
        renameError = "ID rejected; correct it or press Escape";
    }
    ImGui.popID();
  }

  private void select(UUID id) {
    if (properties.searchBlocked() || edits.pending()) {
      state.status =
          "Finish the private Properties editor or resource action before changing selection";
      return;
    }
    if (ImGui.getIO().getKeyShift() && rangeAnchor != null) {
      var range = ResourceTreeIndex.range(selectionRows, rangeAnchor, id);
      if (!range.isEmpty()) {
        acceptSelection(selection.request(range, id), rangeAnchor);
        return;
      }
    }
    acceptSelection(selection.click(id, ImGui.getIO().getKeyCtrl()), id);
  }

  private void acceptSelection(ResourceSelectionSession.Result result, UUID anchor) {
    switch (result) {
      case SELECTED -> rangeAnchor = anchor;
      case NEEDS_DECISION -> {
        pendingAnchor = anchor;
        selectionMessage = "";
        selectionPrompt = true;
      }
      case UNAVAILABLE -> state.status = "Selection changed; choose the resource again";
      default -> {}
    }
  }

  private void selectionDialog() {
    if (selectionPrompt) {
      dialog("Leave Properties for Resource Selection")
          .open("Leave Properties for Resource Selection");
      selectionPrompt = false;
    }
    if (!dialog("Leave Properties for Resource Selection")
        .begin("Leave Properties for Resource Selection", ImGuiWindowFlags.AlwaysAutoResize))
      return;
    if (!selection.pending()) {
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
      return;
    }
    ImGui.textWrapped(
        "Apply the current Properties draft, discard it for this selection, or cancel.");
    var destinations = selection.destinations();
    ImGui.textDisabled(
        destinations.isEmpty()
            ? "Requested selection: none"
            : "Requested selection: " + destinations.size() + " sibling resource(s)");
    for (int i = 0; i < Math.min(4, destinations.size()); i++) {
      var entry = ResourceGraph.find(state.project(), destinations.get(i));
      String label = entry == null ? "Missing resource" : entry.resource().id;
      ImGui.bulletText(
          AppMetadata.uiText(label.length() > 80 ? label.substring(0, 79) + "…" : label));
    }
    if (destinations.size() > 4) ImGui.textDisabled("… and " + (destinations.size() - 4) + " more");
    if (!selectionMessage.isEmpty()) ImGui.textWrapped(selectionMessage);
    for (var decision : SearchNavigation.Decision.values()) {
      if (decision != SearchNavigation.Decision.APPLY) DialogLayout.sameLine(decision.name());
      boolean clicked = DialogLayout.button(decision.name());
      var a = ImGui.getItemRectMin();
      var b = ImGui.getItemRectMax();
      selectionButtons.put(decision.name(), new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
      if (clicked) {
        var result =
            properties.searchBlocked() && decision != SearchNavigation.Decision.CANCEL
                ? ResourceSelectionSession.Result.UNAVAILABLE
                : selection.decide(decision);
        if (result == ResourceSelectionSession.Result.NEEDS_DECISION)
          selectionMessage = "Apply failed. Cancel to correct the draft, or choose Discard.";
        else {
          if (result == ResourceSelectionSession.Result.SELECTED) rangeAnchor = pendingAnchor;
          else if (result == ResourceSelectionSession.Result.UNAVAILABLE)
            state.status = "Selection or draft changed; choose the resource again";
          selection.clear();
          pendingAnchor = null;
          ImGui.closeCurrentPopup();
        }
      }
    }
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      selection.decide(SearchNavigation.Decision.CANCEL);
      pendingAnchor = null;
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
  }

  private void rememberRow(UUID id) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    rowCenters.put(id, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
  }

  float[] probeSelectionRow(UUID id) {
    return rowCenters.get(id);
  }

  float[] probeSelectionButton(String decision) {
    return selectionButtons.get(decision);
  }

  boolean probeSelectionPending() {
    return selection.pending();
  }

  private boolean currentDrag() {
    boolean current = dragProject == state.project() && dragRevision == state.revision;
    if (!current) state.status = "Project changed during the drag; start the move again";
    return current;
  }

  private static boolean acceptedOrPending(ResourceEditSession.Result result) {
    return result == ResourceEditSession.Result.APPLIED
        || result == ResourceEditSession.Result.NEEDS_DECISION;
  }

  private ResourceEditSession.Result requestEdit(
      String label, ResourceBrowserEdits.Edit edit, Runnable committed) {
    if (!actionAvailable()) return ResourceEditSession.Result.UNAVAILABLE;
    var result = edits.request(label, edit);
    if (result == ResourceEditSession.Result.APPLIED) committed.run();
    else if (result == ResourceEditSession.Result.NEEDS_DECISION) {
      editCommitted = committed;
      editMessage = "";
      editPrompt = true;
    }
    return result;
  }

  private void editDialog() {
    if (editPrompt) {
      dialog("Properties Draft and Resource Action").open("Properties Draft and Resource Action");
      editPrompt = false;
    }
    if (!dialog("Properties Draft and Resource Action")
        .begin("Properties Draft and Resource Action", ImGuiWindowFlags.AlwaysAutoResize)) return;
    if (!edits.pending()) {
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
      return;
    }
    ImGui.textWrapped(AppMetadata.uiText("Requested action: " + edits.label()));
    ImGui.textWrapped(
        "Apply the Properties draft and this action together as one undoable edit, discard the"
            + " draft only if the action succeeds, or cancel.");
    if (!editMessage.isEmpty()) ImGui.textWrapped(editMessage);
    for (var decision : SearchNavigation.Decision.values()) {
      if (decision != SearchNavigation.Decision.APPLY) DialogLayout.sameLine(decision.name());
      boolean clicked = DialogLayout.button(decision.name());
      var a = ImGui.getItemRectMin();
      var b = ImGui.getItemRectMax();
      editButtons.put(decision.name(), new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
      if (clicked) {
        var result =
            properties.searchBlocked() && decision != SearchNavigation.Decision.CANCEL
                ? ResourceEditSession.Result.UNAVAILABLE
                : edits.decide(decision);
        if (result == ResourceEditSession.Result.REJECTED)
          editMessage =
              "The draft or action was rejected. Cancel to correct it; no changes were published.";
        else {
          if (result == ResourceEditSession.Result.APPLIED && editCommitted != null)
            editCommitted.run();
          if (result == ResourceEditSession.Result.UNAVAILABLE)
            state.status = "Project, selection or draft changed; request the resource action again";
          edits.clear();
          editCommitted = null;
          ImGui.closeCurrentPopup();
        }
      }
    }
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      edits.decide(SearchNavigation.Decision.CANCEL);
      editCommitted = null;
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
  }

  private ResourceEditSession.Result createResource(
      String kind, String root, UUID parent, String id) {
    Identity resource;
    switch (kind) {
      case "noise2D", "noise3D" ->
          resource =
              EditorCreations.noise(state.metadata, kind.equals("noise2D") ? 2 : 3, id, parent);
      case "layer" -> {
        var layer = new Layer();
        layer.id = id;
        layer.parentFolderUuid = parent;
        resource = layer;
      }
      case "folder" -> {
        var folder = new Folder();
        folder.id = id;
        folder.rootKind = root;
        folder.parentFolderUuid = parent;
        resource = folder;
      }
      case "voxel" -> resource = EditorCreations.voxel(state.metadata, id);
      case "set", "group" -> {
        var membership = new Membership();
        membership.id = id;
        resource = membership;
      }
      default -> throw new IllegalArgumentException("Unsupported resource kind " + kind);
    }
    return requestEdit(
        "Create " + kind + " " + id, ResourceBrowserEdits.insert(kind, resource), () -> {});
  }

  float[] probeEditButton(String decision) {
    return editButtons.get(decision);
  }

  boolean probeEditPending() {
    return edits.pending();
  }

  private void rememberEditButton(String key) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    editButtons.put(key, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
  }

  private void create(String kind, String root, UUID parent) {
    if (!actionAvailable()) return;
    createKind = kind;
    createRoot = root;
    createParent = parent;
    name.set("");
    createPending = true;
    createOpen = true;
  }

  private boolean actionAvailable() {
    boolean available = !properties.searchBlocked() && !selection.pending() && !edits.pending();
    if (!available) state.status = "Finish the private editor or pending resource action first";
    return available;
  }

  private void beginDelete(UUID id) {
    if (!actionAvailable()) return;
    deleteId = id;
    replacement = null;
  }

  boolean blocksProjectTransition() {
    return renameId != null
        || deleteId != null
        || createOpen
        || placementOpen
        || edits.pending()
        || selection.pending()
        || dragActive;
  }

  private void createDialog() {
    if (createPending) {
      dialog("Create Resource").open("Create Resource");
      createPending = false;
    }
    if (dialog("Create Resource").begin("Create Resource", ImGuiWindowFlags.AlwaysAutoResize)) {
      ImGui.text("New " + createKind);
      ImGui.inputText("ID", name);
      if (DialogLayout.button("Create")) {
        var result = createResource(createKind, createRoot, createParent, name.get());
        if (acceptedOrPending(result)) {
          createOpen = false;
          ImGui.closeCurrentPopup();
        }
      }
      DialogLayout.sameLine("Cancel");
      if (DialogLayout.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
        createOpen = false;
        ImGui.closeCurrentPopup();
      }
      ImGui.endPopup();
    }
  }

  private void reorder(UUID uuid, int direction) {
    Set<UUID> selection =
        state.multiSelection.contains(uuid) ? Set.copyOf(state.multiSelection) : Set.of(uuid);
    requestEdit(
        "Reorder Resources", ResourceBrowserEdits.shift(selection, direction, false), () -> {});
  }

  private void dropBefore(UUID target) {
    if (ImGui.beginDragDropTarget()) {
      String value = ImGui.acceptDragDropPayload("resource", String.class);
      if (value != null && !dragSelection.isEmpty()) {
        var ids = List.copyOf(dragSelection);
        if (currentDrag())
          requestEdit("Reorder Resources", ResourceBrowserEdits.before(ids, target), () -> {});
      }
      ImGui.endDragDropTarget();
    }
  }

  private void duplicate(String kind, Identity r, String root, UUID parent) {
    var ids =
        state.multiSelection.contains(r.uuid) ? List.copyOf(state.multiSelection) : List.of(r.uuid);
    requestEdit(
        "Duplicate " + ids.size() + " resource(s), including " + r.id,
        ResourceBrowserEdits.duplicate(ids),
        () -> {});
  }

  public ProjectData selection(boolean dependencies) {
    Set<UUID> ids =
        state.multiSelection.isEmpty() && state.selected != null
            ? Set.of(state.selected)
            : Set.copyOf(state.multiSelection);
    return ResourceGraph.selection(state.project(), ids, dependencies);
  }

  public ProjectData exportSelection() {
    return exportPanel.prepare().resources();
  }

  boolean canExport() {
    return exportPanel.canExport();
  }

  void writeExport(java.nio.file.Path path, ResourcePackageFiles files) throws java.io.IOException {
    exportPanel.write(path, files);
  }

  void cancelExport() {
    exportPanel.reset();
    exportSelection.clear();
  }

  public void beginExport() {
    exportSelection.clear();
    exportPanel.reset();
    exportSelection.addAll(state.multiSelection);
    if (exportSelection.isEmpty() && state.selected != null) exportSelection.add(state.selected);
    var live = new HashSet<UUID>();
    ResourceGraph.entries(state.project()).forEach(e -> live.add(e.uuid()));
    exportSelection.retainAll(live);
    exportPanel.open();
  }

  public void drawExportSelection() {
    exportPanel.draw();
  }

  private void copy(UUID id, String root, boolean cut) {
    try {
      var ids = state.multiSelection.contains(id) ? List.copyOf(state.multiSelection) : List.of(id);
      clipboard = new ResourceClipboard(state.project(), ids, cut);
      state.status = ids.size() + " resource(s) " + (cut ? "cut; paste to move" : "copied");
    } catch (RuntimeException invalid) {
      state.notify("Error", invalid.getMessage());
    }
  }

  private void paste(String root, UUID parent) {
    if (clipboard == null) return;
    ResourceClipboard content = clipboard;
    requestEdit(
        "Paste Resources",
        ResourceBrowserEdits.paste(content, root, parent),
        () -> {
          if (content.cut() && clipboard == content) clipboard = null;
        });
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
      dialog("Delete Resource").open("Delete Resource");
    if (dialog("Delete Resource").begin("Delete Resource", ImGuiWindowFlags.AlwaysAutoResize)) {
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
      boolean canDelete = deps.isEmpty() || replacement != null;
      boolean deleteClicked = canDelete && DialogLayout.button("Delete");
      if (canDelete) rememberEditButton("confirmDelete");
      if (deleteClicked) {
        UUID id = deleteId, target = replacement;
        var result =
            requestEdit(
                "Delete Resource", ResourceBrowserEdits.delete(id, target), () -> deleteId = null);
        if (acceptedOrPending(result)) {
          deleteId = null;
          ImGui.closeCurrentPopup();
        }
      }
      DialogLayout.sameLine("Cancel");
      if (DialogLayout.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
        deleteId = null;
        ImGui.closeCurrentPopup();
      }
      ImGui.endPopup();
    }
  }

  private final Map<String, DialogLayout> dialogs = new HashMap<>();

  private DialogLayout dialog(String title) {
    return dialogs.computeIfAbsent(
        title,
        key ->
            new DialogLayout(
                state,
                "Resources",
                title.equals("Add Noise Generator") ? 560 : 620,
                title.equals("Add Noise Generator") ? 430 : 300));
  }

  private final ImBoolean manager = new ImBoolean(false);

  public void showManager() {
    manager.set(true);
  }

  private void manager() {
    if (!manager.get()) return;
    refreshBrowser();
    managerLayout.prepare();
    boolean visible = ImGui.begin("Voxel Manager", manager);
    UiScale.window(state.user, "Voxel Manager");
    managerLayout.observe();
    if (!visible || !manager.get()) {
      submittedManagerRows = 0;
      ImGui.end();
      return;
    }
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
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
    if (DialogLayout.button(createLabel)) create(kind, kind, null);
    String key = kind + ":" + managerFilter.get();
    if (!key.equals(managerKey)) {
      managerKey = key;
      managerRows = browser.kind(kind, managerFilter.get());
      managerSelectionRows =
          managerRows.stream().map(n -> new ResourceTreeIndex.Row(n, 0, false)).toList();
    }
    selectionRows = managerSelectionRows;
    ImGui.textWrapped(managerRows.size() + " matching " + label.toLowerCase(Locale.ROOT));
    ImGui.beginChild(
        "manager-rows",
        0,
        Math.max(1, ImGui.getContentRegionAvailY() - ImGui.getFrameHeightWithSpacing()),
        true);
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
        && DialogLayout.button("Find References to Selected")) {
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

  void smokeOrganize() {
    var before = PreviewIdentity.of(state.project(), 2, null);
    var execution =
        state.project().worldGenerator.layers.stream().map(layer -> layer.uuid).toList();
    UUID[] worldFolder = {null};
    if (!state.change(
        p -> {
          var world = new Folder();
          world.id = "world-organization";
          world.rootKind = "world";
          p.folders.add(world);
          worldFolder[0] = world.uuid;
          var layer = p.worldGenerator.layers.getFirst();
          ResourceGraph.move(p, List.of(layer.uuid), "world", world.uuid);
          var noise = new Folder();
          noise.id = "noise-organization";
          noise.rootKind = "noise2D";
          p.folders.add(noise);
          p.project.rootOrder.put(
              "noise2D", new ArrayList<>(List.of(p.noise2D.getFirst().uuid, noise.uuid)));
        })) throw new AssertionError(state.issues);
    refreshBrowser();
    treeView.open(worldFolder[0], true);
    if (!execution.equals(
            state.project().worldGenerator.layers.stream().map(layer -> layer.uuid).toList())
        || !before.equals(PreviewIdentity.of(state.project(), 2, null)))
      throw new AssertionError("Organization changed computation identity or execution order");
    try {
      var path = UserConfig.directory().resolve("hierarchy-smoke-project.json");
      new ProjectIO().save(path, state.project());
      if (!new io.voxelcreator.api.ProjectLoader().load(path).success())
        throw new AssertionError("Organized smoke project did not reload");
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
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

  void probeOpenFolder(UUID id) {
    refreshBrowser();
    treeView.open(id, true);
  }
}
