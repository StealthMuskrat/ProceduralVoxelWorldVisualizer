package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/** Deferred real shader rejection, independent tab startup and production Retry Renderer input. */
public final class RendererRecoveryProbe {
  private static EditorState state;
  private static Viewports views;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    System.setProperty("joml.nounsafe", "true");
    if (!glfwInit()) throw new IllegalStateException("GLFW init");
    long window = 0;
    try {
      glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
      glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
      window = glfwCreateWindow(64, 64, "Renderer recovery", 0, 0);
      require(window != 0, "GL context");
      glfwMakeContextCurrent(window);
      GL.createCapabilities();
      ImGui.createContext();
      try {
        ImGui.getIO().setIniFilename(null);
        ImGui.getIO().setDisplaySize(1200, 900);
        ImGui.getIO().setDeltaTime(1f / 60);
        UiFontAtlas.install();
        try (var editor = new EditorState(new UserConfig(), false)) {
          state = editor;
          state.user.editor.autoRegenerate = false;
          state.user.editor.opportunitiesPerSecond = 20;
          state.user.visualization.streaming = false;
          state.user.visualization.unlit = true;
          state.user.visualization.grid = false;
          require(state.change(RendererRecoveryProbe::fixture), "Fixture rejected");
          state.document.save(out.resolve("source.json"));
          state.user.save(out.resolve("preferences-before.json"));
          state.select("noise2D", state.project().noise2D.getFirst().uuid);
          ((Noise) state.draft).id = "private draft";
          state.seedInputs.edit(state, "seedOffset", "Seed", "-");
          var draft = state.draft;
          var privateValue = ProjectIO.JSON.valueToTree(draft);
          var canonical = ProjectIO.JSON.valueToTree(state.project());
          long revision = state.revision, history = state.document.historyBytes();
          int[] calls = new int[3];
          var phases = new ArrayList<Map<String, Object>>();
          try (var viewport =
              new Viewports(
                  state,
                  new PropertyPanel(state),
                  window,
                  kind -> {
                    calls[kind]++;
                    if (kind == 1 && calls[kind] <= 2) {
                      GlPrograms.link(
                          "Controlled 3D startup",
                          "#version 130\nthis_is_not_valid_glsl",
                          "#version 130\nvoid main(){}");
                      throw new AssertionError("Invalid shader unexpectedly compiled");
                    }
                    return new GlViewport();
                  })) {
            views = viewport;
            record(phases, "startup");
            require(!views.rendererStatus().get(1).ready(), "Failed tab published a renderer");
            views.probeSlice(state.project().noise3D.getFirst().uuid, SlicePlane.Y, 8, 0, 0, 0, 1);
            frames(12);
            record(phases, "stable-failure");
            require(
                calls[1] == 1 && views.probeSubmissionStamp(1) == 0,
                "Failed tab retried or scheduled work automatically");
            views.collectGpuTimings();
            views.streamingStatistics();
            views.shadowStatistics();
            views.gpuTimingStatistics();
            require(views.compactArenas() == 0, "Unavailable tab acquired an arena");
            state.document.save(out.resolve("saved-while-unavailable.json"));
            views.probeAdmissionSource(state.project().noise2D.getFirst().uuid);
            views.probeNoiseTab(0);
            until(views::probeAdmissionReady);
            var accepted = views.probeAcceptedImage();
            require(accepted.sample(.5, .5).raw() == .125, "Healthy 2D output changed");
            long imageStamp = views.probeSubmissionStamp(0);
            views.probeWorld(null, "Final", 8, .25);
            until(views::probeWorldReady);
            record(phases, "healthy-tabs");
            views.probeSliceTab();
            frames(4);
            clickRetry();
            require(
                calls[1] == 2 && !views.rendererStatus().get(1).ready(),
                "Failed explicit retry was not retained");
            record(phases, "retry-failed");
            clickRetry();
            until(() -> views.probeNoiseReady(1));
            require(
                calls[1] == 3 && views.rendererStatus().get(1).ready(),
                "Explicit retry did not recover the tab");
            require(
                Arrays.stream(views.probeSliceRaw()).allMatch(value -> value == .25),
                "Recovered slice changed its canonical field");
            require(
                accepted == views.probeAcceptedImage()
                    && imageStamp == views.probeSubmissionStamp(0),
                "Retry replaced a healthy tab's accepted display");
            record(phases, "recovered");
            require(
                calls[0] == 1 && calls[2] == 1,
                "Healthy tabs restarted during another tab's retry");
            require(
                state.draft == draft
                    && privateValue.equals(ProjectIO.JSON.valueToTree(draft))
                    && "-".equals(state.seedInputs.text("seedOffset")),
                "Renderer recovery changed private input");
            require(
                canonical.equals(ProjectIO.JSON.valueToTree(state.project()))
                    && state.revision == revision
                    && state.document.historyBytes() == history,
                "Renderer recovery changed the document/history");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Renderer recovery left GL errors");
            long errors =
                state.events.snapshot().stream()
                    .filter(e -> e.code().equals("VIEWPORT_RENDERER_FAILED"))
                    .mapToLong(EditorEvents.Entry::count)
                    .sum();
            require(errors == 2, "Renderer errors flooded or disappeared");
            new ProjectIO().save(out.resolve("after.json"), state.project());
            state.user.save(out.resolve("preferences.json"));
            Files.writeString(
                out.resolve("renderer-recovery-native.json"),
                ProjectIO.JSON.writeValueAsString(
                    Map.of(
                        "formatVersion",
                        1,
                        "phases",
                        phases,
                        "factoryCalls",
                        calls,
                        "rendererErrors",
                        errors,
                        "privateRetained",
                        true,
                        "healthyPreviewRetained",
                        true,
                        "physicalDpiVerified",
                        false,
                        "glVersion",
                        GL11.glGetString(GL11.GL_VERSION))));
          }
        }
      } finally {
        ImGui.destroyContext();
      }
    } finally {
      if (window != 0) glfwDestroyWindow(window);
      glfwTerminate();
    }
  }

  private static void fixture(ProjectData p) {
    p.project.minY = -4;
    p.project.maxY = 3;
    for (int dimension : new int[] {2, 3}) {
      var n = new Noise();
      n.id = "constant-" + dimension;
      n.dimension = dimension;
      n.algorithm.key = "CONSTANT";
      n.algorithm.parameters.clear();
      n.algorithm.parameters.put("value", dimension == 2 ? .125 : .25);
      (dimension == 2 ? p.noise2D : p.noise3D).add(n);
    }
    var type = new VoxelType();
    type.id = "solid";
    p.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "world";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    var placement = new Operation();
    placement.id = "fill";
    placement.noiseGeneratorUuid = p.noise3D.getFirst().uuid;
    placement.interpretation = "DENSITY";
    layer.operations.add(placement);
    p.worldGenerator.layers.add(layer);
  }

  private static void record(List<Map<String, Object>> phases, String phase) {
    phases.add(Map.of("phase", phase, "renderers", views.rendererStatus()));
  }

  private static void frame() {
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(950, 750, ImGuiCond.Always);
    views.collectGpuTimings();
    views.draw();
    ImGui.render();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void until(BooleanSupplier ready) {
    long deadline = System.nanoTime() + 30_000_000_000L;
    while (!ready.getAsBoolean()) {
      frame();
      require(System.nanoTime() < deadline, "Preview timeout");
      Thread.yield();
    }
  }

  private static void clickRetry() {
    var point = views.probeAdmissionControl("renderer-retry");
    require(point != null, "Missing Retry Renderer control");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    frames(3);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
