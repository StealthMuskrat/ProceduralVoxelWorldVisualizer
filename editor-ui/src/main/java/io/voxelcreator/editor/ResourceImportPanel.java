package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Production COPY/REUSE/SKIP workspace, with bounded rows and explicit stale-preview reload. */
final class ResourceImportPanel {
  private final EditorState state;
  final DialogLayout layout;
  private final BooleanSupplier blocked;
  private final Runnable validation;
  private ImportEditSession session;
  private boolean prompt;
  private final ImString filter = new ImString(256);
  private final UiFields fields = new UiFields();
  private String kind = "All", resolution = "All", message = "", section = "Resources";
  private final ImportRows projection = new ImportRows();
  final SettingsPageChrome chrome = new SettingsPageChrome(4, 3);
  private final Map<String, float[]> controls = new HashMap<>();
  private final Map<UUID, float[]> rowControls = new HashMap<>();
  private final Map<UUID, Integer> rowIds = new LinkedHashMap<>();
  private int submitted;
  private float rowStride;
  private float rowLine, rowFrame, rowGap;
  private DialogBounds.Rect listBounds;
  private Float scroll;
  private boolean revealPage;

  ResourceImportPanel(EditorState state, BooleanSupplier blocked, Runnable validation) {
    this.state = state;
    layout = new DialogLayout(state, "Resources", 820, 650);
    this.blocked = Objects.requireNonNull(blocked);
    this.validation = Objects.requireNonNull(validation);
  }

  boolean pending() {
    return session != null && !session.closed();
  }

  boolean open(ProjectData incoming) {
    if (pending() || blocked.getAsBoolean()) {
      state.status = "Finish the private editor or pending project action before opening Import";
      return false;
    }
    if (incoming == null) {
      state.status = "A resource package must contain resources";
      return false;
    }
    var issues = NoiseCompiler.validate(incoming);
    if (!issues.isEmpty()) {
      state.issues = issues;
      state.validationScope = "Rejected import package";
      issues.forEach(d -> state.reportDiagnostic(d, EditorEvents.Subsystem.IMPORT_EXPORT, true));
      return false;
    }
    session = new ImportEditSession(state, incoming);
    filter.set("");
    kind = resolution = "All";
    message = "";
    section = "Resources";
    resetRows();
    prompt = true;
    return true;
  }

  void cancel() {
    if (session != null) session.cancel();
    session = null;
    prompt = false;
    fields.clear();
    resetRows();
    message = "";
  }

  private void resetRows() {
    projection.clear();
    scroll = 0f;
    revealPage = true;
  }

  void draw() {
    controls.clear();
    rowControls.clear();
    rowIds.clear();
    submitted = 0;
    listBounds = null;
    if (prompt) {
      layout.open("Import Resources");
      prompt = false;
    }
    if (!layout.begin("Import Resources", ImGuiWindowFlags.NoSavedSettings)) return;
    if (!pending()) {
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
      return;
    }
    boolean unavailable = blocked.getAsBoolean();
    var preview = session.preview();
    var view = projection.view(preview, new ImportRows.Filter(filter.get(), kind, resolution));
    chrome.begin("import-body");
    if (revealPage) {
      ImGui.setScrollY(0);
      revealPage = false;
    }
    if (section.equals("Resources")) {
      search();
      view = projection.view(preview, new ImportRows.Filter(filter.get(), kind, resolution));
      ImGui.textWrapped("Rows " + view.rows().size() + " / " + view.total());
      boolean visible = drawRows(view.rows(), session.current() && !unavailable);
      // A changed choice makes this local plan obsolete; only a subsequent display acknowledges it.
      if (visible) session.markShown(preview);
    } else if (section.equals("Filters")) {
      search();
      ImGui.textWrapped(
          "COPY "
              + view.counts().copy()
              + " / REUSE "
              + view.counts().reuse()
              + " / SKIP "
              + view.counts().skip()
              + " (including dependent exclusion)");
      ImGui.textWrapped("Kind");
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      fields.choice(
          "##import-kind",
          kind,
          ImportRows.KINDS,
          value -> {
            kind = value;
            scroll = 0f;
          });
      ImGui.textWrapped("Resolution filter");
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      fields.choice(
          "##import-resolution",
          resolution,
          ImportRows.RESOLUTIONS,
          value -> {
            resolution = value;
            scroll = 0f;
          });
      ImGui.textWrapped(
          "Filters match IDs, kinds, incoming/existing/target UUIDs and target IDs. Return to"
              + " Resources to review the resulting transaction.");
      ImGui.textWrapped(
          "COPY, compatible REUSE and SKIP keep existing definitions owned by this project. SKIP"
              + " can exclude dependent resources.");
    } else {
      ImGui.textWrapped("Import review and issues");
      ImGui.textWrapped(
          "COPY "
              + view.counts().copy()
              + " / REUSE "
              + view.counts().reuse()
              + " / SKIP "
              + view.counts().skip()
              + " (including dependent exclusion)");
      ImGui.textWrapped(
          "Import appends or reuses committed resources in one undoable edit. Unapplied Properties"
              + " input remains private. Cancel discards these import choices.");
      if (!session.current()) {
        ImGui.textWrapped(
            "The destination changed. Reload to retain choices and review a fresh transaction. COPY"
                + " identities may change on reload.");
        if (!session.canReload())
          ImGui.textWrapped("The project was replaced. Cancel and reopen Import for that project.");
      }
      if (unavailable)
        ImGui.textWrapped(
            "Finish the other private editor or project action, or cancel this import.");
      if (!message.isEmpty()) ImGui.textWrapped(bounded(message, 4096));
      ImGui.textWrapped("Preview issues: " + preview.diagnostics().size());
      preview.diagnostics().stream()
          .limit(8)
          .forEach(d -> ImGui.textWrapped(bounded(d.toString(), 1024)));
      ImGui.textWrapped("Rejected candidate issues: " + session.issueCount());
      session.applyIssues().stream()
          .limit(8)
          .forEach(d -> ImGui.textWrapped(bounded(d.toString(), 1024)));
      if (preview.diagnostics().size() > 8 || session.issueCount() > 8)
        ImGui.textWrapped(
            "Only the first eight issues from each source are shown here. Candidate diagnostics are"
                + " also available in Validation and Logs.");
    }
    if (!section.equals("Issues")) {
      if (!session.current())
        ImGui.textWrapped("! Destination changed. Reload and review before importing.");
      if (unavailable)
        ImGui.textWrapped("! Another private editor or project action blocks Import.");
      if (!message.isEmpty() || !preview.diagnostics().isEmpty() || session.issueCount() > 0)
        ImGui.textWrapped("! Review Issues for import feedback.");
    }
    chrome.end();
    boolean apply = action("apply", "Import", session.canApply() && !unavailable);
    boolean cancel = action("cancel", "Cancel", true);
    boolean reload =
        action("reload", "Reload", !session.current() && session.canReload() && !unavailable);
    boolean validate = action("validation", "Validation", session.issueCount() > 0);
    boolean resources = action("resources", "Resources", true);
    boolean filters = action("filters", "Filters", true);
    boolean issues = action("issues", "Issues", true);
    if (cancel || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      cancel();
      ImGui.closeCurrentPopup();
    } else if (reload) {
      try {
        session.reload();
        resetRows();
        message = "";
        page("Resources");
      } catch (RuntimeException failure) {
        message = Objects.requireNonNullElse(failure.getMessage(), failure.toString());
        page("Issues");
      }
    } else if (apply && !blocked.getAsBoolean()) {
      var result = session.apply();
      if (result == ImportEditSession.Result.APPLIED) {
        state.notify("Information", "Previewed resource import committed");
        cancel();
        ImGui.closeCurrentPopup();
      } else {
        message =
            result == ImportEditSession.Result.STALE
                ? "The destination changed; reload and review the import."
                : "The import was rejected. Review its choices and validation results, or cancel.";
        page("Issues");
      }
    } else if (validate) validation.run();
    else if (resources) page("Resources");
    else if (filters) page("Filters");
    else if (issues) page("Issues");
    ImGui.endPopup();
  }

  private void search() {
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    if (ImGui.inputTextWithHint("##import-filter", "Filter incoming resources...", filter))
      scroll = 0f;
    remember("filter");
  }

  private boolean drawRows(List<ResourceImport.Row> rows, boolean editable) {
    boolean visible =
        UiScale.beginChild(
            "import-resources",
            0,
            ResourceReviewLayout.content(ImGui.getContentRegionAvailY()),
            true);
    listBounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    rowLine = ImGui.getTextLineHeight();
    rowFrame = ImGui.getFrameHeight();
    rowGap = ImGui.getStyle().getItemSpacingY();
    rowStride =
        ResourceReviewLayout.importStride(
            ImGui.getTextLineHeight(), ImGui.getFrameHeight(), ImGui.getStyle().getItemSpacingY());
    if (visible) {
      var clipper = new ImGuiListClipper();
      try {
        clipper.begin(rows.size(), rowStride);
        while (clipper.step())
          for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
            var row = rows.get(i);
            float top = ImGui.getCursorPosY();
            submitted++;
            ImGui.pushID(row.uuid().toString());
            ImGui.textUnformatted(AppMetadata.uiText(row.kind() + " / " + bounded(row.id(), 100)));
            if (ImGui.isItemHovered())
              UiScale.tooltip(bounded(row.id(), 1024) + "\nIncoming UUID: " + row.uuid());
            ImGui.textDisabled(
                row.existing() == null
                    ? "No identity/ID conflict"
                    : "Existing: " + row.existing() + "; compatible: " + row.compatible());
            ImGui.beginDisabled(!editable);
            ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
            String label = "##import-resolution";
            int widgetId = ImGui.getID(label);
            boolean expanded = UiScale.beginCombo(label, session.choice(row.uuid()).name());
            var a = ImGui.getItemRectMin();
            var b = ImGui.getItemRectMax();
            rowControls.put(row.uuid(), new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
            rowIds.put(row.uuid(), widgetId);
            if (expanded) {
              for (var choice : ResourceImport.Choice.values()) {
                if (choice == ResourceImport.Choice.REUSE && !row.compatible()) continue;
                if (ImGui.selectable(choice.name(), session.choice(row.uuid()) == choice))
                  session.choose(row.uuid(), choice);
                remember(row.uuid() + ":" + choice);
              }
              ImGui.endCombo();
            }
            ImGui.endDisabled();
            ImGui.textDisabled(
                AppMetadata.uiText(
                    row.resolution()
                        + (row.targetId() == null ? "" : " -> " + bounded(row.targetId(), 100))));
            if (ImGui.isItemHovered() && row.targetUuid() != null)
              UiScale.tooltip("Preview target UUID: " + row.targetUuid());
            ImGui.popID();
            ImGui.setCursorPosY(top + rowStride - ImGui.getStyle().getItemSpacingY());
            ImGui.dummy(0, 0);
          }
        clipper.end();
      } finally {
        clipper.destroy();
      }
    }
    if (scroll != null) {
      ImGui.setScrollY(scroll);
      scroll = null;
    }
    ImGui.endChild();
    return visible;
  }

  private boolean action(String key, String label, boolean enabled) {
    ImGui.beginDisabled(!enabled);
    boolean clicked = chrome.identifiedAction(key, label);
    ImGui.endDisabled();
    remember(key);
    return enabled && clicked;
  }

  private void page(String value) {
    if (!section.equals(value)) {
      section = value;
      revealPage = true;
    }
  }

  private void remember(String key) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    controls.put(key, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
  }

  private static String bounded(String value, int length) {
    return StatusBarText.clip(value, length);
  }

  float[] control(String key) {
    return controls.get(key);
  }

  float[] rowControl(UUID id) {
    return rowControls.get(id);
  }

  int submitted() {
    return submitted;
  }

  long queries() {
    return projection.evaluations();
  }

  String section() {
    return section;
  }

  DialogBounds.Rect listBounds() {
    return listBounds;
  }

  Map<String, Float> rowMetrics() {
    return Map.of("line", rowLine, "frame", rowFrame, "gap", rowGap);
  }

  float rowStride() {
    return rowStride;
  }

  Map<UUID, Integer> rowIds() {
    return Map.copyOf(rowIds);
  }

  void probeScroll(float y) {
    scroll = y;
  }

  ImportEditSession session() {
    return session;
  }

  Set<UUID> visibleIds() {
    return Set.copyOf(rowControls.keySet());
  }
}
