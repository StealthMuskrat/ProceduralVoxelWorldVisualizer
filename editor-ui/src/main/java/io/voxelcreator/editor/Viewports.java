package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.Coordinates;
import io.voxelcreator.model.BookmarkTransactions.Tab;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.joml.Vector3d;

public final class Viewports implements AutoCloseable {
  final FloatingWindowLayout windowLayout = new FloatingWindowLayout(900, 700);
  final FloatingWindowLayout inspectorLayout = new FloatingWindowLayout(600, 600);
  private final InspectorPanel inspectorPanel;
  private final EditorState state;
  private final PropertyPanel properties;
  private TracePanel tracePanel;
  private TraceSnapshot traceSnapshot;
  private String inspectorPickHint = "";
  private boolean inspectorHintVisible, inspectorTraceVisible, inspectorDraftNoticeVisible;
  private boolean inspectorFreshnessNoticeVisible;

  public void tracePanel(TracePanel panel) {
    tracePanel = panel;
  }

  private final ViewportPlatformInput platform;
  private final FlightCursor flightCursor;
  private FlightCursor.Window surfaceWindow;
  private boolean closed;
  private final View[] views = {new View(0), new View(1), new View(2)};
  private final UiFields fields = new UiFields();
  private int active = 0;
  private int drawnView = -1;
  private boolean viewportFocused,
      flightImageVisible,
      sliceImageVisible,
      noiseImageVisible,
      surfacePressed;
  private NoiseImageView.Sample probeImageHover;
  private SliceImageView.Sample probeSliceHover;
  private String probeFlightInput = "";
  private boolean probeOrbitContextVisible;
  private int smokeTab = -1;
  private final InspectorRequests traceRequests = new InspectorRequests();
  private final InspectorTask inspectorTask = new InspectorTask();
  private java.util.function.BooleanSupplier inspectorTaskValid;
  private String inspectorTaskFeedback = "";
  private PickView observedPickView;
  private final ImBoolean inspector = new ImBoolean(false);
  private final InspectorCoordinates inspectorCoordinates = new InspectorCoordinates();
  private final Map<String, ImString> inspectorInputs = new LinkedHashMap<>();
  private String inspectorInputError = "";
  private final BookmarkPanel bookmarkPanel;

  boolean bookmarkInteractionPending() {
    return bookmarkPanel.pending();
  }

  private final ResourcePicker generatorPicker;
  private final StageBoundaryPicker stagePicker;
  private final ViewportCommands[] commands;
  private boolean admissionProbe;
  private final java.util.concurrent.atomic.AtomicBoolean probeRendererStartupFailure =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicBoolean probeRendererFrameFailure =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicBoolean probeStreamCompileFailure =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final Map<String, float[]> admissionControls = new HashMap<>();

  private record PickView(
      int tab,
      long epoch,
      double x,
      double y,
      double z,
      double yaw,
      double pitch,
      double distance,
      boolean flying) {}

  private void observePickView() {
    var v = views[2];
    var eye = v.camera.eye();
    var current =
        new PickView(
            active,
            v.epoch,
            eye.x,
            eye.y,
            eye.z,
            v.camera.yaw,
            v.camera.pitch,
            v.camera.distance,
            v.camera.flying);
    if (!current.equals(observedPickView)) {
      observedPickView = current;
      pickViewChanged();
    }
  }

  private void pickViewChanged() {
    traceRequests.viewChanged();
    if (inspectorTask.pending() && inspectorTask.request().viewBound())
      cancelInspectorTask("View changed; pick canceled.", false);
  }

  private void reconcileInspectorTask() {
    if (inspectorTask.pending() && !inspectorTaskValid.getAsBoolean())
      cancelInspectorTask(
          inspectorTask.request().viewBound()
              ? "Preview changed; pick canceled."
              : "Project changed; Trace XYZ to use the current project.",
          false);
  }

  void probeWorld(UUID stage, String mode, int side, double centerX) {
    admissionProbe = true;
    smokeTab = 2;
    var v = views[2];
    v.fixed = true;
    v.stage = stage;
    v.stageMode = mode;
    v.size = side;
    v.panX = centerX;
    v.panY = v.panZ = .25;
    v.camera.focus.set(centerX, .25, .25);
    v.camera.yaw = Math.PI / 2;
    v.camera.pitch = 0;
    v.camera.distance = 20;
    inspector.set(false);
    mark(v);
  }

  boolean probeWorldReady() {
    return probeNoiseReady(2);
  }

  void probeWorldFallback(UUID stage) {
    probeWorld(stage, "After", 8, .25);
    views[2].fixed = false;
    state.user.visualization.streaming = true;
    probeFailNextRendererStartup();
    mark(views[2]);
  }

  boolean probeWorldFallbackReady() {
    var v = views[2];
    return probeWorldReady() && v.streamFallback && v.scene == null && v.gl.sceneRenderer() == null;
  }

  void probeWorldStream(UUID stage, String mode) {
    probeWorld(stage, mode, 8, .25);
    var v = views[2];
    v.fixed = false;
    v.camera.distance = 60;
    v.rebuildSerial++;
    v.force = true;
    state.user.visualization.streaming = true;
    mark(v);
  }

  boolean probeWorldStreamReady(int chunkX) {
    var v = views[2];
    var gpu = v.gl.sceneRenderer();
    return usesStreaming(v)
        && v.activeSceneKey.equals(sceneKey(v))
        && v.compiler == null
        && v.scene != null
        && gpu != null
        && v.scene.statistics().activeJobs() == 0
        && gpu.pickingVersions().keySet().equals(Set.of(new ChunkPos(chunkX, 0, 0)));
  }

  void probeWorldTravel(double x) {
    views[2].camera.focus.x = x;
    inspector.set(false);
  }

  long probeWorldStreamEpoch() {
    return views[2].scene.epoch();
  }

  void probeWorldProxyView(UUID stage, String mode) {
    probeWorldStream(stage, mode);
    var v = views[2];
    v.camera.focus.set(16, 2.5, 2.5);
    v.camera.distance = 64;
  }

  Optional<ChunkScene.Pick> probeWorldCenterPick() {
    var v = views[2];
    return v.scene == null || v.gl.sceneRenderer() == null
        ? Optional.empty()
        : v.scene.pick(
            v.camera.ray(0, 0, 1, v.origin), 1000, v.gl.sceneRenderer().pickingVersions());
  }

  String probeInspectorFeedback() {
    return inspectorPickHint;
  }

  boolean probeInspectorTraceVisible() {
    return inspectorTraceVisible;
  }

  boolean probeInspectorHintVisible() {
    return inspectorHintVisible;
  }

  boolean probeCoordinateTracePending() {
    return inspectorTask.pending() && !inspectorTask.request().viewBound();
  }

  boolean probeWorldPickPending() {
    return inspectorTask.pending() && inspectorTask.request().viewBound();
  }

  String probeInspectorTaskFeedback() {
    return inspectorTaskFeedback;
  }

  BlockPos probeInspectorCoordinate() {
    return inspectorCoordinate();
  }

  private BlockPos inspectorCoordinate() {
    return inspectorCoordinates.coordinate();
  }

  String probeInspectorCoordinateText(String axis) {
    return inspectorCoordinates.text(axis);
  }

  void probeInspectorCoordinatesPage() {
    inspectorPanel.showCoordinates(false);
  }

  boolean probeInspectorCoordinateValid() {
    return inspectorCoordinates.problem().isEmpty();
  }

  private ImString inspectorInput(String axis) {
    return inspectorInputs.computeIfAbsent(
        axis,
        key -> new ImString(inspectorCoordinates.text(key), CoordinateText.MAX_CHARACTERS + 1));
  }

  private void setInspectorCoordinate(BlockPos point) {
    inspectorCoordinates.set(point);
    for (var axis : inspectorCoordinates.axes())
      inspectorInput(axis).set(inspectorCoordinates.text(axis));
    inspectorInputError = "";
  }

  private BlockPos readInspectorCoordinate() {
    var displayed = new LinkedHashMap<String, String>();
    for (var axis : inspectorCoordinates.axes()) displayed.put(axis, inspectorInput(axis).get());
    return inspectorCoordinates.coordinate(displayed);
  }

  private void addInspectorBookmark() {
    BlockPos point;
    try {
      point = readInspectorCoordinate();
    } catch (RuntimeException invalid) {
      inspectorInputError = Objects.requireNonNullElse(invalid.getMessage(), invalid.toString());
      cancelInspectorTask("Invalid Inspector coordinates; correct XYZ before bookmarking.", true);
      inspectorPanel.showCoordinates(true);
      return;
    }
    inspectorInputError = "";
    try {
      bookmarkPanel.openAdd(Tab.WORLD, new Coordinates(point.x(), point.y(), point.z()));
    } catch (RuntimeException failure) {
      inspectorInputError = Objects.requireNonNullElse(failure.getMessage(), failure.toString());
      state.status = inspectorInputError;
    }
  }

  boolean probeInspectorDraftNoticeVisible() {
    return inspectorDraftNoticeVisible;
  }

  TraceSnapshot.Freshness probeInspectorFreshness() {
    return traceSnapshot == null
        ? null
        : traceSnapshot.freshness(state.project().project.uuid, state.revision);
  }

  boolean probeInspectorFreshnessNoticeVisible() {
    return inspectorFreshnessNoticeVisible;
  }

  Coordinates probeWorldPicked() {
    return views[2].pickedBookmark;
  }

  TraceSnapshot probeInspectorTrace() {
    return traceSnapshot;
  }

  boolean probeInspectorOpen() {
    return inspector.get();
  }

  void probeHideInspector() {
    inspector.set(false);
  }

  ViewportFlight.Mode probeFlightMode(int kind) {
    return views[kind].flight.mode();
  }

  double[] probeCameraPose(int kind) {
    var c = views[kind].camera;
    var eye = c.eye();
    return new double[] {eye.x, eye.y, eye.z, c.yaw, c.pitch, c.distance};
  }

  boolean probeCameraFlying(int kind) {
    return views[kind].camera.flying;
  }

  String probeFlightInput() {
    return probeFlightInput;
  }

  double[] probeOrbitPose(int kind) {
    var c = views[kind].camera;
    return new double[] {c.focus.x, c.focus.y, c.focus.z, c.yaw, c.pitch, c.distance};
  }

  boolean probeOrbitContextVisible() {
    return probeOrbitContextVisible;
  }

  void probeAdmissionSource(UUID noise) {
    admissionProbe = true;
    views[0].noise = noise;
    mark(views[0]);
  }

  float[] probeAdmissionControl(String key) {
    return admissionControls.get(key);
  }

  boolean probeAdmissionReady() {
    var v = views[0];
    return v.completed && !v.dirty && !v.activeJob && !v.failedPreview;
  }

  boolean probeAdmissionBlocked() {
    return views[0].admission.blocked() && views[0].failedPreview;
  }

  boolean probeOtherAdmissionBlocked() {
    return views[1].admission.blocked() || views[2].admission.blocked();
  }

  NoiseImageView probeAcceptedImage() {
    return views[0].displayedImage;
  }

  NoiseImageNavigation probeImageNavigation() {
    return new NoiseImageNavigation(views[0].panX, views[0].panZ, views[0].step);
  }

  NoiseImageView.Sample probeImageHover() {
    return probeImageHover;
  }

  Coordinates probeImagePicked() {
    return views[0].pickedBookmark;
  }

  String probeAdmissionMessage() {
    return views[0].message;
  }

  UUID probeGeneratorSelection(int kind) {
    return views[kind].noise;
  }

  float[] probeGeneratorOption(UUID id) {
    return generatorPicker.optionCenter(id);
  }

  int probeGeneratorRows() {
    return generatorPicker.submitted();
  }

  void probeNoiseTab(int kind) {
    smokeTab = kind;
    if (kind == 1) {
      views[1].fixed = true;
      views[1].mode = "Volume";
      views[1].size = 16;
    }
  }

  void probeSlice(
      UUID noise, SlicePlane plane, int side, int slice, double u, double vertical, double step) {
    admissionProbe = true;
    smokeTab = 1;
    var v = views[1];
    v.noise = noise;
    v.mode = "Slices";
    v.fixed = false;
    v.slicePlane = plane;
    v.slice = slice;
    v.size = side;
    v.step = step;
    v.panX = plane == SlicePlane.X ? 0 : u;
    v.panY = plane == SlicePlane.X ? u : plane == SlicePlane.Z ? vertical : 0;
    v.panZ = plane == SlicePlane.Z ? 0 : vertical;
    v.sliceInput.reset();
    mark(v);
  }

  void probeSliceRequest(SlicePlane plane, int side, int slice) {
    var v = views[1];
    v.slicePlane = plane;
    v.size = side;
    v.slice = slice;
    mark(v);
  }

  void probeSliceTab() {
    smokeTab = 1;
  }

  SliceNavigation probeSliceNavigation() {
    return sliceNavigation(views[1]);
  }

  SliceImageView probeSliceView() {
    return views[1].displayedSlice;
  }

  SliceImageView.Sample probeSliceHover() {
    return probeSliceHover;
  }

  Coordinates probeSlicePicked() {
    return views[1].pickedBookmark;
  }

  double[] probeSliceRaw() {
    return views[1].raw.clone();
  }

  boolean probeNoiseReady(int kind) {
    var v = views[kind];
    return v.completed && !v.dirty && !v.activeJob && !v.failedPreview;
  }

  long probeSubmissionStamp(int kind) {
    return views[kind].lastSubmit;
  }

  float[] probeGeneratorNone() {
    return generatorPicker.noneCenter();
  }

  void probeStreaming3D() {
    smokeTab = 1;
    views[1].fixed = false;
    views[1].mode = "Threshold Voxels";
    views[1].threshold = .75;
    state.user.visualization.streaming = true;
    mark(views[1]);
  }

  void probeVolumeRaySteps(int steps) {
    views[1].volumeSteps = steps;
  }

  VolumeLight.Settings probeVolumeLight() {
    return views[1].gl.volumeLighting();
  }

  void probeVolume(UUID noise, int side, double center) {
    var v = views[1];
    smokeTab = 1;
    v.noise = noise;
    v.mode = "Volume";
    v.fixed = true;
    v.size = side;
    v.panX = v.panY = v.panZ = center;
    v.camera.flying = false;
    v.camera.focus.set(center, center, center);
    v.camera.yaw = v.camera.pitch = 0;
    v.camera.distance = 80;
    v.volumeMin = 0;
    v.volumeMax = 1;
    v.volumeOpacity = .1;
    v.threshold = 0;
    mark(v);
  }

  VolumeTextureUpload.Statistics probeVolumeStatistics() {
    return views[1].gl.volumeStatistics();
  }

  Vector3d probeVolumeOrigin() {
    return new Vector3d(views[1].origin);
  }

  String probeVolumeMessage() {
    return views[1].message;
  }

  void probeVolumeStream(UUID noise, int side, double x, double y, double z) {
    probeVolume(noise, side, x);
    var v = views[1];
    v.fixed = false;
    v.camera.focus.set(x, y, z);
    v.volumeOpacity = .01;
    state.user.visualization.streaming = true;
    mark(v);
  }

  void probeVolumeTravel(double x, double y, double z) {
    views[1].camera.focus.set(x, y, z);
  }

  VolumeWindows.Memory probeVolumeMemory() {
    return views[1].volumeWindows.memory();
  }

  VolumeWindows.Statistics probeVolumeWindow() {
    return views[1].displayedVolumeWindow;
  }

  void probeVolumeRegenerate() {
    var v = views[1];
    v.force = true;
    v.rebuildSerial++;
    mark(v);
  }

  void probeVolumePressure(long now, double cpu, double gpu, double frame, boolean rejected) {
    admissionProbe = true;
    views[1].volumeClock = now;
    views[1].pressure.observe(now, cpu, gpu, frame, rejected);
  }

  void probeVolumeClock(long now) {
    views[1].volumeClock = now;
  }

  int probeVolumePressureLevel() {
    return views[1].pressure.level();
  }

  VolumeStreamingLimits.Plan probeVolumePlan() {
    return views[1].volumePlan;
  }

  int probeVolumeConfiguredSize() {
    return views[1].size;
  }

  int probeVolumeResidentSize() {
    var resident = views[1].gl.volumeResident();
    return resident == null ? 0 : resident.extent();
  }

  int probeVolumeTextureSize() {
    var resident = views[1].gl.volumeResident();
    return resident == null ? 0 : resident.size();
  }

  int probeVolumeSampleStep() {
    var resident = views[1].gl.volumeResident();
    return resident == null ? 0 : resident.sampleStep();
  }

  long probeVolumeReservation() {
    return views[1].volumeWorkingBytes;
  }

  VolumeData.Encoding probeVolumeEncoding() {
    var resident = views[1].gl.volumeResident();
    return resident == null ? null : resident.encoding();
  }

  int probeVolumeResidentTexture() {
    var resident = views[1].gl.volumeResident();
    return resident == null ? 0 : resident.texture();
  }

  void probeVolumeTransfer(double opacity, double threshold) {
    views[1].volumeOpacity = opacity;
    views[1].threshold = threshold;
  }

  void probeSurface(UUID noise, boolean fixed, double x, double y, double z) {
    var v = views[1];
    smokeTab = 1;
    v.noise = noise;
    v.mode = "Isosurface";
    v.fixed = fixed;
    v.threshold = 0;
    v.size = 16;
    v.camera.flying = false;
    v.camera.focus.set(x, y, z);
    v.camera.yaw = 0;
    v.camera.pitch = 0;
    v.camera.distance = 80;
    v.panX = x;
    v.panY = y;
    v.panZ = z;
    state.user.visualization.streaming = true;
    mark(v);
  }

  Map<ChunkPos, Integer> probeSurfaceLevels() {
    var gpu = views[1].gl.sceneRenderer();
    return gpu == null ? Map.of() : gpu.drawnDetailLevels();
  }

  void probeSurfaceDistance(double distance) {
    views[1].camera.distance = distance;
  }

  long probeSurfaceEpoch() {
    return views[1].scene == null ? 0 : views[1].scene.epoch();
  }

  boolean probeSurfaceReady() {
    var v = views[1];
    return v.scene != null
        && v.scene.statistics().meshes() > 0
        && v.gl.sceneRenderer() != null
        && v.gl.sceneRenderer().hasData();
  }

  void probeSurfaceTravel(double x, double y, double z) {
    views[1].camera.focus.set(x, y, z);
  }

  boolean probeSurfaceChunk(int x, int y, int z) {
    var gpu = views[1].gl.sceneRenderer();
    return gpu != null && gpu.pickingVersions().containsKey(new ChunkPos(x, y, z));
  }

  java.util.List<Integer> probeSurfacePixel() {
    org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, views[1].gl.texture());
    int[] width = {0}, height = {0};
    org.lwjgl.opengl.GL11.glGetTexLevelParameteriv(
        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH, width);
    org.lwjgl.opengl.GL11.glGetTexLevelParameteriv(
        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT, height);
    var all = org.lwjgl.BufferUtils.createByteBuffer(width[0] * height[0] * 4);
    org.lwjgl.opengl.GL11.glGetTexImage(
        org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
        0,
        org.lwjgl.opengl.GL11.GL_RGBA,
        org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
        all);
    int at = ((height[0] / 2) * width[0] + width[0] / 2) * 4;
    return java.util.List.of(
        Byte.toUnsignedInt(all.get(at)),
        Byte.toUnsignedInt(all.get(at + 1)),
        Byte.toUnsignedInt(all.get(at + 2)),
        Byte.toUnsignedInt(all.get(at + 3)));
  }

  ChunkScene.Statistics probeSurfaceStatistics() {
    return views[1].scene.statistics();
  }

  boolean probeStream3DReady() {
    var v = views[1];
    return v.scene != null && v.scene.statistics().generated() > 0 && v.gl.sceneRenderer() != null;
  }

  long probeStream3DGenerated() {
    return views[1].scene.statistics().generated();
  }

  boolean probeStream3DReleased() {
    var v = views[1];
    return v.scene == null
        && v.compiler == null
        && !v.setup.pending()
        && v.gl.sceneRenderer() == null;
  }

  String probeSourceStatus(int kind) {
    return views[kind].message;
  }

  void probeFailNextStreamCompilation() {
    probeStreamCompileFailure.set(true);
  }

  long probeStream3DEpoch() {
    return views[1].scene == null ? -1 : views[1].scene.epoch();
  }

  boolean probeStreamSourceFailed() {
    return views[1].failedPreview && !views[1].streamFallback;
  }

  void probeFailNextRendererStartup() {
    probeRendererStartupFailure.set(true);
  }

  void probeFailNextRendererFrame() {
    probeRendererFrameFailure.set(true);
  }

  boolean probeFixedFallbackReady() {
    var v = views[1];
    return v.streamFallback
        && !usesStreaming(v)
        && probeNoiseReady(1)
        && v.scene == null
        && v.gl.sceneRenderer() == null;
  }

  void probeVisibleFallbackSource() {
    var v = views[1];
    v.threshold = .25;
    v.camera.distance = 60;
    mark(v);
  }

  int probe3DTexture() {
    return views[1].gl.texture();
  }

  private void admissionControl(String key) {
    if (admissionProbe) {
      admissionControls.put(
          key,
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          });
      if (key.equals("image"))
        admissionControls.put(
            "image-bounds",
            new float[] {
              ImGui.getItemRectMinX(),
              ImGui.getItemRectMinY(),
              ImGui.getItemRectMaxX() - ImGui.getItemRectMinX(),
              ImGui.getItemRectMaxY() - ImGui.getItemRectMinY()
            });
    }
  }

  private static final class View {
    final int kind;
    final GlViewport gl = new GlViewport();
    final Camera camera = new Camera();
    final ViewportFlight flight = new ViewportFlight();
    final OrbitGesture orbit = new OrbitGesture();
    final NoiseImageRows imageRows = new NoiseImageRows();
    final NoiseImageInput imageInput = new NoiseImageInput();
    final SliceInput sliceInput = new SliceInput();
    final ImageContextGesture imageContext = new ImageContextGesture();
    NoiseImageRows.Statistics imageStatistics;
    NoiseImageView displayedImage;
    VoxelRaycast.Bounds fixedPickBounds;
    UUID noise, stage, keyNoise;
    long keyRevision = -1, rebuildSerial = 0;
    String geometryKey = "",
        fingerprint = "",
        requestedKey = "",
        activeSceneKey = "",
        failedKey = "";
    ChunkScene scene;
    final RenderPressure pressure = new RenderPressure();
    Future<?> compiler;
    final OwnedPreviewMailbox<SceneSetup> setup = new OwnedPreviewMailbox<>();
    int gpuBudget;
    String reportedShadowFailure = "";
    boolean streamingObserved, streamFallback;
    double frameMillis = 16;

    String stageMode = "Final";
    String mode;
    double panX = 0, panZ = 0, panY = 16, step = 1, threshold = 0;
    int size = 32, slice = 0, volumeSteps = 192;
    SlicePlane slicePlane = SlicePlane.Y;
    SliceImageView displayedSlice;
    double volumeMin = -1, volumeMax = 1, volumeOpacity = .12;
    final Set<String> completedModes = new HashSet<>();
    boolean fixed = false, dirty = true, activeJob = false, force = false;
    long revision = -1, lastSubmit = 0;
    volatile long epoch = 0;
    Future<?> job;
    final PreviewMailbox<Preview> ready = new PreviewMailbox<>();
    final PreviewAdmission admission = new PreviewAdmission();
    Preview pendingVolume;
    final VolumeWindows volumeWindows = new VolumeWindows();
    ProjectData volumeSnapshot;
    UUID volumeNoise;
    String volumeSourceKey = "", volumeRequestTag = "";
    VolumeWindows.Statistics displayedVolumeWindow;
    long volumeWorkingBytes;
    long volumeClock; // Native fixture's monotonic clock; zero uses System.nanoTime().
    boolean volumeRejected;
    VolumeStreamingLimits.Limits volumeLimits;
    VolumeStreamingLimits.Plan volumePlan;
    String message = "Ready";
    Vector3d origin = new Vector3d();
    double[] raw;
    NoiseAlgorithms.Field hoverSampler;
    int[] colors;
    float[] histogram = new float[64];
    Coordinates pickedBookmark;
    boolean image = false, completed = false, failedPreview = false;

    View(int k) {
      kind = k;
      mode = k == 0 ? "Grayscale" : "Threshold Voxels";
    }
  }

  private record SceneSetup(
      long id, String key, ChunkScene.Source source, int[] palette, String error)
      implements AutoCloseable {
    public void close() {
      if (source != null) source.close();
    }
  }

  public record StreamingStatistics(
      long cpuBytes,
      long reserved,
      long gpuBytes,
      long uploadedBytes,
      int chunks,
      int jobs,
      int visible,
      int culled,
      int drawCalls,
      boolean indirect,
      long gpuCapacityBytes,
      long gpuFreeBytes,
      long largestFreeRange,
      long relocatedBytes,
      long compactionRemainingBytes,
      long compactions,
      int compactingArenas) {
    public StreamingStatistics(
        long cpuBytes,
        long reserved,
        long gpuBytes,
        long uploadedBytes,
        int chunks,
        int jobs,
        int visible,
        int culled,
        int drawCalls,
        boolean indirect) {
      this(
          cpuBytes,
          reserved,
          gpuBytes,
          uploadedBytes,
          chunks,
          jobs,
          visible,
          culled,
          drawCalls,
          indirect,
          0,
          0,
          0,
          0,
          0,
          0,
          0);
    }
  }

  /**
   * Called even when visualizer tabs are hidden, so capture stop/replacement reclaims old queries.
   */
  public void collectGpuTimings() {
    shadowPreferences();
    var scope = state.engine.profiler().scope(0, state.project().project.uuid, null);
    for (var view : views) view.gl.collectGpuTimings(scope);
  }

  private boolean shadowsEnabled(View v) {
    return state.user.visualization.shadows
        && !state.user.visualization.unlit
        && v.kind > 0
        && (v.kind == 2 || !Set.of("Volume", "Slices", "Point Cloud").contains(v.mode));
  }

  private int sceneGpuBudget(View v) {
    return DirectionalShadow.arenaBytes(
        state.user.performance.sceneGpuMiB << 20,
        shadowsEnabled(v),
        state.user.visualization.shadowResolution);
  }

  private void shadowPreferences() {
    for (var v : views)
      v.gl.shadowAppearance(
          shadowsEnabled(v),
          state.user.visualization.shadowResolution,
          (long) state.user.performance.sceneGpuMiB << 20);
  }

  public void retryShadows() {
    for (var v : views) {
      v.gl.retryShadows();
      v.reportedShadowFailure = "";
    }
  }

  int probeShadowArenaBytes(int kind) {
    var gpu = views[kind].gl.sceneRenderer();
    return gpu == null ? 0 : gpu.statistics().capacityBytes();
  }

  public List<GlViewport.ShadowStatistics> shadowStatistics() {
    return Arrays.stream(views).map(v -> v.gl.shadowStatistics()).toList();
  }

  public long shadowBytes() {
    return shadowStatistics().stream().mapToLong(GlViewport.ShadowStatistics::bytes).sum();
  }

  public List<GpuTimerQueries.Statistics> gpuTimingStatistics() {
    return Arrays.stream(views).map(v -> v.gl.gpuTimingStatistics()).toList();
  }

  public StreamingStatistics streamingStatistics() {
    long cpu = 0, reserved = 0, gpu = 0, uploads = 0;
    int chunks = 0, jobs = 0, visible = 0, culled = 0, draws = 0, compacting = 0;
    long capacity = 0, free = 0, largest = 0, moved = 0, remaining = 0, compactions = 0;
    boolean indirect = false;
    for (var v : views) {
      var volume = v.gl.volumeStatistics();
      cpu += volume.cpuBytes() + v.volumeWindows.memory().bytes();
      var memory = v.volumeWindows.memory();
      reserved +=
          Math.max(
              0,
              VolumeStreamingLimits.cpuBytes(
                      0, memory.workingBytes(), v.volumeWorkingBytes, volume.cpuBytes())
                  - volume.cpuBytes());
      gpu += volume.activeBytes() + volume.pendingBytes() + v.gl.shadowStatistics().bytes();
      uploads += volume.lastBytes();
      if (v.scene != null) {
        var s = v.scene.statistics();
        cpu += s.cpuBytes();
        reserved += s.reserved();
        chunks += s.resident();
        jobs += s.activeJobs();
      }
      if (v.gl.sceneRenderer() != null) {
        var g = v.gl.sceneRenderer().statistics();
        gpu += g.gpuBytes();
        uploads += g.uploadedBytes();
        visible += g.visible();
        culled += g.culled();
        draws += g.draws();
        indirect |= g.indirect();
        capacity += g.capacityBytes();
        free += g.freeBytes();
        largest = Math.max(largest, g.largestFree());
        moved += g.relocatedBytes();
        remaining += g.compactionRemainingBytes();
        compactions += g.compactions();
        if (g.compacting() || g.compactionRequested()) compacting++;
      }
    }
    return new StreamingStatistics(
        cpu,
        reserved,
        gpu,
        uploads,
        chunks,
        jobs,
        visible,
        culled,
        draws,
        indirect,
        capacity,
        free,
        largest,
        moved,
        remaining,
        compactions,
        compacting);
  }

  public boolean flying() {
    return Arrays.stream(views).anyMatch(v -> v.flight.captured());
  }

  public int compactArenas() {
    int requested = 0;
    for (var view : views) {
      var gpu = view.gl.sceneRenderer();
      if (gpu != null && gpu.requestCompaction()) requested++;
    }
    return requested;
  }

  private record Preview(
      long epoch,
      Mesh mesh,
      int[] image,
      double[] raw,
      int width,
      Vector3d origin,
      int[] palette,
      float[] histogram,
      NoiseAlgorithms.Field sampler,
      String error,
      VolumeData volume,
      NoiseImageRows.Statistics imageStatistics,
      NoiseImageView imageView,
      SliceImageView sliceView,
      VolumeWindows.Statistics volumeWindowStatistics,
      VolumeTextureUpload.Region volumeRegion) {
    Preview(
        long epoch,
        Mesh mesh,
        int[] image,
        double[] raw,
        int width,
        Vector3d origin,
        int[] palette,
        float[] histogram,
        NoiseAlgorithms.Field sampler,
        String error,
        VolumeData volume,
        NoiseImageRows.Statistics imageStatistics,
        NoiseImageView imageView,
        SliceImageView sliceView,
        VolumeWindows.Statistics volumeWindowStatistics) {
      this(
          epoch,
          mesh,
          image,
          raw,
          width,
          origin,
          palette,
          histogram,
          sampler,
          error,
          volume,
          imageStatistics,
          imageView,
          sliceView,
          volumeWindowStatistics,
          null);
    }

    Preview(
        long epoch,
        Mesh mesh,
        int[] image,
        double[] raw,
        int width,
        Vector3d origin,
        int[] palette,
        float[] histogram,
        NoiseAlgorithms.Field sampler,
        String error,
        VolumeData volume,
        NoiseImageRows.Statistics imageStatistics,
        NoiseImageView imageView,
        SliceImageView sliceView) {
      this(
          epoch,
          mesh,
          image,
          raw,
          width,
          origin,
          palette,
          histogram,
          sampler,
          error,
          volume,
          imageStatistics,
          imageView,
          sliceView,
          null);
    }

    Preview(
        long epoch,
        Mesh mesh,
        int[] image,
        double[] raw,
        int width,
        Vector3d origin,
        int[] palette,
        float[] histogram,
        NoiseAlgorithms.Field sampler,
        String error,
        VolumeData volume) {
      this(
          epoch, mesh, image, raw, width, origin, palette, histogram, sampler, error, volume, null,
          null, null);
    }

    Preview(
        long epoch,
        Mesh mesh,
        int[] image,
        double[] raw,
        int width,
        Vector3d origin,
        int[] palette,
        float[] histogram,
        NoiseAlgorithms.Field sampler,
        String error) {
      this(
          epoch, mesh, image, raw, width, origin, palette, histogram, sampler, error, null, null,
          null, null);
    }
  }

  public Viewports(EditorState state, PropertyPanel properties, long handle) {
    this.state = state;
    inspectorPanel =
        new InspectorPanel(
            state,
            inspectorCoordinates,
            this::inspectorInput,
            this::editInspectorCoordinate,
            this::admissionControl);
    generatorPicker = new ResourcePicker(state);
    stagePicker = new StageBoundaryPicker(state);
    commands =
        new ViewportCommands[] {
          new ViewportCommands(state), new ViewportCommands(state), new ViewportCommands(state)
        };
    this.properties = properties;
    platform = new ViewportPlatformInput(handle);
    flightCursor = new FlightCursor(platform, GLFW_CURSOR_DISABLED);
    bookmarkPanel =
        new BookmarkPanel(
            state,
            new BookmarkPanel.Navigation() {
              @Override
              public Coordinates current(Tab tab) {
                var c = center(views[tab.ordinal()]);
                return BookmarkTransactions.capture(tab, c.x, c.y, c.z);
              }

              @Override
              public Coordinates picked(Tab tab) {
                return views[tab.ordinal()].pickedBookmark;
              }

              @Override
              public void teleport(Tab tab, Coordinates coordinates) {
                Viewports.this.teleport(
                    views[tab.ordinal()],
                    coordinates.x(),
                    coordinates.y() == null ? 0 : coordinates.y(),
                    coordinates.z());
              }
            });
    for (var v : views) v.size = state.user.visualization.previewSize;
  }

  public void draw() {
    if (closed) return;
    shadowPreferences();
    for (var view : views) view.gl.advanceVolume(0);
    drawnView = -1;
    viewportFocused = false;
    flightImageVisible = false;
    sliceImageVisible = false;
    noiseImageVisible = false;
    probeImageHover = null;
    probeSliceHover = null;
    surfacePressed = false;
    probeOrbitContextVisible = false;
    inspectorHintVisible = inspectorTraceVisible = inspectorDraftNoticeVisible = false;
    inspectorFreshnessNoticeVisible = false;
    if (admissionProbe) admissionControls.remove("trace-refresh");
    reconcileInspectorTask();
    views[0].imageRows.budget(
        Math.min(
            (long) state.user.performance.cacheMiB << 20,
            Math.max(0, ((long) state.user.performance.sceneCpuMiB << 20) - 64L * 256 * 256)));
    windowLayout.prepareExisting();
    boolean visible = ImGui.begin("Visualizers");
    UiScale.window(state.user, "Visualizers");
    windowLayout.observe();
    DialogLayout.observe(state, "Visualizers");
    var nextWindow = platform.current();
    if (!Objects.equals(surfaceWindow, nextWindow)) {
      for (var view : views) {
        view.flight.pause();
        view.orbit.reset();
        view.sliceInput.reset();
        view.imageInput.reset();
        view.imageContext.reset();
      }
      flightCursor.sync(null);
      surfaceWindow = nextWindow;
    }
    viewportFocused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
    if (visible && ImGui.beginTabBar("view-tabs")) {
      for (int k = 0; k < 3; k++) {
        String name = k == 0 ? "2D Noise" : k == 1 ? "3D Noise" : "World";
        if (ImGui.beginTabItem(name, smokeTab == k ? ImGuiTabItemFlags.SetSelected : 0)) {
          active = k;
          drawnView = k;
          drawView(views[k]);
          ImGui.endTabItem();
        }
      }
      ImGui.endTabBar();
    }
    ImGui.end();
    for (var v : views) {
      if (v.kind != drawnView) v.flight.pause();
      if (v.kind != drawnView || !flightImageVisible) v.orbit.reset();
      if (v.kind != drawnView || !sliceImageVisible) v.sliceInput.reset();
      if (v.kind != drawnView || !noiseImageVisible) v.imageInput.reset();
      if (v.kind != drawnView || !(sliceImageVisible || noiseImageVisible)) v.imageContext.reset();
      v.flight.reconcile(flightEligible(v), inspector.get());
    }
    syncFlightCursor();
    observePickView();
    reconcileInspectorTask();
    drawInspector();
    for (var v : views) v.flight.reconcile(flightEligible(v), inspector.get());
    syncFlightCursor();
    bookmarkPanel.drawDialogs();
  }

  public void assertSmokeCompleted() {
    for (View v : views)
      if (!v.completed)
        throw new IllegalStateException(
            "Viewport " + v.kind + " did not complete a valid engine-backed preview: " + v.message);
    if (!views[1].completedModes.containsAll(Set.of("Volume", "Slices X", "Slices Y", "Slices Z")))
      throw new IllegalStateException(
          "Missing volume/slice smoke modes " + views[1].completedModes);
    if (!views[2].completedModes.containsAll(Set.of("Final", "Before", "After", "Only Changes")))
      throw new IllegalStateException("Missing stage modes " + views[2].completedModes);
    if (!views[2].streamingObserved
        || views[2].scene == null
        || views[2].scene.statistics().generated() < 2)
      throw new IllegalStateException("Incremental world streaming did not complete");
    System.out.println("CAMERA_DRIVEN_STREAMING_PASS " + streamingStatistics());
    properties.assertSmokeCurveRendered();
    properties.assertSmokeWorldControlsRendered();
    properties.assertSmokeExpressionRendered();
    System.out.println("EXACT_WORLD_STAGE_PREVIEWS_PASS");
    if (!smokeImageRecolor || !smokeImagePan || !smokeImageReturn || !smokeImageHorizontal)
      throw new IllegalStateException("2D image cache reuse was not observed");
    System.out.println(
        "EXACT_2D_ROW_CACHE_NATIVE_PASS recolor / one-row pan / return view / one-column pan");
    System.out.println("THREE_ENGINE_VIEWPORTS_PASS");
    System.out.println("RAYMARCHED_VOLUME_AND_XYZ_SLICES_PASS");
  }

  public void smokeSelections() {
    views[1].fixed = true;
    views[2].fixed = true;
    if (!state.project().noise2D.isEmpty())
      views[0].noise = state.project().noise2D.getFirst().uuid;
    if (!state.project().noise3D.isEmpty())
      views[1].noise = state.project().noise3D.getFirst().uuid;
  }

  /**
   * Smoke phases advance only after the actual stage preview completed, with timeout in Desktop.
   */
  public boolean smokePhaseReady(int frame) {
    if (frame == 39 || frame == 59 || frame == 79 || frame == 99 || frame == 119) {
      var v = views[0];
      if (!v.completed || v.failedPreview || v.activeJob || v.dirty || v.imageStatistics == null)
        return false;
      var stats = v.imageStatistics;
      if (frame == 59) smokeImageRecolor = stats.sampledRows() == 0 && stats.reusedRows() == 256;
      if (frame == 79) smokeImagePan = stats.sampledRows() == 1 && stats.reusedRows() == 255;
      if (frame == 99) smokeImageReturn = stats.sampledRows() == 0 && stats.reusedRows() == 256;
      if (frame == 119)
        smokeImageHorizontal = stats.sampledValues() == 256 && stats.reusedValues() == 256 * 255;
      return frame == 39
          || frame == 59 && smokeImageRecolor
          || frame == 79 && smokeImagePan
          || frame == 99 && smokeImageReturn
          || frame == 119 && smokeImageHorizontal;
    }
    String required =
        switch (frame) {
          case 274 -> "Only Changes";
          case 299 -> "Before";
          case 324 -> "After";
          case 359 -> "Final";
          default -> null;
        };
    return required == null || views[2].completedModes.contains(required);
  }

  private boolean smokeImageRecolor, smokeImagePan, smokeImageReturn, smokeImageHorizontal;

  public void smokePhase(int frame) {
    if (frame == 40) {
      views[0].mode = "Heatmap";
      mark(views[0]);
    }
    if (frame == 60) {
      views[0].panZ += views[0].step;
      mark(views[0]);
    }
    if (frame == 80) {
      views[0].panZ -= views[0].step;
      mark(views[0]);
    }
    if (frame == 100) {
      var image = views[0].displayedImage;
      if (image == null) throw new AssertionError("No accepted 2D image geometry");
      views[0].panX += views[0].step;
      mark(views[0]);
      var pointer = image.sample(.5, .5);
      if (pointer.x() != image.panX()
          || pointer.z() != image.panZ()
          || pointer.x() == views[0].panX
          || views[0].displayedImage != image)
        throw new AssertionError("Pending pan replaced displayed image geometry");
      System.out.println(
          "ACCEPTED_2D_IMAGE_GEOMETRY_PASS pending pan retains hover program and center");
    }
    if (frame == 335 || frame == 355)
      properties.smokeWorldControls(frame == 335 ? "Numeric parameters" : null);
    if (frame == 365 || frame == 385 || frame == 400 || frame == 415)
      properties.smokeWorldControls(
          frame == 365
              ? "Transforms"
              : frame == 385 ? "Types" : frame == 400 ? "Drivers and gates" : null);
    if (frame == 360) {
      views[2].fixed = false;
      mark(views[2]);
    }
    if (frame == 420) views[2].camera.focus.x += 64;
    if (frame == 445) views[2].camera.focus.z -= 64;
    if (frame == 30) properties.smokeCurve(true);
    if (frame == 90) properties.smokeCurve(false);
    if (frame == 95) properties.smokeExpression(true);
    if (frame == 115) properties.smokeExpression(false);
    if (frame == 240 || frame == 275 || frame == 300 || frame == 325) {
      views[2].stageMode =
          frame == 240
              ? "Only Changes"
              : frame == 275 ? "Before" : frame == 300 ? "After" : "Final";
      if (!state.project().worldGenerator.layers.isEmpty())
        views[2].stage = state.project().worldGenerator.layers.getFirst().operations.getLast().uuid;
      mark(views[2]);
    }
    if (frame == 0 && !state.project().noise2D.isEmpty())
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
    if (frame == 120) {
      views[1].mode = "Volume";
      mark(views[1]);
    }
    if (frame == 180 || frame == 200 || frame == 220) {
      views[1].mode = "Slices";
      views[1].slicePlane =
          frame == 180 ? SlicePlane.X : frame == 200 ? SlicePlane.Z : SlicePlane.Y;
      mark(views[1]);
    }
    if (frame == 120 && !state.project().noise3D.isEmpty())
      state.select("noise3D", state.project().noise3D.getFirst().uuid);
    if (frame == 240 && !state.project().worldGenerator.layers.isEmpty())
      state.select("layer", state.project().worldGenerator.layers.getFirst().uuid);
    if (frame < 120) smokeTab = 0;
    else if (frame < 240) smokeTab = 1;
    else smokeTab = 2;
  }

  private void drawView(View v) {
    String identity = fingerprint(v);
    if (!identity.equals(v.geometryKey)) {
      v.geometryKey = identity;
      mark(v);
    } else if (v.revision != state.revision && v.kind == 2)
      v.gl.setPalette(palette(state.project(), 2));
    v.revision = state.revision;
    if (v.kind < 2) {
      ImGui.pushID("viewport-generator-" + v.kind);
      generatorPicker.draw(
          "Generator",
          v.kind == 0 ? "noise2D" : "noise3D",
          v.noise,
          "No generator selected",
          id -> {
            if (!Objects.equals(v.noise, id)) {
              v.noise = id;
              v.imageInput.reset();
              v.sliceInput.reset();
              v.imageContext.reset();
              v.pickedBookmark = null;
              mark(v);
            }
          });
      if (admissionProbe) admissionControls.put("generator", generatorPicker.buttonCenter());
      ImGui.popID();
    }
    commands[v.kind].toolbar(
        () -> commandContext(v), (command, review) -> command(v, command, review));
    if (admissionProbe)
      admissionControls.put(
          "toolbar", commands[v.kind].toolbarCenter(ViewportCommands.Command.REGENERATE));
    ImGui.textWrapped(v.message);
    if (v.kind == 0) {
      fields.choice(
          "Display",
          v.mode,
          List.of("Grayscale", "Heatmap", "Contour", "Threshold", "Histogram"),
          m -> {
            v.mode = m;
            mark(v);
          });
    } else {
      if (v.kind == 1) {
        fields.choice(
            "Representation",
            v.mode,
            List.of("Threshold Voxels", "Isosurface", "Volume", "Point Cloud", "Slices"),
            m -> {
              v.mode = m;
              mark(v);
            });
        fields.number(
            "Threshold",
            v.threshold,
            x -> {
              if (Double.isFinite(x)) {
                v.threshold = x;
                if (!v.mode.equals("Volume")) mark(v);
              }
            });
        if (v.mode.equals("Isosurface") && wantsStreaming(v))
          ImGui.textDisabled(
              "Camera-relative surface LOD; unit-grid chunk faces stay connected across detail"
                  + " changes. Fixed analysis keeps the full sampled grid.");
        if (v.mode.equals("Volume")) {
          fields.number(
              "Display minimum",
              v.volumeMin,
              x -> {
                if (Double.isFinite(x) && x < v.volumeMax) v.volumeMin = x;
              });
          fields.number(
              "Display maximum",
              v.volumeMax,
              x -> {
                if (Double.isFinite(x) && x > v.volumeMin) v.volumeMax = x;
              });
          fields.number(
              "Opacity per block",
              v.volumeOpacity,
              x -> {
                if (Double.isFinite(x)) v.volumeOpacity = Math.max(0, Math.min(10, x));
              });
          fields.integer(
              "Ray steps", v.volumeSteps, x -> v.volumeSteps = Math.max(16, Math.min(512, x)));
          ImGui.textDisabled(
              "Ray-marched scalar texture; display controls do not alter generation.");
          if (state.user.visualization.volumeShadows)
            ImGui.textDisabled(
                state.user.visualization.unlit
                    ? "Volume light attenuation bypassed by Unlit"
                    : "Volume self-shadowing: "
                        + state.user.visualization.volumeShadowSteps
                        + " samples per light ray");
          if (wantsStreaming(v))
            ImGui.textDisabled(
                "Camera-relative window in whole 32-cell chunks; Fixed bounds keeps the exact"
                    + " analysis extent.");
        }
        if (v.mode.equals("Slices")) {
          fields.choice(
              "Slice normal",
              v.slicePlane.name(),
              List.of("X", "Y", "Z"),
              x -> {
                v.slicePlane = SlicePlane.valueOf(x);
                mark(v);
              });
          fields.integer(
              "Slice " + v.slicePlane.name(),
              v.slice,
              x -> {
                v.slice = x;
                mark(v);
              });
          ImGui.textDisabled("Image axes " + v.slicePlane.axes());
          if (v.displayedSlice != null)
            ImGui.textDisabled(
                String.format(
                    Locale.ROOT,
                    "Displayed: %s slice %d, extent %d, spacing %.5g",
                    v.displayedSlice.plane(),
                    v.displayedSlice.slice(),
                    v.displayedSlice.side(),
                    v.displayedSlice.step()));
        }
      }
      fields.integer(
          "Preview extent",
          v.size,
          x -> {
            v.size = Math.max(2, Math.min(state.user.performance.maxPreviewDimension, x));
            mark(v);
          });
      fields.bool(
          "Fixed world bounds",
          v.fixed,
          x -> {
            v.fixed = x;
            mark(v);
          });
      if (v.fixed) {
        fields.number(
            "Center Y",
            v.panY,
            x -> {
              v.panY = x;
              mark(v);
            });
        fields.number(
            "Center X",
            v.panX,
            x -> {
              v.panX = x;
              mark(v);
            });
        fields.number(
            "Center Z",
            v.panZ,
            x -> {
              v.panZ = x;
              mark(v);
            });
      }
      ImGui.textDisabled(
          v.flight.captured()
              ? "Free-fly: WASD / Space / Shift; Esc to release"
              : v.camera.flying
                  ? "Free-fly paused: Ctrl+click viewport to resume; Esc in viewport exits"
                  : "Orbit: drag left; pan right; wheel zoom; Ctrl+click free-fly");
    }
    bookmarkPanel.draw(Tab.values()[v.kind]);
    if (v.kind == 2) {
      fields.choice(
          "Stage preview",
          v.stageMode,
          List.of("Final", "Before", "After", "Only Changes"),
          mode -> {
            v.stageMode = mode;
            mark(v);
          });
      if (!v.stageMode.equals("Final")) {
        UUID initial = stagePicker.initial(v.stage, state.selected);
        if (!Objects.equals(v.stage, initial)) {
          v.stage = initial;
          mark(v);
        }
      }
      stagePicker.draw(
          v.stageMode,
          v.stage,
          id -> {
            if (!Objects.equals(v.stage, id)) {
              v.stage = id;
              mark(v);
            }
          });
      if (v.stageMode.equals("Only Changes"))
        ImGui.textDisabled("SET: voxel palette | CLEAR: red removal ghosts | unchanged: hidden");
      if (ImGui.button("Inspect Coordinate")) inspector.set(true);
      admissionControl("inspect-coordinate");
      ImGui.sameLine();
      ImGui.textDisabled("Read-only procedural world");
    }
    if (v.kind == 2 && !stagePicker.available(v.stageMode, v.stage)) {
      stopStream(v);
      v.orbit.reset();
      v.imageContext.reset();
      v.pickedBookmark = null;
      v.message = "Stage boundary unavailable; choose a boundary or Final";
      return;
    }
    boolean missingSource =
        v.kind < 2
            && v.noise != null
            && !generatorPicker.contains(v.kind == 0 ? "noise2D" : "noise3D", v.noise);
    boolean source = v.kind == 2 || v.noise != null && !missingSource;
    boolean streaming = source && usesStreaming(v);
    boolean volumeStreaming = streaming && v.kind == 1 && v.mode.equals("Volume");
    if (volumeStreaming) {
      stopStream(v);
      streamVolume(v);
      var plan = v.volumePlan;
      if (plan != null)
        ImGui.textDisabled(
            "Target volume "
                + plan.side()
                + " / configured "
                + v.size
                + " world units per axis | "
                + plan.textureSide()
                + " texels per axis, spacing "
                + plan.sampleStep()
                + " | "
                + volumePressureMessage(v));
      var resident = v.gl.volumeResident();
      if (resident != null
          && plan != null
          && (resident.extent() != plan.side() || resident.sampleStep() != plan.sampleStep()))
        ImGui.textDisabled(
            "Accepted display: "
                + resident.extent()
                + " world units, "
                + resident.size()
                + " texels per axis, spacing "
                + resident.sampleStep());
    } else {
      stopVolumeStream(v);
      if (streaming) streaming = stream(v);
      else stopStream(v);
    }
    Preview ready = v.ready.take();
    if (ready != null && ready.epoch == v.epoch && (!streaming || volumeStreaming) && source) {
      v.activeJob = false;
      v.volumeWorkingBytes = 0;
      if (ready.error != null) {
        v.message = "Failed; previous display retained";
        v.failedPreview = true;
        state.error("PREVIEW_FAILED", EditorEvents.Subsystem.RENDERING, ready.error);
      } else {
        if (ready.volume != null) {
          try {
            if (!v.gl.stageVolume(
                ready.volume,
                (long) state.user.performance.sceneGpuMiB << 20,
                ready.volumeRegion)) {
              if (volumeStreaming) v.volumeRejected = true;
              volumeUploadFailed(
                  v,
                  "Volume replacement exceeds GPU MiB; previous display retained; reduce extent or"
                      + " raise GPU MiB, then Regenerate");
            } else {
              v.pendingVolume = ready;
              v.activeJob = true;
            }
          } catch (RuntimeException failure) {
            volumeUploadFailed(v, failure.getMessage());
          }
        } else acceptPreview(v, ready);
      }
    }
    if (v.pendingVolume != null) {
      try {
        var stats = v.gl.volumeStatistics();
        if (stats.activeBytes() + stats.pendingBytes()
            > (long) state.user.performance.sceneGpuMiB << 20) {
          if (volumeStreaming) v.volumeRejected = true;
          volumeUploadFailed(
              v,
              "Volume replacement exceeds GPU MiB; previous display retained; raise GPU MiB and"
                  + " Regenerate");
        } else if (stats.cpuBytes() + v.volumeWindows.memory().bytes()
            > (long) state.user.performance.sceneCpuMiB << 20) {
          if (volumeStreaming) v.volumeRejected = true;
          volumeUploadFailed(v, "Volume upload exceeds CPU MiB; raise CPU MiB and Regenerate");
        } else {
          var profile = state.engine.profiler().scope(0, state.project().project.uuid, null);
          long stamp = profile == null ? 0 : profile.begin();
          var outcome = Profiler.Outcome.FAILED;
          try {
            v.gl.advanceVolume(state.user.performance.uploadKiB * 1024);
            outcome = Profiler.Outcome.SUCCESS;
          } finally {
            if (stamp != 0) {
              profile.finish(
                  stamp, Profiler.Kind.UPLOAD_CPU, v.noise, "Volume texture upload", outcome);
              profile.gauge(
                  Profiler.Kind.UPLOAD_BYTES,
                  Profiler.Unit.BYTES,
                  v.gl.volumeStatistics().lastHostBytes(),
                  "Volume host upload bytes per frame");
              profile.gauge(
                  Profiler.Kind.UPLOAD_BYTES,
                  Profiler.Unit.BYTES,
                  v.gl.volumeStatistics().lastCopiedBytes(),
                  "Volume resident copy bytes per frame");
            }
          }
          if (!v.gl.volumeStatistics().pending()) {
            var complete = v.pendingVolume;
            v.pendingVolume = null;
            v.activeJob = false;
            acceptPreview(v, complete);
          } else
            v.message =
                "Uploading volume: "
                    + v.gl.volumeStatistics().remainingBytes()
                    + " bytes remaining; previous display retained";
        }
      } catch (RuntimeException failure) {
        volumeUploadFailed(v, failure.getMessage());
      }
    }
    if (source
        && !streaming
        && (!v.failedPreview || v.force)
        && v.dirty
        && !v.activeJob
        && !v.admission.blocked()
        && (state.user.editor.autoRegenerate || v.force || v.lastSubmit == 0)
        && System.nanoTime() - v.lastSubmit
            > 1_000_000_000L / state.user.editor.opportunitiesPerSecond) submit(v);
    if (!source) {
      v.pendingVolume = null;
      v.gl.releaseVolume();
      v.gl.releaseScene();
      v.activeSceneKey = "";
      v.message =
          missingSource
              ? "Selected generator is missing; choose another or restore it"
              : "No generator selected";
      v.imageInput.reset();
      v.sliceInput.reset();
      v.imageContext.reset();
      v.pickedBookmark = null;
      ImGui.spacing();
      ImGui.textWrapped(
          missingSource
              ? "The selected generator no longer exists. Choose another generator or undo its"
                  + " deletion."
              : "Create a "
                  + (v.kind == 0 ? "2D" : "3D")
                  + " noise generator in Resources, then select it above. The project starts"
                  + " empty.");
      return;
    }
    float w = Math.max(16, ImGui.getContentRegionAvailX()),
        h = Math.max(16, ImGui.getContentRegionAvailY() - 28);
    if (v.kind == 0 && v.mode.equals("Histogram") && v.raw != null) {
      ImGui.plotHistogram(
          "Raw value distribution",
          v.histogram,
          v.histogram.length,
          0,
          "Display bins [-1,1]; raw values are unchanged",
          0,
          Float.MAX_VALUE,
          w,
          100);
      h = Math.max(16, h - 110);
    }
    if (v.kind == 1 && v.mode.equals("Volume")) {
      v.gl.volumeAppearance(v.volumeMin, v.volumeMax, v.volumeOpacity, v.threshold, v.volumeSteps);
      v.gl.volumeLighting(
          state.user.visualization.volumeShadows, state.user.visualization.volumeShadowSteps);
    }
    if (!v.image)
      v.gl.render(
          (int) w,
          (int) h,
          v.camera.matrix(w / h, v.origin),
          state.user.visualization.unlit,
          1,
          state.engine.profiler().scope(0, state.project().project.uuid, null),
          v.kind == 2 ? "World viewport GPU commands" : "3D Noise viewport GPU commands");
    var shadow = v.gl.shadowStatistics();
    if (shadow.state().equals("FAILED") && !shadow.detail().equals(v.reportedShadowFailure)) {
      v.reportedShadowFailure = shadow.detail();
      state.error("SHADOW_RENDER_FAILED", EditorEvents.Subsystem.RENDERING, shadow.detail());
    } else if (!shadow.state().equals("FAILED")) v.reportedShadowFailure = "";
    float px = ImGui.getCursorScreenPosX(), py = ImGui.getCursorScreenPosY();
    float cursorX = ImGui.getCursorPosX(), cursorY = ImGui.getCursorPosY();
    ImGui.image(v.gl.texture(), w, h, 0, v.image ? 0 : 1, 1, v.image ? 1 : 0);
    flightImageVisible = v.kind > 0 && !(v.kind == 1 && v.mode.equals("Slices"));
    sliceImageVisible = v.kind == 1 && v.mode.equals("Slices");
    noiseImageVisible = v.kind == 0;
    if (flightImageVisible || sliceImageVisible || noiseImageVisible) {
      // Own image presses so a camera drag cannot also move the containing ImGui window.
      ImGui.setCursorPos(cursorX, cursorY);
      ImGui.invisibleButton(
          "orbit-surface-" + v.kind,
          w,
          h,
          ImGuiButtonFlags.MouseButtonLeft
              | ImGuiButtonFlags.MouseButtonRight
              | ImGuiButtonFlags.MouseButtonMiddle);
    }
    admissionControl("image");
    boolean hovered = ImGui.isItemHovered();
    if (v.kind == 0 && state.user.visualization.grid) {
      var draw = ImGui.getWindowDrawList();
      draw.pushClipRect(px, py, px + w, py + h, true);
      for (double fraction :
          NoiseImageGrid.lines(
              v.displayedImage == null ? v.panX : v.displayedImage.panX(),
              v.displayedImage == null ? v.step : v.displayedImage.step(),
              256)) {
        float sx = (float) (px + fraction * w);
        draw.addLine(sx, py, sx, py + h, 0x33434d5e);
      }
      for (double fraction :
          NoiseImageGrid.lines(
              v.displayedImage == null ? v.panZ : v.displayedImage.panZ(),
              v.displayedImage == null ? v.step : v.displayedImage.step(),
              256)) {
        float sz = (float) (py + fraction * h);
        draw.addLine(px, sz, px + w, sz, 0x33434d5e);
      }
      draw.popClipRect();
    }

    var io = ImGui.getIO();
    boolean enteredFlight = false;
    surfacePressed =
        (flightImageVisible || sliceImageVisible || noiseImageVisible)
            && hovered
            && (ImGui.isMouseClicked(0) || ImGui.isMouseClicked(1) || ImGui.isMouseClicked(2));
    if (surfacePressed && surfaceNativeFocused() && !ShortcutKeys.popup()) {
      // A surface press explicitly transfers focus; ImGui otherwise does this at frame end.
      ImGui.setWindowFocus("Visualizers");
      viewportFocused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
    }
    v.flight.reconcile(flightEligible(v), inspector.get());
    if (admissionProbe)
      probeFlightInput =
          "kind="
              + v.kind
              + " focused="
              + viewportFocused
              + " native="
              + surfaceNativeFocused()
              + " hovered="
              + hovered
              + " text="
              + io.getWantTextInput()
              + " popup="
              + ShortcutKeys.popup()
              + " eligible="
              + flightEligible(v)
              + " ctrl="
              + io.getKeyCtrl()
              + " clicked="
              + ImGui.isMouseClicked(0)
              + " mode="
              + v.flight.mode();
    OrbitGesture.Click orbitClick = orbitInput(v, hovered);
    boolean imageContext = imageContextInput(v, hovered);
    if (orbitClick == OrbitGesture.Click.CONTEXT || imageContext) {
      rememberPicked(v, px, py, w, h);
      commands[v.kind].openContext(commandContext(v));
    }
    probeOrbitContextVisible =
        commands[v.kind].context(
            () -> commandContext(v), (command, review) -> command(v, command, review));
    if (admissionProbe && probeOrbitContextVisible) {
      var at = commands[v.kind].menuCenter(ViewportCommands.Command.REGENERATE);
      if (at != null) admissionControls.put("context", at);
    }
    if (v.kind == 0) {
      try {
        var before = new NoiseImageNavigation(v.panX, v.panZ, v.step);
        var after = before;
        if (surfaceEligible(v)) after = v.imageInput.navigate(before, hovered, px, py, w, h);
        else v.imageInput.reset();
        if (!after.equals(before)) {
          v.panX = after.x();
          v.panZ = after.z();
          v.step = after.step();
          mark(v);
        }
        if (v.imageInput.picked()) rememberPicked(v, px, py, w, h);
      } catch (IllegalArgumentException failure) {
        state.status = "2D navigation exceeds finite coordinate range";
      }
    }
    if (sliceImageVisible) {
      try {
        var before = sliceNavigation(v);
        var result = v.sliceInput.navigate(before, surfaceEligible(v), hovered, px, py, w, h);
        if (!before.equals(result.navigation())) {
          applySliceNavigation(v, before, result.navigation());
          mark(v);
        }
        if (result.picked()) rememberPicked(v, px, py, w, h);
      } catch (IllegalArgumentException invalid) {
        state.status = "Slice navigation exceeds finite coordinate range";
      }
    }
    if (hovered) {
      if (v.kind == 0) {
        String sample = "Waiting for a displayed preview";
        if (v.displayedImage != null) {
          try {
            var observation =
                v.displayedImage.sample(
                    (io.getMousePosX() - px) / (double) w, (io.getMousePosY() - py) / (double) h);
            probeImageHover = observation;
            sample =
                String.format(
                    Locale.ROOT,
                    "X %.5f  Z %.5f   Raw %.8f   Display %.5f",
                    observation.x(),
                    observation.z(),
                    observation.raw(),
                    observation.display());
          } catch (RuntimeException failure) {
            sample = "Sample unavailable at pointer";
          }
        }
        ImGui.textDisabled(sample);
      } else if (v.kind == 1 && v.mode.equals("Slices")) {
        if (v.displayedSlice != null && v.image) {
          try {
            var sample =
                v.displayedSlice.sample(
                    (io.getMousePosX() - px) / (double) w, (io.getMousePosY() - py) / (double) h);
            probeSliceHover = sample;
            var point = sample.point();
            ImGui.textDisabled(
                String.format(
                    Locale.ROOT,
                    "X %.5f Y %.5f Z %.5f  Raw %.8f",
                    point.x(),
                    point.y(),
                    point.z(),
                    sample.raw()));
          } catch (RuntimeException failure) {
            ImGui.textDisabled("Sample failed: " + failure.getMessage());
          }
        }
      } else {
        if (!v.flight.captured()
            && ImGui.isMouseClicked(0)
            && io.getKeyCtrl()
            && flightEligible(v)) {
          for (var other : views) if (other != v) other.flight.pause();
          if (!v.camera.flying) v.camera.enterFly();
          v.flight.enter();
          syncFlightCursor();
          enteredFlight = true;
        }
      }
    }
    if (orbitClick == OrbitGesture.Click.PICK && v.kind == 2)
      pick(v, (io.getMousePosX() - px) / w * 2 - 1, 1 - (io.getMousePosY() - py) / h * 2, w / h);
    if (v.camera.flying && !enteredFlight) {
      if (ImGui.isKeyPressed(ImGuiKey.Escape, false)
          && (v.flight.captured() || flightEligible(v))) {
        release(v);
      } else if (v.flight.captured()) {
        if (v.kind == 2 && ImGui.isMouseClicked(0)) {
          v.flight.inspect();
          inspector.set(true);
          syncFlightCursor();
          pick(v, 0, 0, w / h);
        } else {
          v.camera.orbit(io.getMouseDeltaX(), io.getMouseDeltaY());
          v.camera.move(
              (ImGui.isKeyDown(ImGuiKey.W) ? 1 : 0) - (ImGui.isKeyDown(ImGuiKey.S) ? 1 : 0),
              (ImGui.isKeyDown(ImGuiKey.D) ? 1 : 0) - (ImGui.isKeyDown(ImGuiKey.A) ? 1 : 0),
              (ImGui.isKeyDown(ImGuiKey.Space) ? 1 : 0) - (io.getKeyShift() ? 1 : 0),
              io.getDeltaTime());
        }
      }
    }
    if (v.kind != 0 && !v.fixed && !v.mode.equals("Slices")) {
      Vector3d center = v.camera.flying ? v.camera.position : v.camera.focus;
      double cx = Math.floor(center.x / 16) * 16, cz = Math.floor(center.z / 16) * 16;
      double cy = Math.floor(center.y / 16) * 16;
      if (cx != v.panX || cz != v.panZ || cy != v.panY) {
        v.panY = cy;
        v.panX = cx;
        v.panZ = cz;
        if (!usesStreaming(v)) mark(v);
      }
    }
  }

  private ViewportActionContext commandContext(View v) {
    return ViewportActionContext.capture(
        state,
        v,
        surfaceWindow,
        v.kind,
        v.epoch,
        v.noise,
        v.stage,
        v.mode,
        v.stageMode,
        bookmarkPanel.interactionOwner(),
        v.pickedBookmark);
  }

  private void command(View v, ViewportCommands.Command command, ViewportActionContext review) {
    switch (command) {
      case REGENERATE -> {
        v.force = true;
        v.rebuildSerial++;
        mark(v);
      }
      case AUTO ->
          state.user.applyAutoRegenerate(
              !review.auto(), UserConfig.directory().resolve("user-config.json"));
      case GRID -> state.user.visualization.grid = !review.grid();
      case TELEPORT -> bookmarkPanel.openTeleport(Tab.values()[v.kind]);
      case ADD_BOOKMARK -> bookmarkPanel.openAdd(Tab.values()[v.kind], review.picked());
      default -> {
        v.mode = command.label;
        mark(v);
      }
    }
  }

  private String fingerprint(View v) {
    if (v.keyRevision != state.revision || !Objects.equals(v.keyNoise, v.noise)) {
      v.fingerprint = PreviewIdentity.of(state.project(), v.kind, v.noise);
      v.keyRevision = state.revision;
      v.keyNoise = v.noise;
    }
    return v.fingerprint;
  }

  private void volumeUploadFailed(View v, String detail) {
    v.gl.cancelVolume();
    v.pendingVolume = null;
    v.volumeWorkingBytes = 0;
    v.activeJob = false;
    v.failedPreview = true;
    v.message =
        "Volume upload failed: " + detail + "; previous display retained; Regenerate to retry";
    state.error("VOLUME_UPLOAD_FAILED", EditorEvents.Subsystem.RENDERING, detail);
  }

  private void acceptPreview(View v, Preview ready) {
    if (ready.image != null) {
      v.gl.setImage(ready.width, ready.width, ready.image);
      v.image = true;
    } else {
      if (ready.volume == null) v.gl.setMesh(ready.mesh);
      v.image = false;
    }
    if (v.kind == 1) v.displayedSlice = ready.sliceView;
    v.completedModes.add(
        v.kind == 2
            ? v.stageMode
            : v.mode + (v.mode.equals("Slices") ? " " + v.slicePlane.name() : ""));
    v.displayedVolumeWindow = ready.volumeWindowStatistics;
    v.volumeWorkingBytes = 0;
    v.origin.set(ready.origin);
    v.fixedPickBounds = v.kind == 2 ? VoxelRaycast.Bounds.cube(ready.origin, ready.width) : null;
    v.gl.setPalette(ready.palette);
    v.raw = ready.raw;
    if (v.kind == 0) {
      v.imageStatistics = ready.imageStatistics;
      v.displayedImage = ready.imageView;
    }
    v.hoverSampler = ready.sampler;
    v.histogram = ready.histogram;
    v.message = v.streamFallback ? "Ready (fixed fallback; Regenerate retries streaming)" : "Ready";
    if (ready.volumeWindowStatistics != null
        && !v.volumeSourceKey.equals(fingerprint(v) + "|" + v.noise + "|" + v.rebuildSerial))
      v.message = "Ready (manual source retained; Regenerate to update)";
    v.completed = true;
  }

  private void stopVolumeStream(View v) {
    if (v.volumeSnapshot == null) return;
    mark(v);
    v.volumeWindows.clear();
    v.volumeSnapshot = null;
    v.volumeNoise = null;
    v.volumeSourceKey = "";
    v.volumeRejected = false;
    v.volumeLimits = null;
    v.volumePlan = null;
  }

  private String volumePressureMessage(View v) {
    return switch (v.pressure.level()) {
      case 0 -> v.pressure.paused() ? "Volume paused; Resume required" : "Full configured quality";
      case 1 -> "Pressure: coarser display volume samples (2 world units)";
      case 2 -> "Pressure: coarser samples and effective volume extent reduced";
      case 3 -> "Pressure: coarser samples, reduced extent; chunk cache released";
      case 4 -> "Pressure: new volume windows deferred; previous display retained";
      default -> "Volume paused under critical pressure; Resume required";
    };
  }

  private void streamVolume(View v) {
    long now = v.volumeClock == 0 ? System.nanoTime() : v.volumeClock;
    if (admissionProbe) admissionControls.remove("volume-resume");
    var performance = state.user.performance;
    var limits =
        new VolumeStreamingLimits.Limits(
            (long) performance.sceneCpuMiB << 20,
            (long) performance.sceneGpuMiB << 20,
            (long) performance.cacheMiB << 20,
            performance.previewCellBudget,
            performance.maxPreviewDimension);
    if (!limits.equals(v.volumeLimits)) {
      if (v.failedPreview) mark(v);
      v.volumeLimits = limits;
      v.volumeRejected = false;
    }
    var gpu = v.gl.volumeStatistics();
    // Reclaim evictable history before measuring a newly tightened CPU budget.
    v.volumeWindows.budget(
        VolumeStreamingLimits.plan(v.size, v.pressure.level(), limits, gpu.activeBytes())
            .cacheBytes());
    var memory = v.volumeWindows.memory();
    v.frameMillis = v.frameMillis * .9 + ImGui.getIO().getDeltaTime() * 1000 * .1;
    v.pressure.observe(
        now,
        VolumeStreamingLimits.cpuBytes(
                memory.bytes(), memory.workingBytes(), v.volumeWorkingBytes, gpu.cpuBytes())
            / (double) limits.cpuBytes(),
        (gpu.activeBytes() + gpu.pendingBytes()) / (double) limits.gpuBytes(),
        v.frameMillis,
        v.volumeRejected);
    var plan = VolumeStreamingLimits.plan(v.size, v.pressure.level(), limits, gpu.activeBytes());
    if (v.failedPreview
        && v.volumeRejected
        && v.volumePlan != null
        && (plan.side() != v.volumePlan.side()
            || plan.sampleStep() != v.volumePlan.sampleStep()
            || plan.cacheBytes() != v.volumePlan.cacheBytes())) mark(v);
    v.volumePlan = plan;
    v.volumeWindows.budget(plan.cacheBytes());
    if (v.pressure.paused()) {
      if (v.activeJob || v.pendingVolume != null) mark(v);
      v.message = volumePressureMessage(v) + "; previous display retained";
      if (ImGui.smallButton("Resume Volume")) {
        v.pressure.resume(now);
        v.volumeRejected = false;
        mark(v);
      }
      admissionControl("volume-resume");
      return;
    }
    if (!v.pressure.acceptExpensive()) {
      if (!v.activeJob) v.message = volumePressureMessage(v);
      return;
    }
    String desired = fingerprint(v) + "|" + v.noise + "|" + v.rebuildSerial;
    if (!desired.equals(v.volumeSourceKey)
        && (v.volumeSnapshot == null || state.user.editor.autoRegenerate || v.force)) {
      v.volumeSnapshot = ProjectIO.copy(state.project());
      v.volumeNoise = v.noise;
      v.volumeSourceKey = desired;
      v.volumeRequestTag = "";
    }
    var center = v.camera.flying ? v.camera.position : v.camera.focus;
    VolumeWindows.Bounds bounds;
    try {
      bounds = VolumeWindows.Bounds.around(center.x, center.y, center.z, plan.side());
    } catch (IllegalArgumentException invalid) {
      if (!v.failedPreview) {
        mark(v);
        v.failedPreview = true;
        v.dirty = false;
        v.message = invalid.getMessage() + "; previous display retained";
      }
      return;
    }
    String tag = v.volumeSourceKey + "|" + bounds + "|spacing=" + plan.sampleStep();
    if (!tag.equals(v.volumeRequestTag)) {
      mark(v);
      v.volumeRequestTag = tag;
    }
    if ((v.dirty || v.activeJob) && !v.failedPreview && !plan.allowed()) {
      mark(v);
      v.volumeRequestTag = tag;
      v.dirty = false;
      v.volumeRejected = true;
      volumeUploadFailed(v, plan.rejection() + "; reduce extent or raise the configured limit");
      return;
    }
    if (!v.dirty || v.activeJob || v.failedPreview) return;
    if (now - v.lastSubmit <= 1_000_000_000L / state.user.editor.opportunitiesPerSecond) return;
    long cacheLimit = plan.cacheBytes();
    long epoch = v.epoch;
    var lease = v.volumeWindows.begin(v.volumeSourceKey, epoch, cacheLimit);
    var token = v.ready.token(epoch);
    var snapshot = v.volumeSnapshot;
    var sourceKey = v.volumeSourceKey;
    var noise = v.volumeNoise;
    v.force = false;
    v.dirty = false;
    v.activeJob = true;
    v.lastSubmit = now;
    v.message = "Sampling camera-relative volume...";
    v.volumeRejected = false;
    v.volumeWorkingBytes = plan.workingBytes();
    try {
      v.job =
          state.background.submit(
              () -> {
                var profile = state.engine.profiler().scope(0, snapshot.project.uuid, null);
                long stamp = profile == null ? 0 : profile.begin();
                var outcome = Profiler.Outcome.CANCELLED;
                try {
                  var window =
                      v.volumeWindows.sample(
                          lease,
                          bounds,
                          plan.sampleStep(),
                          () -> new NoiseCompiler(snapshot, snapshot.project.seed).compile(noise),
                          token);
                  token.throwIfCancelled();
                  publish(
                      v,
                      new Preview(
                          epoch,
                          null,
                          null,
                          null,
                          bounds.side(),
                          new Vector3d(bounds.x(), bounds.y(), bounds.z()),
                          palette(snapshot, 1),
                          new float[64],
                          window.field(),
                          null,
                          window.volume(),
                          null,
                          null,
                          null,
                          window.statistics(),
                          new VolumeTextureUpload.Region(
                              sourceKey, bounds.x(), bounds.y(), bounds.z(), plan.sampleStep())));
                  outcome = Profiler.Outcome.SUCCESS;
                } catch (CancellationException stopped) {
                  // An obsolete complete tile may be reused, but no obsolete window publishes.
                } catch (Throwable failure) {
                  outcome = Profiler.Outcome.FAILED;
                  publish(
                      v,
                      new Preview(
                          epoch,
                          null,
                          null,
                          null,
                          0,
                          new Vector3d(),
                          new int[256],
                          new float[64],
                          null,
                          String.valueOf(failure.getMessage())));
                } finally {
                  if (stamp != 0)
                    profile.finish(
                        stamp,
                        Profiler.Kind.PREVIEW,
                        noise,
                        "Camera-relative volume sampling",
                        outcome);
                }
              });
    } catch (RejectedExecutionException full) {
      v.activeJob = false;
      v.failedPreview = true;
      v.volumeWorkingBytes = 0;
      v.volumeRejected = true;
      v.message = "Volume preview queue full; previous display retained; Regenerate to retry";
    }
  }

  private boolean wantsStreaming(View v) {
    return state.user.visualization.streaming
        && !v.fixed
        && (v.kind == 2
            || v.kind == 1
                && (v.mode.equals("Threshold Voxels")
                    || v.mode.equals("Point Cloud")
                    || v.mode.equals("Isosurface")
                    || v.mode.equals("Volume")));
  }

  private boolean usesStreaming(View v) {
    return wantsStreaming(v) && !(v.streamFallback && sceneKey(v).equals(v.failedKey));
  }

  private void rendererFallback(View v, String key) {
    if (v.scene != null) v.scene.close();
    v.scene = null;
    v.gl.releaseScene();
    v.gpuBudget = 0;
    v.activeSceneKey = "";
    v.failedKey = key;
    v.streamFallback = true;
    v.failedPreview = false;
    v.dirty = true;
  }

  private ChunkScene.Config sceneConfig(View v, boolean points) {
    var p = state.user.performance;
    return new ChunkScene.Config(
        32,
        p.renderDistance,
        p.verticalDistance,
        p.lodStart,
        p.lodQuality,
        Math.min(2, p.workers),
        (long) p.sceneCpuMiB << 20,
        points,
        p.streamChunks);
  }

  private String sceneKey(View v) {
    return fingerprint(v)
        + "|"
        + v.noise
        + "|"
        + v.mode
        + "|"
        + v.threshold
        + "|"
        + v.stageMode
        + "|"
        + v.stage
        + "|"
        + v.rebuildSerial;
  }

  private void stopStream(View v) {
    v.setup.invalidate();
    if (v.compiler != null) {
      v.compiler.cancel(true);
      v.compiler = null;
    }
    if (v.scene != null) {
      v.scene.close();
      v.scene = null;
    }
    v.requestedKey = "";
  }

  private boolean stream(View v) {
    if (v.kind == 1 && v.noise == null) return true;
    String key = sceneKey(v);
    if (key.equals(v.failedKey)) return !v.streamFallback;
    long now = System.nanoTime();
    if ((v.scene == null || !v.activeSceneKey.equals(key))
        && !v.requestedKey.equals(key)
        && (state.user.editor.autoRegenerate || v.force || v.lastSubmit == 0)
        && now - v.lastSubmit > 1_000_000_000L / state.user.editor.opportunitiesPerSecond) {
      if (v.pressure.acceptExpensive() && !v.pressure.paused()) {
        if (v.compiler != null) v.compiler.cancel(true);
        long id = v.setup.begin();
        var snapshot = ProjectIO.copy(state.project());
        int kind = v.kind;
        UUID noise = v.noise, stage = v.stage;
        double threshold = v.threshold;
        String mode = v.stageMode, representation = v.mode;
        v.requestedKey = key;
        v.lastSubmit = now;
        try {
          v.compiler =
              state.background.submit(
                  () -> {
                    if (!v.setup.current(id)) return;
                    SceneSetup result;
                    try {
                      if (admissionProbe && probeStreamCompileFailure.compareAndSet(true, false))
                        throw new IllegalStateException(
                            "Injected streaming source compilation failure");
                      var sourcePalette = palette(snapshot, kind);
                      var source =
                          PreviewSources.create(
                              snapshot,
                              state.engine,
                              kind,
                              noise,
                              threshold,
                              mode,
                              stage,
                              representation);
                      result = new SceneSetup(id, key, source, sourcePalette, null);
                    } catch (Throwable failure) {
                      result =
                          new SceneSetup(id, key, null, null, String.valueOf(failure.getMessage()));
                    }
                    v.setup.publish(id, result);
                  });
          v.message = "Compiling streaming source...";
        } catch (RejectedExecutionException full) {
          v.requestedKey = "";
          v.message = "Background queue full; retrying when capacity returns";
        }
      } else v.message = v.pressure.message();
    }
    SceneSetup setup = v.setup.take();
    if (setup != null) {
      v.compiler = null;
      if (!setup.key.equals(key)) {
        if (setup.source != null) setup.source.close();
        v.requestedKey = "";
      } else if (setup.error != null) {
        v.failedKey = key;
        v.streamFallback = false;
        v.failedPreview = true;
        v.force = false;
        v.message = "Source compilation failed; previous display retained; Regenerate to retry";
        v.requestedKey = "";
        v.dirty = true;
        state.error(
            "STREAM_SOURCE_FAILED",
            EditorEvents.Subsystem.RENDERING,
            "Streaming source failed: " + setup.error);
        return true;
      } else {
        v.failedPreview = false;
        v.failedKey = "";
        v.streamFallback = false;
        if (v.scene != null) v.scene.close();
        UUID profileProject = state.project().project.uuid;
        v.scene =
            new ChunkScene(
                setup.source,
                state.background,
                sceneConfig(v, v.mode.equals("Point Cloud")),
                v.pressure,
                position -> state.engine.profiler().scope(0, profileProject, position));
        v.activeSceneKey = key;
        v.requestedKey = "";
        v.force = false;
        v.dirty = false;
        v.completed = false;
        try {
          v.gl.scene(v.scene.epoch(), sceneGpuBudget(v));
          if (admissionProbe && probeRendererStartupFailure.compareAndSet(true, false))
            throw new IllegalStateException("Injected streaming renderer startup failure");
          v.gpuBudget = sceneGpuBudget(v);
          v.gl.setPalette(setup.palette);
        } catch (RuntimeException gpu) {
          rendererFallback(v, key);
          state.notify(
              "Error", "Streaming renderer unavailable; using fixed preview: " + gpu.getMessage());
          return false;
        }
      }
    }
    if (v.scene != null) {
      v.scene.configure(sceneConfig(v, v.scene.points()));
      Vector3d center = v.camera.flying ? v.camera.position : v.camera.focus;
      v.scene.tick(center, v.camera.eye(), 4);
      try {
        boolean resized = v.gpuBudget != sceneGpuBudget(v);
        var gpu = v.gl.scene(v.scene.epoch(), sceneGpuBudget(v));
        if (resized) {
          v.scene.invalidateUploads();
          v.gpuBudget = sceneGpuBudget(v);
        }
        if (admissionProbe && probeRendererFrameFailure.compareAndSet(true, false))
          throw new IllegalStateException("Injected streaming renderer frame failure");
        gpu.beginFrame();
        for (var removed : v.scene.removed()) gpu.remove(removed);
        int budget = state.user.performance.uploadKiB * 1024;
        long before = gpu.statistics().uploadedBytes();
        var uploadProfile = state.engine.profiler().scope(0, state.project().project.uuid, null);
        long uploadStamp = uploadProfile == null ? 0 : uploadProfile.begin();
        var uploadOutcome = Profiler.Outcome.FAILED;
        try {
          int maintenance = gpu.advanceCompaction(budget);
          int uploadBudget = budget - maintenance;
          for (int batch = 0;
              batch < 4
                  && !gpu.compacting()
                  && gpu.statistics().uploadedBytes() - before < uploadBudget;
              batch++) {
            if (!gpu.pending()) {
              var uploads = v.scene.uploads(1, uploadBudget);
              if (uploads.isEmpty()) break;
              if (gpu.contains(uploads.getFirst())) {
                v.scene.uploaded(uploads.getFirst());
                continue;
              }
              if (!gpu.stage(uploads.getFirst())) {
                if (!gpu.compacting()) v.scene.reject();
                break;
              }
            }
            var completed =
                gpu.advance((int) (uploadBudget - (gpu.statistics().uploadedBytes() - before)));
            if (completed != null) v.scene.uploaded(completed);
            else break;
          }
          uploadOutcome = Profiler.Outcome.SUCCESS;
        } finally {
          if (uploadStamp != 0) {
            long uploaded = gpu.statistics().uploadedBytes() - before;
            uploadProfile.finish(
                uploadStamp,
                Profiler.Kind.UPLOAD_CPU,
                null,
                "Streaming upload and arena maintenance",
                uploadOutcome);
            uploadProfile.gauge(
                Profiler.Kind.UPLOAD_BYTES,
                Profiler.Unit.BYTES,
                uploaded,
                "Streaming upload bytes per frame");
            uploadProfile.gauge(
                Profiler.Kind.UPLOAD_BYTES,
                Profiler.Unit.BYTES,
                gpu.statistics().relocatedBytes(),
                "Arena relocation bytes per frame");
          }
        }
        v.origin.set(
            Math.floor(center.x / 32) * 32,
            Math.floor(center.y / 32) * 32,
            Math.floor(center.z / 32) * 32);
        var view = state.user.visualization;
        v.gl.sceneView(
            v.origin,
            v.scene.displaySet(),
            view.indirectDrawing,
            view.ambientOcclusion,
            view.fog
                ? (float)
                    (v.camera.distance
                        + (v.pressure.radius(state.user.performance.renderDistance) + 1) * 32)
                : 0);
        if (gpu.hasData()) {
          v.image = false;
          v.completed = true;
          v.streamingObserved = true;
          v.completedModes.add(v.kind == 2 ? v.stageMode : v.mode);
        }
        var cpu = v.scene.statistics();
        v.frameMillis = v.frameMillis * .9 + ImGui.getIO().getDeltaTime() * 1000 * .1;
        v.pressure.observe(
            now,
            (cpu.cpuBytes() + cpu.reserved())
                / ((double) state.user.performance.sceneCpuMiB * 1024 * 1024),
            (gpu.statistics().gpuBytes() + v.gl.shadowStatistics().bytes())
                / ((double) state.user.performance.sceneGpuMiB * 1024 * 1024),
            v.frameMillis,
            v.scene.rejected());
        if (v.compiler == null)
          v.message =
              (v.activeSceneKey.equals(key)
                      ? cpu.status()
                      : "Definition changed; Regenerate to apply")
                  + " | "
                  + cpu.meshed()
                  + "/"
                  + cpu.wanted()
                  + " meshes; "
                  + cpu.activeJobs()
                  + " jobs"
                  + (gpu.compacting()
                      ? " | Compacting GPU arena: "
                          + gpu.statistics().compactionRemainingBytes()
                          + " bytes remain"
                      : "");
      } catch (RuntimeException gpu) {
        rendererFallback(v, key);
        state.error(
            "STREAM_RENDER_FAILED",
            EditorEvents.Subsystem.RENDERING,
            "Streaming stopped; fixed preview retained: " + gpu.getMessage());
        return false;
      }
    }
    if (v.pressure.paused()) {
      if (ImGui.smallButton("Resume Streaming")) v.pressure.resume(now);
    }
    return true;
  }

  private boolean surfaceNativeFocused() {
    return !ImGui.getIO().getAppFocusLost() && platform.focused(surfaceWindow);
  }

  private void release(View v) {
    v.orbit.reset();
    v.sliceInput.reset();
    v.imageContext.reset();
    v.imageInput.reset();
    if (v.camera.flying) v.camera.leaveFly();
    v.flight.exit();
    syncFlightCursor();
  }

  private OrbitGesture.Click orbitInput(View v, boolean hovered) {
    var io = ImGui.getIO();
    if (!flightEligible(v) || v.camera.flying) {
      v.orbit.reset();
      return OrbitGesture.Click.NONE;
    }
    if (v.orbit.current(v.camera)) {
      if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
        v.orbit.escape(v.camera);
        return OrbitGesture.Click.NONE;
      }
      int button = v.orbit.button();
      if (ImGui.isMouseReleased(button))
        return v.orbit.release(
            v.camera, io.getMousePosX(), io.getMousePosY(), ImGui.getTime(), hovered);
      if (!ImGui.isMouseDown(button)) v.orbit.reset();
      else v.orbit.move(v.camera, io.getMousePosX(), io.getMousePosY());
      return OrbitGesture.Click.NONE;
    }
    if (!hovered) return OrbitGesture.Click.NONE;
    for (int button = 0; button < 3; button++)
      if (ImGui.isMouseClicked(button)) {
        v.orbit.begin(
            v.camera,
            button,
            io.getKeyCtrl(),
            io.getMousePosX(),
            io.getMousePosY(),
            ImGui.getTime());
        return OrbitGesture.Click.NONE;
      }
    if (!ImGui.isMouseDown(0) && !ImGui.isMouseDown(1) && !ImGui.isMouseDown(2))
      v.camera.zoom(io.getMouseWheel());
    return OrbitGesture.Click.NONE;
  }

  private boolean flightEligible(View v) {
    return flightImageVisible && surfaceEligible(v);
  }

  private boolean surfaceEligible(View v) {
    return drawnView == v.kind
        && (flightImageVisible || sliceImageVisible || noiseImageVisible)
        && viewportFocused
        && surfaceNativeFocused()
        && (!ImGui.getIO().getWantTextInput() || surfacePressed)
        && !ShortcutKeys.popup();
  }

  private static SliceNavigation sliceNavigation(View v) {
    return new SliceNavigation(
        v.slicePlane,
        v.slice,
        v.slicePlane == SlicePlane.X ? v.panY : v.panX,
        v.slicePlane == SlicePlane.Z ? v.panY : v.panZ,
        v.step,
        v.size);
  }

  private static void applySliceNavigation(View v, SliceNavigation before, SliceNavigation after) {
    double du = after.u() - before.u(), dv = after.v() - before.v();
    if (v.slicePlane == SlicePlane.X) {
      v.panY = after.u();
      v.camera.focus.y += du;
    } else {
      v.panX = after.u();
      v.camera.focus.x += du;
    }
    if (v.slicePlane == SlicePlane.Z) {
      v.panY = after.v();
      v.camera.focus.y += dv;
    } else {
      v.panZ = after.v();
      v.camera.focus.z += dv;
    }
    v.step = after.step();
  }

  private void syncFlightCursor() {
    if (!flightCursor.sync(flying() ? surfaceWindow : null) && flying())
      for (var view : views) view.flight.pause();
  }

  private boolean imageContextInput(View v, boolean hovered) {
    if (!(noiseImageVisible || sliceImageVisible) || !surfaceEligible(v)) {
      v.imageContext.reset();
      return false;
    }
    var io = ImGui.getIO();
    if (v.imageContext.active()) {
      if (ImGui.isKeyPressed(ImGuiKey.Escape, false)
          || ImGui.isMouseDown(0)
          || ImGui.isMouseDown(2)) v.imageContext.reset();
      else if (ImGui.isMouseReleased(1))
        return v.imageContext.release(
            io.getMousePosX(), io.getMousePosY(), ImGui.getTime(), hovered);
      else if (!ImGui.isMouseDown(1)) v.imageContext.reset();
      else v.imageContext.move(io.getMousePosX(), io.getMousePosY());
    } else if (hovered && ImGui.isMouseClicked(1) && !ImGui.isMouseDown(0) && !ImGui.isMouseDown(2))
      v.imageContext.begin(io.getMousePosX(), io.getMousePosY(), ImGui.getTime());
    return false;
  }

  private void publish(View v, Preview result) {
    v.ready.publish(result.epoch, result);
  }

  private void mark(View v) {
    v.pendingVolume = null;
    v.volumeWorkingBytes = 0;
    v.volumeRejected = false;
    v.volumeRequestTag = "";
    v.gl.cancelVolume();
    if (v.kind == 2) {
      pickViewChanged();
      v.pickedBookmark = null;
    }
    v.dirty = true;
    v.admission.changed();
    v.failedPreview = false;
    v.epoch++;
    v.ready.advance(v.epoch);
    if (v.job != null) v.job.cancel(true);
    state.background.purge();
    v.activeJob = false;
  }

  private void submit(View v) {
    int n = v.kind == 0 ? 256 : v.size;
    var admission =
        v.admission.check(
            v.kind,
            n,
            v.mode,
            state.user.performance.previewCellBudget,
            state.user.performance.maxPreviewDimension,
            (long) state.user.performance.sceneCpuMiB << 20);
    if (!admission.allowed()) {
      v.message = admission.message();
      v.failedPreview = true;
      return;
    }
    if (v.kind == 1
        && v.mode.equals("Volume")
        && 4L * n * n * n + v.gl.volumeStatistics().activeBytes()
            > (long) state.user.performance.sceneGpuMiB << 20) {
      volumeUploadFailed(v, "Volume replacement exceeds GPU MiB; reduce extent or raise GPU MiB");
      return;
    }
    long working = admission.workingBytes();
    var snapshot = ProjectIO.copy(state.project());
    long epoch = v.epoch;
    var cancellation = v.ready.token(epoch);
    int kind = v.kind;
    UUID noise = v.noise, stageId = v.stage;
    String stageMode = v.stageMode;
    String mode = v.mode;
    double panX = v.panX, panZ = v.panZ, step = v.step, threshold = v.threshold;
    int slice = v.slice;
    SlicePlane slicePlane = v.slicePlane;
    double centerY =
        v.fixed || mode.equals("Slices")
            ? v.panY
            : v.camera.flying ? v.camera.position.y : v.camera.focus.y;
    boolean fixed = v.fixed;
    final long cacheBudget = (long) state.user.performance.cacheMiB * 1024 * 1024;
    final boolean freshImage = v.force;
    final long imageBudget =
        Math.min(
            cacheBudget, Math.max(0, ((long) state.user.performance.sceneCpuMiB << 20) - working));
    v.force = false;
    v.dirty = false;
    v.activeJob = true;
    v.lastSubmit = System.nanoTime();
    v.message = "Generating...";
    try {
      v.job =
          state.background.submit(
              () -> {
                var profile = state.engine.profiler().scope(0, snapshot.project.uuid, null);
                long stamp = profile == null ? 0 : profile.begin();
                var outcome = Profiler.Outcome.CANCELLED;
                try {
                  cancellation.throwIfCancelled();
                  var compiled = new CompiledProject(snapshot);
                  int[] palette = palette(snapshot, kind);
                  Vector3d origin =
                      new Vector3d(panX - n / 2., Math.floor(centerY) - n / 2., panZ - n / 2.);
                  if (kind == 1 && mode.equals("Slices"))
                    origin.set(panX - n * step / 2., centerY - n * step / 2., panZ - n * step / 2.);
                  if (kind == 0 || kind == 1 && mode.equals("Slices")) {
                    int side = kind == 0 ? 256 : n;
                    double[] raw = new double[side * side];
                    int[] pixels = new int[raw.length];
                    float[] histogram = new float[64];
                    var compiler = new NoiseCompiler(snapshot, snapshot.project.seed);
                    var sampler = compiler.compile(noise);
                    NoiseImageRows.Statistics imageStatistics = null;
                    if (kind == 0) {
                      var sampled =
                          v.imageRows.sample(
                              PreviewIdentity.of(snapshot, 0, noise),
                              epoch,
                              sampler,
                              side,
                              panX,
                              panZ,
                              step,
                              imageBudget,
                              freshImage,
                              cancellation);
                      raw = sampled.copyValues();
                      imageStatistics = sampled.statistics();
                    }
                    for (int z = 0; z < side; z++) {
                      if (Thread.currentThread().isInterrupted()) return;
                      for (int x = 0; x < side; x++) {
                        double wx = kind == 0 ? panX + (x - side / 2.) * step : origin.x + x,
                            wz = kind == 0 ? panZ + (z - side / 2.) * step : origin.z + z;
                        double u = (slicePlane == SlicePlane.X ? origin.y : origin.x) + x * step,
                            vv = (slicePlane == SlicePlane.Z ? origin.y : origin.z) + z * step;
                        double value =
                            kind == 0
                                ? raw[z * side + x]
                                : sampler.sample(
                                    slicePlane.x(u, vv, slice),
                                    slicePlane.y(u, vv, slice),
                                    slicePlane.z(u, vv, slice));
                        raw[z * side + x] = value;
                        pixels[z * side + x] =
                            color(value, kind == 0 ? mode : "Heatmap", threshold);
                        histogram[Math.max(0, Math.min(63, (int) ((value + 1) * 32)))]++;
                      }
                    }
                    publish(
                        v,
                        new Preview(
                            epoch,
                            null,
                            pixels,
                            raw,
                            side,
                            origin,
                            palette,
                            histogram,
                            sampler,
                            null,
                            null,
                            imageStatistics,
                            kind == 0 ? new NoiseImageView(panX, panZ, step, side, sampler) : null,
                            kind == 1
                                ? new SliceImageView(
                                    slicePlane,
                                    slice,
                                    slicePlane == SlicePlane.X ? origin.y : origin.x,
                                    slicePlane == SlicePlane.Z ? origin.y : origin.z,
                                    step,
                                    side,
                                    sampler)
                                : null));
                  } else if (kind == 1 && mode.equals("Volume")) {
                    var sampler = new NoiseCompiler(snapshot, snapshot.project.seed).compile(noise);
                    var volume =
                        VolumeData.sample(n, origin.x, origin.y, origin.z, sampler, cancellation);
                    publish(
                        v,
                        new Preview(
                            epoch,
                            null,
                            null,
                            null,
                            0,
                            origin,
                            palette,
                            new float[64],
                            sampler,
                            null,
                            volume));
                  } else {
                    var bounds = VoxelRaycast.Bounds.snappedCube(origin, n);
                    origin.set(bounds.min().x(), bounds.min().y(), bounds.min().z());
                    int ox = (int) origin.x, oy = (int) origin.y, oz = (int) origin.z;
                    int[] cells =
                        kind == 1 && mode.equals("Isosurface") ? null : new int[n * n * n];
                    Mesh mesh;
                    if (kind == 1) {
                      var sampler = compiled.noise3D(noise, snapshot.project.seed);
                      if (mode.equals("Isosurface")) {
                        mesh =
                            IsosurfaceMesh.build(
                                n,
                                (x, y, z) ->
                                    sampler.sample(
                                        (double) ox + x, (double) oy + y, (double) oz + z),
                                threshold,
                                cancellation,
                                IsosurfaceMesh.maximumMeshBytes(n));
                      } else {
                        for (int y = 0; y < n; y++) {
                          if (Thread.currentThread().isInterrupted()) return;
                          for (int z = 0; z < n; z++)
                            for (int x = 0; x < n; x++) {
                              double val = sampler.sample(ox + x, oy + y, oz + z);
                              cells[(y * n + z) * n + x] =
                                  val >= threshold
                                      ? Math.max(1, Math.min(255, (int) ((val + 1) * 127.5)))
                                      : 0;
                            }
                        }
                        mesh =
                            mode.equals("Point Cloud")
                                ? MeshBuilder.points(n, (x, y, z) -> cells[(y * n + z) * n + x])
                                : MeshBuilder.greedy(n, (x, y, z) -> cells[(y * n + z) * n + x]);
                      }
                    } else {
                      try (var world =
                          state.engine.openWorld(
                              compiled,
                              new WorldOptions(
                                  null,
                                  32,
                                  WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE,
                                  cacheBudget))) {
                        int cx = Math.floorDiv(ox, 32),
                            cy = Math.floorDiv(oy, 32),
                            cz = Math.floorDiv(oz, 32),
                            nx = Math.floorDiv(ox + n - 1, 32) - cx + 1,
                            ny = Math.floorDiv(oy + n - 1, 32) - cy + 1,
                            nz = Math.floorDiv(oz + n - 1, 32) - cz + 1;
                        VoxelChunk[] chunks = new VoxelChunk[nx * ny * nz];
                        StageChunk[] stageChunks =
                            !stageMode.equals("Final") && stageId != null
                                ? new StageChunk[chunks.length]
                                : null;
                        for (int y = 0; y < ny; y++)
                          for (int z = 0; z < nz; z++)
                            for (int x = 0; x < nx; x++) {
                              if (Thread.currentThread().isInterrupted()) return;
                              int at = (y * nz + z) * nx + x;
                              var position = new ChunkPos(cx + x, cy + y, cz + z);
                              if (stageChunks == null) chunks[at] = world.generateChunk(position);
                              else {
                                var pair = world.generateStageChunk(position, stageId);
                                stageChunks[at] = pair;
                                chunks[at] =
                                    stageMode.equals("Before") ? pair.before() : pair.after();
                              }
                            }
                        for (int y = 0; y < n; y++)
                          for (int z = 0; z < n; z++)
                            for (int x = 0; x < n; x++) {
                              int wx = ox + x, wy = oy + y, wz = oz + z;
                              var chunk =
                                  chunks[
                                      ((Math.floorDiv(wy, 32) - cy) * nz
                                                  + Math.floorDiv(wz, 32)
                                                  - cz)
                                              * nx
                                          + Math.floorDiv(wx, 32)
                                          - cx];
                              int lx = Math.floorMod(wx, 32),
                                  ly = Math.floorMod(wy, 32),
                                  lz = Math.floorMod(wz, 32),
                                  type = chunk.getVoxelTypeId(lx, ly, lz);
                              if (stageChunks != null && stageMode.equals("Only Changes")) {
                                int at =
                                    ((Math.floorDiv(wy, 32) - cy) * nz + Math.floorDiv(wz, 32) - cz)
                                            * nx
                                        + Math.floorDiv(wx, 32)
                                        - cx;
                                var pair = stageChunks[at];
                                int before = pair.before().getVoxelTypeId(lx, ly, lz),
                                    after = pair.after().getVoxelTypeId(lx, ly, lz);
                                type =
                                    before == after
                                        ? 0
                                        : after > 0 ? after : snapshot.voxelTypes.size() + before;
                              }
                              cells[(y * n + z) * n + x] = type;
                            }
                      }
                      mesh = MeshBuilder.greedy(n, (x, y, z) -> cells[(y * n + z) * n + x]);
                    }
                    publish(
                        v,
                        new Preview(
                            epoch,
                            mesh,
                            null,
                            null,
                            n,
                            origin,
                            palette,
                            new float[64],
                            null,
                            null));
                  }
                  outcome = Profiler.Outcome.SUCCESS;
                } catch (Throwable e) {
                  outcome =
                      e instanceof CancellationException
                          ? Profiler.Outcome.CANCELLED
                          : Profiler.Outcome.FAILED;
                  if (!(e instanceof CancellationException))
                    publish(
                        v,
                        new Preview(
                            epoch,
                            null,
                            null,
                            null,
                            0,
                            new Vector3d(),
                            new int[256],
                            new float[64],
                            null,
                            String.valueOf(e.getMessage())));
                } finally {
                  if (stamp != 0)
                    profile.finish(
                        stamp,
                        Profiler.Kind.PREVIEW,
                        noise,
                        "Fixed preview / " + (kind == 2 ? stageMode : mode),
                        outcome);
                }
              });
    } catch (RejectedExecutionException e) {
      v.activeJob = false;
      v.message = "Preview queue is full; Regenerate to retry";
      v.failedPreview = true;
      v.admission.queueFull();
    }
  }

  private static int[] palette(ProjectData p, int kind) {
    int[] palette = new int[kind == 2 ? Math.max(256, 2 * p.voxelTypes.size() + 1) : 256];
    for (int i = 0; i < palette.length; i++) palette[i] = color(i / 127.5 - 1, "Heatmap", 0);
    if (kind == 2)
      for (int i = 0; i < p.voxelTypes.size(); i++) {
        var c = p.voxelTypes.get(i).color;
        palette[i + 1] = rgba(c.r, c.g, c.b);
        palette[p.voxelTypes.size() + i + 1] =
            rgba(235, Math.max(30, c.g / 3), Math.max(45, c.b / 3));
      }
    return palette;
  }

  public static int color(double raw, String mode, double threshold) {
    double t = Math.max(0, Math.min(1, (raw + 1) / 2));
    if (mode.equals("Threshold")) return raw >= threshold ? rgba(215, 237, 241) : rgba(20, 29, 39);
    if (mode.equals("Contour")) {
      double v = Math.abs(raw * 12 - Math.rint(raw * 12));
      return v < .055
          ? rgba(16, 25, 33)
          : rgba((int) (40 + 130 * t), (int) (65 + 150 * t), (int) (70 + 160 * t));
    }
    if (mode.equals("Heatmap")) {
      double r = Math.max(0, Math.min(1, 1.5 - Math.abs(4 * t - 3))),
          g = Math.max(0, Math.min(1, 1.5 - Math.abs(4 * t - 2))),
          b = Math.max(0, Math.min(1, 1.5 - Math.abs(4 * t - 1)));
      return rgba((int) (r * 255), (int) (g * 255), (int) (b * 255));
    }
    int gray = (int) (t * 255);
    return rgba(gray, gray, gray);
  }

  private static int rgba(int r, int g, int b) {
    return r | (g << 8) | (b << 16) | 0xff000000;
  }

  private Vector3d center(View v) {
    if (v.kind == 0) return new Vector3d(v.panX, 0, v.panZ);
    if (v.kind == 1 && v.mode.equals("Slices"))
      return new Vector3d(
          v.slicePlane.x(
              v.slicePlane == SlicePlane.X ? v.panY : v.panX,
              v.slicePlane == SlicePlane.Z ? v.panY : v.panZ,
              v.slice),
          v.slicePlane.y(
              v.slicePlane == SlicePlane.X ? v.panY : v.panX,
              v.slicePlane == SlicePlane.Z ? v.panY : v.panZ,
              v.slice),
          v.slicePlane.z(
              v.slicePlane == SlicePlane.X ? v.panY : v.panX,
              v.slicePlane == SlicePlane.Z ? v.panY : v.panZ,
              v.slice));
    return v.camera.flying ? new Vector3d(v.camera.position) : new Vector3d(v.camera.focus);
  }

  private void rememberPicked(View v, float px, float py, float width, float height) {
    var io = ImGui.getIO();
    try {
      if (v.kind == 0) {
        if (v.displayedImage == null) {
          v.pickedBookmark = null;
          return;
        }
        var point =
            v.displayedImage.at(
                (io.getMousePosX() - px) / (double) width,
                (io.getMousePosY() - py) / (double) height);
        v.pickedBookmark = BookmarkTransactions.capture(Tab.NOISE_2D, point.x(), 0, point.z());
      } else if (v.kind == 1 && v.mode.equals("Slices") && v.image && v.displayedSlice != null) {
        var point =
            v.displayedSlice.at(
                (io.getMousePosX() - px) / (double) width,
                (io.getMousePosY() - py) / (double) height);
        v.pickedBookmark =
            BookmarkTransactions.capture(Tab.NOISE_3D, point.x(), point.y(), point.z());
      }
    } catch (IllegalArgumentException failure) {
      v.pickedBookmark = null;
      state.status = failure.getMessage();
    }
  }

  private void teleport(View v, int x, int y, int z) {
    v.panX = x;
    v.panZ = z;
    if (v.kind > 0) {
      v.panY = y;
      v.slice = v.slicePlane == SlicePlane.X ? x : v.slicePlane == SlicePlane.Y ? y : z;
    }
    v.camera.teleport(x, y, z);
    mark(v);
  }

  private void pick(View v, float nx, float ny, float aspect) {
    if (!stagePicker.available(v.stageMode, v.stage)) {
      state.status = "Choose an existing stage boundary or Final before picking";
      return;
    }
    if (usesStreaming(v)
        ? v.scene == null
            || !v.activeSceneKey.equals(sceneKey(v))
            || v.gl.sceneRenderer() == null
            || !v.gl.sceneRenderer().hasData()
        : v.dirty || v.activeJob || v.failedPreview || v.fixedPickBounds == null) {
      state.status =
          "Preview is rebuilding or outdated; use Inspect Coordinate or Regenerate before picking";
      return;
    }
    var ray = v.camera.ray(nx, ny, aspect, v.origin);
    boolean streamingPick = usesStreaming(v);
    var displayedBounds = v.fixedPickBounds;
    observePickView();
    long revision = state.revision;
    cancelInspectorTask("", false);
    var request = traceRequests.begin(revision, true);
    var sourceScene = v.scene;
    var sourceGpu = v.gl.sceneRenderer();
    var drawn = streamingPick ? sourceGpu.pickingVersions() : Map.<ChunkPos, Long>of();
    Optional<ChunkScene.Pick> streamedHit;
    try {
      streamedHit = streamingPick ? sourceScene.pick(ray, 1000, drawn) : Optional.empty();
    } catch (RuntimeException failure) {
      state.status = failure.getMessage();
      return;
    }
    if (streamingPick && streamedHit.isEmpty()) {
      showPickMiss(v, "No occupied voxel in displayed chunks; enter a coordinate to inspect EMPTY");
      return;
    }
    long coordinateVersion = inspectorCoordinates.version();
    java.util.function.BooleanSupplier current =
        () ->
            traceRequests.current(request, state.revision)
                && coordinateVersion == inspectorCoordinates.version()
                && (!streamingPick
                    || v.scene == sourceScene
                        && v.gl.sceneRenderer() == sourceGpu
                        && drawn.equals(sourceGpu.pickingVersions()));
    ProjectData p = ProjectIO.copy(state.project());
    UUID selectedStage = v.stage;
    String selectedMode = v.stageMode;
    beginInspectorTask(
        request,
        streamedHit.map(ChunkScene.Pick::position).orElse(null),
        current,
        "Picking a voxel from the displayed preview...");
    try {
      inspectorTask.attach(
          request,
          state.background.submit(
              () -> {
                try (var world =
                    state.engine.openWorld(
                        new CompiledProject(p),
                        new WorldOptions(null, 32, WorldOptions.CachePolicy.NO_CACHE, 0))) {
                  GenerationStage boundary =
                      selectedMode.equals("Final")
                          ? null
                          : world.stages().stream()
                              .filter(stage -> stage.uuid().equals(selectedStage))
                              .findFirst()
                              .orElseThrow(
                                  () ->
                                      new IllegalStateException(
                                          "Selected stage boundary no longer exists"));
                  VoxelRaycast.Cells evaluator =
                      (x, y, z) -> {
                        var position = new BlockPos(x, y, z);
                        if (boundary == null) return world.sampleStage(position, Integer.MAX_VALUE);
                        int before = world.sampleStage(position, boundary.before());
                        if (selectedMode.equals("Before")) return before;
                        int after = world.sampleStage(position, boundary.after());
                        return selectedMode.equals("Only Changes")
                            ? (before == after ? 0 : after > 0 ? after : before)
                            : after;
                      };
                  var hit =
                      streamingPick
                          ? streamedHit.map(ChunkScene.Pick::position)
                          : VoxelRaycast.firstOccupied(
                              ray, 1000, displayedBounds.restrict(evaluator));
                  if (hit.isPresent()) {
                    BlockPos cell = hit.get();
                    GenerationTrace found = world.trace(cell);
                    state.uiQueue.offer(
                        () -> {
                          if (!finishInspectorTask(request, current)) return;
                          acceptTrace(found, p, revision);
                          if (streamingPick && streamedHit.orElseThrow().displayProxy()) {
                            inspectorPickHint =
                                String.format(
                                    Locale.ROOT,
                                    "Coarse preview hit at X %d, Y %d, Z %d. This cell is empty in"
                                        + " the exact preview; the trace shows the full world at"
                                        + " this coordinate. Move closer for detail.",
                                    cell.x(),
                                    cell.y(),
                                    cell.z());
                            state.status = inspectorPickHint;
                          }
                          setInspectorCoordinate(cell);
                          v.pickedBookmark = new Coordinates(cell.x(), cell.y(), cell.z());
                          inspector.set(true);
                        });
                    return;
                  }
                  state.uiQueue.offer(
                      () -> {
                        if (!finishInspectorTask(request, current)) return;
                        showPickMiss(
                            v, "No occupied voxel hit; enter a coordinate to inspect EMPTY");
                      });
                } catch (Exception e) {
                  state.uiQueue.offer(
                      () -> {
                        if (!finishInspectorTask(request, current)) return;
                        inspectorTaskFeedback =
                            "Pick failed; choose a voxel or Trace XYZ to retry.";
                        state.status = inspectorTaskFeedback;
                        state.notify("Error", e.getMessage());
                      });
                }
              }));
    } catch (RejectedExecutionException e) {
      finishInspectorTask(request, current);
      inspectorTaskFeedback = "Inspector queue is full; choose a voxel or Trace XYZ to retry.";
      state.status = inspectorTaskFeedback;
      state.notify("Warning", "Inspector queue is full");
    }
  }

  private void acceptTrace(GenerationTrace result, ProjectData snapshot, long revision) {
    inspectorPickHint = "";
    inspectorTaskFeedback = "";
    traceSnapshot = new TraceSnapshot(snapshot, revision, result);
    state.status =
        String.format(
            Locale.ROOT,
            "Trace ready at X %d, Y %d, Z %d",
            result.position().x(),
            result.position().y(),
            result.position().z());
    state.publishTrace(traceSnapshot);
  }

  private void showPickMiss(View v, String message) {
    v.pickedBookmark = null;
    traceSnapshot = null;
    inspectorTaskFeedback = "";
    inspectorPickHint = message;
    inspectorPanel.showCoordinates(false);
    inspector.set(true);
    state.status = message;
  }

  private void cancelInspectorTask(String feedback, boolean invalidate) {
    inspectorTask.cancel();
    inspectorTaskValid = null;
    state.background.purge();
    if (invalidate) traceRequests.cancelCurrent();
    inspectorTaskFeedback = feedback;
    if (!feedback.isEmpty()) state.status = feedback;
  }

  private void beginInspectorTask(
      InspectorRequests.Request request,
      BlockPos target,
      java.util.function.BooleanSupplier valid,
      String feedback) {
    inspectorTask.begin(request, target);
    inspectorPanel.showTrace();
    inspectorTaskValid = valid;
    state.background.purge();
    inspectorTaskFeedback = feedback;
    state.status = feedback;
  }

  private boolean finishInspectorTask(
      InspectorRequests.Request request, java.util.function.BooleanSupplier valid) {
    if (!inspectorTask.finish(request)) return false;
    inspectorTaskValid = null;
    if (!valid.getAsBoolean()) {
      inspectorTaskFeedback = "Inspector request expired; choose a voxel or Trace XYZ to retry.";
      state.status = inspectorTaskFeedback;
      return false;
    }
    inspectorTaskFeedback = "";
    return true;
  }

  private void startCoordinateTrace() {
    BlockPos point;
    try {
      point = readInspectorCoordinate();
    } catch (RuntimeException invalid) {
      inspectorInputError = Objects.requireNonNullElse(invalid.getMessage(), invalid.toString());
      cancelInspectorTask("Invalid Inspector coordinates; correct XYZ before tracing.", true);
      inspectorPanel.showCoordinates(true);
      return;
    }
    inspectorInputError = "";
    startCoordinateTrace(point);
  }

  private void startCoordinateTrace(BlockPos pos) {
    var p = ProjectIO.copy(state.project());
    long revision = state.revision;
    var request = traceRequests.begin(revision, false);
    long coordinateVersion = inspectorCoordinates.version();
    java.util.function.BooleanSupplier current =
        () ->
            traceRequests.current(request, state.revision)
                && coordinateVersion == inspectorCoordinates.version();
    beginInspectorTask(
        request,
        pos,
        current,
        String.format(Locale.ROOT, "Tracing X %d, Y %d, Z %d...", pos.x(), pos.y(), pos.z()));
    try {
      inspectorTask.attach(
          request,
          state.background.submit(
              () -> {
                try (var world = state.engine.openWorld(new CompiledProject(p))) {
                  var result = world.trace(pos);
                  state.uiQueue.offer(
                      () -> {
                        if (!finishInspectorTask(request, current)) return;
                        acceptTrace(result, p, revision);
                      });
                } catch (Exception e) {
                  state.uiQueue.offer(
                      () -> {
                        if (!finishInspectorTask(request, current)) return;
                        inspectorTaskFeedback =
                            String.format(
                                Locale.ROOT,
                                "Trace failed at X %d, Y %d, Z %d. Trace XYZ to retry.",
                                pos.x(),
                                pos.y(),
                                pos.z());
                        state.status = inspectorTaskFeedback;
                        state.notify("Error", e.getMessage());
                      });
                }
              }));
    } catch (RejectedExecutionException full) {
      finishInspectorTask(request, current);
      inspectorTaskFeedback = "Inspector queue is full; Trace XYZ to retry.";
      state.status = inspectorTaskFeedback;
      state.notify("Warning", "Inspector queue is full");
    }
  }

  private void restoreTracedCoordinate() {
    var point = traceSnapshot.trace().position();
    cancelInspectorTask("", true);
    setInspectorCoordinate(point);
    state.status =
        String.format(
            Locale.ROOT,
            "Inspector coordinates restored to X %d, Y %d, Z %d",
            point.x(),
            point.y(),
            point.z());
  }

  private void editInspectorCoordinate(String axis, String raw) {
    boolean wasPending = inspectorTask.pending();
    try {
      inspectorCoordinates.edit(axis, raw);
      inspectorInputError = "";
    } catch (RuntimeException invalid) {
      inspectorInputError = Objects.requireNonNullElse(invalid.getMessage(), invalid.toString());
    }
    cancelInspectorTask(wasPending ? "Coordinate changed; Trace XYZ to continue." : "", true);
  }

  private void drawInspector() {
    if (tracePanel != null) tracePanel.hide(traceSnapshot);
    if (!inspector.get()) {
      inspectorPanel.suspend();
      return;
    }
    inspectorLayout.prepare();
    boolean visible = ImGui.begin("Voxel Inspector", inspector);
    UiScale.window(state.user, "Voxel Inspector");
    inspectorLayout.observe();
    if (admissionProbe) {
      var position = ImGui.getWindowPos();
      var size = ImGui.getWindowSize();
      float halfTitle = ImGui.getFrameHeight() / 2;
      admissionControls.put(
          "inspector-close", new float[] {position.x + size.x - halfTitle, position.y + halfTitle});
    }
    if (!inspector.get()) {
      inspectorPanel.suspend();
      cancelInspectorTask("", true);
      state.status = "Inspector closed.";
      ImGui.end();
      return;
    }
    if (!visible) {
      inspectorPanel.suspend();
      ImGui.end();
      return;
    }
    var action = inspectorPanel.draw(this::inspectorStatus, tracePanel);
    inspectorTraceVisible = inspectorPanel.traceVisible();
    inspectorHintVisible = inspectorPanel.hintVisible();
    inspectorDraftNoticeVisible = inspectorPanel.draftNoticeVisible();
    inspectorFreshnessNoticeVisible = inspectorPanel.freshnessNoticeVisible();
    switch (action) {
      case NONE -> {}
      case TRACE -> startCoordinateTrace();
      case BOOKMARK -> addInspectorBookmark();
      case RESTORE -> restoreTracedCoordinate();
      case REFRESH -> startCoordinateTrace(traceSnapshot.trace().position());
      case CANCEL ->
          cancelInspectorTask(
              inspectorStatus().pick() ? "World pick canceled." : "Coordinate trace canceled.",
              true);
    }
    ImGui.end();
  }

  private InspectorPanel.Status inspectorStatus() {
    return new InspectorPanel.Status(
        traceSnapshot,
        inspectorTask.request(),
        inspectorTaskFeedback,
        inspectorPickHint,
        inspectorInputError);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      bookmarkPanel.cancel();
      traceRequests.close();
      inspectorTask.close();
      inspectorTaskValid = null;
      for (var v : views) {
        release(v);
        v.ready.close();
        v.setup.close();
        if (v.job != null) v.job.cancel(true);
        state.background.purge();
        stopStream(v);
        v.imageRows.close();
        v.volumeWindows.close();
        v.volumeSnapshot = null;
        v.pendingVolume = null;
        v.gl.close();
      }
    } finally {
      flightCursor.close();
    }
  }
}
