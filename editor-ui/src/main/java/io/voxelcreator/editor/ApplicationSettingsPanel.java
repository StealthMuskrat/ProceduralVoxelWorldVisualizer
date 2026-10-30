package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import imgui.*;
import imgui.flag.ImGuiKey;
import imgui.type.*;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.Path;
import java.util.*;

/** Clipped searchable application metadata/default editing; only Apply affects the live profile. */
public final class ApplicationSettingsPanel {
  private final EditorState state;
  final SettingsPageChrome layout = new SettingsPageChrome(4);
  private DialogBounds.Rect resultsBounds, detailBounds;
  private SettingsPageLayout.Panes panes;
  private final Map<String, Integer> controlIds = new HashMap<>();
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
  private float resultsRowHeight;
  private boolean applyEnabled;
  private boolean detailReset;
  private MetadataEditSession session;
  final SettingsActionScope actionScope = new SettingsActionScope();
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
    long request = actionScope.synchronize(state.metadata, session.draft());
    controls.clear();
    controlIds.clear();
    layout.begin("application-profile-body");
    submitted = 0;
    drawn = true;
    ImGui.textWrapped(
        "Private application profile. Apply saves labels/help and validated defaults for future"
            + " user-created items. Existing project values remain authored values.");
    ImGui.textWrapped("Section");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice(
        "##metadata-section",
        page,
        List.of("Presentation", "Creation Defaults"),
        value -> {
          page = value;
          fields.clear();
          values.clear();
          detailReset = true;
        });
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
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
    panes =
        SettingsPageLayout.panes(
            ImGui.getContentRegionAvailX(),
            ImGui.getContentRegionAvailY() - 3 * ImGui.getFrameHeightWithSpacing(),
            ImGui.calcTextSize("0000000000000000000000000000").x,
            ImGui.getTextLineHeightWithSpacing(),
            ImGui.getStyle().getItemSpacingX(),
            ImGui.getStyle().getItemSpacingY());
    UiScale.beginChild("metadata-results", panes.resultsWidth(), panes.resultsHeight(), true);
    resultsBounds = windowBounds();
    resultsRowHeight = ImGui.getTextLineHeightWithSpacing();
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size(), resultsRowHeight);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = rows.get(i);
          submitted++;
          String fullLabel = row.getValue().group + " / " + row.getValue().label;
          String rowLabel = AppMetadata.uiText(StatusBarText.clip(fullLabel, 243));
          if (ImGui.selectable(
              rowLabel + "###profile" + row.getKey(), row.getKey().equals(selected)))
            select(row.getKey());
          if (ImGui.isItemHovered()) UiScale.tooltip(fullLabel + " / " + row.getKey());
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    if (panes.columns()) ImGui.sameLine();
    UiScale.beginChild("metadata-detail", panes.detailWidth(), panes.detailHeight(), true);
    detailBounds = windowBounds();
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
    ImGui.textWrapped(
        draftChanged
            ? "Unsaved application profile draft"
            : "Application profile matches active metadata");
    if (!issue.isEmpty()) ImGui.textWrapped("! " + bounded(issue));
    applyEnabled = issue.isEmpty();
    if (!message.isEmpty()) ImGui.textWrapped(bounded(message));
    layout.end();
    ImGui.pushID("application-profile-request:" + request);
    ImGui.beginDisabled(!applyEnabled);
    boolean apply = layout.identifiedAction("apply", "Apply Application Profile");
    control("apply");
    ImGui.endDisabled();
    boolean revert = layout.identifiedAction("revert", "Revert Profile");
    control("revert");
    boolean builtins = layout.identifiedAction("builtins", "Stage Built-In Profile");
    control("builtins");
    ImGui.beginDisabled(errors.isEmpty());
    boolean invalid = layout.identifiedAction("invalid-value", "Show Invalid Value");
    control("invalid-value");
    ImGui.endDisabled();
    ImGui.popID();
    if (!actionScope.current(state.metadata, session.draft())) return;
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) return;
    // Resolve this entire frame before persisting or replacing any private profile.
    if (revert) {
      session.revert(state.metadata);
      resetEditors();
      message = "Draft reverted";
    } else if (builtins) {
      session.stageBuiltIns();
      resetEditors();
      message = "Built-in profile staged; Apply publishes it";
    } else if (apply && applyEnabled) apply(UserConfig.directory().resolve("app-config.json"));
    else if (invalid && !errors.isEmpty()) {
      select(errors.keySet().iterator().next());
      page = "Creation Defaults";
      detailReset = true;
    }
  }

  private void resetEditors() {
    actionScope.release();
    fields.clear();
    values.clear();
    creationBuffers.clear();
    invalidated = true;
    rowsChanged = true;
    detailReset = true;
    draftChanged = session.changed();
  }

  private void presentation(AppMetadata.Control control) {
    presentationLabel("Label");
    fields.text(
        "##profile-label",
        control.label,
        v -> {
          control.label = v;
          changed();
        });
    presentationLabel("Tooltip");
    fields.text(
        "##profile-tooltip",
        control.tooltip,
        v -> {
          control.tooltip = v;
          changed();
        });
    var description =
        values.computeIfAbsent(
            "description:" + selected, key -> new ImString(control.description, 16384));
    ImGui.textWrapped("Description");
    if (ImGui.inputTextMultiline(
        "##profile-description",
        description,
        Math.max(1, ImGui.getContentRegionAvailX()),
        Math.max(90, 3 * ImGui.getTextLineHeightWithSpacing()))) {
      control.description = description.get();
      changed();
    }
    presentationLabel("Presentation group");
    fields.text(
        "##profile-group",
        control.group,
        v -> {
          control.group = v;
          changed();
        });
    presentationLabel("Order");
    fields.integer(
        "##profile-order",
        control.order,
        v -> {
          control.order = v;
          changed();
        });
    var spec = CreationRegistry.CONTROLS.get(selected);
    if (spec != null && !spec.options().isEmpty()) {
      ImGui.textDisabled("Option keys are fixed; only their display labels can change.");
      for (String option : spec.options()) {
        presentationLabel(option);
        fields.text(
            "##profile-option-" + option,
            control.options.getOrDefault(option, option),
            value -> {
              if (value.equals(option)) control.options.remove(option);
              else control.options.put(option, value);
              changed();
            });
      }
    }
  }

  private void presentationLabel(String label) {
    ImGui.textWrapped(label);
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
  }

  private void creation(CreationRegistry.Spec spec) {
    var draft = session.draft();
    boolean overridden = draft.creationDefaults.containsKey(spec.id());
    ImGui.textWrapped("Immutable type: " + spec.kind() + " / built-in: " + spec.builtIn());
    if (spec.kind() == CreationRegistry.Kind.LONG)
      ImGui.textWrapped("Signed 64-bit integer; use exact integer text.");
    else if (spec.options().isEmpty()
        && spec.kind() != CreationRegistry.Kind.TEXT
        && spec.kind() != CreationRegistry.Kind.BOOLEAN)
      ImGui.textWrapped("Creation range: " + spec.minimum() + " .. " + spec.maximum());
    fields.bool(
        "##creation-override",
        overridden,
        value -> {
          session.inputs().override(spec.id(), value);
          creationBuffers.remove(spec.id());
          changed();
        });
    control("override");
    DialogLayout.sameLine("Override future-item default");
    ImGui.textWrapped("Override future-item default");
    ImGui.beginDisabled(!draft.creationDefaults.containsKey(spec.id()));
    JsonNode value = CreationRegistry.value(draft, spec.id());
    switch (spec.kind()) {
      case BOOLEAN ->
          fields.bool("Value", value.asBoolean(), v -> put(spec, ProjectIO.JSON.valueToTree(v)));
      case CHOICE -> {
        if (creationCombo(draft.option(spec.id(), value.asText()))) {
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
          if (creationCombo(label)) {
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
    if (DialogLayout.button("Use Built-In Value")) {
      session.inputs().override(spec.id(), false);
      creationBuffers.remove(spec.id());
      changed();
    }
    control("builtin-value");
    DialogLayout.sameLine("Reload Value");
    if (DialogLayout.button("Reload Value")) {
      session.inputs().reload(spec.id());
      creationBuffers.remove(spec.id());
      changed();
    }
    control("reload-value");
    ImGui.textWrapped(
        "New items store concrete values; changing this profile never updates existing resources or"
            + " resets a loaded definition.");
  }

  private boolean creationCombo(String preview) {
    ImGui.textWrapped("Value");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    return UiScale.beginCombo("##creation-value", preview);
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
      ImGui.textWrapped(label);
      var pair = SettingsPageChrome.pair();
      if (numeric) {
        ImGui.setNextItemWidth(pair.sliderWidth());
        controlIds.put("slider-" + i, ImGui.getID("##slider"));
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
        if (pair.inline()) ImGui.sameLine();
      }
      ImGui.setNextItemWidth(
          numeric ? pair.inputWidth() : Math.max(1, ImGui.getContentRegionAvailX()));
      String inputLabel =
          spec.kind() == CreationRegistry.Kind.TEXT
              ? "##raw-text"
              : numeric ? "##number" : "##raw-value";
      controlIds.put("value-" + i, ImGui.getID(inputLabel));
      boolean edited =
          spec.kind() == CreationRegistry.Kind.TEXT
              ? ImGui.inputTextMultiline(
                  inputLabel,
                  buffer,
                  Math.max(1, ImGui.getContentRegionAvailX()),
                  Math.max(90, 3 * ImGui.getTextLineHeightWithSpacing()))
              : ImGui.inputText(inputLabel, buffer);
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

  private static DialogBounds.Rect windowBounds() {
    return new DialogBounds.Rect(
        ImGui.getWindowPosX(),
        ImGui.getWindowPosY(),
        ImGui.getWindowSizeX(),
        ImGui.getWindowSizeY());
  }

  DialogBounds.Rect resultsBounds() {
    return resultsBounds;
  }

  DialogBounds.Rect detailBounds() {
    return detailBounds;
  }

  SettingsPageLayout.Panes panes() {
    return panes;
  }

  Map<String, Integer> controlIds() {
    return Map.copyOf(controlIds);
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

  void probeStageCreation(String id, String raw) {
    ensure();
    session.inputs().override(id, true);
    if (CreationRegistry.get(id).kind() == CreationRegistry.Kind.CHOICE)
      session.inputs().replace(id, ProjectIO.JSON.valueToTree(raw));
    else session.inputs().editAll(id, List.of(raw));
    creationBuffers.remove(id);
    changed();
  }

  void probeRevert() {
    ensure();
    session.revert(state.metadata);
    resetEditors();
    message = "";
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

  int totalRows() {
    return rows.size();
  }

  float resultsRowHeight() {
    return resultsRowHeight;
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
