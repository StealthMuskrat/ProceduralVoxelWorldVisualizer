package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.NoiseAuthoring;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import java.util.function.Consumer;

/**
 * Explicit dependency resolution stays private until the complete structural candidate validates.
 */
public final class NoiseEditDialog {
  private final EditorState state;
  private final Noise owner;
  private final io.voxelcreator.model.ProjectData projectOwner;
  private final JsonNode draftBase, projectBase;
  private final UUID target;
  private final Action action;
  private final NoiseDraftIndex index;
  private final Consumer<UUID> applied;
  private final UiFields fields = new UiFields();
  private final DraftChoicePicker picker = new DraftChoicePicker();
  private Resolution resolution = Resolution.REJECT;
  private UUID replacement;
  private NoiseAuthoring.Plan plan;
  private boolean opening = true;
  private String message = "";
  private float[] applyCenter;

  public NoiseEditDialog(EditorState state, Noise owner, Request request, Consumer<UUID> applied) {
    this.state = state;
    this.owner = owner;
    projectOwner = state.project();
    target = request.target();
    action = request.action();
    this.applied = applied;
    draftBase = ProjectIO.JSON.valueToTree(owner);
    projectBase = ProjectIO.JSON.valueToTree(state.project());
    index = new NoiseDraftIndex(owner);
    plan = NoiseAuthoring.plan(state.project(), owner, request);
  }

  public void choose(Resolution resolution, UUID replacement) {
    this.resolution = resolution;
    this.replacement = replacement;
    plan =
        NoiseAuthoring.plan(
            state.project(), owner, new Request(action, target, resolution, replacement));
  }

  public boolean draw() {
    if (opening) {
      ImGui.openPopup("Resolve Noise Item Edit");
      opening = false;
    }
    if (!ImGui.beginPopupModal("Resolve Noise Item Edit", ImGuiWindowFlags.AlwaysAutoResize))
      return false;
    var row = index.get(target);
    ImGui.text(action + " / " + (row == null ? target : row.id()));
    ImGui.textWrapped(
        "Choose how to replace affected inputs and Output. Apply updates the noise draft; Apply in"
            + " Properties saves it to the project.");
    var resolutions = new ArrayList<String>(List.of("REJECT", "REPLACE"));
    if (action == Action.DELETE_PARAMETER) resolutions.add("INLINE_PARAMETER");
    else if (row != null
        && row.section() == NoiseDraftIndex.Section.PROCESSING
        && row.type().equals("Scalar")) resolutions.add("SOURCE");
    fields.choice(
        "Resolution",
        resolution.name(),
        resolutions,
        value -> choose(Resolution.valueOf(value), null));
    if (resolution == Resolution.INLINE_PARAMETER)
      ImGui.textWrapped(
          "Inline Current Value copies this parameter's current value into its bindings and removes"
              + " its pins.");
    if (resolution == Resolution.SOURCE)
      ImGui.textWrapped(
          "Use Raw Source explicitly replaces numeric results or sampled prefixes with the source"
              + " field.");
    if (resolution == Resolution.REPLACE && row != null) {
      var candidates =
          index
              .choices(
                  action == Action.DELETE_PARAMETER ? "parameter" : "replacement",
                  target,
                  row.type())
              .stream()
              .filter(c -> !c.key().equals(target.toString()))
              .toList();
      picker.draw(
          "Replacement",
          replacement == null ? null : replacement.toString(),
          candidates,
          null,
          value -> choose(Resolution.REPLACE, UUID.fromString(value)));
    }
    ImGui.beginChild("edit-diagnostics", 580, 150, true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(plan.diagnostics().size(), 2 * ImGui.getTextLineHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var diagnostic = plan.diagnostics().get(i);
          ImGui.text(diagnostic.code() + " / " + diagnostic.jsonPointer());
          ImGui.textDisabled(diagnostic.message());
          if (ImGui.isItemHovered()) ImGui.setTooltip(diagnostic.toString());
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    boolean current =
        state.draft == owner
            && Objects.equals(state.selected, owner.uuid)
            && state.project() == projectOwner;
    if (!current) ImGui.textWrapped("! Draft or project changed; Cancel and reopen this edit.");
    if (!message.isEmpty()) ImGui.textWrapped(message);
    boolean closed = false;
    ImGui.beginDisabled(!current || !plan.valid());
    if (ImGui.button("Apply Resolved Edit")) {
      try {
        if (!draftBase.equals(ProjectIO.JSON.valueToTree(owner))
            || !projectBase.equals(ProjectIO.JSON.valueToTree(state.project())))
          throw new IllegalStateException("Draft or project changed; Cancel and reopen this edit");
        plan.applyTo(owner);
        applied.accept(plan.selected());
        closed = true;
      } catch (RuntimeException invalid) {
        message = invalid.getMessage();
      }
    }
    var min = ImGui.getItemRectMin();
    var max = ImGui.getItemRectMax();
    applyCenter = new float[] {(min.x + max.x) / 2, (min.y + max.y) / 2};
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) closed = true;
    if (closed) ImGui.closeCurrentPopup();
    ImGui.endPopup();
    return closed;
  }

  float[] applyCenter() {
    return applyCenter.clone();
  }
}
