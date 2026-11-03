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

/** Deferred production background Open, real modal input and late-result isolation replay. */
final class ProjectOpenProbe {
  private static EditorState state;
  private static ProjectFileDialog dialog;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var rows = new ArrayList<Map<String, Object>>();
    var uiThread = Thread.currentThread();
    for (String mode :
        List.of(
            "SUCCESS",
            "CANCEL",
            "ESCAPE",
            "COMMITTED",
            "PRIVATE",
            "COMPILER",
            "MALFORMED",
            "POLICY")) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var worker = new AtomicReference<Thread>();
      int[] replacements = {0};
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  p.project.seed = 41;
                  p.noise2D.add(noise("owned", .25));
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "unapplied";
        var active = state.project();
        var token = state.document.sessionToken();
        var before = ProjectIO.JSON.valueToTree(active);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var candidate = new ProjectData();
        candidate.project.seed = 99;
        candidate.noise2D.add(noise("incoming", .75));
        var file = out.resolve(mode + "-incoming.json").toAbsolutePath();
        new ProjectIO().save(file, candidate);
        if (mode.equals("MALFORMED")) Files.writeString(file, "{ malformed");
        state.document.setValidator(
            project -> {
              worker.set(Thread.currentThread());
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Gate timeout");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Cancelled gate", interrupted);
              }
              if (mode.equals("COMPILER")) throw new AssertionError("Controlled compiler failure");
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
        dialog.workflow.request(Intent.OPEN);
        dialog.workflow.decideDraft(DraftDecision.DISCARD);
        dialog.workflow.decide(Decision.DISCARD);
        frame();
        frame();
        text(file.toString());
        click("Open");
        if (!mode.equals("MALFORMED")) {
          require(entered.await(5, TimeUnit.SECONDS), "Worker never entered compiler");
          for (int i = 0; i < 12; i++) frame();
          require(
              dialog.workflow.phase() == Phase.LOADING && replacements[0] == 0,
              "Blocked preparation replaced active UI");
          require(
              state.project() == active && state.document.sessionToken() == token,
              "Worker published before UI completion");
          if (mode.equals("CANCEL")) click("Cancel Open");
          else if (mode.equals("ESCAPE")) key(ImGuiKey.Escape);
          else if (mode.equals("COMMITTED")) state.project().project.seed = 42;
          else if (mode.equals("PRIVATE")) ((Noise) state.draft).id = "later private input";
          release.countDown();
          if (mode.equals("POLICY")) {
            await(() -> !state.uiQueue.isEmpty(), false);
            state.document.setValidator(NoiseCompiler::validate);
          }
        }
        await(() -> dialog.workflow.phase() != Phase.LOADING, true);
        boolean success = mode.equals("SUCCESS");
        if (success) {
          require(
              state.project().project.seed == 99 && replacements[0] == 1,
              "Background Open did not publish once");
          require(
              !state.document.dirty() && !state.document.canUndo() && state.draft == null,
              "Opened checkpoint/history/private state incorrect");
        } else {
          require(
              state.project() == active && state.document.sessionToken() == token,
              "Rejected result replaced active document");
          var expected = before.deepCopy();
          if (mode.equals("COMMITTED"))
            ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("project"))
                .put("seed", 42);
          require(
              expected.equals(ProjectIO.JSON.valueToTree(state.project())),
              "Rejected result changed committed values");
          require(
              ((Noise) state.draft)
                  .id.equals(mode.equals("PRIVATE") ? "later private input" : "unapplied"),
              "Rejected result discarded private input");
          if (Set.of("COMPILER", "MALFORMED", "POLICY").contains(mode)) {
            require(
                dialog.workflow.phase() == Phase.OPEN && !dialog.workflow.error().isEmpty(),
                "Failure did not leave explicit retry");
            frame();
            frame();
            require(file.toString().equals(dialog.probePath()), "Failure reset entered path");
          }
        }
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)), "Open changed preferences");
        Files.writeString(
            out.resolve(mode + "-after.json"), ProjectIO.JSON.writeValueAsString(state.project()));
        rows.add(
            Map.of(
                "mode",
                mode,
                "phase",
                dialog.workflow.phase().name(),
                "replacements",
                replacements[0],
                "seed",
                state.project().project.seed,
                "workerOffUi",
                mode.equals("MALFORMED") || worker.get() != uiThread,
                "responsiveFrames",
                mode.equals("MALFORMED") ? 0 : 12,
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
        out.resolve("project-open-native.json"),
        ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "cases", rows)));
  }

  private static Noise noise(String id, double value) {
    var noise = new Noise();
    noise.id = id;
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", value);
    return noise;
  }

  private static void await(BooleanSupplier condition, boolean draw) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Open completion timeout");
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
    require(center != null, "Missing control " + label);
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void text(String value) {
    click("Path");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    key(ImGuiKey.Backspace);
    io.addInputCharactersUTF8(value);
    frame();
  }

  private static void key(int key) {
    ImGui.getIO().addKeyEvent(key, true);
    frame();
    ImGui.getIO().addKeyEvent(key, false);
    frame();
  }

  private static void frame() {
    state.drain();
    ImGui.getIO().setDisplaySize(1100, 800);
    ImGui.getIO().setFontGlobalScale(1);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 800, ImGuiCond.Always);
    ImGui.begin("Background Project Open reference");
    ImGui.text("Current project remains owned while preparation runs.");
    ImGui.end();
    dialog.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
