package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.lwjgl.opengl.GL;

/** Production viewport controls and real bounded editor queue, with a native GL/ImGui context. */
public final class PreviewAdmissionProbe {
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
      window = glfwCreateWindow(64, 64, "Admission probe", 0, 0);
      require(window != 0, "GL context");
      glfwMakeContextCurrent(window);
      GL.createCapabilities();
      ImGui.createContext();
      try {
        var io = ImGui.getIO();
        io.setIniFilename(null);
        io.setDisplaySize(1200, 900);
        io.setDeltaTime(1f / 60);
        io.getFonts().addFontDefault();
        io.getFonts().build();
        try (var state = new EditorState(new UserConfig(), false)) {
          state.user.editor.opportunitiesPerSecond = 20;
          require(
              state.change(
                  p -> {
                    var n = new Noise();
                    n.id = "admission";
                    n.algorithm.key = "CONSTANT";
                    n.algorithm.parameters.put("value", .25);
                    p.noise2D.add(n);
                  }),
              "Fixture rejected");
          var authored = ProjectIO.JSON.valueToTree(state.project());
          long revision = state.revision, history = state.document.historyBytes();
          try (var viewport = new Viewports(state, new PropertyPanel(state), window)) {
            views = viewport;
            views.probeAdmissionSource(state.project().noise2D.getFirst().uuid);
            until(views::probeAdmissionReady);
            var initial = views.probeAcceptedImage();
            require(initial != null, "No initial image");
            state.user.performance.previewCellBudget = 65535;
            click("toolbar", 0);
            until(views::probeAdmissionBlocked);
            require(views.probeAcceptedImage() == initial, "Budget rejection discarded image");
            require(!views.probeOtherAdmissionBlocked(), "Budget rejection blocked other views");
            String budgetMessage = views.probeAdmissionMessage();
            state.user.performance.previewCellBudget = 65536;
            frames(4);
            require(views.probeAdmissionBlocked(), "Budget change silently retried");
            contextRetry();
            until(views::probeAdmissionReady);
            var afterBudget = views.probeAcceptedImage();
            require(afterBudget != initial, "Context retry failed to publish");
            var release = new CountDownLatch(1);
            var entered = new CountDownLatch(2);
            var jobs = new ArrayList<Future<?>>();
            try {
              for (int i = 0; i < 2; i++)
                jobs.add(
                    state.background.submit(
                        () -> {
                          entered.countDown();
                          try {
                            if (!release.await(20, TimeUnit.SECONDS))
                              throw new AssertionError("Worker timeout");
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
              require(entered.await(5, TimeUnit.SECONDS), "Workers failed to occupy queue");
              for (int i = 0; i < 8; i++) jobs.add(state.background.submit(() -> {}));
              require(state.background.getQueue().remainingCapacity() == 0, "Queue not saturated");
              click("toolbar", 0);
              until(views::probeAdmissionBlocked);
              require(
                  views.probeAdmissionMessage().contains("queue is full"),
                  "Queue rejection not observed");
              require(views.probeAcceptedImage() == afterBudget, "Queue rejection discarded image");
              require(!views.probeOtherAdmissionBlocked(), "Queue rejection blocked other views");
            } finally {
              release.countDown();
              for (var job : jobs) job.get(5, TimeUnit.SECONDS);
            }
            String queueMessage = views.probeAdmissionMessage();
            contextRetry();
            until(views::probeAdmissionReady);
            var afterQueue = views.probeAcceptedImage();
            require(afterQueue != afterBudget, "Queue retry failed to publish");
            // Exercise toolbar recovery after a second independent budget rejection.
            state.user.performance.previewCellBudget = 65535;
            contextRetry();
            until(views::probeAdmissionBlocked);
            require(views.probeAcceptedImage() == afterQueue, "Second failure discarded image");
            state.user.performance.previewCellBudget = 65536;
            click("toolbar", 0);
            until(views::probeAdmissionReady);
            require(views.probeAcceptedImage() != afterQueue, "Toolbar retry failed to publish");
            require(
                authored.equals(ProjectIO.JSON.valueToTree(state.project()))
                    && revision == state.revision
                    && history == state.document.historyBytes(),
                "Preview controls changed authored state");
            double raw = views.probeAcceptedImage().sample(.5, .5).raw();
            require(raw == .25, "Retry changed field result");
            Files.writeString(
                out.resolve("preview-admission-native.json"),
                ProjectIO.JSON.writeValueAsString(
                    Map.ofEntries(
                        Map.entry("formatVersion", 1),
                        Map.entry("budgetMessage", budgetMessage),
                        Map.entry("queueMessage", queueMessage),
                        Map.entry("workers", 2),
                        Map.entry("queueCapacity", 8),
                        Map.entry("retainedImage", true),
                        Map.entry("contextRetry", true),
                        Map.entry("toolbarRetry", true),
                        Map.entry("otherViewsUnblocked", true),
                        Map.entry("viewOnly", true),
                        Map.entry("raw", raw))));
            System.out.println(
                "PREVIEW_ADMISSION_NATIVE_PASS budget / real full queue / retained image / context"
                    + " retry / toolbar retry / view-only");
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

  private static void contextRetry() {
    click("image", 1);
    frames(2);
    click("context", 0);
  }

  private static void click(String name, int button) {
    var p = views.probeAdmissionControl(name);
    require(p != null, "Missing control " + name);
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0], p[1]);
    frame();
    io.addMouseButtonEvent(button, true);
    frame();
    io.addMouseButtonEvent(button, false);
    frame();
  }

  private static void until(BooleanSupplier ready) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!ready.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end)
        throw new AssertionError("Preview timeout: " + views.probeAdmissionMessage());
      Thread.yield();
    }
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(950, 750, ImGuiCond.Always);
    views.draw();
    ImGui.render();
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
