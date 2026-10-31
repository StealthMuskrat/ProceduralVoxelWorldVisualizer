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
  private final ProfileCaptureInputs captureInputs = new ProfileCaptureInputs();
  private final Map<ProfileCaptureInputs.Field, ImString> captureText =
      new EnumMap<>(ProfileCaptureInputs.Field.class);
  final SettingsPageChrome layout = new SettingsPageChrome(4, 4);
  final PreferenceActionScope captureActions = new PreferenceActionScope();
  final SettingsActionScope prepareActions = new SettingsActionScope(),
      reviewActions = new SettingsActionScope();
  static final List<String> SECTIONS = List.of("Capture & runtime", "Measurements", "Export");
  private String section = "Capture & runtime";
  private ProfileCaptureInputs.Field revealLimit;
  private DialogBounds.Rect selector, seriesBounds;
  private final Map<String, Integer> fieldIds = new LinkedHashMap<>();
  private final Map<String, Integer> rowIds = new LinkedHashMap<>();
  private float rowStride;
  private int submittedRows;
  private boolean revealSeries;
  private final Map<String, DialogBounds.Rect> inputBounds = new LinkedHashMap<>();
  private String kind = "All", message = "";
  private Profiler.Capture displayed;
  private Profiler.Snapshot snapshot;
  private ProfileExportSession exportReview;
  private Profiler.Snapshot filteredSnapshot;
  private String filteredKind, filteredQuery;
  private List<Profiler.Series> filteredRows = List.of();
  private long rowQueries;
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
  private boolean drawn;
  private boolean workProbe;
  private final Map<String, float[]> workControls = new HashMap<>();

  public ProfilePanel(EditorState state, Consumer<EditorEvents.Target> navigate) {
    this.state = state;
    this.navigate = navigate;
    captureInputs
        .text()
        .forEach(
            (field, text) ->
                captureText.put(field, new ImString(text, 4 * ProfileCaptureInputs.MAX_TEXT + 1)));
  }

  public void start() {
    try {
      var options = captureInputs.request(displayedCaptureInputs());
      state.engine.profiler().start(options);
      snapshot = null;
      filteredSnapshot = null;
      filteredRows = List.of();
      selected = null;
      clearWorkTotal();
      gaugesAt = 0;
      message = "";
    } catch (RuntimeException invalid) {
      section = "Capture & runtime";
      revealLimit = captureInputs.firstProblem(displayedCaptureInputs());
      message =
          "Capture not started: "
              + Objects.requireNonNullElse(
                  invalid.getMessage(), invalid.getClass().getSimpleName());
      state.error("PROFILE_CAPTURE_REJECTED", EditorEvents.Subsystem.APPLICATION, message);
    }
  }

  private Map<ProfileCaptureInputs.Field, String> displayedCaptureInputs() {
    var result = new EnumMap<ProfileCaptureInputs.Field, String>(ProfileCaptureInputs.Field.class);
    captureText.forEach((field, text) -> result.put(field, text.get()));
    return result;
  }

  void probeWorkControls() {
    workProbe = true;
  }

  float[] probeWorkControl(String key) {
    return workControls.get(key);
  }

  private void workControl(String key) {
    if (workProbe) {
      workControls.put(
          key,
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          });
      if (fieldIds.containsKey(key)) inputBounds.put(key, PopupLayout.itemBounds());
    }
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
    if (!open.get()) {
      suspendActions();
      return;
    }
    windowLayout.prepare();
    boolean visible = ImGui.begin("Profiler", open);
    UiScale.window(state.user, "Profiler");
    windowLayout.observe();
    if (!visible || !open.get()) {
      suspendActions();
      ImGui.end();
      return;
    }
    drawn = true;
    if (workProbe) workControls.clear();
    fieldIds.clear();
    inputBounds.clear();
    rowIds.clear();
    submittedRows = 0;
    seriesBounds = null;
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice("##profile-section", section, SECTIONS, this::section);
    selector = PopupLayout.itemBounds();
    fields.tooltip("Choose a profiler section");
    refreshSnapshot();
    var capture = ProfileActionContext.from(state.engine.profiler());
    long captureRequest = captureActions.synchronize(capture);
    var shown = snapshot;
    long prepareRequest = prepareActions.synchronize(this, shown == null ? this : shown);
    var review = exportReview;
    long reviewRequest =
        reviewActions.synchronize(
            this, review == null ? this : review, review != null && review.available() ? 1 : 0);
    layout.begin("profile-workspace-" + section);
    if (snapshot == null) ImGui.textWrapped("No displayed capture. Prepare Export is unavailable.");
    else
      ImGui.textWrapped(
          "Displayed capture: "
              + snapshot.captureId()
              + " / "
              + snapshot.state()
              + " / "
              + snapshot.samples().size()
              + " samples / "
              + snapshot.series().size()
              + " series");
    switch (section) {
      case "Capture & runtime" -> {
        drawCaptureInputs();
        drawRuntime(rendering, compact, gpuTimings);
      }
      case "Measurements" -> drawMeasurements();
      case "Export" -> drawExport();
      default -> throw new IllegalStateException("Unknown profiler section");
    }
    if (!message.isEmpty() && !section.equals("Export")) ImGui.textWrapped(message);
    layout.end();
    drawActions(capture, captureRequest, shown, prepareRequest, review, reviewRequest);
    ImGui.end();
  }

  private void drawRuntime(
      Viewports.StreamingStatistics rendering,
      java.util.function.IntSupplier compact,
      List<io.voxelcreator.render.GpuTimerQueries.Statistics> gpuTimings) {
    ImGui.textWrapped(state.engine.metrics().toString());
    ImGui.textWrapped(state.engine.schedulerStats().toString());
    ImGui.textWrapped(
        String.format(
            Locale.ROOT,
            "Scene: %d chunks / %d jobs / %d visible / %d culled / %d draws / indirect %s",
            rendering.chunks(),
            rendering.jobs(),
            rendering.visible(),
            rendering.culled(),
            rendering.drawCalls(),
            rendering.indirect()));
    ImGui.textWrapped(
        String.format(
            Locale.ROOT,
            "CPU: %,d resident + %,d reserved bytes | GPU data: %,d bytes | vertex arena"
                + " allocation: %,d bytes",
            rendering.cpuBytes(),
            rendering.reserved(),
            rendering.gpuBytes(),
            rendering.gpuCapacityBytes()));
    ImGui.textWrapped(
        String.format(
            Locale.ROOT,
            "GPU free: %,d bytes / largest range %,d | frame writes: %,d upload + %,d relocated"
                + " bytes",
            rendering.gpuFreeBytes(),
            rendering.largestFreeRange(),
            rendering.uploadedBytes(),
            rendering.relocatedBytes()));
    ImGui.textWrapped(
        String.format(
            Locale.ROOT,
            "Compaction: %d active or queued arenas / %,d bytes remain / %d completed",
            rendering.compactingArenas(),
            rendering.compactionRemainingBytes(),
            rendering.compactions()));
    if (compact != null && DialogLayout.button("Compact GPU Arenas"))
      message =
          "Requested compaction for "
              + compact.getAsInt()
              + " arenas; active uploads finish first.";
    ImGui.textWrapped(
        "Relocation shares the upload budget; no additional GPU vertex arena is allocated.");
    for (int i = 0; i < gpuTimings.size(); i++) {
      var gpu = gpuTimings.get(i);
      String view = i == 0 ? "2D" : i == 1 ? "3D Noise" : "World";
      ImGui.textWrapped(
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
    ImGui.textWrapped(
        "GPU_DRAW measures offscreen 3D commands; excludes chunk/volume transfers, UI and"
            + " presentation.");
    ImGui.textWrapped(
        "Results expire after at most 2 s; stopping a capture discards unfinished GPU"
            + " measurements.");
  }

  private void drawCaptureInputs() {
    ImGui.textWrapped(
        "Limits configure the next capture. Current measurements and an export review keep their"
            + " own immutable options.");
    for (var field : ProfileCaptureInputs.Field.values()) {
      ImGui.textWrapped(field.label);
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      String id = "##profile-limit-" + field.name();
      fieldIds.put(field.name(), ImGui.getID(id));
      boolean reveal = field == revealLimit;
      if (reveal) ImGui.setKeyboardFocusHere();
      if (ImGui.inputText(id, captureText.get(field))) {
        try {
          captureInputs.edit(field, captureText.get(field).get());
          message = "";
        } catch (RuntimeException invalid) {
          message = Objects.requireNonNullElse(invalid.getMessage(), invalid.toString());
        }
      }
      workControl(field.name());
      if (reveal) {
        ImGui.setScrollHereY(.25f);
        revealLimit = null;
      }
    }
    var work = new ImBoolean(captureInputs.work());
    if (ImGui.checkbox("##profile-work-totals", work)) captureInputs.work(work.get());
    workControl("enabled");
    DialogLayout.sameLine("Layer/operation work totals");
    ImGui.textWrapped("Layer/operation work totals");
    ImGui.textWrapped(
        "Work totals time every visited placement and include column prefetch. They add capture"
            + " overhead; operation stride still applies only to individual OPERATION samples.");
    var feedback = captureInputs.feedback();
    feedback
        .problems()
        .forEach((field, problem) -> ImGui.textWrapped("! " + field.label + ": " + problem));
    if (feedback.options() != null) {
      var options = feedback.options();
      ImGui.textWrapped(
          "Next capture: "
              + options.durationMillis()
              + " ms / "
              + options.maxSamples()
              + " samples / "
              + options.maxSeries()
              + " series / operation stride "
              + options.operationStride());
      if (options.worldContributions())
        ImGui.textWrapped(
            "Additional admitted scratch: up to "
                + (256 + 256L * options.maxSeries())
                + " bytes per generation task.");
    }
  }

  private void refreshSnapshot() {
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
  }

  private void drawMeasurements() {
    if (snapshot == null) {
      ImGui.textWrapped(
          "Capture is disabled. Start to collect bounded timings and metrics. CPU-side elapsed"
              + " timings include scheduling/preemption. GPU_DRAW uses optional asynchronous GPU"
              + " timestamps; unavailable contexts produce no GPU samples.");
      return;
    }
    ImGui.textWrapped(
        String.format(
            Locale.ROOT,
            "%s / %.2f s / %d of %d samples / %d of %d series",
            snapshot.state(),
            snapshot.elapsedNanos() / 1e9,
            snapshot.samples().size(),
            snapshot.options().maxSamples(),
            snapshot.series().size(),
            snapshot.options().maxSeries()));
    ImGui.textWrapped("Search label, UUID or context");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fieldIds.put("filter", ImGui.getID("##profile-series-filter"));
    ImGui.inputText("##profile-series-filter", filter);
    workControl("filter");
    ImGui.textWrapped("Measurement");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fields.choice("##profile-measurement", kind, KINDS, v -> kind = v);
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
    float listHeight =
        SettingsPageLayout.multiline(layout.body().height(), ImGui.getTextLineHeightWithSpacing());
    UiScale.beginChild("profile-series", 0, listHeight, true);
    seriesBounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    var clipper = new ImGuiListClipper();
    rowStride = (float) Math.ceil(ImGui.getTextLineHeightWithSpacing());
    try {
      clipper.begin(rows.size(), rowStride);
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = rows.get(i);
          float top = ImGui.getCursorPosY();
          String label =
              row.kind()
                  + " / "
                  + row.label().replace('\n', ' ').replace('\r', ' ').replace("##", "# #")
                  + " / context "
                  + row.contextId();
          String key = ProfileSeriesKey.of(row).widgetId();
          ImGui.pushID(key);
          String id = StatusBarText.clip(label, 120) + "###series";
          if (workProbe) rowIds.put(key, ImGui.getID(id));
          if (ImGui.selectable(
              id,
              sameSeries(row, selected),
              0,
              Math.max(1, ImGui.getContentRegionAvailX()),
              ImGui.getTextLineHeight())) selected = row;
          PopupLayout.clippedLabel(label);
          ImGui.popID();
          ImGui.setCursorPosY(top + rowStride - ImGui.getStyle().getItemSpacingY());
          ImGui.dummy(0, 0);
          submittedRows++;
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    if (revealSeries) {
      ImGui.setScrollHereY(.3f);
      revealSeries = false;
    }
    if (selected != null) {
      ImGui.textWrapped(selected.kind() + " / " + selected.label());
      if (selected.kind() == Profiler.Kind.OPERATION_WORK
          || selected.kind() == Profiler.Kind.LAYER_WORK) {
        if (totalSnapshot != snapshot || !sameSeries(totalSeries, selected)) {
          selectedTotal = workTotal(snapshot, selected);
          totalSnapshot = snapshot;
          totalSeries = selected;
        }
        ImGui.textWrapped(
            "Attributed total in retained capture: " + value(selectedTotal, selected.unit()));
        ImGui.textWrapped(
            "Each value sums attributed elapsed work in one chunk/stage computation. Layer totals"
                + " sum their placements; do not add both kinds. Storage, scheduling and collection"
                + " overhead are outside these totals. Failed/cancelled computations describe an"
                + " executed prefix.");
      }
      ImGui.textWrapped(
          "Count "
              + selected.count()
              + "  Mean "
              + value(selected.mean(), selected.unit())
              + "  Min "
              + value(selected.minimum(), selected.unit())
              + "  Max "
              + value(selected.maximum(), selected.unit()));
      ImGui.textWrapped(
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
      if (state.canNavigate(target) && DialogLayout.button("Go To Source")) navigate.accept(target);
    } else ImGui.textDisabled("Select a series for counts, P50/P95/P99 and source navigation.");
  }

  private void drawExport() {
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
      if (!exportReview.available())
        ImGui.textWrapped(
            "This review has been consumed. Prepare the displayed capture to export again.");
    } else ImGui.textWrapped("Prepare the displayed capture to review its export.");
    ImGui.textWrapped("Export path");
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    fieldIds.put("export-path", ImGui.getID("##profile-export-path"));
    ImGui.inputText("##profile-export-path", exportPath);
    workControl("export-path");
    ImGui.textWrapped(message);
    ImGui.textWrapped(
        "Exports use the reviewed snapshot and include every retained sample and series. A failed"
            + " export keeps its review. Prepare again to use the displayed capture.");
  }

  private void drawActions(
      ProfileActionContext capture,
      long captureRequest,
      Profiler.Snapshot shown,
      long prepareRequest,
      ProfileExportSession review,
      long reviewRequest) {
    var first = captureInputs.firstProblem(displayedCaptureInputs());
    ImGui.pushID("profile-capture:" + captureRequest);
    ImGui.beginDisabled(first != null);
    boolean start = layout.identifiedAction("start", "Start Capture");
    workControl("start");
    ImGui.endDisabled();
    ImGui.beginDisabled(!capture.recording());
    boolean stop = layout.identifiedAction("stop", "Stop");
    workControl("stop");
    ImGui.endDisabled();
    ImGui.beginDisabled(capture.capture() == null);
    boolean clear = layout.identifiedAction("clear", "Clear");
    workControl("clear");
    ImGui.endDisabled();
    ImGui.beginDisabled(first == null);
    boolean problem = layout.identifiedAction("problem", "Show Invalid Limit");
    workControl("problem");
    ImGui.endDisabled();
    ImGui.popID();
    ImGui.pushID("profile-prepare:" + prepareRequest);
    ImGui.beginDisabled(shown == null);
    boolean prepare = layout.identifiedAction("prepare-export", "Prepare Export");
    workControl("prepare-export");
    ImGui.endDisabled();
    ImGui.popID();
    ImGui.pushID("profile-review:" + reviewRequest);
    ImGui.beginDisabled(review == null);
    boolean discard = layout.identifiedAction("discard-export", "Discard Review");
    workControl("discard-export");
    ImGui.endDisabled();
    ImGui.beginDisabled(review == null || !review.available());
    boolean json = layout.identifiedAction("export-json", "Export JSON");
    workControl("export-json");
    boolean csv = layout.identifiedAction("export-csv", "Export CSV");
    workControl("export-csv");
    ImGui.endDisabled();
    ImGui.popID();
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) return;
    boolean captureCurrent =
        captureActions.current(ProfileActionContext.from(state.engine.profiler()));
    boolean prepareCurrent = prepareActions.current(this, snapshot == null ? this : snapshot);
    boolean reviewCurrent =
        reviewActions.current(
            this,
            exportReview == null ? this : exportReview,
            exportReview != null && exportReview.available() ? 1 : 0);
    if (discard && review != null && reviewCurrent) {
      review.cancel();
      exportReview = null;
      message = "";
    } else if (stop && capture.recording() && captureCurrent) {
      capture.profiler().stop();
      refreshed = 0;
    } else if (clear && capture.capture() != null && captureCurrent) {
      capture.profiler().clear();
      resetDisplayed();
    } else if (problem && first != null && captureCurrent) {
      section = "Capture & runtime";
      revealLimit = first;
    } else if (start && first == null && captureCurrent) start();
    else if (prepare && shown != null && prepareCurrent) {
      if (exportReview != null) exportReview.cancel();
      exportReview = new ProfileExportSession(shown);
      section = "Export";
      message = "";
    } else if (json && review != null && review.available() && reviewCurrent) export(review, false);
    else if (csv && review != null && review.available() && reviewCurrent) export(review, true);
  }

  void suspendActions() {
    captureActions.resetDraft();
    prepareActions.release();
    reviewActions.release();
  }

  private void resetDisplayed() {
    snapshot = null;
    filteredSnapshot = null;
    filteredRows = List.of();
    selected = null;
    displayed = null;
    clearWorkTotal();
  }

  private void export(ProfileExportSession owner, boolean csv) {
    section = "Export";
    try {
      var reviewed = owner.snapshot();
      var path = owner.write(exportPath.get(), csv, state.document.path());
      if (exportReview == owner)
        message = "Exported " + reviewed.samples().size() + " samples to " + path;
    } catch (Exception error) {
      String detail = error.getMessage();
      if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
      String feedback = "Export failed: " + StatusBarText.clip(detail, 1024);
      state.error("PROFILE_EXPORT_FAILED", EditorEvents.Subsystem.IMPORT_EXPORT, feedback);
      if (exportReview == owner) message = feedback;
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

  void probeCaptureInput(ProfileCaptureInputs.Field field, String raw) {
    captureInputs.edit(field, raw);
    captureText.get(field).set(raw);
  }

  void probeReview(Profiler.Snapshot value) {
    if (exportReview != null) exportReview.cancel();
    exportReview = new ProfileExportSession(value);
    section = "Export";
    message = "";
  }

  void probeExportPath(String path) {
    exportPath.set(path);
    section = "Export";
  }

  void section(String value) {
    if (value == null || !SECTIONS.contains(value))
      throw new IllegalArgumentException("Unknown profiler section");
    section = value;
    if (value.equals("Measurements")) revealSeries = true;
  }

  String section() {
    return section;
  }

  DialogBounds.Rect selector() {
    return selector;
  }

  DialogBounds.Rect seriesBounds() {
    return seriesBounds;
  }

  int submittedRows() {
    return submittedRows;
  }

  Map<String, Integer> fieldIds() {
    return Map.copyOf(fieldIds);
  }

  Map<ProfileCaptureInputs.Field, String> captureText() {
    return displayedCaptureInputs();
  }

  ProfileCaptureInputs.Feedback captureFeedback() {
    return captureInputs.feedback();
  }

  Map<String, Integer> rowIds() {
    return Map.copyOf(rowIds);
  }

  float rowStride() {
    return rowStride;
  }

  Map<String, DialogBounds.Rect> inputBounds() {
    return Map.copyOf(inputBounds);
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
