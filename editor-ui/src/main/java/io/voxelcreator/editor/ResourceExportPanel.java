package io.voxelcreator.editor;

import imgui.*;
import imgui.type.*;
import io.voxelcreator.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Owned export pages; only a visible exact Included preview acknowledges file publication. */
public final class ResourceExportPanel {
  private final EditorState state;
  private final Set<UUID> selected;
  private ExportEditSession edit;
  private final ImString filter = new ImString(256);
  private final ExportRows projection = new ExportRows();
  private boolean selectedOnly, revealPage;
  private String message = "", section = "Choices";
  private int submitted, includedRows;
  private final Map<String, float[]> controls = new LinkedHashMap<>();
  private final Map<UUID, Integer> rowIds = new LinkedHashMap<>();
  final SettingsPageChrome chrome = new SettingsPageChrome(3, 2);
  private DialogBounds.Rect listBounds;
  private float rowStride, rowFrame, rowLine, rowGap;

  public ResourceExportPanel(EditorState state, Set<UUID> selected) {
    this.state = Objects.requireNonNull(state);
    this.selected = Objects.requireNonNull(selected);
  }

  void open() {
    if (edit == null) edit = new ExportEditSession(state, selected);
  }

  public ResourceExportSession prepare() {
    open();
    edit.select(selected);
    return edit.preparedModel();
  }

  boolean canExport() {
    return edit != null && selected.equals(edit.selected()) && edit.canExport();
  }

  boolean current() {
    return edit != null && edit.current();
  }

  boolean canReload() {
    return edit != null && !edit.current() && edit.canReload();
  }

  int unavailable() {
    return edit == null ? 0 : edit.unavailableCount();
  }

  void write(Path destination, ResourcePackageFiles files) throws IOException {
    open();
    edit.select(selected);
    edit.write(destination, files);
  }

  void reload() {
    open();
    edit.reload();
    projection.clear();
    message = "";
    revealPage = true;
  }

  void removeUnavailable() {
    if (!current()) return;
    selected.removeIf(id -> edit.catalog().get(id) == null);
    edit.select(selected);
  }

  public void reset() {
    if (edit != null) edit.cancel();
    edit = null;
    filter.set("");
    selectedOnly = false;
    projection.clear();
    message = "";
    section = "Choices";
    revealPage = true;
    controls.clear();
    rowIds.clear();
    listBounds = null;
  }

  /** Standalone composition retained for callers that host this panel without the package modal. */
  public void draw() {
    chrome.begin("export-body");
    drawPage(section);
    chrome.end();
    boolean choices = action("choices", "Choices", true);
    boolean included = action("included", "Included", true);
    boolean issues = action("issues", "Issues", true);
    boolean reload = action("reload", "Reload", canReload());
    boolean remove = action("remove-unavailable", "Remove Missing", current() && unavailable() > 0);
    if (reload) {
      try {
        reload();
      } catch (RuntimeException failure) {
        fail(failure);
      }
    } else if (remove) removeUnavailable();
    else if (choices) page("Choices");
    else if (included) page("Included");
    else if (issues) page("Issues");
  }

  void page(String value) {
    if (!Set.of("Choices", "Included", "Issues").contains(value))
      throw new IllegalArgumentException("Unknown export page");
    if (!section.equals(value)) {
      section = value;
      revealPage = true;
    }
  }

  /** The package dialog supplies the scrolling body and persistent actions. */
  void drawPage(String value) {
    page(value);
    controls.clear();
    rowIds.clear();
    submitted = includedRows = 0;
    listBounds = null;
    if (revealPage) {
      ImGui.setScrollY(0);
      revealPage = false;
    }
    try {
      open();
      if (edit.current()) edit.select(selected);
    } catch (RuntimeException failure) {
      fail(failure);
      ImGui.textWrapped("! " + bounded(message));
      return;
    }
    if (edit.closed()) {
      ImGui.textWrapped("This export was consumed. Cancel and reopen Export.");
      return;
    }
    if (section.equals("Choices")) drawChoices();
    else if (section.equals("Included")) drawIncluded();
    else drawIssues();
  }

  private void drawChoices() {
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    ImGui.inputTextWithHint("##export-filter", "Filter resource ID, kind or UUID...", filter);
    remember("filter");
    var only = new ImBoolean(selectedOnly);
    if (ImGui.checkbox("Selected only", only)) selectedOnly = only.get();
    remember("selected-only");
    var rows = projection.rows(edit.catalog(), filter.get(), selectedOnly, selected);
    ImGui.textWrapped(selected.size() + " selected");
    if (ImGui.isItemHovered()) UiScale.tooltip(rows.size() + " matching catalog resources");
    if (!edit.current())
      ImGui.textWrapped("! Source changed. Reload before choosing or exporting.");
    if (unavailable() > 0)
      ImGui.textWrapped("! Missing selections: " + unavailable() + ". Remove Missing explicitly.");
    boolean visible = beginList("export-choices", true);
    rowStride = ResourceReviewLayout.membershipStride(rowFrame, rowGap);
    ImGui.beginDisabled(!edit.current());
    if (visible) {
      var clipper = new ImGuiListClipper();
      try {
        clipper.begin(rows.size(), rowStride);
        while (clipper.step())
          for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
            var node = rows.get(i);
            float top = ImGui.getCursorPosY();
            String label =
                AppMetadata.uiText(bounded(node.kind() + " / " + node.id())) + "###" + node.uuid();
            var chosen = new ImBoolean(selected.contains(node.uuid()));
            if (ImGui.checkbox(label, chosen)) {
              if (chosen.get()) selected.add(node.uuid());
              else selected.remove(node.uuid());
            }
            rowIds.put(node.uuid(), ImGui.getID(label));
            remember(node.uuid().toString());
            tooltip(node);
            tail(top);
            submitted++;
          }
        clipper.end();
      } finally {
        clipper.destroy();
      }
    }
    ImGui.endDisabled();
    ImGui.endChild();
    if (edit.current()) edit.select(selected);
  }

  private void drawIncluded() {
    var preview = edit.preview();
    if (!edit.current())
      ImGui.textWrapped(
          "! This closure belongs to the previous source. Reload and review its successor.");
    if (preview == null) {
      ImGui.textWrapped(
          "No export preview. Choose current resources or explicitly remove missing selections.");
      return;
    }
    ImGui.textWrapped("Included " + preview.included().size() + " resources");
    boolean visible = beginList("export-included", true);
    rowStride = ResourceReviewLayout.membershipStride(rowLine, rowGap);
    if (visible) {
      var clipper = new ImGuiListClipper();
      try {
        clipper.begin(preview.included().size(), rowStride);
        while (clipper.step())
          for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
            var node = preview.included().get(i);
            float top = ImGui.getCursorPosY();
            ImGui.textUnformatted(
                AppMetadata.uiText(
                    bounded(
                        (preview.selected().contains(node.uuid()) ? "Selected: " : "Required: ")
                            + node.kind()
                            + " / "
                            + node.id())));
            tooltip(node);
            tail(top);
            includedRows++;
          }
        clipper.end();
      } finally {
        clipper.destroy();
      }
    }
    ImGui.endChild();
    if (visible) edit.markShown(preview);
  }

  private void drawIssues() {
    ImGui.textWrapped(
        "Export uses committed resources. Unapplied Properties input remains private. Visit"
            + " Included after changing the selection or reloading the source, then choose an"
            + " explicit output path.");
    if (!edit.current()) {
      ImGui.textWrapped(
          "The source changed. Reload retains selected UUIDs and prepares a new preview; it must be"
              + " displayed before exporting.");
      if (!edit.canReload())
        ImGui.textWrapped("The project was replaced. Cancel and reopen Export.");
    }
    if (unavailable() > 0)
      ImGui.textWrapped(
          "Missing selections: "
              + unavailable()
              + ". Remove Missing removes only unavailable UUIDs.");
    if (!message.isEmpty()) ImGui.textWrapped("! " + bounded(message));
    if (edit.current() && unavailable() == 0 && message.isEmpty())
      ImGui.textWrapped("No export problems reported.");
  }

  private boolean beginList(String id, boolean border) {
    boolean visible =
        UiScale.beginChild(
            id, 0, ResourceReviewLayout.content(ImGui.getContentRegionAvailY()), border);
    listBounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    rowFrame = ImGui.getFrameHeight();
    rowLine = ImGui.getTextLineHeight();
    rowGap = ImGui.getStyle().getItemSpacingY();
    return visible;
  }

  private void tail(float top) {
    ImGui.setCursorPosY(top + rowStride - ImGui.getStyle().getItemSpacingY());
    ImGui.dummy(0, 0);
  }

  private static String bounded(String text) {
    return StatusBarText.clip(text, 512);
  }

  private static void tooltip(ResourceTreeIndex.Node node) {
    if (ImGui.isItemHovered()) UiScale.tooltip(bounded(node.id()) + "\nUUID: " + node.uuid());
  }

  private void fail(RuntimeException failure) {
    message = Objects.requireNonNullElse(failure.getMessage(), failure.toString());
  }

  private boolean action(String key, String label, boolean enabled) {
    ImGui.beginDisabled(!enabled);
    boolean clicked = chrome.identifiedAction(key, label);
    ImGui.endDisabled();
    remember(key);
    return enabled && clicked;
  }

  private void remember(String key) {
    var r = PopupLayout.itemBounds();
    controls.put(key, new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2});
  }

  float[] probeControl(String key) {
    return controls.get(key);
  }

  int submitted() {
    return submitted;
  }

  int includedRows() {
    return includedRows;
  }

  long preparations() {
    return edit == null ? 0 : edit.preparations();
  }

  long queries() {
    return projection.evaluations();
  }

  void filter(String text) {
    filter.set(text);
  }

  String section() {
    return section;
  }

  DialogBounds.Rect listBounds() {
    return listBounds;
  }

  float rowStride() {
    return rowStride;
  }

  Map<String, Float> rowMetrics() {
    return Map.of("line", rowLine, "frame", rowFrame, "gap", rowGap);
  }

  Map<UUID, Integer> rowIds() {
    return Map.copyOf(rowIds);
  }

  ExportEditSession session() {
    return edit;
  }
}
