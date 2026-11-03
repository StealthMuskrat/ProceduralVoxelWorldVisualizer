package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Deferred explicit launch path before drawing, using the desktop's background dialog factory. */
final class ProjectLaunchProbe {
  private static EditorState state;
  private static ProjectFileDialog dialog;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var rows = new ArrayList<Map<String, Object>>();
    var uiThread = Thread.currentThread();
    for (String mode : List.of("SUCCESS", "MALFORMED", "RETRY", "CANCEL", "CHANGED", "SHUTDOWN")) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var worker = new AtomicReference<Thread>();
      int[] replacements = {0};
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        var active = state.project();
        var token = state.document.sessionToken();
        var before = ProjectIO.JSON.valueToTree(active);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var candidate = new ProjectData();
        candidate.project.seed = 99;
        var noise = new Noise();
        noise.id = "launched";
        noise.algorithm.key = "CONSTANT";
        noise.algorithm.parameters.clear();
        noise.algorithm.parameters.put("value", .75);
        candidate.noise2D.add(noise);
        var file = out.resolve(mode + " project-\u03b1.json").toAbsolutePath();
        String candidateText = ProjectIO.JSON.writeValueAsString(candidate);
        Files.writeString(
            file, Set.of("MALFORMED", "RETRY").contains(mode) ? "{ malformed" : candidateText);
        state.document.setValidator(
            project -> {
              worker.set(Thread.currentThread());
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Launch gate timeout");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Launch cancelled", interrupted);
              }
              return NoiseCompiler.validate(project);
            });
        dialog =
            ProjectFileDialog.background(
                state,
                () -> replacements[0]++,
                () -> {
                  throw new AssertionError("Exit");
                },
                () -> false);
        // Like Desktop.preRun: no native frame has been submitted by this dialog yet.
        require(dialog.openLocation(file.toString()), "Launch path admission failed");
        require(dialog.probePath().equals(file.toString()), "Launch path was not retained");
        require(
            state.project() == active && state.document.sessionToken() == token,
            "Explicit path installed synchronously before first frame");
        if (Set.of("MALFORMED", "RETRY").contains(mode)) {
          await(() -> dialog.workflow.phase() == Phase.OPEN, true);
          require(
              state.project() == active
                  && !state.document.dirty()
                  && dialog.probePath().equals(file.toString()),
              "Failed launch lost empty session or path");
          if (mode.equals("RETRY")) {
            Files.writeString(file, candidateText);
            click("Open");
          }
        }
        int frames = 0;
        if (!mode.equals("MALFORMED")) {
          require(entered.await(5, TimeUnit.SECONDS), "Launch compiler did not enter worker");
          for (int i = 0; i < 12; i++) frame();
          frames = 12;
          require(
              dialog.workflow.phase() == Phase.LOADING
                  && state.project() == active
                  && replacements[0] == 0,
              "Launch gate blocked rendering or published early");
          if (mode.equals("CANCEL")) click("Cancel Open");
          else if (mode.equals("CHANGED")) state.project().project.seed = 17;
          else if (mode.equals("SHUTDOWN")) dialog.workflow.close();
          release.countDown();
          await(() -> dialog.workflow.phase() != Phase.LOADING, true);
        }
        boolean opened = mode.equals("SUCCESS") || mode.equals("RETRY");
        if (opened) {
          require(
              replacements[0] == 1
                  && state.project().project.seed == 99
                  && state.document.path().equals(file)
                  && !state.document.dirty()
                  && !state.document.canUndo()
                  && state.draft == null
                  && token != state.document.sessionToken(),
              "Launched project checkpoint/publication incorrect");
        } else {
          var expected = before.deepCopy();
          if (mode.equals("CHANGED"))
            ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("project"))
                .put("seed", 17);
          require(
              expected.equals(ProjectIO.JSON.valueToTree(state.project()))
                  && state.project() == active
                  && state.document.sessionToken() == token
                  && state.document.path() == null
                  && replacements[0] == 0,
              "Rejected launch changed current session");
        }
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Launch changed preferences");
        Files.writeString(
            out.resolve(mode + "-active.json"), ProjectIO.JSON.writeValueAsString(state.project()));
        rows.add(
            Map.of(
                "mode",
                mode,
                "phase",
                dialog.workflow.phase().name(),
                "seed",
                state.project().project.seed,
                "replacements",
                replacements[0],
                "workerOffUi",
                mode.equals("MALFORMED") || worker.get() != uiThread,
                "responsiveFrames",
                frames,
                "preferencesPreserved",
                true));
        dialog.workflow.cancel();
        frame();
        frame();
      } finally {
        release.countDown();
        if (dialog != null) dialog.workflow.close();
      }
    }
    Files.writeString(
        out.resolve("project-launch-native.json"),
        ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "cases", rows)));
  }

  private static void await(BooleanSupplier condition, boolean draw) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Launch completion timeout");
      if (draw) frame();
      Thread.sleep(1);
    }
    if (draw) {
      frame();
      frame();
    }
  }

  private static void click(String label) {
    frame();
    frame();
    var center = dialog.probeControl(label);
    require(center != null, "Missing launch control " + label);
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void frame() {
    state.drain();
    ImGui.getIO().setDisplaySize(1100, 800);
    ImGui.getIO().setFontGlobalScale(1);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 800, ImGuiCond.Always);
    ImGui.begin("Explicit project launch reference");
    ImGui.text("Launch preparation before first frame");
    ImGui.end();
    dialog.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
