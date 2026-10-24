package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.joml.Vector3d;

public final class Viewports implements AutoCloseable {
  private final EditorState state;
  private final PropertyPanel properties;
  private final long handle;
  private final View[] views = {new View(0), new View(1), new View(2)};
  private final UiFields fields = new UiFields();
  private int active = 0;
  private int smokeTab = -1;
  private boolean paused = false;
  private GenerationTrace trace;
  private long traceSerial = 0, traceRevision = -1;
  private Map<Integer, String> traceNames = Map.of();
  private Map<Integer, UUID> traceTypes = Map.of();
  private final ImBoolean inspector = new ImBoolean(false);
  private final int[] inspect = {0, 0, 0};
  private String bookmarkAction = "";
  private boolean coordinatesPending = false;
  private final ImString bookmarkName = new ImString(256);
  private final int[] teleport = {0, 0, 0};

  private static final class View {
    final int kind;
    final GlViewport gl = new GlViewport();
    final Camera camera = new Camera();
    UUID noise, stage, keyNoise;
    long keyRevision = -1, rebuildSerial = 0, compileId = 0;
    String geometryKey = "",
        fingerprint = "",
        requestedKey = "",
        activeSceneKey = "",
        failedKey = "";
    ChunkScene scene;
    final RenderPressure pressure = new RenderPressure();
    Future<?> compiler;
    final AtomicReference<SceneSetup> setup = new AtomicReference<>();
    int gpuBudget;
    boolean streamingObserved;
    double frameMillis = 16;

    String stageMode = "Final";
    String mode;
    double panX = 0, panZ = 0, panY = 16, step = 1, threshold = 0;
    int size = 32, slice = 0, volumeSteps = 192;
    SlicePlane slicePlane = SlicePlane.Y, displayedSlicePlane = SlicePlane.Y;
    int displayedSlice;
    double displayedStep = 1;
    double volumeMin = -1, volumeMax = 1, volumeOpacity = .12;
    final Set<String> completedModes = new HashSet<>();
    boolean fixed = false, dirty = true, activeJob = false, force = false;
    long revision = -1, lastSubmit = 0;
    volatile long epoch = 0;
    Future<?> job;
    final AtomicReference<Preview> ready = new AtomicReference<>();
    String message = "Ready";
    Vector3d origin = new Vector3d();
    double[] raw;
    NoiseAlgorithms.Field hoverSampler;
    int[] colors;
    float[] histogram = new float[64];
    UUID selectedBookmark;
    boolean image = false, completed = false, failedPreview = false;

    View(int k) {
      kind = k;
      mode = k == 0 ? "Grayscale" : "Threshold Voxels";
    }
  }

  private record SceneSetup(
      long id, String key, ChunkScene.Source source, int[] palette, String error) {}

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

  public StreamingStatistics streamingStatistics() {
    long cpu = 0, reserved = 0, gpu = 0, uploads = 0;
    int chunks = 0, jobs = 0, visible = 0, culled = 0, draws = 0, compacting = 0;
    long capacity = 0, free = 0, largest = 0, moved = 0, remaining = 0, compactions = 0;
    boolean indirect = false;
    for (var v : views) {
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
      VolumeData volume) {
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
      this(epoch, mesh, image, raw, width, origin, palette, histogram, sampler, error, null);
    }
  }

  public Viewports(EditorState state, PropertyPanel properties, long handle) {
    this.state = state;
    this.properties = properties;
    this.handle = handle;
    for (var v : views) v.size = state.user.visualization.previewSize;
  }

  public void draw() {
    ImGui.begin("Visualizers");
    ImGui.setWindowFontScale(state.user.appearance.windowScales.getOrDefault("Visualizers", 1f));
    if (ImGui.beginTabBar("view-tabs")) {
      for (int k = 0; k < 3; k++) {
        String name = k == 0 ? "2D Noise" : k == 1 ? "3D Noise" : "World";
        if (ImGui.beginTabItem(name, smokeTab == k ? ImGuiTabItemFlags.SetSelected : 0)) {
          active = k;
          drawView(views[k]);
          ImGui.endTabItem();
        }
      }
      ImGui.endTabBar();
    }
    ImGui.end();
    drawInspector();
    drawBookmarkDialogs();
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

  public void smokePhase(int frame) {
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
      List<Noise> list = v.kind == 0 ? state.project().noise2D : state.project().noise3D;
      String label =
          list.stream()
              .filter(n -> n.uuid.equals(v.noise))
              .map(n -> n.id)
              .findFirst()
              .orElse("Select a noise generator...");
      if (ImGui.beginCombo("Generator", label)) {
        for (var n : list)
          if (ImGui.selectable(n.id)) {
            v.noise = n.uuid;
            mark(v);
          }
        ImGui.endCombo();
      }
    }
    if (ImGui.button("Regenerate")) {
      paused = false;
      v.force = true;
      v.rebuildSerial++;
      mark(v);
    }
    ImGui.sameLine();
    fields.bool(
        "Auto",
        state.user.editor.autoRegenerate,
        x -> {
          state.user.editor.autoRegenerate = x;
          state.user.save();
        });
    ImGui.sameLine();
    ImGui.textDisabled(v.message);
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
              v.threshold = x;
              if (!v.mode.equals("Volume")) mark(v);
            });
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
              "Ray-marched canonical scalar texture; display controls do not alter generation.");
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
          v.camera.flying
              ? "Free-fly: WASD / Space / Shift; Esc to release"
              : "Orbit: drag left; pan right; wheel zoom; Ctrl+click free-fly");
    }
    bookmarks(v);
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
        var stages =
            ResourceGraph.entries(state.project()).stream()
                .filter(e -> e.kind().equals("layer") || e.kind().equals("operation"))
                .toList();
        if (v.stage == null && !stages.isEmpty()) {
          v.stage =
              stages.stream()
                  .filter(e -> e.uuid().equals(state.selected))
                  .findFirst()
                  .orElse(stages.getFirst())
                  .uuid();
          mark(v);
        }
        String label =
            stages.stream()
                .filter(e -> e.uuid().equals(v.stage))
                .map(e -> e.kind() + " / " + e.resource().id)
                .findFirst()
                .orElse("Choose stage");
        if (ImGui.beginCombo("Boundary", label)) {
          for (var entry : stages)
            if (ImGui.selectable(
                entry.kind() + " / " + entry.resource().id + "###stage" + entry.uuid())) {
              v.stage = entry.uuid();
              mark(v);
            }
          ImGui.endCombo();
        }
        if (v.stageMode.equals("Only Changes"))
          ImGui.textDisabled("SET: voxel palette | CLEAR: red removal ghosts | unchanged: hidden");
      }
      if (ImGui.button("Inspect Coordinate")) inspector.set(true);
      ImGui.sameLine();
      ImGui.textDisabled("Read-only procedural world");
    }
    boolean streaming = wantsStreaming(v);
    if (streaming) streaming = stream(v);
    else stopStream(v);
    Preview ready = v.ready.getAndSet(null);
    if (ready != null && ready.epoch == v.epoch && !streaming) {
      v.activeJob = false;
      if (ready.error != null) {
        v.message = "Failed; previous display retained";
        v.failedPreview = true;
        state.error("PREVIEW_FAILED", EditorEvents.Subsystem.RENDERING, ready.error);
      } else {
        if (ready.image != null) {
          v.gl.setImage(ready.width, ready.width, ready.image);
          v.image = true;
        } else {
          if (ready.volume != null) v.gl.setVolume(ready.volume);
          else v.gl.setMesh(ready.mesh);
          v.image = false;
        }
        if (v.mode.equals("Slices")) {
          v.displayedSlicePlane = v.slicePlane;
          v.displayedSlice = v.slice;
          v.displayedStep = v.step;
        }
        v.completedModes.add(
            v.kind == 2
                ? v.stageMode
                : v.mode + (v.mode.equals("Slices") ? " " + v.slicePlane.name() : ""));
        v.origin.set(ready.origin);
        v.gl.setPalette(ready.palette);
        v.raw = ready.raw;
        v.hoverSampler = ready.sampler;
        v.histogram = ready.histogram;
        v.message = "Ready";
        v.completed = true;
      }
    }
    boolean source = v.kind == 2 || v.noise != null;
    if (source
        && !streaming
        && (!v.failedPreview || v.force)
        && v.dirty
        && !v.activeJob
        && !paused
        && (state.user.editor.autoRegenerate || v.force || v.lastSubmit == 0)
        && System.nanoTime() - v.lastSubmit
            > 1_000_000_000L / state.user.editor.opportunitiesPerSecond) submit(v);
    if (!source) {
      ImGui.spacing();
      ImGui.textWrapped(
          "Create a "
              + (v.kind == 0 ? "2D" : "3D")
              + " noise generator in Resources, then select it above. The project starts empty.");
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
      h -= 110;
    }
    if (v.kind == 1 && v.mode.equals("Volume"))
      v.gl.volumeAppearance(v.volumeMin, v.volumeMax, v.volumeOpacity, v.threshold, v.volumeSteps);
    if (!v.image)
      v.gl.render(
          (int) w, (int) h, v.camera.matrix(w / h, v.origin), state.user.visualization.unlit, 1);
    float px = ImGui.getCursorScreenPosX(), py = ImGui.getCursorScreenPosY();
    ImGui.image(v.gl.texture(), w, h, 0, v.image ? 0 : 1, 1, v.image ? 1 : 0);
    boolean hovered = ImGui.isItemHovered();
    if (v.kind == 0 && state.user.visualization.grid) {
      var draw = ImGui.getWindowDrawList();
      draw.pushClipRect(px, py, px + w, py + h, true);
      double extent = 256 * v.step,
          spacing = Math.pow(10, Math.floor(Math.log10(Math.max(.00001, extent / 12)))),
          minX = v.panX - extent / 2,
          minZ = v.panZ - extent / 2;
      for (double x = Math.ceil(minX / spacing) * spacing; x < minX + extent; x += spacing) {
        float sx = (float) (px + (x - minX) / extent * w);
        draw.addLine(sx, py, sx, py + h, 0x33434d5e);
      }
      for (double z = Math.ceil(minZ / spacing) * spacing; z < minZ + extent; z += spacing) {
        float sz = (float) (py + (z - minZ) / extent * h);
        draw.addLine(px, sz, px + w, sz, 0x33434d5e);
      }
      draw.popClipRect();
    }

    var io = ImGui.getIO();
    if (hovered && ImGui.beginPopupContextItem("viewport-context")) {
      if (v.kind == 0) {
        for (String mode : List.of("Grayscale", "Heatmap", "Contour", "Threshold", "Histogram"))
          if (ImGui.menuItem(mode)) {
            v.mode = mode;
            mark(v);
          }
      }
      if (ImGui.menuItem("Teleport")) openTeleport(v);
      if (ImGui.menuItem("Add Bookmark")) openBookmark(v);
      if (ImGui.menuItem("Regenerate")) {
        v.force = true;
        v.rebuildSerial++;
        mark(v);
      }
      ImGui.endPopup();
    }
    if (hovered) {
      if (v.kind == 0) {
        if (ImGui.isMouseDragging(0)) {
          v.panX -= io.getMouseDeltaX() * v.step * 256 / w;
          v.panZ -= io.getMouseDeltaY() * v.step * 256 / h;
          mark(v);
        }
        if (io.getMouseWheel() != 0) {
          v.step = Math.max(.0001, Math.min(10000, v.step * Math.exp(-io.getMouseWheel() * .15)));
          mark(v);
        }
        double x = v.panX + (io.getMousePosX() - px - w / 2) / w * 256 * v.step,
            z = v.panZ + (io.getMousePosY() - py - h / 2) / h * 256 * v.step;
        String sample = "";
        try {
          var n = state.project().noise2D.stream().filter(a -> a.uuid.equals(v.noise)).findFirst();
          if (n.isPresent()) {
            double raw = v.hoverSampler.sample(x, 0, z);
            sample =
                String.format(
                    Locale.ROOT,
                    "X %.5f  Z %.5f   Raw %.8f   Display %.5f",
                    x,
                    z,
                    raw,
                    Math.max(0, Math.min(1, (raw + 1) / 2)));
          }
        } catch (Exception ignored) {
        }
        ImGui.textDisabled(sample);
      } else if (v.kind == 1 && v.mode.equals("Slices")) {
        if (ImGui.isMouseDragging(0) || ImGui.isMouseDragging(2)) {
          double du = -io.getMouseDeltaX() / w * v.size * v.step,
              dv = -io.getMouseDeltaY() / h * v.size * v.step;
          if (v.slicePlane == SlicePlane.X) {
            v.panY += du;
            v.camera.focus.y += du;
          } else {
            v.panX += du;
            v.camera.focus.x += du;
          }
          if (v.slicePlane == SlicePlane.Z) {
            v.panY += dv;
            v.camera.focus.y += dv;
          } else {
            v.panZ += dv;
            v.camera.focus.z += dv;
          }
          mark(v);
        }
        if (io.getMouseWheel() != 0) {
          v.step = Math.max(.0001, Math.min(10000, v.step * Math.exp(-io.getMouseWheel() * .15)));
          mark(v);
        }
        if (v.hoverSampler != null && v.image) {
          SlicePlane plane = v.displayedSlicePlane;
          double
              u =
                  (plane == SlicePlane.X ? v.origin.y : v.origin.x)
                      + (io.getMousePosX() - px) / w * v.size * v.displayedStep,
              vv =
                  (plane == SlicePlane.Z ? v.origin.y : v.origin.z)
                      + (io.getMousePosY() - py) / h * v.size * v.displayedStep;
          double x = plane.x(u, vv, v.displayedSlice),
              y = plane.y(u, vv, v.displayedSlice),
              z = plane.z(u, vv, v.displayedSlice);
          try {
            ImGui.textDisabled(
                String.format(
                    Locale.ROOT,
                    "X %.5f Y %.5f Z %.5f  Raw %.8f",
                    x,
                    y,
                    z,
                    v.hoverSampler.sample(x, y, z)));
          } catch (RuntimeException failure) {
            ImGui.textDisabled("Sample failed: " + failure.getMessage());
          }
        }
      } else {
        if (!v.camera.flying) {
          if (ImGui.isMouseDragging(0) && !io.getKeyCtrl())
            v.camera.orbit(io.getMouseDeltaX(), io.getMouseDeltaY());
          if (ImGui.isMouseDragging(1)) v.camera.pan(io.getMouseDeltaX(), io.getMouseDeltaY());
          if (ImGui.isMouseDragging(2)) {
            if (io.getKeyCtrl()) v.camera.focus.y -= io.getMouseDeltaY() * v.camera.distance * .002;
            else {
              v.camera.focus.x -= io.getMouseDeltaX() * v.camera.distance * .002;
              v.camera.focus.z -= io.getMouseDeltaY() * v.camera.distance * .002;
            }
          }
          v.camera.zoom(io.getMouseWheel());
          if (ImGui.isMouseClicked(0) && io.getKeyCtrl()) {
            v.camera.enterFly();
            glfwSetInputMode(handle, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
          } else if (v.kind == 2
              && ImGui.isMouseReleased(0)
              && Math.abs(ImGui.getMouseDragDeltaX(0)) < 3
              && Math.abs(ImGui.getMouseDragDeltaY(0)) < 3)
            pick(
                v,
                (io.getMousePosX() - px) / w * 2 - 1,
                1 - (io.getMousePosY() - py) / h * 2,
                w / h);
        }
      }
    }
    if (v.camera.flying) {
      if (ImGui.isKeyPressed(ImGuiKey.Escape)
          || !glfwGetWindowAttribBoolean(handle, GLFW_FOCUSED)) {
        release(v);
      } else {
        v.camera.orbit(io.getMouseDeltaX(), io.getMouseDeltaY());
        v.camera.move(
            (ImGui.isKeyDown(ImGuiKey.W) ? 1 : 0) - (ImGui.isKeyDown(ImGuiKey.S) ? 1 : 0),
            (ImGui.isKeyDown(ImGuiKey.D) ? 1 : 0) - (ImGui.isKeyDown(ImGuiKey.A) ? 1 : 0),
            (ImGui.isKeyDown(ImGuiKey.Space) ? 1 : 0) - (io.getKeyShift() ? 1 : 0),
            io.getDeltaTime());
        if (v.kind == 2 && ImGui.isMouseClicked(0)) {
          release(v);
          pick(v, 0, 0, w / h);
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
        if (!wantsStreaming(v)) mark(v);
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

  private boolean wantsStreaming(View v) {
    return state.user.visualization.streaming
        && !v.fixed
        && (v.kind == 2
            || v.kind == 1 && (v.mode.equals("Threshold Voxels") || v.mode.equals("Point Cloud")));
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
    if (v.compiler != null) {
      v.compileId++;
      v.compiler.cancel(true);
      v.compiler = null;
    }
    var setup = v.setup.getAndSet(null);
    if (setup != null && setup.source != null) setup.source.close();
    if (v.scene != null) {
      v.scene.close();
      v.scene = null;
    }
    v.requestedKey = "";
  }

  private boolean stream(View v) {
    if (v.kind == 1 && v.noise == null) return true;
    String key = sceneKey(v);
    if (key.equals(v.failedKey)) return false;
    long now = System.nanoTime();
    if ((v.scene == null || !v.activeSceneKey.equals(key))
        && !v.requestedKey.equals(key)
        && (state.user.editor.autoRegenerate || v.force || v.lastSubmit == 0)
        && now - v.lastSubmit > 1_000_000_000L / state.user.editor.opportunitiesPerSecond) {
      if (v.pressure.acceptExpensive() && !v.pressure.paused()) {
        if (v.compiler != null) v.compiler.cancel(true);
        long id = ++v.compileId;
        var snapshot = ProjectIO.copy(state.project());
        int kind = v.kind;
        UUID noise = v.noise, stage = v.stage;
        double threshold = v.threshold;
        String mode = v.stageMode;
        v.requestedKey = key;
        v.lastSubmit = now;
        try {
          v.compiler =
              state.background.submit(
                  () -> {
                    SceneSetup result;
                    try {
                      var source =
                          PreviewSources.create(
                              snapshot, state.engine, kind, noise, threshold, mode, stage);
                      result = new SceneSetup(id, key, source, palette(snapshot, kind), null);
                    } catch (Throwable failure) {
                      result =
                          new SceneSetup(id, key, null, null, String.valueOf(failure.getMessage()));
                    }
                    SceneSetup ready = result;
                    state.uiQueue.offer(
                        () -> {
                          if (id != v.compileId) {
                            if (ready.source != null) ready.source.close();
                            return;
                          }
                          var old = v.setup.getAndSet(ready);
                          if (old != null && old.source != null) old.source.close();
                        });
                  });
          v.message = "Compiling streaming source...";
        } catch (RejectedExecutionException full) {
          v.requestedKey = "";
          v.message = "Background queue full; retrying when capacity returns";
        }
      } else v.message = v.pressure.message();
    }
    SceneSetup setup = v.setup.getAndSet(null);
    if (setup != null) {
      v.compiler = null;
      if (!setup.key.equals(key)) {
        if (setup.source != null) setup.source.close();
        v.requestedKey = "";
      } else if (setup.error != null) {
        v.failedKey = key;
        v.requestedKey = "";
        v.dirty = true;
        state.error(
            "STREAM_SOURCE_FAILED",
            EditorEvents.Subsystem.RENDERING,
            "Streaming source failed: " + setup.error);
        return false;
      } else {
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
          v.gl.scene(v.scene.epoch(), state.user.performance.sceneGpuMiB << 20);
          v.gpuBudget = state.user.performance.sceneGpuMiB;
          v.gl.setPalette(setup.palette);
        } catch (RuntimeException gpu) {
          v.scene.close();
          v.scene = null;
          v.gl.sceneDisabled();
          v.failedKey = key;
          v.dirty = true;
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
        boolean resized = v.gpuBudget != state.user.performance.sceneGpuMiB;
        var gpu = v.gl.scene(v.scene.epoch(), state.user.performance.sceneGpuMiB << 20);
        if (resized) {
          v.scene.invalidateUploads();
          v.gpuBudget = state.user.performance.sceneGpuMiB;
        }
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
            gpu.statistics().gpuBytes()
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
        v.scene.close();
        v.scene = null;
        v.gl.sceneDisabled();
        v.failedKey = key;
        v.dirty = true;
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

  private static boolean glfwGetWindowAttribBoolean(long h, int key) {
    return glfwGetWindowAttrib(h, key) != 0;
  }

  private void release(View v) {
    v.camera.leaveFly();
    glfwSetInputMode(handle, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
  }

  private void publish(View v, Preview result) {
    v.ready.accumulateAndGet(
        result,
        (previous, next) -> previous == null || next.epoch >= previous.epoch ? next : previous);
  }

  private void mark(View v) {
    v.dirty = true;
    v.failedPreview = false;
    v.epoch++;
    if (v.job != null) v.job.cancel(true);
    state.background.purge();
    v.activeJob = false;
  }

  private void submit(View v) {
    int n = v.kind == 0 ? 256 : v.size;
    if ((long) n * n * (v.kind == 0 ? 1 : n) > state.user.performance.previewCellBudget
        || n > state.user.performance.maxPreviewDimension && v.kind != 0) {
      v.message = "Preview exceeds configured budget; reduce extent";
      paused = true;
      return;
    }
    long working =
        (v.kind == 0
            ? 64L * n * n
            : v.mode.equals("Volume")
                ? 12L * n * n * n
                : v.mode.equals("Point Cloud") ? 96L * n * n * n : 3024L * n * n * n);
    if (working > ((long) state.user.performance.sceneCpuMiB << 20)) {
      v.message = "Preview exceeds working-memory budget; reduce extent or raise CPU MiB";
      paused = true;
      return;
    }
    var snapshot = ProjectIO.copy(state.project());
    long epoch = v.epoch;
    int kind = v.kind;
    UUID noise = v.noise, stageId = v.stage;
    String stageMode = v.stageMode;
    String mode = v.mode;
    double panX = v.panX, panZ = v.panZ, step = v.step, threshold = v.threshold;
    int slice = v.slice;
    SlicePlane slicePlane = v.slicePlane;
    double centerY = v.fixed ? v.panY : v.camera.flying ? v.camera.position.y : v.camera.focus.y;
    boolean fixed = v.fixed;
    final long cacheBudget = (long) state.user.performance.cacheMiB * 1024 * 1024;
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
                    for (int z = 0; z < side; z++) {
                      if (Thread.currentThread().isInterrupted()) return;
                      for (int x = 0; x < side; x++) {
                        double wx = kind == 0 ? panX + (x - side / 2.) * step : origin.x + x,
                            wz = kind == 0 ? panZ + (z - side / 2.) * step : origin.z + z;
                        double u = (slicePlane == SlicePlane.X ? origin.y : origin.x) + x * step,
                            vv = (slicePlane == SlicePlane.Z ? origin.y : origin.z) + z * step;
                        double value =
                            kind == 0
                                ? sampler.sample(wx, 0, wz)
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
                            epoch, null, pixels, raw, side, origin, palette, histogram, sampler,
                            null));
                  } else if (kind == 1 && mode.equals("Volume")) {
                    var sampler = new NoiseCompiler(snapshot, snapshot.project.seed).compile(noise);
                    var volume = VolumeData.sample(n, origin.x, origin.y, origin.z, sampler);
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
                    int ox = (int) origin.x, oy = (int) origin.y, oz = (int) origin.z;
                    int[] cells = new int[n * n * n];
                    Mesh mesh;
                    if (kind == 1) {
                      var sampler = compiled.noise3D(noise, snapshot.project.seed);
                      if (mode.equals("Isosurface")) {
                        mesh =
                            MeshBuilder.isosurface(
                                n, (x, y, z) -> sampler.sample(ox + x, oy + y, oz + z), threshold);
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
                            0,
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
      paused = true;
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

  private List<Bookmark> list(ProjectData p, View v) {
    return v.kind == 0
        ? p.bookmarks.noise2D
        : v.kind == 1 ? p.bookmarks.noise3D : p.bookmarks.world;
  }

  private void bookmarks(View v) {
    if (ImGui.beginCombo(
        "Bookmark",
        list(state.project(), v).stream()
            .filter(b -> b.uuid.equals(v.selectedBookmark))
            .map(b -> b.name)
            .findFirst()
            .orElse("Choose coordinates..."))) {
      for (var b : list(state.project(), v))
        if (ImGui.selectable(b.name)) {
          v.selectedBookmark = b.uuid;
          teleport(v, b.x, b.y == null ? 0 : b.y, b.z);
        }
      ImGui.endCombo();
    }
    if (ImGui.smallButton("Add Bookmark")) openBookmark(v);
    ImGui.sameLine();
    if (ImGui.smallButton("Teleport")) openTeleport(v);
    if (v.selectedBookmark != null) {
      ImGui.sameLine();
      if (ImGui.smallButton("Update Coordinates")) {
        Vector3d center = center(v);
        UUID id = v.selectedBookmark;
        state.change(
            p ->
                list(p, v).stream()
                    .filter(b -> b.uuid.equals(id))
                    .findFirst()
                    .ifPresent(
                        b -> {
                          b.x = (int) center.x;
                          b.z = (int) center.z;
                          if (v.kind > 0) b.y = (int) center.y;
                        }));
      }
      ImGui.sameLine();
      if (ImGui.smallButton("Delete Bookmark")) {
        UUID id = v.selectedBookmark;
        state.change(p -> list(p, v).removeIf(b -> b.uuid.equals(id)));
        v.selectedBookmark = null;
      }
    }
  }

  private Vector3d center(View v) {
    return v.kind == 0
        ? new Vector3d(v.panX, 0, v.panZ)
        : v.camera.flying ? new Vector3d(v.camera.position) : new Vector3d(v.camera.focus);
  }

  private void openBookmark(View v) {
    bookmarkAction = "add";
    bookmarkName.set("");
    Vector3d c = center(v);
    teleport[0] = (int) c.x;
    teleport[1] = (int) c.y;
    teleport[2] = (int) c.z;
    coordinatesPending = true;
  }

  private void openTeleport(View v) {
    bookmarkAction = "teleport";
    Vector3d c = center(v);
    teleport[0] = (int) c.x;
    teleport[1] = (int) c.y;
    teleport[2] = (int) c.z;
    coordinatesPending = true;
  }

  private void drawBookmarkDialogs() {
    if (coordinatesPending) {
      ImGui.openPopup("Coordinates");
      coordinatesPending = false;
    }
    if (ImGui.beginPopupModal("Coordinates", ImGuiWindowFlags.AlwaysAutoResize)) {
      View v = views[active];
      if (bookmarkAction.equals("add")) ImGui.inputText("Name", bookmarkName);
      fields.integer("X", teleport[0], x -> teleport[0] = x);
      if (v.kind > 0) fields.integer("Y", teleport[1], x -> teleport[1] = x);
      fields.integer("Z", teleport[2], x -> teleport[2] = x);
      if (ImGui.button("Apply")) {
        if (bookmarkAction.equals("add")) {
          var b = new Bookmark();
          b.name = bookmarkName.get();
          b.x = teleport[0];
          b.z = teleport[2];
          if (v.kind > 0) b.y = teleport[1];
          if (state.change(p -> list(p, v).add(b))) ImGui.closeCurrentPopup();
        } else {
          teleport(v, teleport[0], teleport[1], teleport[2]);
          ImGui.closeCurrentPopup();
        }
      }
      ImGui.sameLine();
      if (ImGui.button("Cancel")) ImGui.closeCurrentPopup();
      ImGui.endPopup();
    }
  }

  private void teleport(View v, int x, int y, int z) {
    v.panX = x;
    v.panZ = z;
    v.camera.teleport(x, y, z);
    mark(v);
  }

  private void pick(View v, float nx, float ny, float aspect) {
    if (wantsStreaming(v)
        ? v.scene == null
            || !v.activeSceneKey.equals(sceneKey(v))
            || v.gl.sceneRenderer() == null
            || !v.gl.sceneRenderer().hasData()
        : v.dirty || v.activeJob || v.failedPreview) {
      state.status =
          "Preview is rebuilding or outdated; use Inspect Coordinate or Regenerate before picking";
      return;
    }
    var ray = v.camera.ray(nx, ny, aspect, v.origin);
    ProjectData p = ProjectIO.copy(state.project());
    long request = ++traceSerial, revision = state.revision;
    UUID selectedStage = v.stage;
    String selectedMode = v.stageMode;
    try {
      state.background.execute(
          () -> {
            try (var world =
                state.engine.openWorld(
                    new CompiledProject(p),
                    new WorldOptions(null, 32, WorldOptions.CachePolicy.NO_CACHE, 0))) {
              GenerationStage boundary =
                  selectedMode.equals("Final") || selectedStage == null
                      ? null
                      : world.stages().stream()
                          .filter(stage -> stage.uuid().equals(selectedStage))
                          .findFirst()
                          .orElse(null);
              var hit =
                  io.voxelcreator.render.VoxelRaycast.firstOccupied(
                      ray,
                      1000,
                      (x, y, z) -> {
                        var position = new BlockPos(x, y, z);
                        if (boundary == null) return world.sampleStage(position, Integer.MAX_VALUE);
                        int before = world.sampleStage(position, boundary.before());
                        if (selectedMode.equals("Before")) return before;
                        int after = world.sampleStage(position, boundary.after());
                        return selectedMode.equals("Only Changes")
                            ? (before == after ? 0 : after > 0 ? after : before)
                            : after;
                      });
              if (hit.isPresent()) {
                BlockPos cell = hit.get();
                GenerationTrace found = world.trace(cell);
                state.uiQueue.offer(
                    () -> {
                      if (request != traceSerial || revision != state.revision) return;
                      acceptTrace(found, p, revision);
                      inspect[0] = cell.x();
                      inspect[1] = cell.y();
                      inspect[2] = cell.z();
                      inspector.set(true);
                    });
                return;
              }
              state.uiQueue.offer(
                  () -> {
                    if (request != traceSerial || revision != state.revision) return;
                    inspector.set(true);
                    state.status = "No occupied voxel hit; enter a coordinate to inspect EMPTY";
                  });
            } catch (Exception e) {
              state.uiQueue.offer(() -> state.notify("Error", e.getMessage()));
            }
          });
    } catch (RejectedExecutionException e) {
      state.notify("Warning", "Inspector queue is full");
    }
  }

  private void acceptTrace(GenerationTrace result, ProjectData snapshot, long revision) {
    trace = result;
    traceRevision = revision;
    var names = new HashMap<Integer, String>();
    var types = new HashMap<Integer, UUID>();
    names.put(0, "EMPTY");
    for (int i = 0; i < snapshot.voxelTypes.size(); i++) {
      names.put(i + 1, snapshot.voxelTypes.get(i).id);
      types.put(i + 1, snapshot.voxelTypes.get(i).uuid);
    }
    traceNames = Map.copyOf(names);
    traceTypes = Map.copyOf(types);
  }

  private static String outcome(Boolean value) {
    return value == null ? "not reached" : value ? "passed" : "rejected";
  }

  private void drawInspector() {
    if (!inspector.get()) return;
    ImGui.setNextWindowSize(600, 600, ImGuiCond.FirstUseEver);
    ImGui.begin("Voxel Inspector", inspector);
    ImGui.inputInt3("XYZ", inspect);
    if (ImGui.button("Trace Coordinate")) {
      var p = ProjectIO.copy(state.project());
      long request = ++traceSerial, revision = state.revision;
      BlockPos pos = new BlockPos(inspect[0], inspect[1], inspect[2]);
      try {
        state.background.execute(
            () -> {
              try (var world = state.engine.openWorld(new CompiledProject(p))) {
                var result = world.trace(pos);
                state.uiQueue.offer(
                    () -> {
                      if (request == traceSerial && revision == state.revision)
                        acceptTrace(result, p, revision);
                    });
              } catch (Exception e) {
                state.uiQueue.offer(() -> state.notify("Error", e.getMessage()));
              }
            });
      } catch (RejectedExecutionException e) {
        state.notify("Warning", "Inspector queue is full");
      }
    }
    if (trace != null) {
      ImGui.text("Coordinate: " + trace.position());
      String semantic = traceNames.getOrDefault(trace.finalType(), "Unknown snapshot type");
      ImGui.text("Final: " + semantic + "  Runtime ID " + trace.finalType());
      if (traceRevision != state.revision)
        ImGui.textWrapped(
            "! The project changed after this trace. Retrace to inspect current generation.");
      var deciding =
          trace.steps().stream()
              .filter(step -> !step.action().equals("NO_CHANGE"))
              .reduce((a, b) -> b);
      deciding.ifPresent(
          step ->
              ImGui.textWrapped(
                  "Last deciding stage: "
                      + step.layerId()
                      + " / "
                      + step.operationId()
                      + " ("
                      + step.action()
                      + ")"));
      UUID finalType = traceTypes.get(trace.finalType());
      if (finalType != null && ImGui.button("Go To Voxel Type")) state.select("voxel", finalType);
      for (var step : trace.steps())
        if (ImGui.treeNode(step.layerId() + " / " + step.operationId() + "  " + step.action())) {
          ImGui.text("Field: " + (Double.isNaN(step.field()) ? "not sampled" : step.field()));
          ImGui.text(
              "Previous: "
                  + traceNames.getOrDefault(step.previous(), "?")
                  + " -> "
                  + traceNames.getOrDefault(step.result(), "?"));
          if (step.details() != null) {
            var details = step.details();
            ImGui.text(
                "Selected: "
                    + details.selected()
                    + " | Gate: "
                    + outcome(details.gatePassed())
                    + " | Previous-state predicate: "
                    + outcome(details.predicatePassed()));
            ImGui.text(
                "Probability "
                    + details.probability()
                    + "; deterministic draw "
                    + details.random());
            if (details.sampleCoordinates() != null)
              ImGui.textWrapped("Sample coordinates: " + details.sampleCoordinates());
            if (details.matchedInterval() != null)
              ImGui.text(
                  "Type interval "
                      + (details.matchedInterval() + 1)
                      + " -> "
                      + traceNames.getOrDefault(details.candidateType(), "Unknown snapshot type"));
            for (var driver : details.drivers())
              ImGui.textWrapped(
                  driver.property()
                      + " driver: sample="
                      + driver.sample()
                      + ", mapped="
                      + driver.mapped()
                      + ", constant="
                      + driver.constant()
                      + ", effective="
                      + driver.effective());
            for (var parameter : details.parameters())
              ImGui.textWrapped(
                  "Parameter row "
                      + (parameter.row() + 1)
                      + " "
                      + AppMetadata.settingLabel(parameter.destination())
                      + " "
                      + parameter.combination()
                      + ": sample="
                      + parameter.sample()
                      + ", mapped="
                      + parameter.mapped()
                      + ", previous="
                      + parameter.previous()
                      + ", effective="
                      + parameter.effective());
            if (ImGui.treeNode("Effective values")) {
              details
                  .effectiveSettings()
                  .forEach((k, value) -> ImGui.textWrapped(k + " = " + value));
              ImGui.treePop();
            }
            if (ImGui.treeNode("Field gates (executed branches)")) {
              for (var gate : details.gates())
                ImGui.textWrapped(
                    gate.path()
                        + " "
                        + gate.kind()
                        + " value="
                        + (gate.value() == null ? "not sampled" : gate.value())
                        + " passed="
                        + gate.passed()
                        + (gate.random() == null ? "" : " draw=" + gate.random()));
              ImGui.treePop();
            }
          }
          ImGui.textWrapped(step.reason());
          if (ImGui.button("Go To Layer")) state.select("layer", step.layerUuid());
          ImGui.sameLine();
          if (ImGui.button("Go To Operation")) {
            state.select("operation", step.operationUuid());
          }
          ImGui.sameLine();
          if (ImGui.button("Go To Resource")) {
            var resource = EditorState.find(state.project(), step.noiseUuid());
            if (resource instanceof Noise n) state.select("noise" + n.dimension + "D", n.uuid);
          }
          if (ImGui.treeNode("Resolved setting sources")) {
            step.settingsSources().forEach((k, value) -> ImGui.textWrapped(k + ": " + value));
            ImGui.treePop();
          }
          ImGui.treePop();
        }
    }
    ImGui.end();
  }

  @Override
  public void close() {
    for (var v : views) {
      if (v.job != null) v.job.cancel(true);
      state.background.purge();
      stopStream(v);
      v.gl.close();
    }
  }
}
