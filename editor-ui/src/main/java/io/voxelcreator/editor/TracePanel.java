package io.voxelcreator.editor;

import imgui.*;
import imgui.type.*;
import java.util.*;

/** Shared Inspector/Logs trace view: clipped summaries, one selected step and fixed-height rows. */
public final class TracePanel {
  private final EditorState state;
  private final GuardedNavigation navigation;
  private final ImString query = new ImString(256);
  private TraceSnapshot snapshot;
  private int step, row, submittedSteps, submittedRows;
  private String previousQuery = "", message = "";
  private List<Integer> filtered = List.of();
  private int indexedStep = -1;
  private float[] goToCenter;
  private TraceSnapshot.Row pagedRow;
  private int page;

  public TracePanel(EditorState state, GuardedNavigation navigation) {
    this.state = state;
    this.navigation = navigation;
  }

  public void draw(TraceSnapshot value) {
    submittedSteps = submittedRows = 0;
    goToCenter = null;
    if (snapshot != value) {
      snapshot = value;
      step = row = 0;
      indexedStep = -1;
      query.set("");
      message = "";
    }
    if (value == null) {
      ImGui.textDisabled("No retained trace selected.");
      return;
    }
    ImGui.textWrapped(shortText(value.summary(), 1024));
    var freshness = value.freshness(state.project().project.uuid, state.revision);
    if (freshness == TraceSnapshot.Freshness.OTHER_PROJECT)
      ImGui.textWrapped("Historical project: source links are unavailable.");
    else if (freshness == TraceSnapshot.Freshness.PROJECT_CHANGED)
      ImGui.textWrapped(
          "Project changed after this trace. Values belong to its snapshot; changed source links"
              + " require a new trace.");
    if (ImGui.button("Copy Trace Details")) ImGui.setClipboardText(value.details());
    if (value.finalTypeTarget() != null && state.canNavigate(value.finalTypeTarget())) {
      ImGui.sameLine();
      if (ImGui.button("Go To Voxel Type")) {
        var target = value.finalTypeTarget();
        if (navigation.request(target, () -> value.available(state, target))
            == SearchNavigation.Result.UNAVAILABLE)
          message = "Voxel type changed since this trace; retrace before navigating.";
      }
    }
    if (!message.isEmpty()) ImGui.textWrapped(message);
    var steps = value.trace().steps();
    if (steps.isEmpty()) {
      ImGui.textWrapped(
          "No active operation was reached (including coordinates outside project Y bounds).");
      return;
    }
    step = Math.min(step, steps.size() - 1);
    ImGui.beginChild("trace-steps", 0, 120, true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(steps.size());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          submittedSteps++;
          var s = steps.get(i);
          String label =
              (i + 1) + " / " + s.layerId() + " / " + s.operationId() + " / " + s.action();
          if (ImGui.selectable(shortText(label, 140) + "###trace-step-" + i, step == i)) {
            step = i;
            row = 0;
            indexedStep = -1;
          }
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    ImGui.inputTextWithHint(
        "##trace-search", "Find comparison, setting, noise item or value...", query);
    String text = query.get().strip().toLowerCase(Locale.ROOT);
    var rows = value.rows(step);
    if (indexedStep != step || !text.equals(previousQuery)) {
      var matches = new ArrayList<Integer>();
      for (int i = 0; i < rows.size(); i++) {
        var r = rows.get(i);
        if (text.isEmpty() || (r.label() + " " + r.value()).toLowerCase(Locale.ROOT).contains(text))
          matches.add(i);
      }
      filtered = List.copyOf(matches);
      previousQuery = text;
      indexedStep = step;
      row = 0;
    }
    ImGui.text(filtered.size() + " observations and controls");
    ImGui.beginChild("trace-values", 0, 180, true);
    var values = new ImGuiListClipper();
    try {
      values.begin(filtered.size());
      while (values.step())
        for (int i = values.getDisplayStart(); i < values.getDisplayEnd(); i++) {
          submittedRows++;
          var r = rows.get(filtered.get(i));
          if (ImGui.selectable(
              shortText(r.label(), 80)
                  + " : "
                  + shortText(r.value(), 100)
                  + "###trace-value-"
                  + filtered.get(i),
              row == i)) row = i;
        }
      values.end();
    } finally {
      values.destroy();
    }
    ImGui.endChild();
    if (!filtered.isEmpty()) {
      row = Math.min(row, filtered.size() - 1);
      var selected = rows.get(filtered.get(row));
      if (pagedRow != selected) {
        pagedRow = selected;
        page = 0;
      }
      ImGui.textWrapped(shortText(selected.label(), 512));
      int pages = TraceText.count(selected.value());
      if (pages > 1) {
        if (ImGui.smallButton("Previous Page") && page > 0) page--;
        ImGui.sameLine();
        if (ImGui.smallButton("Next Page") && page + 1 < pages) page++;
        ImGui.sameLine();
        ImGui.text("Page " + (page + 1) + " / " + pages);
      }
      ImGui.textWrapped(TraceText.page(selected.value(), page));
      if (ImGui.smallButton("Copy Selected Value")) ImGui.setClipboardText(selected.value());
      if (selected.target() != null && state.canNavigate(selected.target())) {
        boolean clicked = ImGui.button("Go To Control");
        var a = ImGui.getItemRectMin();
        var b = ImGui.getItemRectMax();
        goToCenter = new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2};
        if (clicked) {
          var target = selected.target();
          var result = navigation.request(target, () -> value.available(state, target));
          message =
              result == SearchNavigation.Result.UNAVAILABLE
                  ? "Source changed, was deleted, or an editor is open. Retrace or finish the"
                      + " current edit before navigating."
                  : "";
        }
      } else if (selected.target() != null)
        ImGui.textDisabled("Source unavailable in the current project or draft.");
    }
  }

  private static String shortText(String text, int length) {
    String visible = text.length() > length ? text.substring(0, length - 1) + "…" : text;
    return AppMetadata.uiText(visible.replace('\n', ' ').replace('\r', ' '));
  }

  void filter(String text) {
    query.set(text);
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
}
