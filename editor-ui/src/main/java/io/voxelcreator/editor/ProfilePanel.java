package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.api.*;
import java.lang.management.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** UI-owned capture controls. Capture/export/navigation never change the project document. */
public final class ProfilePanel {
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
  private Profiler.Series selected;
  private long refreshed, gaugesAt;
  private boolean drawn;

  public ProfilePanel(EditorState state, Consumer<EditorEvents.Target> navigate) {
    this.state = state;
    this.navigate = navigate;
  }

  public void start() {
    state.engine.profiler().start(new Profiler.Options(duration, samples, series, stride));
    snapshot = null;
    selected = null;
    gaugesAt = 0;
    message = "";
  }

  /** Called once per editor frame, including while the Profiler window is closed. */
  public void frame(float seconds, Viewports views) {
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
  }

  public void draw(ImBoolean open, Viewports.StreamingStatistics rendering) {
    draw(open, rendering, null);
  }

  public void draw(
      ImBoolean open,
      Viewports.StreamingStatistics rendering,
      java.util.function.IntSupplier compact) {
    if (!open.get()) return;
    ImGui.setNextWindowSize(1050, 800, ImGuiCond.FirstUseEver);
    ImGui.begin("Profiler", open);
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
    if (ImGui.collapsingHeader("Capture limits")) {
      fields.integer("Duration (ms)", duration, v -> duration = Math.clamp(v, 100, 60000));
      fields.integer("Maximum samples", samples, v -> samples = Math.clamp(v, 64, 65536));
      fields.integer("Maximum series", series, v -> series = Math.clamp(v, 1, 1024));
      fields.integer("Operation sampling stride", stride, v -> stride = Math.clamp(v, 1, 65536));
    }
    ImGui.text(
        "Next capture: "
            + duration
            + " ms / "
            + samples
            + " samples / "
            + series
            + " series / operation stride "
            + stride);
    if (ImGui.button("Start Capture")) start();
    ImGui.sameLine();
    if (ImGui.button("Stop")) {
      state.engine.profiler().stop();
      refreshed = 0;
    }
    ImGui.sameLine();
    if (ImGui.button("Clear")) {
      state.engine.profiler().clear();
      snapshot = null;
      selected = null;
    }
    var capture = state.engine.profiler().current().orElse(null);
    long now = System.nanoTime();
    if (capture != null
        && (capture != displayed || snapshot == null || now - refreshed >= 250_000_000L)) {
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
    if (capture == null) snapshot = null;
    if (snapshot == null) {
      ImGui.textWrapped(
          "Capture is disabled. Start to collect bounded timings and metrics. CPU-side elapsed"
              + " timings include scheduling/preemption; GPU completion times are not measured.");
      ImGui.textWrapped(message);
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
    var kinds = new ArrayList<String>();
    kinds.add("All");
    for (var k : Profiler.Kind.values()) kinds.add(k.name());
    fields.choice("Measurement", kind, kinds, v -> kind = v);
    var rows =
        filter(snapshot, kind.equals("All") ? null : Profiler.Kind.valueOf(kind), filter.get());
    ImGui.beginChild("profile-series", 0, 195, true);
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
    ImGui.inputText("Export path", exportPath);
    if (ImGui.button("Export JSON")) export(false);
    ImGui.sameLine();
    if (ImGui.button("Export CSV")) export(true);
    ImGui.textWrapped(message);
    ImGui.textDisabled(
        "Exports include all retained samples and series; filters affect this view only.");
    ImGui.end();
  }

  private void export(boolean csv) {
    try {
      if (exportPath.get().isBlank()) throw new IllegalArgumentException("Choose an export path");
      // Export an immutable snapshot taken at the explicit request, without stopping collection.
      var current = state.engine.profiler().current().orElseThrow().snapshot();
      Path path = Path.of(exportPath.get());
      if (csv) ProfileIO.writeCSV(path, current);
      else ProfileIO.writeJSON(path, current);
      message = "Exported " + current.samples().size() + " samples to " + path.toAbsolutePath();
    } catch (Exception error) {
      message = "Export failed: " + error.getMessage();
    }
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
