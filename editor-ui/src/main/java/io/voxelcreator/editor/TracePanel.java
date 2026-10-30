package io.voxelcreator.editor;

import imgui.*;
import imgui.type.*;
import java.util.*;
import java.util.function.Consumer;

/** Shared immutable trace view with measured clipped lists and persistent owned actions. */
public final class TracePanel {
  private final EditorState state;
  private final GuardedNavigation navigation;
  private final Consumer<String> copy;
  private final ImString query = new ImString(256);
  private final TraceObservationSelection observations = new TraceObservationSelection();
  final SettingsPageChrome chrome = new SettingsPageChrome(3, 3);
  private TraceSnapshot snapshot;
  private String publication = UUID.randomUUID().toString();
  private int step, submittedSteps, submittedRows, page;
  private String message = "";
  private TraceSnapshot.Row pagedRow;
  private boolean revealValues;
  private float[] goToCenter;
  private DialogBounds.Rect workspaceBounds, stepsBounds, valuesBounds;
  private float rowStride;
  private final Map<String, float[]> controls = new LinkedHashMap<>();
  private final Map<String, Integer> rowIds = new LinkedHashMap<>();

  public TracePanel(EditorState state, GuardedNavigation navigation) {
    this(state, navigation, ImGui::setClipboardText);
  }

  TracePanel(EditorState state, GuardedNavigation navigation, Consumer<String> copy) {
    this.state = Objects.requireNonNull(state);
    this.navigation = Objects.requireNonNull(navigation);
    this.copy = Objects.requireNonNull(copy);
  }

  public void draw(TraceSnapshot value) {
    draw(value, List.of(), false);
  }

  void drawContained(TraceSnapshot value, List<String> notices) {
    draw(value, List.copyOf(notices), true);
  }

  /** Keep source decisions current even when the containing Inspector is hidden or on XYZ. */
  void hide(TraceSnapshot value) {
    submittedSteps = submittedRows = 0;
    goToCenter = null;
    controls.clear();
    rowIds.clear();
    workspaceBounds = stepsBounds = valuesBounds = null;
    if (snapshot != value) {
      snapshot = value;
      step = page = 0;
      pagedRow = null;
      revealValues = false;
      observations.clear();
      query.set("");
      message = "";
      publication = UUID.randomUUID().toString();
    }
  }

  private void draw(TraceSnapshot value, List<String> notices, boolean contained) {
    hide(value);
    if (value == null) {
      ImGui.textWrapped("No retained trace selected.");
      return;
    }
    float available =
        contained
            ? ImGui.getContentRegionAvailY()
            : ImGui.getWindowSizeY() - ImGui.getCursorPosY() - ImGui.getStyle().getWindowPaddingY();
    float height =
        contained
            ? TraceLayout.contained(available)
            : TraceLayout.workspace(available, ImGui.getFrameHeight());
    UiScale.beginChild("trace-workspace", 0, height, false);
    workspaceBounds = bounds();
    chrome.begin("trace-body");
    for (var notice : notices) ImGui.textWrapped(notice);
    ImGui.textWrapped(shortText(value.summary(), 1024));
    var freshness = value.freshness(state.project().project.uuid, state.revision);
    if (!contained && freshness == TraceSnapshot.Freshness.OTHER_PROJECT)
      ImGui.textWrapped("Historical project: source links are unavailable.");
    else if (!contained && freshness == TraceSnapshot.Freshness.PROJECT_CHANGED)
      ImGui.textWrapped(
          "Project changed after this trace. Values belong to its snapshot; changed source links"
              + " require a new trace.");
    if (!message.isEmpty()) ImGui.textWrapped(message);
    var steps = value.trace().steps();
    TraceObservationSelection.Review review = null;
    if (steps.isEmpty()) {
      observations.clear();
      ImGui.textWrapped(
          "No active operation was reached (including coordinates outside project Y bounds).");
    } else {
      step = Math.min(step, steps.size() - 1);
      drawSteps(value);
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      ImGui.pushID(publication);
      ImGui.inputTextWithHint(
          "##trace-search", "Find comparison, setting, noise item or value...", query);
      control("query");
      ImGui.popID();
      var filtered = observations.observe(value, step, query.get());
      ImGui.textWrapped(filtered.size() + " observations and controls");
      drawValues(value, filtered);
      review = observations.review();
      if (review != null) {
        var selected = review.value();
        if (pagedRow != selected) {
          pagedRow = selected;
          page = 0;
        }
        ImGui.textWrapped(shortText(selected.label(), 512));
        ImGui.textWrapped("Page " + (page + 1) + " / " + TraceText.count(selected.value()));
        ImGui.textWrapped(TraceText.page(selected.value(), page));
      } else ImGui.textWrapped("No observation matches this filter.");
    }
    chrome.end();
    drawActions(value, review);
    ImGui.endChild();
  }

  private void drawSteps(TraceSnapshot value) {
    UiScale.beginChild(
        "trace-steps",
        0,
        TraceLayout.steps(chrome.body().height(), ImGui.getTextLineHeightWithSpacing()),
        true);
    stepsBounds = bounds();
    rowStride = (float) Math.ceil(ImGui.getTextLineHeightWithSpacing());
    ImGui.pushID(publication);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(value.trace().steps().size(), rowStride);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var item = value.trace().steps().get(i);
          float top = ImGui.getCursorPosY();
          String label =
              shortText(
                  (i + 1)
                      + " / "
                      + item.layerId()
                      + " / "
                      + item.operationId()
                      + " / "
                      + item.action(),
                  140);
          if (ImGui.selectable(
              label + "###trace-step-" + i,
              step == i,
              0,
              Math.max(1, ImGui.getContentRegionAvailX()),
              ImGui.getTextLineHeight())) {
            step = i;
            page = 0;
            observations.clear();
            revealValues = true;
          }
          rowIds.put("step:" + i, ImGui.getID(label + "###trace-step-" + i));
          control("step:" + i);
          PopupLayout.clippedLabel(label);
          tail(top);
          submittedSteps++;
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.popID();
    ImGui.endChild();
  }

  private void drawValues(TraceSnapshot value, List<Integer> filtered) {
    UiScale.beginChild(
        "trace-values",
        0,
        TraceLayout.values(chrome.body().height(), ImGui.getTextLineHeightWithSpacing()),
        true);
    valuesBounds = bounds();
    ImGui.pushID(publication + ":" + step);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(filtered.size(), rowStride);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var item = value.rows(step).get(filtered.get(i));
          float top = ImGui.getCursorPosY();
          String label = shortText(item.label(), 80) + " : " + shortText(item.value(), 100);
          String id = label + "###trace-value-" + filtered.get(i);
          if (ImGui.selectable(
              id,
              observations.index() == filtered.get(i),
              0,
              Math.max(1, ImGui.getContentRegionAvailX()),
              ImGui.getTextLineHeight())) observations.select(i);
          rowIds.put("value:" + filtered.get(i), ImGui.getID(id));
          control("value:" + filtered.get(i));
          PopupLayout.clippedLabel(label);
          tail(top);
          submittedRows++;
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.popID();
    ImGui.endChild();
    if (revealValues) {
      ImGui.setScrollHereY(.25f);
      revealValues = false;
    }
  }

  private void drawActions(TraceSnapshot shown, TraceObservationSelection.Review review) {
    String shownQuery = query.get();
    // Scope native activation to the exact shown trace/step/row, including across held gestures.
    ImGui.pushID(publication + ":" + step + ":" + (review == null ? -1 : review.row()));
    if (chrome.action("copy-trace", "Copy Trace Details")) copy.accept(shown.details());
    control("copy-trace");
    var voxel = shown.finalTypeTarget();
    ImGui.beginDisabled(voxel == null || !state.canNavigate(voxel));
    if (chrome.action("voxel", "Go To Voxel Type")) {
      if (navigation.request(voxel, () -> snapshot == shown && shown.available(state, voxel))
          == SearchNavigation.Result.UNAVAILABLE)
        message = "Voxel type changed since this trace; retrace before navigating.";
    }
    control("voxel");
    ImGui.endDisabled();
    var target = review == null ? null : review.value().target();
    boolean available = target != null && state.canNavigate(target);
    ImGui.beginDisabled(!available);
    if (chrome.action("control", "Go To Control")) {
      var result = navigation.request(target, () -> available(shown, review, shownQuery, target));
      message =
          result == SearchNavigation.Result.UNAVAILABLE
              ? "Source or trace selection changed. Retrace or finish the current edit before"
                  + " navigating."
              : "";
    }
    control("control");
    if (available) goToCenter = controls.get("control");
    ImGui.endDisabled();
    ImGui.beginDisabled(review == null);
    if (chrome.action("copy-value", "Copy Selected Value")) copy.accept(review.value().value());
    control("copy-value");
    ImGui.endDisabled();
    int pages = review == null ? 1 : TraceText.count(review.value().value());
    ImGui.beginDisabled(review == null || page <= 0);
    if (chrome.action("previous", "Previous Page")) page--;
    control("previous");
    ImGui.endDisabled();
    ImGui.beginDisabled(review == null || page + 1 >= pages);
    if (chrome.action("next", "Next Page")) page++;
    control("next");
    ImGui.endDisabled();
    ImGui.popID();
  }

  private boolean available(
      TraceSnapshot shown,
      TraceObservationSelection.Review review,
      String shownQuery,
      EditorEvents.Target target) {
    boolean current =
        snapshot == shown
            && observations.current(review)
            && query.get().equals(shownQuery)
            && shown.available(state, target);
    if (!current)
      message = "Source or trace selection changed. Select an observation again before navigating.";
    return current;
  }

  private void tail(float top) {
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

  private static String shortText(String text, int length) {
    return text.isEmpty() ? "" : AppMetadata.uiText(StatusBarText.clip(text, length));
  }

  private void control(String key) {
    var r = PopupLayout.itemBounds();
    controls.put(key, new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2});
  }

  void filter(String text) {
    query.set(text);
    revealValues = true;
  }

  float[] goToCenter() {
    return goToCenter;
  }

  int submittedRows() {
    return submittedRows;
  }

  int submittedSteps() {
    return submittedSteps;
  }

  float[] controlAt(String key) {
    return controls.get(key);
  }

  DialogBounds.Rect workspaceBounds() {
    return workspaceBounds;
  }

  DialogBounds.Rect stepsBounds() {
    return stepsBounds;
  }

  DialogBounds.Rect valuesBounds() {
    return valuesBounds;
  }

  Map<String, Integer> rowIds() {
    return Map.copyOf(rowIds);
  }

  long queries() {
    return observations.builds();
  }

  float rowStride() {
    return rowStride;
  }

  TraceObservationSelection.Review review() {
    return observations.review();
  }

  int page() {
    return page;
  }
}
