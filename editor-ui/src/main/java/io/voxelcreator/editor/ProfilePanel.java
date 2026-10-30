package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.api.*;
import java.lang.management.*;
import java.util.*;
import java.util.function.Consumer;

/** UI-owned capture controls. Capture/export/navigation never change the project document. */
public final class ProfilePanel {
  final FloatingWindowLayout windowLayout = new FloatingWindowLayout(1050, 800);
  private final EditorState state;
  private final Consumer<EditorEvents.Target> navigate;
  private final UiFields fields = new UiFields();
  private final ImString filter = new ImString(512),
      exportPath = new ImString("profile-capture.json", 4096);
  private final List<GarbageCollectorMXBean> collectors =
      ManagementFactory.getGarbageCollectorMXBeans();
  private int duration = 10000, samples = 8192, series = 128, stride = 256;
  private String kind = "All", message = "";
  private Profiler.Capture displayed;
  private Profiler.Snapshot snapshot;
  private ProfileExportSession exportReview;
  private Profiler.Snapshot filteredSnapshot;
  private String filteredKind, filteredQuery;
  private List<Profiler.Series> filteredRows = List.of();
  private long rowQueries;
  private boolean scrollToExport;
  private static final List<String> KINDS;

  static {
    var values = new ArrayList<String>();
    values.add("All");
    for (var value : Profiler.Kind.values()) values.add(value.name());
    KINDS = List.copyOf(values);
  }

  private Profiler.Series selected, totalSeries;
  private Profiler.Snapshot totalSnapshot;
  private long selectedTotal;
  private long refreshed, gaugesAt;
  private boolean drawn, worldContributions;
  private boolean workProbe;
  private final Map<String, float[]> workControls = new HashMap<>();

  public ProfilePanel(EditorState state, Consumer<EditorEvents.Target> navigate) {
    this.state = state;
    this.navigate = navigate;
  }

  public void start() {
    state
        .engine
        .profiler()
        .start(new Profiler.Options(duration, samples, series, stride, worldContributions));
    snapshot = null;
    filteredSnapshot = null;
    filteredRows = List.of();
    selected = null;
    clearWorkTotal();
    gaugesAt = 0;
    message = "";
  }

  void probeWorkControls() {
    workProbe = true;
  }

  float[] probeWorkControl(String key) {
    return workControls.get(key);
  }

  private void workControl(String key) {
    if (workProbe)
      workControls.put(
          key,
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          });
  }

  /** Called once per editor frame, including while the Profiler window is closed. */
  public void frame(float seconds, Viewports views) {
    views.collectGpuTimings();
    var scope = state.engine.profiler().scope(0, state.project().project.uuid, null);
    if (scope == null) return;
    scope.gauge(
        Profiler.Kind.FRAME,
        Profiler.Unit.NANOSECONDS,
        (long) (Math.max(0, seconds) * 1_000_000_000L),
        "Frame interval");
    long now = System.nanoTime();
    if (now - gaugesAt < 250_000_000L) return;
    gaugesAt = now;
    var rendering = views.streamingStatistics();
    var engine = state.engine.metrics();
    var runtime = Runtime.getRuntime();
    scope.gauge(
        Profiler.Kind.HEAP_BYTES,
        Profiler.Unit.BYTES,
        runtime.totalMemory() - runtime.freeMemory(),
        "JVM used heap");
    long gcMillis = 0, gcCount = 0;
    for (var collector : collectors) {
      gcMillis += Math.max(0, collector.getCollectionTime());
      gcCount += Math.max(0, collector.getCollectionCount());
    }
    scope.gauge(
        Profiler.Kind.GC_MILLIS, Profiler.Unit.MILLISECONDS, gcMillis, "JVM cumulative GC time");
    scope.gauge(Profiler.Kind.GC_COUNT, Profiler.Unit.COUNT, gcCount, "JVM cumulative GC count");
    scope.gauge(
        Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, engine.queued(), "Engine queued requests");
    scope.gauge(
        Profiler.Kind.QUEUE_DEPTH,
        Profiler.Unit.COUNT,
        state.background.getQueue().size(),
        "Editor background queue");
    scope.gauge(
        Profiler.Kind.ACTIVE_WORKERS,
        Profiler.Unit.COUNT,
        engine.active(),
        "Engine active workers");
    scope.gauge(
        Profiler.Kind.CACHE_BYTES,
        Profiler.Unit.BYTES,
        engine.cacheBytes(),
        "Engine retained cache");
    scope.gauge(
        Profiler.Kind.STAGING_BYTES,
        Profiler.Unit.BYTES,
        engine.stagingBytes(),
        "Engine admitted staging");
    scope.gauge(
        Profiler.Kind.COLLECTION_BYTES,
        Profiler.Unit.BYTES,
        engine.collectionBytes(),
        "Engine region collections");
    scope.gauge(
        Profiler.Kind.SCENE_CPU_BYTES,
        Profiler.Unit.BYTES,
        rendering.cpuBytes() + rendering.reserved(),
        "Scene CPU resident and reserved");
    scope.gauge(
        Profiler.Kind.SCENE_GPU_BYTES,
        Profiler.Unit.BYTES,
        rendering.gpuCapacityBytes(),
        "Scene GPU arena allocation");
    scope.gauge(
        Profiler.Kind.SCENE_GPU_BYTES,
        Profiler.Unit.BYTES,
        views.shadowBytes(),
        "Shadow depth-map allocation");
  }

  public void draw(ImBoolean open, Viewports.StreamingStatistics rendering) {
    draw(open, rendering, null);
  }

  public void draw(
      ImBoolean open,
      Viewports.StreamingStatistics rendering,
      java.util.function.IntSupplier compact) {
    draw(open, rendering, compact, List.of());
  }

  public void draw(
      ImBoolean open,
      Viewports.StreamingStatistics rendering,
      java.util.function.IntSupplier compact,
      List<io.voxelcreator.render.GpuTimerQueries.Statistics> gpuTimings) {
    if (!open.get()) return;
    windowLayout.prepare();
    boolean visible = ImGui.begin("Profiler", open);
    UiScale.window(state.user, "Profiler");
    windowLayout.observe();
    if (!visible || !open.get()) {
      ImGui.end();
      return;
    }
    drawn = true;
    ImGui.textWrapped(state.engine.metrics().toString());
    ImGui.textWrapped(state.engine.schedulerStats().toString());
    ImGui.text(
        String.format(
            Locale.ROOT,
            "Scene: %d chunks / %d jobs / %d visible / %d culled / %d draws / indirect %s",
            rendering.chunks(),
            rendering.jobs(),
            rendering.visible(),
            rendering.culled(),
            rendering.drawCalls(),
            rendering.indirect()));
    ImGui.text(
        String.format(
            Locale.ROOT,
            "CPU: %,d resident + %,d reserved bytes | GPU data: %,d bytes | vertex arena"
                + " allocation: %,d bytes",
            rendering.cpuBytes(),
            rendering.reserved(),
            rendering.gpuBytes(),
            rendering.gpuCapacityBytes()));
    ImGui.text(
        String.format(
            Locale.ROOT,
            "GPU free: %,d bytes / largest range %,d | frame writes: %,d upload + %,d relocated"
                + " bytes",
            rendering.gpuFreeBytes(),
            rendering.largestFreeRange(),
            rendering.uploadedBytes(),
            rendering.relocatedBytes()));
    ImGui.text(
        String.format(
            Locale.ROOT,
            "Compaction: %d active or queued arenas / %,d bytes remain / %d completed",
            rendering.compactingArenas(),
            rendering.compactionRemainingBytes(),
            rendering.compactions()));
    if (compact != null && ImGui.button("Compact GPU Arenas"))
      message =
          "Requested compaction for "
              + compact.getAsInt()
              + " arenas; active uploads finish first.";
    ImGui.textDisabled(
        "Relocation shares the upload budget; no additional GPU vertex arena is allocated.");
    for (int i = 0; i < gpuTimings.size(); i++) {
      var gpu = gpuTimings.get(i);
      String view = i == 0 ? "2D" : i == 1 ? "3D Noise" : "World";
      ImGui.textDisabled(
          view
              + " GPU: "
              + gpu.state()
              + " / "
              + gpu.pending()
              + " pending / "
              + gpu.recorded()
              + " measured / "
              + gpu.skipped()
              + " skipped / "
              + gpu.discarded()
              + " discarded");
      if (gpu.state() == io.voxelcreator.render.GpuTimerQueries.State.FAILED
          || gpu.state() == io.voxelcreator.render.GpuTimerQueries.State.UNAVAILABLE)
        ImGui.textWrapped(gpu.detail());
    }
    ImGui.textDisabled(
        "GPU_DRAW measures offscreen 3D commands; excludes chunk/volume transfers, UI and"
            + " presentation.");
    ImGui.textDisabled(
        "Results expire after at most 2 s; stopping a capture discards unfinished GPU"
            + " measurements.");
    if (workProbe) ImGui.setNextItemOpen(true, ImGuiCond.Always);
    if (ImGui.collapsingHeader("Capture limits")) {
      fields.integer("Duration (ms)", duration, v -> duration = Math.clamp(v, 100, 60000));
      fields.integer("Maximum samples", samples, v -> samples = Math.clamp(v, 64, 65536));
      fields.integer("Maximum series", series, v -> series = Math.clamp(v, 1, 1024));
      fields.integer("Operation sampling stride", stride, v -> stride = Math.clamp(v, 1, 65536));
      fields.bool("Layer/operation work totals", worldContributions, v -> worldContributions = v);
      workControl("enabled");
      ImGui.textWrapped(
          "Work totals time every visited placement and include column prefetch. They add capture"
              + " overhead; operation stride still applies only to individual OPERATION samples.");
      if (worldContributions)
        ImGui.textDisabled(
            "Additional admitted scratch: up to "
                + (256 + 256L * series)
                + " bytes per generation task.");
    }
    ImGui.text(
        "Next capture: "
            + duration
            + " ms / "
            + samples
            + " samples / "
            + series
            + " series / operation stride "
            + stride
            + (worldContributions ? " / work totals enabled" : ""));
    if (ImGui.button("Start Capture")) start();
    workControl("start");
    ImGui.sameLine();
    if (ImGui.button("Stop")) {
      state.engine.profiler().stop();
      refreshed = 0;
    }
    workControl("stop");
    ImGui.sameLine();
    if (ImGui.button("Clear")) {
      state.engine.profiler().clear();
      snapshot = null;
      filteredSnapshot = null;
      filteredRows = List.of();
      selected = null;
      displayed = null;
      clearWorkTotal();
    }
    var capture = state.engine.profiler().current().orElse(null);
    long now = System.nanoTime();
    if (capture != null
        && (capture != displayed
            || snapshot == null
            || snapshot.state() == Profiler.State.RECORDING && now - refreshed >= 250_000_000L)) {
      snapshot = capture.snapshot();
      if (selected == null && !snapshot.series().isEmpty()) selected = snapshot.series().getFirst();
      displayed = capture;
      refreshed = now;
      if (selected != null)
        selected =
            snapshot.series().stream()
                .filter(s -> sameSeries(s, selected))
                .findFirst()
                .orElse(null);
    }
    if (capture == null) {
      snapshot = null;
      filteredSnapshot = null;
      filteredRows = List.of();
      selected = null;
      displayed = null;
      clearWorkTotal();
    }
    if (snapshot == null) {
      ImGui.textWrapped(
          "Capture is disabled. Start to collect bounded timings and metrics. CPU-side elapsed"
              + " timings include scheduling/preemption. GPU_DRAW uses optional asynchronous"
              + " GPU timestamps; unavailable contexts produce no GPU samples.");
      drawExport();
      ImGui.end();
      return;
    }
    ImGui.text(
        String.format(
            Locale.ROOT,
            "%s / %.2f s / %d of %d samples / %d of %d series",
            snapshot.state(),
            snapshot.elapsedNanos() / 1e9,
            snapshot.samples().size(),
            snapshot.options().maxSamples(),
            snapshot.series().size(),
            snapshot.options().maxSeries()));
    ImGui.inputText("Search label, UUID or context", filter);
    fields.choice("Measurement", kind, KINDS, v -> kind = v);
    String query = filter.get().strip().toLowerCase(Locale.ROOT);
    if (filteredSnapshot != snapshot
        || !kind.equals(filteredKind)
        || !query.equals(filteredQuery)) {
      filteredRows =
          filter(snapshot, kind.equals("All") ? null : Profiler.Kind.valueOf(kind), query);
      filteredSnapshot = snapshot;
      filteredKind = kind;
      filteredQuery = query;
      rowQueries++;
    }
    var rows = filteredRows;
    UiScale.beginChild("profile-series", 0, 195, true);
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = rows.get(i);
          String label =
              row.kind()
                  + " / "
                  + row.label().replace('\n', ' ').replace('\r', ' ').replace("##", "# #")
                  + " / context "
                  + row.contextId();
          if (ImGui.selectable(label + "###series" + i, sameSeries(row, selected))) selected = row;
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    if (selected != null) {
      ImGui.textWrapped(selected.kind() + " / " + selected.label());
      if (selected.kind() == Profiler.Kind.OPERATION_WORK
          || selected.kind() == Profiler.Kind.LAYER_WORK) {
        if (totalSnapshot != snapshot || !sameSeries(totalSeries, selected)) {
          selectedTotal = workTotal(snapshot, selected);
          totalSnapshot = snapshot;
          totalSeries = selected;
        }
        ImGui.text(
            "Attributed total in retained capture: " + value(selectedTotal, selected.unit()));
        ImGui.textWrapped(
            "Each value sums attributed elapsed work in one chunk/stage computation. Layer totals"
                + " sum their placements; do not add both kinds. Storage, scheduling and collection"
                + " overhead are outside these totals. Failed/cancelled computations describe an"
                + " executed prefix.");
      }
      ImGui.text(
          "Count "
              + selected.count()
              + "  Mean "
              + value(selected.mean(), selected.unit())
              + "  Min "
              + value(selected.minimum(), selected.unit())
              + "  Max "
              + value(selected.maximum(), selected.unit()));
      ImGui.text(
          "P50 "
              + value(selected.p50(), selected.unit())
              + "  P95 "
              + value(selected.p95(), selected.unit())
              + "  P99 "
              + value(selected.p99(), selected.unit())
              + "  Failed "
              + selected.failed()
              + "  Cancelled "
              + selected.cancelled());
      ImGui.textWrapped(
          "Project "
              + selected.projectUuid()
              + " / source "
              + selected.sourceUuid()
              + " / context "
              + selected.contextId());
      var target = target(selected);
      if (state.canNavigate(target) && ImGui.button("Go To Source")) navigate.accept(target);
    } else ImGui.textDisabled("Select a series for counts, P50/P95/P99 and source navigation.");
    drawExport();
    ImGui.end();
  }

  private void drawExport() {
    ImGui.separator();
    ImGui.beginDisabled(snapshot == null);
    if (ImGui.button("Prepare Export")) {
      if (exportReview != null) exportReview.cancel();
      exportReview = new ProfileExportSession(snapshot);
      message = "";
    }
    workControl("prepare-export");
    ImGui.endDisabled();
    if (exportReview != null) {
      var reviewed = exportReview.snapshot();
      ImGui.textWrapped(
          "Export review: "
              + reviewed.captureId()
              + " / "
              + reviewed.state()
              + " / "
              + reviewed.samples().size()
              + " samples / "
              + reviewed.series().size()
              + " series");
      ImGui.sameLine();
      if (ImGui.button("Discard Export Review")) {
        exportReview.cancel();
        exportReview = null;
        message = "";
      }
      workControl("discard-export");
    } else ImGui.textDisabled("Prepare the displayed capture to review its export.");
    ImGui.inputText("Export path", exportPath);
    workControl("export-path");
    ImGui.beginDisabled(exportReview == null || !exportReview.available());
    if (ImGui.button("Export JSON")) export(false);
    workControl("export-json");
    ImGui.sameLine();
    if (ImGui.button("Export CSV")) export(true);
    workControl("export-csv");
    ImGui.endDisabled();
    ImGui.textWrapped(message);
    ImGui.textDisabled(
        "Exports use the reviewed snapshot and include every retained sample and series.");
    ImGui.textDisabled(
        "A failed export keeps its review. Prepare again to use the displayed capture.");
    if (scrollToExport) {
      ImGui.setScrollHereY(1);
      scrollToExport = false;
    }
  }

  private void export(boolean csv) {
    try {
      var reviewed = exportReview.snapshot();
      var path = exportReview.write(exportPath.get(), csv, state.document.path());
      message = "Exported " + reviewed.samples().size() + " samples to " + path;
    } catch (Exception error) {
      String detail = error.getMessage();
      if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
      message =
          "Export failed: " + (detail.length() > 1024 ? detail.substring(0, 1023) + "…" : detail);
      state.error("PROFILE_EXPORT_FAILED", EditorEvents.Subsystem.IMPORT_EXPORT, message);
    }
  }

  ProfileExportSession exportReview() {
    return exportReview;
  }

  Profiler.Snapshot displayedSnapshot() {
    return snapshot;
  }

  long rowQueries() {
    return rowQueries;
  }

  void probeExportPath(String path) {
    exportPath.set(path);
    scrollToExport = true;
  }

  static List<Profiler.Series> filter(
      Profiler.Snapshot snapshot, Profiler.Kind kind, String query) {
    String text = query.strip().toLowerCase(Locale.ROOT);
    return snapshot.series().stream()
        .filter(s -> kind == null || s.kind() == kind)
        .filter(
            s ->
                text.isEmpty()
                    || (s.label()
                            + " "
                            + s.kind()
                            + " "
                            + s.contextId()
                            + " "
                            + s.projectUuid()
                            + " "
                            + s.sourceUuid())
                        .toLowerCase(Locale.ROOT)
                        .contains(text))
        .toList();
  }

  static EditorEvents.Target target(Profiler.Series series) {
    return new EditorEvents.Target(
        series.projectUuid(), series.sourceUuid(), null, null, "", false);
  }

  private void clearWorkTotal() {
    totalSeries = null;
    totalSnapshot = null;
    selectedTotal = 0;
  }

  static long workTotal(Profiler.Snapshot snapshot, Profiler.Series series) {
    long total = 0;
    for (var sample : snapshot.samples())
      if (sample.kind() == series.kind()
          && sample.unit() == series.unit()
          && sample.contextId() == series.contextId()
          && Objects.equals(sample.projectUuid(), series.projectUuid())
          && Objects.equals(sample.sourceUuid(), series.sourceUuid())
          && sample.label().equals(series.label()))
        total = sample.value() > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + sample.value();
    return total;
  }

  private static boolean sameSeries(Profiler.Series a, Profiler.Series b) {
    return a != null
        && b != null
        && a.kind() == b.kind()
        && a.unit() == b.unit()
        && a.contextId() == b.contextId()
        && Objects.equals(a.projectUuid(), b.projectUuid())
        && Objects.equals(a.sourceUuid(), b.sourceUuid())
        && a.label().equals(b.label());
  }

  private static String value(double value, Profiler.Unit unit) {
    return String.format(
        Locale.ROOT,
        "%.3f %s",
        unit == Profiler.Unit.NANOSECONDS ? value / 1e6 : value,
        unit == Profiler.Unit.NANOSECONDS
            ? "ms"
            : unit == Profiler.Unit.MILLISECONDS
                ? "ms"
                : unit == Profiler.Unit.BYTES ? "bytes" : "count");
  }

  public void assertSmokeDrawn() {
    var captured = state.engine.profiler().current().orElseThrow().snapshot();
    if (!drawn
        || captured.samples().stream().noneMatch(s -> s.kind() == Profiler.Kind.FRAME)
        || captured.samples().stream().noneMatch(s -> s.kind() == Profiler.Kind.HEAP_BYTES))
      throw new IllegalStateException(
          "Native capture controls or real frame/heap metrics were not observed");
  }
}
