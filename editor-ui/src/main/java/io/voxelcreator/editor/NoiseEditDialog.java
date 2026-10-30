package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import java.util.function.Consumer;

/**
 * Explicit dependency resolution stays private until the complete structural candidate validates.
 */
public final class NoiseEditDialog {
  private final EditorState state;
  final DialogLayout layout;
  private final NoiseEditSession session;
  private final UUID target;
  private final Action action;
  private final NoiseDraftIndex.Row row;
  private final List<DraftChoicePicker.Choice> candidates;
  private final Consumer<UUID> applied;
  private final UiFields fields = new UiFields();
  private final DraftChoicePicker picker = new DraftChoicePicker();
  private boolean opening = true;
  private String message = "";
  final SettingsPageChrome chrome = new SettingsPageChrome(2, 2);
  private final String nativeId = UUID.randomUUID().toString();
  private boolean issues, reveal = true, issuesReveal = true;
  private int submitted;

  public NoiseEditDialog(EditorState state, Noise owner, Request request, Consumer<UUID> applied) {
    this.state = state;
    layout = new DialogLayout(state, "Properties", 640, 510);
    this.applied = Objects.requireNonNull(applied);
    session = new NoiseEditSession(state, owner, request);
    target = request.target();
    action = request.action();
    var index = new NoiseDraftIndex(owner);
    row = index.get(target);
    candidates =
        row == null
            ? List.of()
            : index
                .choices(
                    action == Action.DELETE_PARAMETER ? "parameter" : "replacement",
                    target,
                    row.type())
                .stream()
                .filter(c -> !c.key().equals(target.toString()))
                .toList();
  }

  public void choose(Resolution resolution, UUID replacement) {
    try {
      session.choose(resolution, replacement);
      message = "";
      reveal = issuesReveal = true;
    } catch (RuntimeException invalid) {
      message = Objects.requireNonNullElse(invalid.getMessage(), invalid.toString());
      reveal = true;
    }
  }

  public boolean draw() {
    String title = "Resolve Noise Item Edit";
    if (session.closed()) {
      if (layout.begin(title)) {
        ImGui.closeCurrentPopup();
        ImGui.endPopup();
      }
      return true;
    }
    if (opening) {
      layout.open(title);
      opening = false;
    }
    if (!layout.begin(title)) return false;
    boolean current = session.current();
    chrome.begin("noise-edit-body");
    if (reveal) {
      ImGui.setScrollY(0);
      reveal = false;
    }
    ImGui.textWrapped(action + " / " + bounded(row == null ? target.toString() : row.id(), 1024));
    if (!current) ImGui.textWrapped("Draft or project changed; Cancel and reopen this edit.");
    if (!message.isEmpty()) ImGui.textWrapped(bounded(message, 2048));
    if (issues) diagnostics();
    else resolution(current);
    chrome.end();
    ImGui.pushID(nativeId);
    ImGui.beginDisabled(!current || !session.preview().valid());
    boolean apply = chrome.identifiedAction("apply", "Apply Resolved Edit");
    ImGui.endDisabled();
    boolean cancel =
        chrome.identifiedAction("cancel", "Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false);
    boolean showResolution = chrome.identifiedAction("resolution", "Resolution");
    boolean showIssues =
        chrome.identifiedAction(
            "issues", "Issues (" + session.preview().diagnostics().size() + ")");
    ImGui.popID();
    boolean closed = false;
    if (cancel) {
      session.cancel();
      closed = true;
    } else if (apply) {
      try {
        UUID selected = session.stage();
        closed = true;
        try {
          applied.accept(selected);
        } catch (RuntimeException failure) {
          state.error(
              "NOISE_EDIT_FOCUS_FAILED",
              EditorEvents.Subsystem.APPLICATION,
              "Noise edit staged in Properties; focus could not be updated: "
                  + Objects.requireNonNullElse(failure.getMessage(), failure.toString()));
        }
      } catch (RuntimeException invalid) {
        message = Objects.requireNonNullElse(invalid.getMessage(), invalid.toString());
        reveal = true;
      }
    } else if (showResolution && issues) {
      issues = false;
      reveal = true;
    } else if (showIssues && !issues) {
      issues = true;
      reveal = issuesReveal = true;
    }
    if (closed) ImGui.closeCurrentPopup();
    ImGui.endPopup();
    return closed;
  }

  private void resolution(boolean current) {
    submitted = 0;
    ImGui.textWrapped(
        "Choose how to replace affected inputs and Output. Apply updates the noise draft; Apply in"
            + " Properties saves it to the project.");
    var resolutions = new ArrayList<String>(List.of("REJECT", "REPLACE"));
    if (action == Action.DELETE_PARAMETER) resolutions.add("INLINE_PARAMETER");
    else if (row != null
        && row.section() == NoiseDraftIndex.Section.PROCESSING
        && row.type().equals("Scalar")) resolutions.add("SOURCE");
    ImGui.pushID(nativeId);
    ImGui.beginDisabled(!current);
    // A separate label preserves the complete preview width in narrow content.
    ImGui.text("Resolution");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice(
        "##resolution",
        session.resolution().name(),
        resolutions,
        value -> choose(Resolution.valueOf(value), null));
    if (session.resolution() == Resolution.INLINE_PARAMETER)
      ImGui.textWrapped(
          "Inline Current Value copies this parameter's current value into its bindings and removes"
              + " its pins.");
    if (session.resolution() == Resolution.SOURCE)
      ImGui.textWrapped(
          "Use Raw Source explicitly replaces numeric results or sampled prefixes with the source"
              + " field.");
    if (session.resolution() == Resolution.REPLACE && row != null)
      picker.draw(
          "Replacement",
          session.replacement() == null ? null : session.replacement().toString(),
          candidates,
          null,
          value -> choose(Resolution.REPLACE, UUID.fromString(value)));
    ImGui.endDisabled();
    ImGui.popID();
    ImGui.textWrapped(
        session.preview().diagnostics().size() + " issue(s); see Issues for the complete list.");
  }

  private void diagnostics() {
    submitted = 0;
    var diagnostics = session.preview().diagnostics();
    ImGui.textWrapped(diagnostics.size() + " issue(s)");
    UiScale.beginChild(
        "edit-diagnostics", 0, ResourceReviewLayout.content(ImGui.getContentRegionAvailY()), true);
    if (issuesReveal) {
      ImGui.setScrollY(0);
      issuesReveal = false;
    }
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(diagnostics.size(), 2 * ImGui.getTextLineHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var diagnostic = diagnostics.get(i);
          submitted++;
          ImGui.text(bounded(diagnostic.code() + " / " + diagnostic.jsonPointer(), 1024));
          ImGui.textDisabled(bounded(diagnostic.message(), 1024));
          if (ImGui.isItemHovered()) UiScale.tooltip(bounded(diagnostic.toString(), 4096));
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
  }

  private static String bounded(String value, int limit) {
    return StatusBarText.clip(Objects.requireNonNullElse(value, ""), limit);
  }

  float[] applyCenter() {
    return control("apply");
  }

  float[] control(String key) {
    var b = chrome.actions().get(key);
    return b == null ? null : new float[] {b.x() + b.width() / 2, b.y() + b.height() / 2};
  }

  String page() {
    return issues ? "Issues" : "Resolution";
  }

  String nativeId() {
    return nativeId;
  }

  void probeCancel() {
    session.cancel();
  }

  int submitted() {
    return submitted;
  }
}
