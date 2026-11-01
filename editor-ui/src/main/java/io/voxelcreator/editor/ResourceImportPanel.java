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
  private final NavigationDecisionScope delivery = new NavigationDecisionScope();
  private long generation, shownOpening = -1;
  private int drawnFrame = Integer.MIN_VALUE;

  enum Command {
    CANCEL,
    APPLY,
    RELOAD,
    VALIDATION,
    RESOURCES,
    FILTERS,
    ISSUES
  }

  record Decision(
      NavigationDecisionScope.Ticket ticket,
      ImportEditSession owner,
      ResourceImport.Plan preview,
      ResourceReviewContext context) {}

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
    if (pending()) {
      state.status = "Finish the private editor or pending project action before opening Import";
      return false;
    }
    long opening = ++generation;
    var context = ResourceReviewContext.capture(state);
    try {
      boolean unavailable = blocked.getAsBoolean();
      if (generation != opening || pending() || !context.current(state)) return false;
      if (unavailable) {
        state.status = "Finish the private editor or pending project action before opening Import";
        return false;
      }
    } catch (RuntimeException failure) {
      report("IMPORT_REVIEW_GATE_FAILED", failure);
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
    delivery.open();
    filter.set("");
    kind = resolution = "All";
    message = "";
    section = "Resources";
    resetRows();
    prompt = true;
    return true;
  }

  void cancel() {
    if (session != null && session.busy()) return;
    generation++;
    delivery.close();
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
    int frame = ImGui.getFrameCount();
    if (drawnFrame == frame) return;
    drawnFrame = frame;
    controls.clear();
    rowControls.clear();
    rowIds.clear();
    submitted = 0;
    listBounds = null;
    if (!pending()) {
      closePopup();
      return;
    }
    var opening = capture(frame);
    boolean unavailable;
    try {
      unavailable = blocked.getAsBoolean();
    } catch (RuntimeException failure) {
      feedback(opening, "IMPORT_REVIEW_GATE_FAILED", failure);
      unavailable = true;
    }
    if (!owns(opening)) return;
    if (prompt || shownOpening != delivery.version()) {
      closePopup();
      layout.open("Import Resources");
      prompt = false;
      shownOpening = delivery.version();
    }
    if (!layout.begin("Import Resources", ImGuiWindowFlags.NoSavedSettings)) return;
    Command command = null;
    Decision decision = null;
    try {
      ImGui.pushID("import-review:" + opening.ticket().version());
      try {
        chrome.begin("import-body");
        try {
          var preview = session.preview();
          var view =
              projection.view(preview, new ImportRows.Filter(filter.get(), kind, resolution));
          if (revealPage) {
            ImGui.setScrollY(0);
            revealPage = false;
          }
          if (section.equals("Resources")) {
            search();
            view = projection.view(preview, new ImportRows.Filter(filter.get(), kind, resolution));
            ImGui.textWrapped("Rows " + view.rows().size() + " / " + view.total());
            boolean visible = drawRows(view.rows(), session.current() && !unavailable);
            // A changed choice makes this local plan obsolete; only a subsequent display
            // acknowledges it.
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
                "COPY, compatible REUSE and SKIP keep existing definitions owned by this project."
                    + " SKIP can exclude dependent resources.");
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
                "Import appends or reuses committed resources in one undoable edit. Unapplied"
                    + " Properties input remains private. Cancel discards these import choices.");
            if (!session.current()) {
              ImGui.textWrapped(
                  "The destination changed. Reload to retain choices and review a fresh"
                      + " transaction. COPY identities may change on reload.");
              if (!session.canReload())
                ImGui.textWrapped(
                    "The project was replaced. Cancel and reopen Import for that project.");
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
                  "Only the first eight issues from each source are shown here. Candidate"
                      + " diagnostics are also available in Validation and Logs.");
          }
          if (!section.equals("Issues")) {
            if (!session.current())
              ImGui.textWrapped("! Destination changed. Reload and review before importing.");
            if (unavailable)
              ImGui.textWrapped("! Another private editor or project action blocks Import.");
            if (!message.isEmpty() || !preview.diagnostics().isEmpty() || session.issueCount() > 0)
              ImGui.textWrapped("! Review Issues for import feedback.");
          }
        } finally {
          chrome.end();
        }
        boolean apply = action("apply", "Import", session.canApply() && !unavailable);
        boolean cancel = action("cancel", "Cancel", true);
        boolean reload =
            action("reload", "Reload", !session.current() && session.canReload() && !unavailable);
        boolean validate = action("validation", "Validation", session.issueCount() > 0);
        boolean resources = action("resources", "Resources", true);
        boolean filters = action("filters", "Filters", true);
        boolean issues = action("issues", "Issues", true);
        if (cancel || ImGui.isKeyPressed(ImGuiKey.Escape, false)) command = Command.CANCEL;
        else if (reload) command = Command.RELOAD;
        else if (apply) command = Command.APPLY;
        else if (validate) command = Command.VALIDATION;
        else if (resources) command = Command.RESOURCES;
        else if (filters) command = Command.FILTERS;
        else if (issues) command = Command.ISSUES;
        decision = capture(frame);
      } finally {
        ImGui.popID();
      }
    } finally {
      ImGui.endPopup();
    }
    if (command != null) dispatch(decision, command, frame);
    if (delivery.version() == opening.ticket().version() && !pending()) closePopup();
  }

  Decision capture(int frame) {
    return new Decision(
        delivery.capture(frame), session, session.preview(), ResourceReviewContext.capture(state));
  }

  private boolean owns(Decision decision) {
    return decision != null
        && delivery.owns(decision.ticket())
        && pending()
        && session == decision.owner();
  }

  void dispatch(Decision decision, Command command, int frame) {
    if (!owns(decision) || session.busy() || !delivery.consume(decision.ticket(), frame)) return;
    if (command == Command.CANCEL) {
      cancel();
      return;
    }
    if (command == Command.RESOURCES) {
      page("Resources");
      return;
    }
    if (command == Command.FILTERS) {
      page("Filters");
      return;
    }
    if (command == Command.ISSUES) {
      page("Issues");
      return;
    }
    if (command == Command.VALIDATION) {
      if (session.issueCount() > 0) {
        try {
          validation.run();
        } catch (RuntimeException failure) {
          feedback(decision, "IMPORT_REVIEW_CALLBACK_FAILED", failure);
        }
      }
      return;
    }
    try {
      boolean unavailable = blocked.getAsBoolean();
      if (!owns(decision)) return;
      if (unavailable
          || !decision.context().current(state)
          || session.preview() != decision.preview()) return;
      if (command == Command.RELOAD) {
        session.reload();
        resetRows();
        message = "";
        page("Resources");
      } else if (command == Command.APPLY) {
        var result = session.apply();
        if (result == ImportEditSession.Result.APPLIED) {
          cancel();
          state.notify("Information", "Previewed resource import committed");
        } else if (owns(decision)) {
          message =
              result == ImportEditSession.Result.STALE
                  ? "The destination changed; reload and review the import."
                  : "The import was rejected. Review its choices and validation results, or"
                      + " cancel.";
          page("Issues");
        }
      }
    } catch (RuntimeException failure) {
      feedback(decision, "IMPORT_REVIEW_ACTION_FAILED", failure);
    }
  }

  private void feedback(Decision decision, String code, RuntimeException failure) {
    String detail = report(code, failure);
    if (owns(decision)) {
      message = detail;
      page("Issues");
    }
  }

  private String report(String code, RuntimeException failure) {
    String detail =
        StatusBarText.bounded(
            Objects.requireNonNullElse(failure.getMessage(), failure.toString()), 1024);
    state.error(code, EditorEvents.Subsystem.IMPORT_EXPORT, detail);
    return detail;
  }

  private void closePopup() {
    if (layout.begin("Import Resources", ImGuiWindowFlags.NoSavedSettings)) {
      try {
        ImGui.closeCurrentPopup();
      } finally {
        ImGui.endPopup();
      }
    }
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
    try {
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
              ImGui.getTextLineHeight(),
              ImGui.getFrameHeight(),
              ImGui.getStyle().getItemSpacingY());
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
              try {
                ImGui.textUnformatted(
                    AppMetadata.uiText(row.kind() + " / " + bounded(row.id(), 100)));
                if (ImGui.isItemHovered())
                  UiScale.tooltip(bounded(row.id(), 1024) + "\nIncoming UUID: " + row.uuid());
                ImGui.textDisabled(
                    row.existing() == null
                        ? "No identity/ID conflict"
                        : "Existing: " + row.existing() + "; compatible: " + row.compatible());
                ImGui.beginDisabled(!editable);
                try {
                  ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
                  String label = "##import-resolution";
                  int widgetId = ImGui.getID(label);
                  boolean expanded = UiScale.beginCombo(label, session.choice(row.uuid()).name());
                  var a = ImGui.getItemRectMin();
                  var b = ImGui.getItemRectMax();
                  rowControls.put(row.uuid(), new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
                  rowIds.put(row.uuid(), widgetId);
                  if (expanded) {
                    try {
                      for (var choice : ResourceImport.Choice.values()) {
                        if (choice == ResourceImport.Choice.REUSE && !row.compatible()) continue;
                        if (ImGui.selectable(choice.name(), session.choice(row.uuid()) == choice))
                          session.choose(row.uuid(), choice);
                        remember(row.uuid() + ":" + choice);
                      }
                    } finally {
                      ImGui.endCombo();
                    }
                  }
                } finally {
                  ImGui.endDisabled();
                }
                ImGui.textDisabled(
                    AppMetadata.uiText(
                        row.resolution()
                            + (row.targetId() == null
                                ? ""
                                : " -> " + bounded(row.targetId(), 100))));
                if (ImGui.isItemHovered() && row.targetUuid() != null)
                  UiScale.tooltip("Preview target UUID: " + row.targetUuid());
              } finally {
                ImGui.popID();
              }
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
      return visible;
    } finally {
      ImGui.endChild();
    }
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

  String message() {
    return message;
  }

  ImportEditSession session() {
    return session;
  }

  Set<UUID> visibleIds() {
    return Set.copyOf(rowControls.keySet());
  }
}
