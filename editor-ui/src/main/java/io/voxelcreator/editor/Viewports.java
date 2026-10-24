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
    UUID noise;
    String mode;
    double panX = 0, panZ = 0, panY = 16, step = 1, threshold = 0;
    int size = 32, slice = 0;
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
    boolean image = false, completed = false;

    View(int k) {
      kind = k;
      mode = k == 0 ? "Grayscale" : "Threshold Voxels";
    }
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
      String error) {}

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
    System.out.println("THREE_ENGINE_VIEWPORTS_PASS");
  }

  public void smokeSelections() {
    if (!state.project().noise2D.isEmpty())
      views[0].noise = state.project().noise2D.getFirst().uuid;
    if (!state.project().noise3D.isEmpty())
      views[1].noise = state.project().noise3D.getFirst().uuid;
  }

  public void smokePhase(int frame) {
    if (frame == 0 && !state.project().noise2D.isEmpty())
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
    if (frame == 120 && !state.project().noise3D.isEmpty())
      state.select("noise3D", state.project().noise3D.getFirst().uuid);
    if (frame == 240 && !state.project().worldGenerator.layers.isEmpty())
      state.select("layer", state.project().worldGenerator.layers.getFirst().uuid);
    if (frame < 120) smokeTab = 0;
    else if (frame < 240) smokeTab = 1;
    else smokeTab = 2;
  }

  private void drawView(View v) {
    if (v.revision != state.revision) {
      v.revision = state.revision;
      mark(v);
    }
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
              mark(v);
            });
        if (v.mode.equals("Slices"))
          fields.integer(
              "Slice Y",
              v.slice,
              x -> {
                v.slice = x;
                mark(v);
              });
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
      if (ImGui.button("Inspect Coordinate")) inspector.set(true);
      ImGui.sameLine();
      ImGui.textDisabled("Read-only procedural world");
    }
    Preview ready = v.ready.getAndSet(null);
    if (ready != null && ready.epoch == v.epoch) {
      v.activeJob = false;
      if (ready.error != null) {
        v.message = "Failed; previous display retained";
        state.notify("Error", ready.error);
      } else {
        if (ready.image != null) {
          v.gl.setImage(ready.width, ready.width, ready.image);
          v.image = true;
        } else {
          v.gl.setMesh(ready.mesh);
          v.image = false;
        }
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
    if (!v.image)
      v.gl.render(
          (int) w,
          (int) h,
          v.camera.matrix(w / h, v.origin),
          state.user.visualization.unlit,
          v.mode.equals("Volume") ? .12f : 1);
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
    if (v.kind != 0 && !v.fixed) {
      Vector3d center = v.camera.flying ? v.camera.position : v.camera.focus;
      double cx = Math.floor(center.x / 16) * 16, cz = Math.floor(center.z / 16) * 16;
      double cy = Math.floor(center.y / 16) * 16;
      if (cx != v.panX || cz != v.panZ || cy != v.panY) {
        v.panY = cy;
        v.panX = cx;
        v.panZ = cz;
        mark(v);
      }
    }
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
    var snapshot = ProjectIO.copy(state.project());
    long epoch = v.epoch;
    int kind = v.kind;
    UUID noise = v.noise;
    String mode = v.mode;
    double panX = v.panX, panZ = v.panZ, step = v.step, threshold = v.threshold;
    int slice = v.slice;
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
                try {
                  var compiled = new CompiledProject(snapshot);
                  int[] palette = palette(snapshot, kind);
                  Vector3d origin =
                      new Vector3d(panX - n / 2., Math.floor(centerY) - n / 2., panZ - n / 2.);
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
                        double value = sampler.sample(wx, kind == 0 ? 0 : slice, wz);
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
                            mode.equals("Point Cloud") || mode.equals("Volume")
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
                        for (int y = 0; y < ny; y++)
                          for (int z = 0; z < nz; z++)
                            for (int x = 0; x < nx; x++) {
                              if (Thread.currentThread().isInterrupted()) return;
                              chunks[(y * nz + z) * nx + x] =
                                  world.generateChunk(new ChunkPos(cx + x, cy + y, cz + z));
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
                              cells[(y * n + z) * n + x] =
                                  chunk.getVoxelTypeId(
                                      Math.floorMod(wx, 32),
                                      Math.floorMod(wy, 32),
                                      Math.floorMod(wz, 32));
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
                } catch (Throwable e) {
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
                }
              });
    } catch (RejectedExecutionException e) {
      v.activeJob = false;
      v.message = "Preview queue is full; Regenerate to retry";
      paused = true;
    }
  }

  private static int[] palette(ProjectData p, int kind) {
    int[] palette = new int[Math.max(256, p.voxelTypes.size() + 1)];
    for (int i = 0; i < palette.length; i++) palette[i] = color(i / 127.5 - 1, "Heatmap", 0);
    if (kind == 2)
      for (int i = 0; i < p.voxelTypes.size(); i++) {
        var c = p.voxelTypes.get(i).color;
        palette[i + 1] = rgba(c.r, c.g, c.b);
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
    var ray = v.camera.ray(nx, ny, aspect, v.origin);
    ProjectData p = ProjectIO.copy(state.project());
    try {
      state.background.execute(
          () -> {
            try (var world =
                state.engine.openWorld(
                    new CompiledProject(p),
                    new WorldOptions(null, 32, WorldOptions.CachePolicy.NO_CACHE, 0))) {
              var hit =
                  io.voxelcreator.render.VoxelRaycast.firstOccupied(
                      ray,
                      1000,
                      (x, y, z) -> world.sampleStage(new BlockPos(x, y, z), Integer.MAX_VALUE));
              if (hit.isPresent()) {
                BlockPos cell = hit.get();
                GenerationTrace found = world.trace(cell);
                state.uiQueue.offer(
                    () -> {
                      trace = found;
                      inspect[0] = cell.x();
                      inspect[1] = cell.y();
                      inspect[2] = cell.z();
                      inspector.set(true);
                    });
                return;
              }
              state.uiQueue.offer(
                  () -> {
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

  private void drawInspector() {
    if (!inspector.get()) return;
    ImGui.setNextWindowSize(600, 600, ImGuiCond.FirstUseEver);
    ImGui.begin("Voxel Inspector", inspector);
    ImGui.inputInt3("XYZ", inspect);
    if (ImGui.button("Trace Coordinate")) {
      var p = ProjectIO.copy(state.project());
      BlockPos pos = new BlockPos(inspect[0], inspect[1], inspect[2]);
      try {
        state.background.execute(
            () -> {
              try (var world = state.engine.openWorld(new CompiledProject(p))) {
                var result = world.trace(pos);
                state.uiQueue.offer(() -> trace = result);
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
      String semantic =
          trace.finalType() == 0
              ? "EMPTY"
              : state.project().voxelTypes.size() >= trace.finalType()
                  ? state.project().voxelTypes.get(trace.finalType() - 1).id
                  : "Registry changed; retrace";
      ImGui.text("Final: " + semantic + "  Runtime ID " + trace.finalType());
      for (var step : trace.steps())
        if (ImGui.treeNode(step.layerId() + " / " + step.operationId() + "  " + step.action())) {
          ImGui.text("Field: " + step.field());
          ImGui.text("Previous: " + step.previous() + " -> " + step.result());
          ImGui.textWrapped(step.reason());
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
      v.gl.close();
    }
  }
}
