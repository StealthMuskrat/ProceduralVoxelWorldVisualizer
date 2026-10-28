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
  private float[] applyCenter;

  public NoiseEditDialog(EditorState state, Noise owner, Request request, Consumer<UUID> applied) {
    this.state = state;
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
    } catch (RuntimeException invalid) {
      message = Objects.requireNonNullElse(invalid.getMessage(), invalid.toString());
    }
  }

  public boolean draw() {
    if (session.closed()) return true;
    if (opening) {
      ImGui.openPopup("Resolve Noise Item Edit");
      opening = false;
    }
    if (!ImGui.beginPopupModal("Resolve Noise Item Edit", ImGuiWindowFlags.AlwaysAutoResize))
      return false;
    boolean current = session.current();
    ImGui.text(action + " / " + bounded(row == null ? target.toString() : row.id(), 1024));
    ImGui.textWrapped(
        "Choose how to replace affected inputs and Output. Apply updates the noise draft; Apply in"
            + " Properties saves it to the project.");
    var resolutions = new ArrayList<String>(List.of("REJECT", "REPLACE"));
    if (action == Action.DELETE_PARAMETER) resolutions.add("INLINE_PARAMETER");
    else if (row != null
        && row.section() == NoiseDraftIndex.Section.PROCESSING
        && row.type().equals("Scalar")) resolutions.add("SOURCE");
    ImGui.beginDisabled(!current);
    fields.choice(
        "Resolution",
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
    var plan = session.preview();
    ImGui.beginChild("edit-diagnostics", 580, 150, true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(plan.diagnostics().size(), 2 * ImGui.getTextLineHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var diagnostic = plan.diagnostics().get(i);
          ImGui.text(bounded(diagnostic.code() + " / " + diagnostic.jsonPointer(), 1024));
          ImGui.textDisabled(bounded(diagnostic.message(), 1024));
          if (ImGui.isItemHovered()) ImGui.setTooltip(bounded(diagnostic.toString(), 4096));
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    if (!current) ImGui.textWrapped("! Draft or project changed; Cancel and reopen this edit.");
    if (!message.isEmpty()) ImGui.textWrapped(bounded(message, 2048));
    boolean closed = false;
    ImGui.beginDisabled(!current || !plan.valid());
    if (ImGui.button("Apply Resolved Edit")) {
      try {
        UUID selected = session.stage();
        closed = true;
        // Focus is a consequence of accepted staging. A failed callback must not reopen/reapply it.
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
      }
    }
    var min = ImGui.getItemRectMin();
    var max = ImGui.getItemRectMax();
    applyCenter = new float[] {(min.x + max.x) / 2, (min.y + max.y) / 2};
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      session.cancel();
      closed = true;
    }
    if (closed) ImGui.closeCurrentPopup();
    ImGui.endPopup();
    return closed;
  }

  private static String bounded(String value, int limit) {
    value = Objects.requireNonNullElse(value, "");
    return value.length() > limit ? value.substring(0, limit - 1) + "…" : value;
  }

  float[] applyCenter() {
    return applyCenter == null ? null : applyCenter.clone();
  }
}
