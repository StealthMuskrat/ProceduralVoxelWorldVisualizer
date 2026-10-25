package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.model.Diagnostic;
import java.util.*;
import java.util.function.Consumer;

/** Bounded, clipped result lists with real detail/action routing; owned by the native UI thread. */
public final class DiagnosticsPanel {
  private final EditorState state;
  private final Consumer<EditorEvents.Target> navigate;
  private TracePanel tracePanel;
  private final ImString filter = new ImString(512);
  private final UiFields fields = new UiFields();
  private String severity = "All", subsystem = "All";
  private long selected = -1;
  private int selectedIssue = -1;
  private boolean logsDrawn, noticesDrawn, validationDrawn;

  public DiagnosticsPanel(EditorState state, Consumer<EditorEvents.Target> navigate) {
    this.state = state;
    this.navigate = navigate;
  }

  public void tracePanel(TracePanel panel) {
    tracePanel = panel;
  }

  public void reveal(long id) {
    selected = id;
    filter.set("");
    severity = "All";
    subsystem = "All";
  }

  public void drawLogs(ImBoolean open) {
    if (!open.get()) return;
    ImGui.setNextWindowSize(920, 640, ImGuiCond.FirstUseEver);
    ImGui.begin("Logs & Error Center", open);
    logsDrawn = true;
    ImGui.inputText("Search code, message or source", filter);
    fields.choice("Severity", severity, choices(Diagnostic.Severity.values()), v -> severity = v);
    fields.choice(
        "Subsystem", subsystem, choices(EditorEvents.Subsystem.values()), v -> subsystem = v);
    var rows =
        state.events.search(
            filter.get(),
            severity.equals("All") ? null : Diagnostic.Severity.valueOf(severity),
            subsystem.equals("All") ? null : EditorEvents.Subsystem.valueOf(subsystem),
            false);
    var counts = state.events.counts();
    ImGui.text(
        rows.size()
            + " groups / "
            + counts.retained()
            + " retained; "
            + counts.evicted()
            + " older groups evicted");
    eventRows(rows, "log-results");
    detail(false);
    ImGui.end();
  }

  public void drawNotifications(ImBoolean open, Runnable openLogs) {
    if (!open.get()) return;
    ImGui.setNextWindowSize(850, 580, ImGuiCond.FirstUseEver);
    ImGui.begin("Notifications", open);
    noticesDrawn = true;
    var counts = state.events.counts();
    ImGui.text(counts.unread() + " unread / " + counts.actionRequired() + " action required");
    if (ImGui.button("Mark All Read")) state.events.markAllRead();
    if (state.events.get(selected).isPresent()) {
      ImGui.sameLine();
      if (ImGui.button("View Selected in Logs")) {
        reveal(selected);
        openLogs.run();
      }
    }
    eventRows(state.events.search("", null, null, true), "notice-results");
    detail(true);
    ImGui.end();
  }

  private void eventRows(List<EditorEvents.Entry> rows, String id) {
    ImGui.beginChild(id, 0, Math.max(100, ImGui.getContentRegionAvailY() * .38f), true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var e = rows.get(i);
          String message = e.message().replace('\n', ' ').replace('\r', ' ').replace("##", "# #");
          if (message.length() > 140) message = message.substring(0, 139) + "…";
          String label =
              (!e.read() ? "* " : "")
                  + (e.actionRequired() ? "[Action required] " : "")
                  + e.severity()
                  + " / "
                  + e.code()
                  + "  x"
                  + e.count()
                  + "  "
                  + message;
          if (ImGui.selectable(label + "###event" + e.id(), selected == e.id())) {
            selected = e.id();
            state.events.markRead(selected);
          }
          if (ImGui.isItemHovered()) ImGui.setTooltip(e.last() + " / " + e.subsystem());
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
  }

  private void detail(boolean notification) {
    var selectedEntry = state.events.get(selected);
    if (selectedEntry.isEmpty()) {
      ImGui.textDisabled("Select an entry to inspect details and actions.");
      return;
    }
    var e = selectedEntry.get();
    if (ImGui.button("Copy Details")) ImGui.setClipboardText(e.details());
    if (state.canNavigate(e.target())) {
      ImGui.sameLine();
      if (ImGui.button(e.target().settings() ? "Open Project Settings" : "Go To Problem")) {
        state.events.markRead(e.id());
        navigate.accept(e.target());
      }
    }
    if (notification) {
      ImGui.sameLine();
      if (ImGui.button("Mark Read")) state.events.markRead(e.id());
      if (e.actionRequired()) {
        ImGui.sameLine();
        if (ImGui.button("Mark Action Resolved")) state.events.resolve(e.id());
      } else {
        ImGui.sameLine();
        if (ImGui.button("Dismiss Notification")) state.events.dismiss(e.id());
      }
    }
    var trace = state.traces.get(e.id());
    if (tracePanel != null && trace.isPresent()) {
      ImGui.beginChild("retained-trace", 0, 0, true);
      tracePanel.draw(trace.get());
      ImGui.endChild();
      return;
    }
    if (e.code().equals("GENERATION_TRACE"))
      ImGui.textWrapped(
          "Detailed trace payload is no longer retained. Trace the coordinate again for source"
              + " links.");
    ImGui.beginChild("event-details", 0, 0, true);
    ThemeStyle.text("Information", e.code() + " / " + e.subsystem());
    if (e.actionRequired())
      ThemeStyle.text("Warning", "! Action required; reading this entry does not resolve it.");
    ImGui.textWrapped(e.message());
    ImGui.separator();
    ImGui.textWrapped("First: " + e.first() + "   Last: " + e.last() + "   Count: " + e.count());
    if (e.target() != null) {
      var target = e.target();
      if (target.resource() != null) ImGui.textWrapped("Resource: " + target.resource());
      if (target.control() != null) ImGui.textWrapped("Control: " + target.control());
      if (target.parameter() != null) ImGui.textWrapped("Parameter: " + target.parameter());
      if (target.pointer() != null && !target.pointer().isBlank())
        ImGui.textWrapped("Path: " + target.pointer());
      if (!state.canNavigate(target) && (target.resource() != null || target.settings()))
        ImGui.textDisabled("Source is unavailable in the current project or draft.");
    }
    ImGui.endChild();
  }

  public void drawValidation(ImBoolean open) {
    if (!open.get()) return;
    ImGui.setNextWindowSize(900, 600, ImGuiCond.FirstUseEver);
    ImGui.begin("Validation Results", open);
    validationDrawn = true;
    if (ImGui.button("Validate Committed Project")) {
      state.validateProject();
      selectedIssue = -1;
    }
    ImGui.textWrapped(
        "Scope: " + state.validationScope + ". Validation does not modify the project.");
    if (state.issues.isEmpty())
      ThemeStyle.text("Success", "OK: Project is valid; no validation issues.");
    else {
      ImGui.text(state.issues.size() + " issue(s)");
      ImGui.beginChild(
          "validation-list", 0, Math.max(100, ImGui.getContentRegionAvailY() * .4f), true);
      var clipper = new ImGuiListClipper();
      try {
        clipper.begin(state.issues.size());
        while (clipper.step())
          for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
            var d = state.issues.get(i);
            String message = Objects.requireNonNullElse(d.message(), "").replace('\n', ' ');
            if (message.length() > 160) message = message.substring(0, 159) + "…";
            if (ImGui.selectable(
                d.severity() + " / " + d.code() + "  " + message + "###issue" + i,
                selectedIssue == i)) selectedIssue = i;
          }
        clipper.end();
      } finally {
        clipper.destroy();
      }
      ImGui.endChild();
      if (selectedIssue < 0 || selectedIssue >= state.issues.size()) selectedIssue = 0;
      var d = state.issues.get(selectedIssue);
      var target = EditorEvents.Target.diagnostic(state.project().project.uuid, d);
      if (ImGui.button("Copy Details"))
        ImGui.setClipboardText(
            d.toString()
                + "\nSeverity: "
                + d.severity()
                + "\nResource: "
                + d.sourceUuid()
                + "\nControl: "
                + d.itemUuid()
                + "\nParameter: "
                + d.parameterKey());
      if (state.canNavigate(target)) {
        ImGui.sameLine();
        if (ImGui.button(target.settings() ? "Open Project Settings" : "Go To Problem"))
          navigate.accept(target);
      }
      ImGui.beginChild("validation-detail", 0, 0, true);
      ImGui.textWrapped(d.toString());
      if (d.sourceUuid() != null) ImGui.textWrapped("Resource: " + d.sourceUuid());
      if (d.itemUuid() != null) ImGui.textWrapped("Control: " + d.itemUuid());
      ImGui.endChild();
    }
    ImGui.end();
  }

  public void assertSmokeDrawn() {
    if (!logsDrawn || !noticesDrawn || !validationDrawn)
      throw new IllegalStateException("Diagnostics panels were not rendered");
    System.out.println("DIAGNOSTICS_AND_NOTIFICATION_NATIVE_PASS");
  }

  private static List<String> choices(Object[] values) {
    var result = new ArrayList<String>();
    result.add("All");
    for (Object v : values) result.add(v.toString());
    return result;
  }
}
