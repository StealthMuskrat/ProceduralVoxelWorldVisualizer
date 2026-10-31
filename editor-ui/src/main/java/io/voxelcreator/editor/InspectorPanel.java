package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImString;
import java.util.*;
import java.util.function.*;

/** Inspector presentation; request, document, camera and bookmark ownership stay in Viewports. */
final class InspectorPanel {
  enum Action {
    NONE,
    TRACE,
    CANCEL,
    BOOKMARK,
    RESTORE,
    REFRESH
  }

  record Status(
      TraceSnapshot trace,
      InspectorRequests.Request request,
      String feedback,
      String hint,
      String error) {
    Status {
      Objects.requireNonNull(feedback);
      Objects.requireNonNull(hint);
      Objects.requireNonNull(error);
    }

    boolean pending() {
      return request != null;
    }

    boolean pick() {
      return request != null && request.viewBound();
    }
  }

  private final EditorState state;
  private final InspectorCoordinates coordinates;
  private final Function<String, ImString> buffers;
  private final BiConsumer<String, String> edit;
  private final Consumer<String> record;
  final InspectorCoordinateFields fields = new InspectorCoordinateFields();
  final SettingsPageChrome chrome = new SettingsPageChrome(3, 3);
  private final Map<String, float[]> controls = new LinkedHashMap<>();
  private boolean tracePage, revealPage;
  private boolean traceVisible, hintVisible, draftNoticeVisible, freshnessNoticeVisible;
  private final SettingsActionScope actions = new SettingsActionScope();
  private final String actionId = UUID.randomUUID().toString();
  private InspectorActionContext actionContext;

  InspectorPanel(
      EditorState state,
      InspectorCoordinates coordinates,
      Function<String, ImString> buffers,
      BiConsumer<String, String> edit,
      Consumer<String> record) {
    this.state = Objects.requireNonNull(state);
    this.coordinates = Objects.requireNonNull(coordinates);
    this.buffers = Objects.requireNonNull(buffers);
    this.edit = Objects.requireNonNull(edit);
    this.record = Objects.requireNonNull(record);
  }

  void showCoordinates(boolean revealProblem) {
    tracePage = false;
    revealPage = true;
    fields.reveal(revealProblem ? coordinates.problemAxis(displayed()) : null);
  }

  void showTrace() {
    tracePage = true;
    revealPage = true;
    fields.reveal(null);
  }

  String section() {
    return tracePage ? "Trace" : "Coordinates";
  }

  private Map<String, String> displayed() {
    var result = new LinkedHashMap<String, String>();
    for (var axis : coordinates.axes()) result.put(axis, buffers.apply(axis).get());
    return result;
  }

  private boolean differs(TraceSnapshot trace) {
    if (trace == null) return false;
    if (coordinates.differs(trace.trace().position())) return true;
    for (var axis : coordinates.axes())
      if (!coordinates.text(axis).equals(buffers.apply(axis).get())) return true;
    return false;
  }

  Action draw(Supplier<Status> currentStatus, TracePanel tracePanel) {
    var status = Objects.requireNonNull(currentStatus.get());
    controls.clear();
    traceVisible = hintVisible = draftNoticeVisible = freshnessNoticeVisible = false;
    var trace = status.trace();
    if (tracePanel != null && (!tracePage || trace == null)) tracePanel.hide(trace);
    var freshness =
        trace == null ? null : trace.freshness(state.project().project.uuid, state.revision);
    chrome.begin("inspector-body");
    if (revealPage) {
      ImGui.setScrollY(0);
      revealPage = false;
    }
    if (!tracePage) {
      ImGui.text("Coordinates");
      fields.draw(buffers, edit, axis -> recordControl("trace-" + axis.toLowerCase(Locale.ROOT)));
      String problem = coordinates.problem();
      if (!problem.isEmpty()) ImGui.textWrapped("! " + problem);
      if (!status.error().isEmpty()) ImGui.textWrapped("! " + status.error());
    }
    var notices = new ArrayList<String>();
    if (freshness == TraceSnapshot.Freshness.PROJECT_CHANGED) {
      notices.add(
          "Project changed since this trace. Refresh its displayed coordinate in the current"
              + " project; entered XYZ stays unchanged.");
      freshnessNoticeVisible = true;
    } else if (freshness == TraceSnapshot.Freshness.OTHER_PROJECT) {
      notices.add(
          "This trace belongs to another project. Enter a coordinate and use Trace XYZ to inspect"
              + " the current project.");
      freshnessNoticeVisible = true;
    }
    if (!status.feedback().isEmpty()) notices.add(status.feedback());
    if (!status.pending()
        && !status.hint().isEmpty()
        && (status.feedback().isEmpty() || trace != null)) {
      notices.add(status.hint());
      hintVisible = true;
    }
    boolean different = differs(trace);
    if (different) {
      var p = trace.trace().position();
      notices.add(
          String.format(
              Locale.ROOT,
              "XYZ differs from the displayed trace at X %d, Y %d, Z %d. Trace the entered"
                  + " coordinate or use the traced coordinate.",
              p.x(),
              p.y(),
              p.z()));
      draftNoticeVisible = true;
    }
    if (tracePage && trace != null && tracePanel != null) {
      if (!status.feedback().isEmpty() || different) notices.add("Previous trace result");
      // Notices scroll with the trace body, preserving both sets of action rows.
      tracePanel.drawContained(trace, notices);
      traceVisible = true;
    } else {
      for (var notice : notices) ImGui.textWrapped(notice);
      if (tracePage)
        ImGui.textWrapped(
            status.pending()
                ? "Waiting for the requested trace."
                : "No trace selected. Enter XYZ or choose a voxel in World.");
      else
        ImGui.textWrapped(
            "Trace XYZ inspects the committed project. Bookmark opens a separate explicit edit. Use"
                + " Traced replaces entered XYZ without tracing.");
    }
    chrome.end();
    var context =
        InspectorActionContext.capture(state, coordinates, displayed(), status, tracePage);
    if (!context.same(actionContext)) actionContext = context;
    long version = actions.synchronize(context.lifetime(), actionContext);
    ImGui.pushID("inspector-actions:" + actionId + ":" + version);
    boolean traceClicked = action("trace-coordinate", "Trace XYZ", true);
    boolean cancel =
        action("trace-cancel", status.pick() ? "Cancel Pick" : "Cancel Trace", status.pending());
    boolean bookmark = action("trace-bookmark", "Bookmark", true);
    boolean restore = action("trace-restore", "Use Traced", differs(trace));
    boolean refresh =
        action(
            "trace-refresh",
            "Refresh",
            trace != null
                && trace.freshness(state.project().project.uuid, state.revision)
                    == TraceSnapshot.Freshness.PROJECT_CHANGED);
    boolean section = action("trace-page", tracePage ? "XYZ" : "Trace", true);
    ImGui.popID();
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)
        || !context.same(
            InspectorActionContext.capture(
                state,
                coordinates,
                displayed(),
                Objects.requireNonNull(currentStatus.get()),
                tracePage))
        || !actions.current(state.document.sessionToken(), actionContext)) return Action.NONE;
    // A cancellation decision takes priority over starting/replacing work in the same frame.
    if (cancel) return Action.CANCEL;
    if (restore) return Action.RESTORE;
    if (refresh) return Action.REFRESH;
    if (bookmark) return Action.BOOKMARK;
    if (traceClicked) return Action.TRACE;
    if (section) {
      if (tracePage) showCoordinates(true);
      else showTrace();
    }
    return Action.NONE;
  }

  void suspend() {
    actions.release();
    controls.clear();
  }

  private boolean action(String key, String label, boolean enabled) {
    ImGui.beginDisabled(!enabled);
    boolean pressed = chrome.identifiedAction(key, label);
    ImGui.endDisabled();
    if (enabled) recordControl(key);
    return enabled && pressed;
  }

  private void recordControl(String key) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    controls.put(key, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
    record.accept(key);
  }

  float[] control(String key) {
    return controls.get(key);
  }

  boolean traceVisible() {
    return traceVisible;
  }

  boolean hintVisible() {
    return hintVisible;
  }

  boolean draftNoticeVisible() {
    return draftNoticeVisible;
  }

  boolean freshnessNoticeVisible() {
    return freshnessNoticeVisible;
  }
}
