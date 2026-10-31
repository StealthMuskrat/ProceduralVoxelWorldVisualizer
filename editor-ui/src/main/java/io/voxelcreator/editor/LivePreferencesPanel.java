package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import java.util.*;
import java.util.function.*;

/** Immediate Editor/Visualization preferences; private settings forms keep their own owners. */
final class LivePreferencesPanel {
  private final UserConfig user;
  private final Runnable save, limits, retryShadows;
  private final Supplier<List<String>> shadows;
  final SettingsActionScope editorActions = new SettingsActionScope(),
      visualizationActions = new SettingsActionScope();
  private final String actionId = UUID.randomUUID().toString();
  final SettingsPageChrome editorLayout = new SettingsPageChrome(1);
  final SettingsPageChrome visualizationLayout = new SettingsPageChrome(3);
  private final Map<String, float[]> controls = new HashMap<>();
  private final Map<String, Integer> ids = new LinkedHashMap<>();
  private final Map<String, DialogBounds.Rect> inputBounds = new LinkedHashMap<>();
  private boolean probing;
  private String reveal;

  LivePreferencesPanel(
      UserConfig user,
      Runnable save,
      Runnable limits,
      Runnable retryShadows,
      Supplier<List<String>> shadows) {
    this.user = Objects.requireNonNull(user);
    this.save = Objects.requireNonNull(save);
    this.limits = Objects.requireNonNull(limits);
    this.retryShadows = Objects.requireNonNull(retryShadows);
    this.shadows = Objects.requireNonNull(shadows);
  }

  void drawEditor() {
    long request = editorActions.synchronize(user, this);
    begin(editorLayout, "editor-preferences-body");
    ImGui.textWrapped(
        "These preferences take effect immediately. Save Preferences retains them for the next"
            + " launch.");
    toggle(
        "auto",
        "Auto regenerate on changes",
        user.editor.autoRegenerate,
        value -> user.editor.autoRegenerate = value);
    ImGui.textWrapped("Regeneration opportunities per second");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    String id = "##editor-opportunities";
    identify("rate", id);
    int[] rate = {user.editor.opportunitiesPerSecond};
    if (ImGui.sliderInt(id, rate, 1, 20))
      user.editor.opportunitiesPerSecond = Math.max(1, Math.min(20, rate[0]));
    control("rate");
    ImGui.textWrapped(
        "Auto mode offers 1–20 opportunities per second and regenerates only dirty selected data or"
            + " dependencies. It does not generate on every opportunity.");
    editorLayout.end();
    ImGui.pushID("editor-actions:" + actionId + ":" + request);
    boolean persist = editorLayout.identifiedAction("save", "Save Preferences");
    control("save");
    ImGui.popID();
    if (!ImGui.isKeyPressed(ImGuiKey.Escape, false) && editorActions.current(user, this) && persist)
      save.run();
  }

  void drawVisualization() {
    long request = visualizationActions.synchronize(user, this, user.visualization.shadows ? 1 : 0);
    begin(visualizationLayout, "visualization-preferences-body");
    ImGui.textWrapped(
        "Display preferences take effect immediately and do not alter generated voxel values. Save"
            + " Preferences retains active values only.");
    var v = user.visualization;
    toggle("grid", "Grid", v.grid, value -> v.grid = value);
    toggle("unlit", "Unlit", v.unlit, value -> v.unlit = value);
    toggle("streaming", "Camera-driven chunk streaming", v.streaming, value -> v.streaming = value);
    toggle("ao", "Ambient occlusion", v.ambientOcclusion, value -> v.ambientOcclusion = value);
    toggle("fog", "Distance fog", v.fog, value -> v.fog = value);
    toggle("shadows", "Directional shadows", v.shadows, value -> v.shadows = value);
    if (v.shadows) {
      choice(
          "resolution",
          "Shadow map maximum resolution",
          v.shadowResolution,
          List.of(512, 1024, 2048),
          value -> v.shadowResolution = value);
      ImGui.textWrapped(
          "Triangle meshes cast shadows from complete resident geometry. GPU budget may lower"
              + " resolution. Unlit, points, volumes and slices bypass shadows.");
      for (String status : shadows.get())
        ImGui.textWrapped(status.length() > 1024 ? status.substring(0, 1023) + "…" : status);
    }
    toggle(
        "volume-shadows",
        "Volume self-shadowing",
        v.volumeShadows,
        value -> v.volumeShadows = value);
    if (v.volumeShadows) {
      choice(
          "volume-steps",
          "Volume light ray samples",
          v.volumeShadowSteps,
          List.of(8, 16, 32),
          value -> v.volumeShadowSteps = value);
      ImGui.textWrapped(
          "Directional attenuation through the accepted volume only. Adds up to 32 texture samples"
              + " per view-ray step. Unlit bypasses it; no volume regeneration is required.");
    }
    toggle(
        "indirect",
        "Indirect drawing when supported",
        v.indirectDrawing,
        value -> v.indirectDrawing = value);
    ImGui.textWrapped("Default preview extent: " + v.previewSize);
    ImGui.textWrapped(
        "Edit preview limits opens the separate private Performance form. Saving these active"
            + " display preferences does not apply its pending text.");
    visualizationLayout.end();
    ImGui.pushID("visualization-actions:" + actionId + ":" + request);
    boolean persist = visualizationLayout.identifiedAction("save", "Save Preferences");
    control("save");
    boolean editLimits = visualizationLayout.identifiedAction("limits", "Edit Preview Limits");
    control("limits");
    ImGui.beginDisabled(!v.shadows);
    boolean retry = visualizationLayout.identifiedAction("retry", "Retry Shadows");
    control("retry");
    ImGui.endDisabled();
    ImGui.popID();
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)
        || !visualizationActions.current(user, this, user.visualization.shadows ? 1 : 0)) return;
    if (persist) save.run();
    else if (editLimits) limits.run();
    else if (retry && user.visualization.shadows) retryShadows.run();
  }

  void suspendEditorActions() {
    editorActions.release();
  }

  void suspendVisualizationActions() {
    visualizationActions.release();
  }

  private void begin(SettingsPageChrome layout, String id) {
    if (probing) {
      controls.clear();
      ids.clear();
      inputBounds.clear();
    }
    layout.begin(id);
  }

  private void toggle(String key, String label, boolean value, Consumer<Boolean> set) {
    String id = "##live-preference-" + key;
    identify(key, id);
    var selected = new ImBoolean(value);
    if (ImGui.checkbox(id, selected)) set.accept(selected.get());
    control(key);
    DialogLayout.sameLine(label);
    ImGui.textWrapped(label);
  }

  private void choice(String key, String label, int value, List<Integer> options, IntConsumer set) {
    ImGui.textWrapped(label);
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    String id = "##live-preference-" + key;
    identify(key, id);
    boolean open = UiScale.beginCombo(id, Integer.toString(value));
    control(key);
    if (open) {
      for (int option : options) {
        if (ImGui.selectable(Integer.toString(option), option == value)) set.accept(option);
        control(key + ":" + option);
      }
      ImGui.endCombo();
    }
  }

  void probeControls() {
    probing = true;
  }

  void probeReveal(String key) {
    reveal = key;
  }

  private void identify(String key, String id) {
    if (probing) ids.put(key, ImGui.getID(id));
  }

  private void control(String key) {
    if (!probing) return;
    var rect = PopupLayout.itemBounds();
    controls.put(key, new float[] {rect.x() + rect.width() / 2, rect.y() + rect.height() / 2});
    if (ids.containsKey(key)) inputBounds.put(key, rect);
    if (key.equals(reveal)) {
      ImGui.setScrollHereY(.25f);
      reveal = null;
    }
  }

  float[] probeControl(String key) {
    return controls.get(key);
  }

  Map<String, Integer> fieldIds() {
    return Map.copyOf(ids);
  }

  Map<String, DialogBounds.Rect> inputBounds() {
    return Map.copyOf(inputBounds);
  }
}
