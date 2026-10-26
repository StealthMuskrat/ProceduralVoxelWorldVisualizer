package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import imgui.*;
import imgui.type.*;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.Path;
import java.util.*;

/** Clipped searchable application metadata/default editing; only Apply affects the live profile. */
public final class ApplicationSettingsPanel {
  private final EditorState state;
  private final UiFields fields = new UiFields();
  private final ImString filter = new ImString(256);
  private final Map<String, ImString> values = new HashMap<>();
  private final Map<String, String> errors = new LinkedHashMap<>();
  private MetadataEditSession session;
  private String page = "Presentation",
      selected = "noise2D.algorithm.CONSTANT.value",
      message = "",
      problem = "";
  private boolean invalidated = true, drawn, draftChanged;

  public ApplicationSettingsPanel(EditorState state) {
    this.state = state;
  }

  private void ensure() {
    if (session == null) session = new MetadataEditSession(state.metadata);
  }

  private void changed() {
    invalidated = true;
    draftChanged = true;
  }

  private void select(String id) {
    if (!selected.equals(id)) {
      selected = id;
      fields.clear();
      values.clear();
    }
  }

  public void draw() {
    ensure();
    drawn = true;
    ImGui.textWrapped(
        "Private application profile. Apply saves labels/help and validated defaults for future"
            + " user-created items. Existing project values remain authored values.");
    fields.choice(
        "Section",
        page,
        List.of("Presentation", "Creation Defaults"),
        value -> {
          page = value;
          fields.clear();
          values.clear();
        });
    ImGui.inputTextWithHint(
        "##metadata-filter", "Filter labels, groups or stable control IDs...", filter);
    String query = filter.get().strip().toLowerCase(Locale.ROOT);
    var draft = session.draft();
    var rows =
        draft.orderedControls().stream()
            .filter(
                e ->
                    page.equals("Presentation")
                        || CreationRegistry.CONTROLS.containsKey(e.getKey()))
            .filter(
                e ->
                    (e.getKey() + " " + e.getValue().label + " " + e.getValue().group)
                        .toLowerCase(Locale.ROOT)
                        .contains(query))
            .toList();
    ImGui.beginChild("metadata-results", 0, 150, true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = rows.get(i);
          if (ImGui.selectable(
              AppMetadata.uiText(row.getValue().group)
                  + " / "
                  + AppMetadata.uiText(row.getValue().label)
                  + "###profile"
                  + row.getKey(),
              row.getKey().equals(selected))) select(row.getKey());
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    ImGui.beginChild(
        "metadata-detail", 0, Math.max(150, ImGui.getContentRegionAvailY() - 110), true);
    var control = draft.controls.get(selected);
    if (control == null) ImGui.textDisabled("Select a control.");
    else {
      ImGui.textWrapped("Stable ID: " + selected);
      if (page.equals("Presentation")) presentation(control);
      else if (CreationRegistry.CONTROLS.containsKey(selected))
        creation(CreationRegistry.get(selected));
      else ImGui.textDisabled("Select a creation control from the list.");
    }
    ImGui.endChild();
    if (invalidated) {
      try {
        draft.validate();
        problem = "";
      } catch (RuntimeException e) {
        problem = Objects.toString(e.getMessage(), "Invalid application metadata");
      }
      invalidated = false;
    }
    String issue = !errors.isEmpty() ? errors.values().iterator().next() : problem;
    ImGui.text(
        draftChanged
            ? "Unsaved application profile draft"
            : "Application profile matches active metadata");
    if (!issue.isEmpty()) ImGui.textWrapped("! " + issue);
    ImGui.beginDisabled(!issue.isEmpty());
    if (ImGui.button("Apply Application Profile"))
      apply(UserConfig.directory().resolve("app-config.json"));
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Revert Profile")) {
      session.revert(state.metadata);
      resetEditors();
      message = "Draft reverted";
    }
    ImGui.sameLine();
    if (ImGui.button("Stage Built-In Profile")) {
      session.draft().controls = AppMetadata.defaults().controls;
      session.draft().creationDefaults.clear();
      resetEditors();
      message = "Built-in profile staged; Apply publishes it";
    }
    ImGui.textWrapped(message);
  }

  private void resetEditors() {
    fields.clear();
    values.clear();
    errors.clear();
    invalidated = true;
    draftChanged = session.changed();
  }

  private void presentation(AppMetadata.Control control) {
    fields.text(
        "Label",
        control.label,
        v -> {
          control.label = v;
          changed();
        });
    fields.text(
        "Tooltip",
        control.tooltip,
        v -> {
          control.tooltip = v;
          changed();
        });
    var description =
        values.computeIfAbsent(
            "description:" + selected, key -> new ImString(control.description, 16384));
    if (ImGui.inputTextMultiline("Description", description, ImGui.getContentRegionAvailX(), 90)) {
      control.description = description.get();
      changed();
    }
    fields.text(
        "Presentation group",
        control.group,
        v -> {
          control.group = v;
          changed();
        });
    fields.integer(
        "Order",
        control.order,
        v -> {
          control.order = v;
          changed();
        });
    var spec = CreationRegistry.CONTROLS.get(selected);
    if (spec != null && !spec.options().isEmpty()) {
      ImGui.textDisabled("Option keys are fixed; only their display labels can change.");
      for (String option : spec.options())
        fields.text(
            option,
            control.options.getOrDefault(option, option),
            value -> {
              if (value.equals(option)) control.options.remove(option);
              else control.options.put(option, value);
              changed();
            });
    }
  }

  private void creation(CreationRegistry.Spec spec) {
    var draft = session.draft();
    boolean overridden = draft.creationDefaults.containsKey(spec.id());
    ImGui.textWrapped("Immutable type: " + spec.kind() + " / built-in: " + spec.builtIn());
    if (spec.kind() == CreationRegistry.Kind.LONG)
      ImGui.textDisabled("Signed 64-bit integer; use exact integer text.");
    else if (spec.options().isEmpty()
        && spec.kind() != CreationRegistry.Kind.TEXT
        && spec.kind() != CreationRegistry.Kind.BOOLEAN)
      ImGui.text("Creation range: " + spec.minimum() + " .. " + spec.maximum());
    fields.bool(
        "Override future-item default",
        overridden,
        value -> {
          if (value) draft.creationDefaults.put(spec.id(), spec.builtIn());
          else {
            draft.creationDefaults.remove(spec.id());
            errors.remove(spec.id());
            values.clear();
          }
          changed();
        });
    ImGui.beginDisabled(!draft.creationDefaults.containsKey(spec.id()));
    JsonNode value = CreationRegistry.value(draft, spec.id());
    switch (spec.kind()) {
      case BOOLEAN ->
          fields.bool("Value", value.asBoolean(), v -> put(spec, ProjectIO.JSON.valueToTree(v)));
      case CHOICE -> {
        if (ImGui.beginCombo("Value", draft.option(spec.id(), value.asText()))) {
          for (String option : spec.options())
            if (ImGui.selectable(draft.option(spec.id(), option) + "###" + option))
              put(spec, ProjectIO.JSON.valueToTree(option));
          ImGui.endCombo();
        }
      }
      case LONG, TEXT -> {
        var text =
            values.computeIfAbsent(
                spec.id(),
                key ->
                    new ImString(
                        spec.kind() == CreationRegistry.Kind.TEXT ? value.asText() : value.asText(),
                        16384));
        if (ImGui.inputText("Value", text)) {
          try {
            JsonNode parsed =
                spec.kind() == CreationRegistry.Kind.LONG
                    ? ProjectIO.JSON.valueToTree(Long.parseLong(text.get()))
                    : ProjectIO.JSON.valueToTree(text.get());
            put(spec, parsed);
            errors.remove(spec.id());
          } catch (RuntimeException e) {
            errors.put(spec.id(), "Exact signed 64-bit integer required");
          }
        }
      }
      case NUMBER, INTEGER -> {
        if (spec.kind() == CreationRegistry.Kind.INTEGER && !spec.options().isEmpty()) {
          int current = value.intValue();
          String label =
              current >= 0 && current < spec.options().size()
                  ? draft.option(spec.id(), spec.options().get(current))
                  : "Invalid index " + value;
          if (ImGui.beginCombo("Value", label)) {
            for (int i = 0; i < spec.options().size(); i++)
              if (ImGui.selectable(
                  draft.option(spec.id(), spec.options().get(i)) + "###source-index-" + i,
                  current == i)) put(spec, ProjectIO.JSON.valueToTree(i));
            ImGui.endCombo();
          }
        } else {
          double edited = number("Value", value.asDouble(), spec.minimum(), spec.maximum());
          if (Double.doubleToLongBits(edited) != Double.doubleToLongBits(value.asDouble()))
            put(spec, ProjectIO.JSON.valueToTree(edited));
        }
      }
      case VECTOR2, VECTOR3 -> {
        var array = value.deepCopy();
        boolean changed = false;
        for (int i = 0; i < value.size(); i++) {
          double before = value.get(i).asDouble(),
              next = number("Component " + i, before, spec.minimum(), spec.maximum());
          if (Double.doubleToLongBits(next) != Double.doubleToLongBits(before)) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) array)
                .set(i, ProjectIO.JSON.valueToTree(next));
            changed = true;
          }
        }
        if (changed) put(spec, array);
      }
    }
    ImGui.endDisabled();
    if (ImGui.button("Use Built-In Value")) {
      draft.creationDefaults.remove(spec.id());
      errors.remove(spec.id());
      values.clear();
      changed();
    }
    ImGui.textWrapped(
        "New items store concrete values; changing this profile never updates existing resources or"
            + " resets a loaded definition.");
  }

  private double number(String label, double current, double minimum, double maximum) {
    double[] slider = {current};
    if (Double.isFinite(current)
        && ImGui.sliderScalar(
            "##slider" + label, slider, Math.max(-1e6, minimum), Math.min(1e6, maximum), "%.5f"))
      current = slider[0];
    var input = new ImDouble(current);
    if (ImGui.inputDouble(label, input, 0, 0, "%.5f")) current = input.get();
    return current;
  }

  private void put(CreationRegistry.Spec spec, JsonNode value) {
    session.draft().creationDefaults.put(spec.id(), value);
    changed();
  }

  private void apply(Path path) {
    try {
      state.metadata = session.apply(state.metadata, path);
      resetEditors();
      message = "Application profile saved and activated";
    } catch (Exception e) {
      message = "! " + e.getMessage();
    }
  }

  public void smokeDraft() {
    ensure();
    page = "Creation Defaults";
    selected = "noise2D.algorithm.CONSTANT.value";
    session.draft().creationDefaults.put(selected, ProjectIO.JSON.valueToTree(.625));
    changed();
  }

  public void assertSmokeDrawn() {
    if (!drawn
        || session == null
        || state.metadata.creationDefaults.containsKey("noise2D.algorithm.CONSTANT.value"))
      throw new IllegalStateException("Creation-default draft did not stay isolated");
    session.revert(state.metadata);
    resetEditors();
  }
}
