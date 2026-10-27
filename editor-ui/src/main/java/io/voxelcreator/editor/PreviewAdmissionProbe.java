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
                    for (int i = 0; i < 63; i++) {
                      var option = new Noise();
                      option.id = i == 62 ? "viewport-target" : "option-" + i;
                      option.algorithm.key = "CONSTANT";
                      option.algorithm.parameters.put("value", .75);
                      p.noise2D.add(option);
                    }
                    var other = new Noise();
                    other.id = "viewport-target";
                    other.dimension = 3;
                    other.algorithm.key = "CONSTANT";
                    other.algorithm.parameters.put("value", .5);
                    p.noise3D.add(other);
                    var spatial = new Noise();
                    spatial.id = "spatial-other";
                    spatial.dimension = 3;
                    spatial.algorithm.key = "CONSTANT";
                    spatial.algorithm.parameters.put("value", .5);
                    p.noise3D.add(spatial);
                  }),
              "Fixture rejected");
          var authored = ProjectIO.JSON.valueToTree(state.project());
          long revision = state.revision, history = state.document.historyBytes();
          try (var viewport = new Viewports(state, new PropertyPanel(state), window)) {
            views = viewport;
            views.probeAdmissionSource(state.project().noise2D.getFirst().uuid);
            until(views::probeAdmissionReady);
            var originalSelection = views.probeGeneratorSelection(0);
            var otherSelection = views.probeGeneratorSelection(1);
            var target = state.project().noise2D.getLast().uuid;
            click("generator", 0);
            frames(3);
            int unfilteredRows = views.probeGeneratorRows();
            require(unfilteredRows > 0 && unfilteredRows < 64, "Generator list was not clipped");
            io.addInputCharactersUTF8("viewport-target");
            frames(3);
            int filteredRows = views.probeGeneratorRows();
            require(filteredRows == 1, "Generator filter mixed dimensions or missed target");
            require(
                Objects.equals(originalSelection, views.probeGeneratorSelection(0)),
                "Filtering changed selection");
            var option = views.probeGeneratorOption(target);
            clickPoint(option, 0);
            until(views::probeAdmissionReady);
            require(target.equals(views.probeGeneratorSelection(0)), "UUID selection failed");
            require(
                Objects.equals(otherSelection, views.probeGeneratorSelection(1)),
                "2D picker changed 3D selection");
            var selectedImage = views.probeAcceptedImage();
            require(selectedImage.sample(.5, .5).raw() == .75, "Selected sampler not published");
            click("generator", 0);
            frames(2);
            io.addKeyEvent(ImGuiKey.Escape, true);
            frame();
            io.addKeyEvent(ImGuiKey.Escape, false);
            frame();
            require(
                target.equals(views.probeGeneratorSelection(0))
                    && views.probeAcceptedImage() == selectedImage,
                "Picker Escape changed selection or image");
            require(!ShortcutKeys.popup(), "Picker Escape left popup open");
            views.probeNoiseTab(1);
            frames(3);
            click("generator", 0);
            frames(3);
            int spatialRows = views.probeGeneratorRows();
            require(spatialRows == 2, "3D picker inherited 2D filter or mixed dimensions");
            io.addInputCharactersUTF8("spatial-other");
            frames(3);
            require(views.probeGeneratorRows() == 1, "3D filter failed");
            var spatialId = state.project().noise3D.getLast().uuid;
            clickPoint(views.probeGeneratorOption(spatialId), 0);
            until(() -> views.probeNoiseReady(1));
            require(
                spatialId.equals(views.probeGeneratorSelection(1))
                    && target.equals(views.probeGeneratorSelection(0)),
                "Independent 3D selection failed");
            views.probeNoiseTab(0);
            frames(3);
            click("generator", 0);
            frames(3);
            require(views.probeGeneratorRows() == 1, "2D filter state was overwritten by 3D");
            clickPoint(views.probeGeneratorNone(), 0);
            frames(3);
            require(
                views.probeGeneratorSelection(0) == null
                    && spatialId.equals(views.probeGeneratorSelection(1)),
                "Clear crossed tabs");
            long clearedStamp = views.probeSubmissionStamp(0);
            frames(4);
            require(clearedStamp == views.probeSubmissionStamp(0), "Cleared source submitted work");
            click("generator", 0);
            frames(3);
            clickPoint(views.probeGeneratorOption(target), 0);
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
            var beforeDelete = views.probeAcceptedImage();
            long deletionStamp = views.probeSubmissionStamp(0);
            require(
                state.change(p -> p.noise2D.removeIf(n -> n.uuid.equals(target))),
                "Delete fixture rejected");
            frames(5);
            require(
                target.equals(views.probeGeneratorSelection(0))
                    && views.probeAdmissionMessage().contains("missing"),
                "Missing UUID was not retained/reported");
            require(
                deletionStamp == views.probeSubmissionStamp(0)
                    && views.probeAcceptedImage() == beforeDelete,
                "Missing source submitted work or discarded observation");
            state.document.undo();
            state.revision++;
            until(views::probeAdmissionReady);
            require(
                authored.equals(ProjectIO.JSON.valueToTree(state.project()))
                    && target.equals(views.probeGeneratorSelection(0)),
                "Undo did not restore selection/project");
            double raw = views.probeAcceptedImage().sample(.5, .5).raw();
            require(raw == .75, "Retry changed field result");
            Files.writeString(
                out.resolve("preview-admission-native.json"),
                ProjectIO.JSON.writeValueAsString(
                    Map.ofEntries(
                        Map.entry("formatVersion", 3),
                        Map.entry("spatialRows", spatialRows),
                        Map.entry("spatialUuid", spatialId.toString()),
                        Map.entry("independentFilters", true),
                        Map.entry("clearReselect", true),
                        Map.entry("missingNoWork", true),
                        Map.entry("deleteUndo", true),
                        Map.entry("unfilteredRows", unfilteredRows),
                        Map.entry("filteredRows", filteredRows),
                        Map.entry("generatorCount", 64),
                        Map.entry("generatorUuid", target.toString()),
                        Map.entry("dimensionScoped", true),
                        Map.entry("pickerEscape", true),
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
                "PREVIEW_ADMISSION_NATIVE_PASS searchable UUID generator / budget / real full queue"
                    + " / retained image / context retry / toolbar retry / view-only");
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
    clickPoint(p, button);
  }

  private static void clickPoint(float[] p, int button) {
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
