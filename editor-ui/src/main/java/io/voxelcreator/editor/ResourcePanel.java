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
  private ResourceFormOwner createOwner, placementOwner, deleteOwner;
  final SettingsPageChrome createChrome = new SettingsPageChrome(2),
      placementChrome = new SettingsPageChrome(2),
      deleteChrome = new SettingsPageChrome(2);
  private final DraftChoicePicker deletePicker;
  private List<DraftChoicePicker.Choice> deleteChoices = List.of();
  private List<String> deleteDependents = List.of();
  private String deleteLabel = "", createMessage = "", placementMessage = "", deleteMessage = "";
  private boolean createFocus, createReveal, placementReveal, deleteReveal;
  private boolean placementListReveal, deleteListReveal;
  private int placementSubmitted, deleteSubmitted;
  private DialogBounds.Rect createInput;

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
  private ResourceEditCompletion editCommitted;
  final NavigationGuardDialog selectionGuard, editGuard;
  private final Map<String, float[]> editButtons = new HashMap<>();
  private ProjectData dragProject;
  private long dragRevision;
  private boolean dragActive;
  private boolean selectionPrompt;
  private UUID pendingAnchor;
  private final Map<UUID, float[]> rowCenters = new HashMap<>();

  public ResourcePanel(EditorState s, PropertyPanel properties) {
    state = s;
    deletePicker =
        new DraftChoicePicker(
            s, () -> deleteOwner, () -> deleteOwner != null && deleteOwner.current(state) ? 0 : 1);
    this.properties = properties;
    exportPanel = new ResourceExportPanel(s, exportSelection);
    selection = new ResourceSelectionSession(s);
    edits = new ResourceEditSession(s);
    selectionGuard = new NavigationGuardDialog(s, "Resources", 620, 300);
    editGuard = new NavigationGuardDialog(s, "Resources", 620, 300);
  }

  public void reset() {
    closeCreate();
    closePlacement();
    closeDelete();
    edits.clear();
    editPrompt = false;
    if (editCommitted != null) editCommitted.clear();
    editCommitted = null;
    editButtons.clear();
    dragProject = null;
    dragActive = false;
    selection.clear();
    selectionPrompt = false;
    pendingAnchor = null;
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
      UiScale.beginChild(
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
    if (!formAvailable() || !(EditorState.find(state.project(), layer) instanceof Layer)) return;
    placementOwner = new ResourceFormOwner(state);
    placementLayer = layer;
    placementNoise = null;
    placementFilter.set("");
    placementKey = null;
    placementMessage = "";
    placementReveal = placementListReveal = true;
    placementPending = placementOpen = true;
  }

  private void placementDialog() {
    String title = "Add Noise Generator";
    placementSubmitted = 0;
    if (!placementOpen) {
      closeInactive(title);
      return;
    }
    if (placementPending) {
      dialog(title).open(title);
      placementPending = false;
    }
    if (!dialog(title).begin(title)) return;
    boolean current = placementOwner.current(state);
    placementChrome.begin("placement-body");
    if (placementReveal) {
      ImGui.setScrollY(0);
      placementReveal = false;
    }
    ImGui.pushID(placementOwner.nativeId());
    staleForm(current);
    ImGui.beginDisabled(!current);
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    ImGui.inputTextWithHint("##placement-filter", "Find noise by ID...", placementFilter);
    if (!Objects.equals(placementKey, placementFilter.get())) {
      placementKey = placementFilter.get();
      placementRows = browser.noises(placementKey);
      placementListReveal = true;
    }
    ImGui.textWrapped(placementRows.size() + " matching noise generator(s)");
    if (!placementMessage.isEmpty()) ImGui.textWrapped(placementMessage);
    ImGui.popID();
    UiScale.beginChild(
        "placement-options", 0, ResourceReviewLayout.content(ImGui.getContentRegionAvailY()), true);
    if (placementListReveal) {
      ImGui.setScrollY(0);
      placementListReveal = false;
    }
    ImGui.pushID(placementOwner.nativeId());
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(placementRows.size(), ImGui.getTextLineHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var node = placementRows.get(i);
          placementSubmitted++;
          String label =
              StatusBarText.clip(node.id(), 512)
                  + " ["
                  + (node.kind().equals("noise2D") ? "2D" : "3D")
                  + "]";
          if (ImGui.selectable(
              AppMetadata.uiText(label) + "###" + node.uuid(), node.uuid().equals(placementNoise)))
            placementNoise = node.uuid();
          PopupLayout.clippedLabel(node.id());
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.popID();
    ImGui.endChild();
    ImGui.endDisabled();
    placementChrome.end();
    ImGui.pushID(placementOwner.nativeId());
    ImGui.beginDisabled(!current || placementNoise == null || browser.get(placementLayer) == null);
    boolean add = placementChrome.identifiedAction("primary", "Add Placement");
    ImGui.endDisabled();
    boolean cancel =
        placementChrome.identifiedAction("cancel", "Cancel")
            || ImGui.isKeyPressed(ImGuiKey.Escape, false);
    ImGui.popID();
    if (cancel) {
      closePlacement();
      ImGui.closeCurrentPopup();
    } else if (add && placementOwner.current(state)) {
      var result =
          requestEdit(
              "Add Placement",
              ResourceBrowserEdits.placement(placementLayer, placementNoise),
              () -> {});
      if (acceptedOrPending(result)) {
        closePlacement();
        ImGui.closeCurrentPopup();
      } else placementMessage = "Placement rejected. Check the selected resources or cancel.";
    }
    ImGui.endPopup();
  }

  private void rootMenu(String kind, UUID parent) {
    if (UiScale.beginPopupContextItem("root-menu" + kind + parent)) {
      if (ImGui.menuItem(kind.equals("world") ? "Create Layer" : "Create Noise Generator"))
        create(kind.equals("world") ? "layer" : kind, kind, parent);
      if (ImGui.menuItem("Create Folder")) create("folder", kind, parent);
      if (ImGui.menuItem("Paste", clipboard != null && clipboard.root().equals(kind)))
        paste(kind, parent);
      ImGui.endPopup();
    }
  }

  private void rowMenu(String kind, Identity r, String root, UUID parent) {
    if (UiScale.beginPopupContextItem("resource-menu" + r.uuid)) {
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
    if (UiScale.beginDragDropSource()) {
      try {
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
      } finally {
        UiScale.endDragDropSource();
      }
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
        selectionPrompt = true;
      }
      case UNAVAILABLE -> state.status = "Selection changed; choose the resource again";
      default -> {}
    }
  }

  private void selectionDialog() {
    String title = "Leave Properties for Resource Selection";
    if (!selection.pending()) {
      selectionPrompt = false;
      selectionGuard.close(title);
      return;
    }
    if (selectionPrompt) {
      selectionGuard.open(title);
      selectionPrompt = false;
    }
    selectionGuard.draw(
        title,
        () -> {
          ImGui.textWrapped(
              "Apply the current Properties draft, discard it for this selection, or cancel.");
          var destinations = selection.destinations();
          ImGui.textWrapped(
              destinations.isEmpty()
                  ? "Requested selection: none"
                  : "Requested selection: " + destinations.size() + " sibling resource(s)");
          for (int i = 0; i < Math.min(4, destinations.size()); i++) {
            var entry = ResourceGraph.find(state.project(), destinations.get(i));
            ImGui.textWrapped(
                AppMetadata.uiText(
                    StatusBarText.clip(
                        entry == null ? "Missing resource" : entry.resource().id, 80)));
          }
          if (destinations.size() > 4)
            ImGui.textWrapped("... and " + (destinations.size() - 4) + " more");
        },
        decision -> {
          var result =
              properties.searchBlocked() && decision != SearchNavigation.Decision.CANCEL
                  ? ResourceSelectionSession.Result.UNAVAILABLE
                  : selection.decide(decision);
          if (result == ResourceSelectionSession.Result.NEEDS_DECISION)
            return SearchNavigation.Result.NEEDS_DECISION;
          if (result == ResourceSelectionSession.Result.SELECTED) rangeAnchor = pendingAnchor;
          else if (result == ResourceSelectionSession.Result.UNAVAILABLE)
            state.status = "Selection or draft changed; choose the resource again";
          selection.clear();
          pendingAnchor = null;
          return result == ResourceSelectionSession.Result.SELECTED
              ? SearchNavigation.Result.NAVIGATED
              : SearchNavigation.Result.UNAVAILABLE;
        },
        "Apply failed. Cancel to correct the draft, or choose Discard.");
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
    return selectionGuard.button(decision);
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
    if (result == ResourceEditSession.Result.APPLIED)
      new ResourceEditCompletion(state, committed).complete();
    else if (result == ResourceEditSession.Result.NEEDS_DECISION) {
      editCommitted = new ResourceEditCompletion(state, committed);
      editPrompt = true;
    }
    return result;
  }

  private void editDialog() {
    String title = "Properties Draft and Resource Action";
    if (!edits.pending()) {
      editPrompt = false;
      if (editCommitted != null) editCommitted.clear();
      editCommitted = null;
      editGuard.close(title);
      return;
    }
    if (editPrompt) {
      editGuard.open(title);
      editPrompt = false;
    }
    editGuard.draw(
        title,
        () -> {
          ImGui.textWrapped(AppMetadata.uiText("Requested action: " + edits.label()));
          ImGui.textWrapped(
              "Apply the Properties draft and this action together as one undoable edit, discard"
                  + " the draft only if the action succeeds, or cancel.");
        },
        decision -> {
          var result =
              properties.searchBlocked() && decision != SearchNavigation.Decision.CANCEL
                  ? ResourceEditSession.Result.UNAVAILABLE
                  : edits.decide(decision);
          if (result == ResourceEditSession.Result.REJECTED)
            return SearchNavigation.Result.NEEDS_DECISION;
          var completion = editCommitted;
          editCommitted = null;
          edits.clear();
          if (result == ResourceEditSession.Result.APPLIED && completion != null)
            completion.complete();
          else if (completion != null) completion.clear();
          if (result == ResourceEditSession.Result.UNAVAILABLE)
            state.status = "Project, selection or draft changed; request the resource action again";
          return result == ResourceEditSession.Result.APPLIED
              ? SearchNavigation.Result.NAVIGATED
              : SearchNavigation.Result.UNAVAILABLE;
        },
        "The draft or action was rejected. Cancel to correct it; no changes were published.");
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
    return Set.of("APPLY", "DISCARD", "CANCEL").contains(decision)
        ? editGuard.button(decision)
        : editButtons.get(decision);
  }

  boolean probeEditPending() {
    return edits.pending();
  }

  void probeRequestSelection(UUID id) {
    acceptSelection(selection.click(id, false), id);
  }

  ResourceEditSession.Result probeRequestEdit(
      String label, ResourceBrowserEdits.Edit edit, Runnable committed) {
    return requestEdit(label, edit, committed);
  }

  void probeDrawDecisions() {
    selectionDialog();
    editDialog();
  }

  private void rememberEditButton(String key) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    editButtons.put(key, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
  }

  private void create(String kind, String root, UUID parent) {
    if (!formAvailable()) return;
    createOwner = new ResourceFormOwner(state);
    createKind = kind;
    createRoot = root;
    createParent = parent;
    name.set("");
    createMessage = "";
    createPending = createOpen = createFocus = createReveal = true;
  }

  private boolean formAvailable() {
    return !createOpen && !placementOpen && deleteId == null && actionAvailable();
  }

  private boolean actionAvailable() {
    boolean available = !properties.searchBlocked() && !selection.pending() && !edits.pending();
    if (!available) state.status = "Finish the private editor or pending resource action first";
    return available;
  }

  private void beginDelete(UUID id) {
    if (!formAvailable()) return;
    var source = ResourceGraph.find(state.project(), id);
    if (source == null) return;
    deleteOwner = new ResourceFormOwner(state);
    deleteId = id;
    replacement = null;
    deleteMessage = "";
    deleteReveal = deleteListReveal = true;
    var deleted = ResourceGraph.subtree(state.project(), List.of(id));
    deleteLabel =
        "Delete "
            + source.resource().id
            + " and "
            + (deleted.size() - 1)
            + " contained resource(s)?";
    deleteDependents =
        ResourceGraph.dependents(state.project(), deleted).stream()
            .map(e -> e.kind() + " / " + e.resource().id)
            .toList();
    deleteChoices =
        source.resource() instanceof Folder
            ? List.of()
            : ResourceGraph.entries(state.project()).stream()
                .filter(e -> e.kind().equals(source.kind()) && !deleted.contains(e.uuid()))
                .map(
                    e ->
                        new DraftChoicePicker.Choice(
                            e.uuid().toString(), e.resource().id, e.kind()))
                .toList();
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
    String title = "Create Resource";
    if (!createOpen) {
      closeInactive(title);
      return;
    }
    if (createPending) {
      dialog(title).open(title);
      createPending = false;
    }
    if (!dialog(title).begin(title)) return;
    boolean current = createOwner.current(state);
    createChrome.begin("create-body");
    if (createReveal) {
      ImGui.setScrollY(0);
      createReveal = false;
    }
    ImGui.pushID(createOwner.nativeId());
    ImGui.textWrapped("New " + createKind);
    staleForm(current);
    ImGui.beginDisabled(!current);
    ImGui.text("ID");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    boolean focus = createFocus && current;
    if (focus) ImGui.setKeyboardFocusHere();
    ImGui.inputText("##resource-id", name);
    if (focus) {
      ImGui.setScrollHereY(.25f);
      createFocus = false;
    }
    createInput = PopupLayout.itemBounds();
    ImGui.endDisabled();
    if (!createMessage.isEmpty()) ImGui.textWrapped(createMessage);
    ImGui.popID();
    createChrome.end();
    ImGui.pushID(createOwner.nativeId());
    ImGui.beginDisabled(!current);
    boolean create = createChrome.identifiedAction("primary", "Create");
    ImGui.endDisabled();
    boolean cancel =
        createChrome.identifiedAction("cancel", "Cancel")
            || ImGui.isKeyPressed(ImGuiKey.Escape, false);
    ImGui.popID();
    if (cancel) {
      closeCreate();
      ImGui.closeCurrentPopup();
    } else if (create && createOwner.current(state)) {
      var result = createResource(createKind, createRoot, createParent, name.get());
      if (acceptedOrPending(result)) {
        closeCreate();
        ImGui.closeCurrentPopup();
      } else {
        createMessage = "Creation rejected. Correct the ID or cancel; no changes were published.";
        createFocus = createReveal = true;
      }
    }
    ImGui.endPopup();
  }

  private void closeCreate() {
    if (createOwner != null) createOwner.close();
    createOwner = null;
    createOpen = createPending = createFocus = false;
    createMessage = "";
  }

  private void closePlacement() {
    if (placementOwner != null) placementOwner.close();
    placementOwner = null;
    placementOpen = placementPending = false;
    placementLayer = placementNoise = null;
    placementMessage = "";
  }

  private void closeDelete() {
    if (deleteOwner != null) deleteOwner.close();
    deleteOwner = null;
    deleteId = replacement = null;
    deleteMessage = "";
    deleteChoices = List.of();
    deleteDependents = List.of();
  }

  private void closeInactive(String title) {
    if (dialog(title).begin(title)) {
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
    }
  }

  private static void staleForm(boolean current) {
    if (!current) ImGui.textWrapped("Project changed; Cancel and reopen this form.");
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

  ResourceExportPanel exportView() {
    return exportPanel;
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
    String title = "Delete Resource";
    deleteSubmitted = 0;
    if (deleteId == null) {
      closeInactive(title);
      return;
    }
    if (!ImGui.isPopupOpen(title)) dialog(title).open(title);
    if (!dialog(title).begin(title)) return;
    boolean current = deleteOwner.current(state);
    deleteChrome.begin("delete-body");
    if (deleteReveal) {
      ImGui.setScrollY(0);
      deleteReveal = false;
    }
    ImGui.pushID(deleteOwner.nativeId());
    ImGui.textWrapped(StatusBarText.clip(deleteLabel, 1024));
    staleForm(current);
    if (!deleteDependents.isEmpty()) {
      ImGui.textWrapped(deleteDependents.size() + " external dependent(s)");
      ImGui.beginDisabled(!current);
      if (!deleteChoices.isEmpty()) {
        deletePicker.draw(
            "Replace references with",
            replacement == null ? null : replacement.toString(),
            deleteChoices,
            null,
            value -> replacement = UUID.fromString(value));
        ImGui.textWrapped("References and deletion commit together as one undoable edit.");
      } else
        ImGui.textWrapped(
            "Edit external references before deleting this resource or folder subtree.");
      ImGui.endDisabled();
    }
    if (!deleteMessage.isEmpty()) ImGui.textWrapped(deleteMessage);
    ImGui.popID();
    UiScale.beginChild(
        "delete-dependents", 0, ResourceReviewLayout.content(ImGui.getContentRegionAvailY()), true);
    if (deleteListReveal) {
      ImGui.setScrollY(0);
      deleteListReveal = false;
    }
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(deleteDependents.size(), ImGui.getTextLineHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          String label = deleteDependents.get(i);
          deleteSubmitted++;
          ImGui.text(StatusBarText.clip(label, 512));
          PopupLayout.clippedLabel(label);
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    deleteChrome.end();
    ImGui.pushID(deleteOwner.nativeId());
    boolean canDelete = current && (deleteDependents.isEmpty() || replacement != null);
    ImGui.beginDisabled(!canDelete);
    boolean delete = deleteChrome.identifiedAction("primary", "Delete");
    rememberEditButton("confirmDelete");
    ImGui.endDisabled();
    boolean cancel =
        deleteChrome.identifiedAction("cancel", "Cancel")
            || ImGui.isKeyPressed(ImGuiKey.Escape, false);
    ImGui.popID();
    if (cancel) {
      closeDelete();
      ImGui.closeCurrentPopup();
    } else if (delete && deleteOwner.current(state)) {
      var result =
          requestEdit(
              "Delete Resource", ResourceBrowserEdits.delete(deleteId, replacement), () -> {});
      if (acceptedOrPending(result)) {
        closeDelete();
        ImGui.closeCurrentPopup();
      } else {
        deleteMessage =
            "Deletion rejected. Correct the replacement or cancel; no changes were published.";
        deleteReveal = true;
      }
    }
    ImGui.endPopup();
  }

  void probeOpenForm(String form, UUID target) {
    refreshBrowser();
    switch (form) {
      case "Create" -> create("noise2D", "noise2D", null);
      case "Placement" -> beginPlacement(target);
      case "Delete" -> beginDelete(target);
      default -> throw new IllegalArgumentException(form);
    }
  }

  void probeDrawForms() {
    refreshBrowser();
    createDialog();
    placementDialog();
    deleteDialog();
  }

  void probeFormName(String value) {
    name.set(value);
  }

  void probePlacement(UUID id) {
    placementNoise = id;
  }

  void probeReplacement(UUID id) {
    replacement = id;
  }

  boolean probeFormOpen(String form) {
    return form.equals("Create")
        ? createOpen
        : form.equals("Placement") ? placementOpen : deleteId != null;
  }

  SettingsPageChrome probeFormChrome(String form) {
    return form.equals("Create")
        ? createChrome
        : form.equals("Placement") ? placementChrome : deleteChrome;
  }

  DialogLayout probeFormLayout(String form) {
    return dialog(
        form.equals("Create")
            ? "Create Resource"
            : form.equals("Placement") ? "Add Noise Generator" : "Delete Resource");
  }

  String probeFormId(String form) {
    var owner =
        form.equals("Create")
            ? createOwner
            : form.equals("Placement") ? placementOwner : deleteOwner;
    return owner == null ? "" : owner.nativeId();
  }

  int probeFormSubmitted(String form) {
    return form.equals("Placement") ? placementSubmitted : deleteSubmitted;
  }

  DraftChoicePicker probeDeletePicker() {
    return deletePicker;
  }

  DialogBounds.Rect probeCreateInput() {
    return createInput;
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
    UiScale.beginChild(
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
            UiScale.colorButton(
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
