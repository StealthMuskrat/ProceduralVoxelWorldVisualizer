package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import io.voxelcreator.api.Profiler;
import java.util.*;
import java.util.function.Supplier;

/** Read-only diagnostic sections and persistent controls for the one shared profiler. */
final class DebugSettingsPanel {
  static final List<String> SECTIONS =
      List.of(
          "General",
          "Generation",
          "World-Chunks",
          "Rendering",
          "Memory",
          "Scheduler",
          "LOD",
          "Diagnostics");
  private final EditorState state;
  private final Supplier<Viewports.StreamingStatistics> rendering;
  private final Runnable start, open;
  private final UiFields fields = new UiFields();
  final SettingsPageChrome layout = new SettingsPageChrome(2);
  final PreferenceActionScope actionScope = new PreferenceActionScope();
  private String section = "General";
  private DialogBounds.Rect selector;

  DebugSettingsPanel(
      EditorState state,
      Supplier<Viewports.StreamingStatistics> rendering,
      Runnable start,
      Runnable open) {
    this.state = Objects.requireNonNull(state);
    this.rendering = Objects.requireNonNull(rendering);
    this.start = Objects.requireNonNull(start);
    this.open = Objects.requireNonNull(open);
  }

  void draw() {
    var context = ProfileActionContext.from(state.engine.profiler());
    long request = actionScope.synchronize(context);
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice("##debug-settings-section", section, SECTIONS, value -> section = value);
    selector = PopupLayout.itemBounds();
    fields.tooltip("Choose a diagnostic section");
    layout.begin("debug-settings-body");
    boolean recording = context.recording();
    ImGui.textWrapped(
        recording ? "Targeted capture is recording." : "Targeted capture is stopped.");
    ImGui.textWrapped(
        "Capture limits and export review live in Profiler. Opening a section or the Profiler"
            + " window does not start instrumentation.");
    var m = state.engine.metrics();
    var r = rendering.get();
    switch (section) {
      case "General" -> {
        ImGui.textWrapped("Workers: " + m.active() + " active / " + m.workers() + " configured");
        ImGui.textWrapped(
            "Engine requests: " + m.queued() + " queued / " + m.completed() + " completed");
        ImGui.textWrapped("Scene: " + r.chunks() + " chunks / " + r.jobs() + " jobs");
      }
      case "Generation" -> {
        ImGui.textWrapped("Completed requests: " + m.completed());
        ImGui.textWrapped("Cumulative generation time: " + m.generationNanos() + " ns");
        ImGui.textWrapped(
            "Per-request and percentile timings are available in a targeted capture.");
      }
      case "World-Chunks" -> {
        ImGui.textWrapped("Resident chunks: " + r.chunks() + " / jobs: " + r.jobs());
        ImGui.textWrapped("Visible: " + r.visible() + " / culled: " + r.culled());
        ImGui.textWrapped(
            "Chunk scene CPU: "
                + r.cpuBytes()
                + " bytes resident / "
                + r.reserved()
                + " bytes reserved");
      }
      case "Rendering" -> {
        ImGui.textWrapped("Draw calls: " + r.drawCalls() + " / indirect: " + r.indirect());
        ImGui.textWrapped(
            "Frame uploads: "
                + r.uploadedBytes()
                + " bytes / relocation: "
                + r.relocatedBytes()
                + " bytes");
        ImGui.textWrapped(
            "Compaction: "
                + r.compactingArenas()
                + " active or queued arenas / "
                + r.compactionRemainingBytes()
                + " bytes remain");
      }
      case "Memory" -> {
        ImGui.textWrapped(
            "Engine budget: " + m.memoryBudget() + " bytes / cache: " + m.cacheBytes() + " bytes");
        ImGui.textWrapped(
            "Engine staging: "
                + m.stagingBytes()
                + " bytes / collections: "
                + m.collectionBytes()
                + " bytes");
        ImGui.textWrapped(
            "Scene CPU: " + r.cpuBytes() + " bytes resident + " + r.reserved() + " bytes reserved");
        ImGui.textWrapped(
            "GPU vertex arena allocation: "
                + r.gpuCapacityBytes()
                + " bytes / data: "
                + r.gpuBytes()
                + " bytes");
        ImGui.textWrapped(
            "GPU free: "
                + r.gpuFreeBytes()
                + " bytes / largest range: "
                + r.largestFreeRange()
                + " bytes");
      }
      case "Scheduler" -> ImGui.textWrapped(state.engine.schedulerStats().toString());
      case "LOD" -> {
        var p = state.user.performance;
        ImGui.textWrapped(
            "Configured render distance: "
                + p.renderDistance
                + " / vertical distance: "
                + p.verticalDistance);
        ImGui.textWrapped("Configured LOD start: " + p.lodStart + " / quality: " + p.lodQuality);
        ImGui.textWrapped("Visible chunks: " + r.visible() + " / culled: " + r.culled());
        ImGui.textWrapped(
            "These are active display preferences. Actual detail also depends on camera, source and"
                + " available budgets.");
      }
      case "Diagnostics" -> {
        ImGui.textWrapped(
            "Validation scope: " + Objects.requireNonNullElse(state.validationScope, ""));
        ImGui.textWrapped("Validation entries: " + state.issues.size());
        ImGui.textWrapped("Retained event groups: " + state.events.snapshot().size());
        ImGui.textWrapped(
            "Use Logs and Validation Results for bounded details and guarded source navigation.");
      }
      default -> throw new IllegalStateException("Unknown diagnostic section");
    }
    layout.end();
    ImGui.pushID("debug-capture:" + request);
    boolean capture =
        layout.identifiedAction("capture", recording ? "Stop Capture" : "Start Capture");
    boolean profiler = layout.identifiedAction("profiler", "Open Profiler");
    ImGui.popID();
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)
        || !actionScope.current(ProfileActionContext.from(state.engine.profiler()))) return;
    if (capture) {
      if (recording) context.profiler().stop();
      else {
        start.run();
        open.run();
      }
    } else if (profiler) open.run();
  }

  void suspendActions() {
    actionScope.resetDraft();
  }

  boolean recording() {
    return state.engine.profiler().current().map(Profiler.Capture::recording).orElse(false);
  }

  void section(String value) {
    if (value == null || !SECTIONS.contains(value))
      throw new IllegalArgumentException("Unknown diagnostic section");
    section = value;
  }

  String section() {
    return section;
  }

  DialogBounds.Rect selector() {
    return selector;
  }
}
