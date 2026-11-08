package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiCond;
import imgui.type.ImString;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Execution-order overview with independent selection and explicit guarded Properties navigation. */
final class WorldEditorPanel {
  private final EditorState state;
  private final BiConsumer<EditorEvents.Target, BooleanSupplier> edit;
  private final Consumer<UUID> references;
  private final BooleanSupplier blocked;
  private final ImString filter = new ImString(256);
  private final FloatingWindowLayout layout = new FloatingWindowLayout(420, 560);
  final PanelDockControls docking = new PanelDockControls("World Editor");
  final WorldEditorSelection selection = new WorldEditorSelection();
  private final SettingsPageChrome chrome = new SettingsPageChrome(2);
  private final ContextHelp help;
  private boolean focus;
  private int submitted;
  private final Map<String, float[]> controls = new HashMap<>();

  WorldEditorPanel(
      EditorState state,
      BiConsumer<EditorEvents.Target, BooleanSupplier> edit,
      Consumer<UUID> references) {
    this(state, edit, references, () -> false);
  }

  WorldEditorPanel(
      EditorState state,
      BiConsumer<EditorEvents.Target, BooleanSupplier> edit,
      Consumer<UUID> references,
      BooleanSupplier blocked) {
    this.state = state;
    this.edit = edit;
    this.references = references;
    this.blocked = blocked;
    help = new ContextHelp(state.helpRequests);
  }

  void reveal() {
    focus = true;
  }

  void draw() {
    controls.clear();
    submitted = 0;
    if (selection.synchronize(state)) filter.set("");
    layout.prepare();
    docking.prepare();
    if (focus) {
      ImGui.setNextWindowFocus();
      ImGui.setNextWindowCollapsed(false, ImGuiCond.Always);
    }
    focus = false;
    boolean visible = ImGui.begin("World Editor");
    UiScale.window(state.user, "World Editor");
    layout.observe();
    if (visible) {
      docking.draw();
      chrome.begin("world-editor-body");
      ImGui.textWrapped("Execution order: layers run top to bottom, then their placements in order.");
      help.item("help.world-selection");
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      ImGui.inputTextWithHint("##world-filter", "Filter layers, placements or noise resources...", filter);
      remember("filter");
      ImGui.textWrapped(
          selection.selected().size()
              + " selected here. Ctrl toggles siblings; Shift selects a visible range."
              + " Resources selection and Properties input stay separate.");
      if (selection.selected().size() > 1)
        ImGui.textWrapped("Selection summary only. Select one item to edit its properties.");
      var rows = selection.rows(filter.get());
      if (rows.isEmpty())
        ImGui.textWrapped(
            "No matching layers or placements. Add layers and noise placements in Resources.");
      else drawRows(rows);
      chrome.end();
      var ticket = selection.ticket();
      ImGui.pushID("world-actions:" + selection.version());
      ImGui.beginDisabled(ticket == null || blocked.getAsBoolean());
      boolean open = chrome.identifiedAction("edit", "Edit Properties");
      remember("edit");
      boolean find = chrome.identifiedAction("references", "Find References");
      remember("references");
      ImGui.endDisabled();
      ImGui.popID();
      if (ticket != null && !blocked.getAsBoolean() && selection.current(state, ticket)) {
        if (open)
          edit.accept(
              new EditorEvents.Target(
                  state.project().project.uuid, ticket.resource(), null, null, "", false),
              () -> selection.current(state, ticket));
        else if (find) references.accept(ticket.resource());
      }
    }
    ImGui.end();
  }

  private void drawRows(List<WorldEditorSelection.Row> rows) {
    var selected = selection.selected();
    float stride =
        ResourceReviewLayout.membershipStride(
            ImGui.getFrameHeight(), ImGui.getStyle().getItemSpacingY());
    boolean visible =
        UiScale.beginChild("world-execution-list", 0, Math.max(1, ImGui.getContentRegionAvailY()), true);
    if (!visible) {
      ImGui.endChild();
      return;
    }
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size(), stride);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = rows.get(i);
          submitted++;
          float top = ImGui.getCursorPosY();
          String label =
              (row.layerRow() ? "Layer " : "    Placement ")
                  + row.stage() + ": " + row.id()
                  + (row.layerRow() ? "" : " / " + row.source())
                  + (row.enabled() ? "" : " [disabled]");
          String widget = AppMetadata.uiText(StatusBarText.clip(label, 200)) + "###" + row.uuid();
          if (ImGui.selectable(
              widget,
              selected.contains(row.uuid()),
              0,
              Math.max(1, ImGui.getContentRegionAvailX()),
              ImGui.getFrameHeight()))
            selection.select(
                row.uuid(), ImGui.getIO().getKeyCtrl(), ImGui.getIO().getKeyShift(), rows);
          remember(row.uuid().toString());
          if (ImGui.isItemHovered())
            UiScale.tooltip(StatusBarText.clip(label, 4096) + "\nUUID: " + row.uuid());
          ImGui.setCursorPosY(top + stride - ImGui.getStyle().getItemSpacingY());
          ImGui.dummy(0, 0);
        }
    } finally {
      clipper.end();
      clipper.destroy();
      ImGui.endChild();
    }
  }

  private void remember(String key) {
    controls.put(
        key,
        new float[] {
          (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
          (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
        });
  }

  float[] control(String key) {
    return controls.get(key);
  }

  Map<String, Object> receipt() {
    return Map.of(
        "window", layout.bounds(), "body", chrome.body(), "actions", chrome.actions(),
        "submitted", submitted, "selected", selection.selected().size());
  }
}
