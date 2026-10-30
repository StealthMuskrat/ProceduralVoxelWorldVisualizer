package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.model.Diagnostic;
import java.util.*;
import java.util.function.Consumer;

/** Immutable result views, owned actions and measured clipped diagnostic workspaces. */
public final class DiagnosticsPanel {
  final FloatingWindowLayout logsLayout = new FloatingWindowLayout(920, 640);
  final FloatingWindowLayout noticesLayout = new FloatingWindowLayout(850, 580);
  final FloatingWindowLayout validationLayout = new FloatingWindowLayout(900, 600);
  final SettingsPageChrome logsChrome = new SettingsPageChrome(3);
  final SettingsPageChrome noticesChrome = new SettingsPageChrome(3, 3);
  final SettingsPageChrome validationChrome = new SettingsPageChrome(3);
  private final EditorState state;
  private final Consumer<EditorEvents.Target> navigate;
  private final Consumer<String> copy;
  private TracePanel tracePanel;
  private final ImString filter = new ImString(512);
  private final UiFields fields = new UiFields();
  private final EventProjection logsProjection = new EventProjection(),
      noticesProjection = new EventProjection();
  private final ValidationSelection validationSelection = new ValidationSelection();
  private String severity = "All", subsystem = "All", eventMessage = "";
  private long selected = -1;
  private boolean revealSelected;
  private boolean logsDrawn, noticesDrawn, validationDrawn, probing;
  private SettingsPageLayout.Panes panes;
  private DialogBounds.Rect resultsBounds, detailBounds;
  private float rowStride;
  private int submitted;
  private boolean revealViewport;
  private final Map<String, float[]> controls = new LinkedHashMap<>();
  private final Map<String, Integer> rowIds = new LinkedHashMap<>();

  public DiagnosticsPanel(EditorState state, Consumer<EditorEvents.Target> navigate) {
    this(state, navigate, ImGui::setClipboardText);
  }

  DiagnosticsPanel(
      EditorState state, Consumer<EditorEvents.Target> navigate, Consumer<String> copy) {
    this.state = Objects.requireNonNull(state);
    this.navigate = Objects.requireNonNull(navigate);
    this.copy = Objects.requireNonNull(copy);
  }

  public void tracePanel(TracePanel panel) {
    tracePanel = panel;
  }

  public void reveal(long id) {
    selected = id;
    filter.set("");
    severity = "All";
    subsystem = "All";
    revealSelected = true;
    eventMessage = "";
  }

  public void drawLogs(ImBoolean open) {
    if (!begin(open, "Logs & Error Center", "Logs", logsLayout)) return;
    logsDrawn = true;
    logsChrome.begin("logs-workspace-body");
    ImGui.textWrapped("Search code, message or source");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    ImGui.inputText("##diagnostics-search", filter);
    control("filter");
    choice(
        "Severity",
        "##diagnostics-severity",
        severity,
        choices(Diagnostic.Severity.values()),
        v -> severity = v);
    choice(
        "Subsystem",
        "##diagnostics-subsystem",
        subsystem,
        choices(EditorEvents.Subsystem.values()),
        v -> subsystem = v);
    var view = state.events.view();
    var rows =
        logsProjection.rows(
            view,
            filter.get(),
            severity.equals("All") ? null : Diagnostic.Severity.valueOf(severity),
            subsystem.equals("All") ? null : EditorEvents.Subsystem.valueOf(subsystem),
            false);
    ImGui.textWrapped(
        rows.size()
            + " groups / "
            + view.counts().retained()
            + " retained; "
            + view.counts().evicted()
            + " older groups evicted");
    selectedOutside(rows);
    preparePanes();
    eventRows(rows, "log-results", true);
    var entry = eventDetail("log-details");
    logsChrome.end();
    copyAction(logsChrome, entry);
    navigateAction(logsChrome, entry);
    ImGui.beginDisabled(entry == null);
    if (logsChrome.action("show", "Show Selected")) reveal(selected);
    control("show");
    ImGui.endDisabled();
    ImGui.end();
  }

  public void drawNotifications(ImBoolean open, Runnable openLogs) {
    if (!begin(open, "Notifications", "Notifications", noticesLayout)) return;
    noticesDrawn = true;
    noticesChrome.begin("notifications-workspace-body");
    var view = state.events.view();
    ImGui.textWrapped(
        view.counts().unread()
            + " unread / "
            + view.counts().actionRequired()
            + " action required");
    ImGui.textWrapped(
        "Reading an entry does not resolve its required action. New occurrences remain unread until"
            + " reviewed.");
    var rows = noticesProjection.rows(view, "", null, null, true);
    selectedOutside(rows);
    preparePanes();
    eventRows(rows, "notice-results", false);
    var entry = eventDetail("notice-details");
    noticesChrome.end();
    if (noticesChrome.action("all-read", "Mark All Read")) {
      state.events.markAllRead(view);
      eventMessage = "Marked reviewed entries read. Newer occurrences stay unread.";
    }
    control("all-read");
    ImGui.beginDisabled(entry == null);
    if (noticesChrome.action("logs", "View Selected in Logs")) {
      reveal(entry.id());
      openLogs.run();
    }
    control("logs");
    ImGui.endDisabled();
    copyAction(noticesChrome, entry);
    navigateAction(noticesChrome, entry);
    ImGui.beginDisabled(entry == null);
    if (noticesChrome.action("read", "Mark Read")) acknowledge(state.events.markRead(entry));
    control("read");
    if (noticesChrome.action(
        "acknowledge",
        entry != null && entry.actionRequired() ? "Mark Action Resolved" : "Dismiss Notification"))
      acknowledge(
          entry.actionRequired() ? state.events.resolve(entry) : state.events.dismiss(entry));
    control("acknowledge");
    ImGui.endDisabled();
    ImGui.end();
  }

  private void selectedOutside(List<EditorEvents.Entry> rows) {
    if (selected >= 0 && rows.stream().noneMatch(e -> e.id() == selected))
      ImGui.textWrapped(
          "The selected event is outside this result list. Details still belong to that event.");
    if (!eventMessage.isEmpty()) ImGui.textWrapped(eventMessage);
  }

  private boolean begin(ImBoolean open, String title, String scale, FloatingWindowLayout window) {
    if (!open.get()) return false;
    window.prepare();
    boolean visible = ImGui.begin(title, open);
    UiScale.window(state.user, scale);
    window.observe();
    if (!visible || !open.get()) {
      ImGui.end();
      return false;
    }
    if (probing) {
      controls.clear();
      rowIds.clear();
    }
    submitted = 0;
    return true;
  }

  private void choice(
      String label, String id, String value, List<String> options, Consumer<String> change) {
    ImGui.textWrapped(label);
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice(id, value, options, change);
  }

  private void preparePanes() {
    panes =
        SettingsPageLayout.panes(
            ImGui.getContentRegionAvailX(),
            ImGui.getWindowSizeY() - ImGui.getCursorPosY() - ImGui.getStyle().getWindowPaddingY(),
            ImGui.calcTextSize("0000000000000000000000000000").x,
            ImGui.getTextLineHeightWithSpacing(),
            ImGui.getStyle().getItemSpacingX(),
            ImGui.getStyle().getItemSpacingY());
  }

  private void eventRows(List<EditorEvents.Entry> rows, String id, boolean logs) {
    UiScale.beginChild(id, panes.resultsWidth(), panes.resultsHeight(), true);
    resultsBounds = bounds();
    rowStride = (float) Math.ceil(ImGui.getTextLineHeightWithSpacing());
    boolean reveal = logs && revealSelected;
    int index = -1;
    if (reveal) {
      for (int i = 0; i < rows.size(); i++)
        if (rows.get(i).id() == selected) {
          index = i;
          break;
        }
      if (index >= 0) ImGui.setScrollY(index * rowStride);
    }
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size(), rowStride);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var e = rows.get(i);
          float top = ImGui.getCursorPosY();
          String label =
              (!e.read() ? "* " : "")
                  + (e.actionRequired() ? "[Action required] " : "")
                  + e.severity()
                  + " / "
                  + e.code()
                  + "  x"
                  + e.count()
                  + "  "
                  + e.message();
          String widget = AppMetadata.uiText(StatusBarText.clip(label, 160)) + "###event" + e.id();
          if (probing) rowIds.put(Long.toString(e.id()), ImGui.getID(widget));
          if (ImGui.selectable(
              widget,
              selected == e.id(),
              0,
              Math.max(1, ImGui.getContentRegionAvailX()),
              ImGui.getTextLineHeight())) {
            selected = e.id();
            acknowledge(state.events.markRead(e));
          }
          control("event:" + e.id());
          PopupLayout.clippedLabel(StatusBarText.clip(label, 512));
          rowTail(top);
          submitted++;
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    revealViewport();
    if (reveal) {
      if (index >= 0) ImGui.setScrollHereY(.2f);
      revealSelected = false;
    }
  }

  private EditorEvents.Entry eventDetail(String id) {
    if (panes.columns()) ImGui.sameLine();
    UiScale.beginChild(id, panes.detailWidth(), panes.detailHeight(), true);
    detailBounds = bounds();
    var e = state.events.get(selected).orElse(null);
    if (e == null)
      ImGui.textWrapped(
          selected < 0
              ? "Select an entry to inspect details and actions."
              : "The selected event is no longer retained. Select another entry.");
    else {
      var trace = state.traces.get(e.id());
      if (tracePanel != null && trace.isPresent()) tracePanel.draw(trace.get());
      else {
        if (e.code().equals("GENERATION_TRACE"))
          ImGui.textWrapped(
              "Detailed trace payload is no longer retained. Trace the coordinate again for source"
                  + " links.");
        ImGui.textWrapped(e.code() + " / " + e.subsystem());
        if (e.actionRequired())
          ImGui.textWrapped("! Action required; reading this entry does not resolve it.");
        ImGui.textWrapped(e.message());
        ImGui.separator();
        ImGui.textWrapped(
            "First: " + e.first() + " / Last: " + e.last() + " / Count: " + e.count());
        if (e.target() != null) {
          var t = e.target();
          if (t.resource() != null) ImGui.textWrapped("Resource: " + t.resource());
          if (t.control() != null) ImGui.textWrapped("Control: " + t.control());
          if (t.parameter() != null) ImGui.textWrapped("Parameter: " + t.parameter());
          if (t.pointer() != null && !t.pointer().isBlank())
            ImGui.textWrapped("Path: " + t.pointer());
          if (!state.canNavigate(t) && (t.resource() != null || t.settings()))
            ImGui.textWrapped("Source is unavailable in the current project or draft.");
        }
      }
    }
    ImGui.endChild();
    return e;
  }

  private void copyAction(SettingsPageChrome chrome, EditorEvents.Entry entry) {
    ImGui.beginDisabled(entry == null);
    if (chrome.action("copy", "Copy Details")) copy.accept(entry.details());
    control("copy");
    ImGui.endDisabled();
  }

  private void navigateAction(SettingsPageChrome chrome, EditorEvents.Entry entry) {
    boolean available = entry != null && state.canNavigate(entry.target());
    ImGui.beginDisabled(!available);
    if (chrome.action(
        "navigate",
        entry != null && entry.target() != null && entry.target().settings()
            ? "Open Project Settings"
            : "Go To Problem")) {
      if (state.events.markRead(entry) && state.canNavigate(entry.target()))
        navigate.accept(entry.target());
      else acknowledge(false);
    }
    control("navigate");
    ImGui.endDisabled();
  }

  private void acknowledge(boolean accepted) {
    eventMessage =
        accepted
            ? ""
            : "This event changed or was evicted. Review its current details before acknowledging"
                + " it.";
  }

  public void drawValidation(ImBoolean open) {
    if (!begin(open, "Validation Results", "Validation", validationLayout)) return;
    validationDrawn = true;
    validationSelection.synchronize(state);
    validationChrome.begin("validation-workspace-body");
    ImGui.textWrapped(
        "Scope: " + validationSelection.scope() + ". Validation does not modify the project.");
    var rows = validationSelection.rows();
    ImGui.textWrapped(
        rows.isEmpty() ? "OK: No validation issues in this result." : rows.size() + " issue(s)");
    preparePanes();
    UiScale.beginChild("validation-list", panes.resultsWidth(), panes.resultsHeight(), true);
    resultsBounds = bounds();
    rowStride = (float) Math.ceil(ImGui.getTextLineHeightWithSpacing());
    ImGui.pushID(validationSelection.publication());
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size(), rowStride);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var d = rows.get(i);
          float top = ImGui.getCursorPosY();
          String widget =
              AppMetadata.uiText(
                      StatusBarText.clip(
                          d.severity() + " / " + d.code() + " / " + d.message(), 160))
                  + "###issue"
                  + i;
          if (probing) rowIds.put(Integer.toString(i), ImGui.getID(widget));
          if (ImGui.selectable(
              widget,
              validationSelection.index() == i,
              0,
              Math.max(1, ImGui.getContentRegionAvailX()),
              ImGui.getTextLineHeight())) validationSelection.select(i);
          control("issue:" + i);
          PopupLayout.clippedLabel(StatusBarText.clip(d.toString(), 512));
          rowTail(top);
          submitted++;
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.popID();
    ImGui.endChild();
    revealViewport();
    if (panes.columns()) ImGui.sameLine();
    UiScale.beginChild("validation-detail", panes.detailWidth(), panes.detailHeight(), true);
    detailBounds = bounds();
    var diagnostic = validationSelection.selected();
    if (diagnostic == null)
      ImGui.textWrapped("Select an issue to inspect its exact details and source.");
    else ImGui.textWrapped(diagnosticDetails(diagnostic));
    ImGui.endChild();
    validationChrome.end();
    if (validationChrome.action("validate", "Validate Committed Project")) {
      state.validateProject();
      validationSelection.synchronize(state);
    }
    control("validate");
    ImGui.beginDisabled(
        diagnostic == null
            || validationSelection.selected() != diagnostic
            || !validationSelection.current(state));
    if (validationChrome.action("copy", "Copy Details")) copy.accept(diagnosticDetails(diagnostic));
    control("copy");
    ImGui.endDisabled();
    var target = validationSelection.target(state);
    ImGui.beginDisabled(target == null || !state.canNavigate(target));
    if (validationChrome.action(
            "navigate",
            target != null && target.settings() ? "Open Project Settings" : "Go To Problem")
        && validationSelection.current(state)
        && state.canNavigate(target)) navigate.accept(target);
    control("navigate");
    ImGui.endDisabled();
    ImGui.end();
  }

  private void rowTail(float top) {
    ImGui.setCursorPosY(top + rowStride - ImGui.getStyle().getItemSpacingY());
    ImGui.dummy(0, 0);
  }

  private static DialogBounds.Rect bounds() {
    return new DialogBounds.Rect(
        ImGui.getWindowPosX(),
        ImGui.getWindowPosY(),
        ImGui.getWindowSizeX(),
        ImGui.getWindowSizeY());
  }

  private static String diagnosticDetails(Diagnostic d) {
    return d
        + "\nSeverity: "
        + d.severity()
        + "\nResource: "
        + d.sourceUuid()
        + "\nControl: "
        + d.itemUuid()
        + "\nParameter: "
        + d.parameterKey();
  }

  public void assertSmokeDrawn() {
    if (!logsDrawn || !noticesDrawn || !validationDrawn)
      throw new IllegalStateException("Diagnostics panels were not rendered");
    System.out.println("DIAGNOSTICS_AND_NOTIFICATION_NATIVE_PASS");
  }

  private static List<String> choices(Object[] values) {
    var result = new ArrayList<String>();
    result.add("All");
    for (Object value : values) result.add(value.toString());
    return List.copyOf(result);
  }

  void probeControls() {
    probing = true;
  }

  void probeRevealResults() {
    revealViewport = true;
  }

  private void revealViewport() {
    if (revealViewport) {
      ImGui.setScrollHereY(.2f);
      revealViewport = false;
    }
  }

  private void control(String key) {
    if (!probing) return;
    var r = PopupLayout.itemBounds();
    controls.put(key, new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2});
  }

  float[] controlAt(String key) {
    return controls.get(key);
  }

  Map<String, Integer> rowIds() {
    return Map.copyOf(rowIds);
  }

  SettingsPageLayout.Panes panes() {
    return panes;
  }

  DialogBounds.Rect resultsBounds() {
    return resultsBounds;
  }

  DialogBounds.Rect detailBounds() {
    return detailBounds;
  }

  float rowStride() {
    return rowStride;
  }

  int submittedRows() {
    return submitted;
  }

  long selectedEvent() {
    return selected;
  }

  int selectedIssue() {
    return validationSelection.index();
  }

  long projectionBuilds(boolean notices) {
    return (notices ? noticesProjection : logsProjection).builds();
  }
}
