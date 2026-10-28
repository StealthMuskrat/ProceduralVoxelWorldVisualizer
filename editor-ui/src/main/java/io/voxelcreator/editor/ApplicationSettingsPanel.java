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
  private final Map<String, List<ImString>> creationBuffers = new LinkedHashMap<>();
  private final Map<String, float[]> controls = new HashMap<>();
  private List<Map.Entry<String, AppMetadata.Control>> ordered = List.of(), rows = List.of();
  private AppMetadata rowOwner;
  private String rowPage = "", rowQuery = "";
  private boolean rowsChanged = true;
  private long rowBuilds;
  private int submitted;
  private boolean applyEnabled;
  private boolean detailReset;
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
    rowsChanged = true;
  }

  private void select(String id) {
    if (!selected.equals(id)) {
      selected = id;
      fields.clear();
      values.clear();
      detailReset = true;
    }
  }

  public void draw() {
    ensure();
    controls.clear();
    submitted = 0;
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
          detailReset = true;
        });
    ImGui.inputTextWithHint(
        "##metadata-filter", "Filter labels, groups or stable control IDs...", filter);
    control("filter");
    String query = filter.get().strip().toLowerCase(Locale.ROOT);
    var draft = session.draft();
    boolean rebuild = rowsChanged || rowOwner != draft;
    if (rebuild) {
      ordered = draft.orderedControls();
      rowOwner = draft;
      rowsChanged = false;
    }
    if (rebuild || !rowPage.equals(page) || !rowQuery.equals(query)) {
      rows =
          ordered.stream()
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
      rowPage = page;
      rowQuery = query;
      rowBuilds++;
    }
    ImGui.beginChild("metadata-results", 0, 150, true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = rows.get(i);
          submitted++;
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
        "metadata-detail", 0, Math.max(150, ImGui.getContentRegionAvailY() - 170), true);
    if (detailReset) {
      ImGui.setScrollY(0);
      detailReset = false;
    }
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
    var errors = session.inputs().errors();
    String issue = problem;
    if (!errors.isEmpty()) {
      var first = errors.entrySet().iterator().next();
      issue =
          errors.size()
              + " creation input(s) need correction: "
              + first.getKey()
              + ": "
              + first.getValue();
    } else
      try {
        session.inputs().requireDisplayed(displayed());
      } catch (RuntimeException invalid) {
        issue = Objects.requireNonNullElse(invalid.getMessage(), invalid.toString());
      }
    ImGui.text(
        draftChanged
            ? "Unsaved application profile draft"
            : "Application profile matches active metadata");
    if (!issue.isEmpty()) ImGui.textWrapped("! " + bounded(issue));
    if (!errors.isEmpty()) {
      if (ImGui.smallButton("Show Invalid Value")) {
        select(errors.keySet().iterator().next());
        page = "Creation Defaults";
        detailReset = true;
      }
      control("invalid-value");
    }
    applyEnabled = issue.isEmpty();
    ImGui.beginDisabled(!applyEnabled);
    if (ImGui.button("Apply Application Profile"))
      apply(UserConfig.directory().resolve("app-config.json"));
    control("apply");
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Revert Profile")) {
      session.revert(state.metadata);
      resetEditors();
      message = "Draft reverted";
    }
    control("revert");
    ImGui.sameLine();
    if (ImGui.button("Stage Built-In Profile")) {
      session.stageBuiltIns();
      resetEditors();
      message = "Built-in profile staged; Apply publishes it";
    }
    control("builtins");
    ImGui.textWrapped(bounded(message));
  }

  private void resetEditors() {
    fields.clear();
    values.clear();
    creationBuffers.clear();
    invalidated = true;
    rowsChanged = true;
    detailReset = true;
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
          session.inputs().override(spec.id(), value);
          creationBuffers.remove(spec.id());
          changed();
        });
    control("override");
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
      case LONG, TEXT -> rawValue(spec, value);
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
          rawValue(spec, value);
        }
      }
      case VECTOR2, VECTOR3 -> rawValue(spec, value);
    }
    ImGui.endDisabled();
    if (ImGui.button("Use Built-In Value")) {
      session.inputs().override(spec.id(), false);
      creationBuffers.remove(spec.id());
      changed();
    }
    control("builtin-value");
    ImGui.sameLine();
    if (ImGui.button("Reload Value")) {
      session.inputs().reload(spec.id());
      creationBuffers.remove(spec.id());
      changed();
    }
    control("reload-value");
    ImGui.textWrapped(
        "New items store concrete values; changing this profile never updates existing resources or"
            + " resets a loaded definition.");
  }

  private void rawValue(CreationRegistry.Spec spec, JsonNode value) {
    var input = session.inputs();
    var raw = input.text(spec.id());
    var buffers =
        creationBuffers.computeIfAbsent(
            spec.id(),
            key ->
                raw.stream()
                    .map(text -> new ImString(text, 4 * CreationValueInputs.limit(spec) + 1))
                    .toList());
    for (int i = 0; i < buffers.size(); i++) {
      ImGui.pushID(spec.id() + ":" + i);
      var buffer = buffers.get(i);
      String label = buffers.size() == 1 ? "Value" : "Component " + i;
      boolean numeric =
          spec.kind() != CreationRegistry.Kind.LONG && spec.kind() != CreationRegistry.Kind.TEXT;
      if (numeric) {
        ImGui.text(label);
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * .52f);
        double[] slider = {value.isArray() ? value.get(i).asDouble() : value.asDouble()};
        ImGui.beginDisabled(
            !input.error(spec.id()).isEmpty()
                || !input.text(spec.id()).equals(buffers.stream().map(ImString::get).toList())
                || !Double.isFinite(slider[0]));
        if (ImGui.sliderScalar(
            "##slider",
            slider,
            Math.max(-1e6, spec.minimum()),
            Math.min(1e6, spec.maximum()),
            "%.5g")) {
          String text =
              spec.kind() == CreationRegistry.Kind.INTEGER
                  ? Long.toString(Math.round(slider[0]))
                  : Double.toString(slider[0]);
          input.edit(spec.id(), i, text);
          buffer.set(text);
          changed();
        }
        control("slider-" + i);
        ImGui.endDisabled();
        ImGui.sameLine();
        ImGui.setNextItemWidth(-1);
      }
      boolean edited =
          spec.kind() == CreationRegistry.Kind.TEXT
              ? ImGui.inputTextMultiline(label, buffer, ImGui.getContentRegionAvailX(), 90)
              : ImGui.inputText(numeric ? "##number" : label, buffer);
      control("value-" + i);
      if (edited) {
        message = "";
        try {
          input.editAll(spec.id(), buffers.stream().map(ImString::get).toList());
        } catch (RuntimeException invalid) {
          message = Objects.requireNonNullElse(invalid.getMessage(), invalid.toString());
        }
        changed();
      }
      ImGui.popID();
    }
    var error = input.error(spec.id());
    if (!error.isEmpty()) ImGui.textWrapped("! " + bounded(error));
  }

  private void put(CreationRegistry.Spec spec, JsonNode value) {
    session.inputs().replace(spec.id(), value);
    creationBuffers.remove(spec.id());
    changed();
  }

  private void apply(Path path) {
    try {
      session.inputs().requireDisplayed(displayed());
      state.metadata = session.apply(state.metadata, path);
      resetEditors();
      message = "Application profile saved and activated";
    } catch (Exception e) {
      message = "! " + Objects.requireNonNullElse(e.getMessage(), e.toString());
    }
  }

  private Map<String, List<String>> displayed() {
    var displayed = new LinkedHashMap<String, List<String>>();
    creationBuffers.forEach(
        (id, buffers) -> displayed.put(id, buffers.stream().map(ImString::get).toList()));
    return displayed;
  }

  private static String bounded(String message) {
    return message.length() > 1024 ? message.substring(0, 1023) + "…" : message;
  }

  private void control(String name) {
    controls.put(
        name,
        new float[] {
          (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
          (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
        });
  }

  float[] controlAt(String name) {
    return controls.get(name);
  }

  void showControl(String id, String section) {
    ensure();
    if (!session.draft().controls.containsKey(id)
        || !List.of("Presentation", "Creation Defaults").contains(section))
      throw new IllegalArgumentException("Unknown application control or section");
    select(id);
    if (!page.equals(section)) detailReset = true;
    page = section;
  }

  List<String> rawText(String id) {
    var buffers = creationBuffers.get(id);
    return buffers == null
        ? session.inputs().text(id)
        : buffers.stream().map(ImString::get).toList();
  }

  JsonNode defaultValue(String id) {
    return CreationRegistry.value(session.draft(), id);
  }

  boolean applyEnabled() {
    return applyEnabled;
  }

  long rowBuilds() {
    return rowBuilds;
  }

  int submittedRows() {
    return submitted;
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
