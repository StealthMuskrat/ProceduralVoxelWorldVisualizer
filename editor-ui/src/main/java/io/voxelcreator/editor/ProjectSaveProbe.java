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

/** Deferred staged Save publication, real Cancel/Escape input and guarded New/Exit replay. */
final class ProjectSaveProbe {
  private static EditorState state;
  private static ProjectFileDialog dialog;
  private static boolean holdUi;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var rows = new ArrayList<Map<String, Object>>();
    var uiThread = Thread.currentThread();
    for (String mode :
        List.of("SAVE", "CANCEL_READY", "ESCAPE", "COMMITTED", "COMPILER", "RETRY_EXIT", "NEW")) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var worker = new AtomicReference<Thread>();
      int[] replacements = {0}, exits = {0};
      holdUi = false;
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  p.project.seed = 41;
                  var noise = new Noise();
                  noise.id = "owned";
                  noise.algorithm.key = "CONSTANT";
                  noise.algorithm.parameters.clear();
                  noise.algorithm.parameters.put("value", .25);
                  p.noise2D.add(noise);
                }),
            "Save fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "unapplied";
        var before = ProjectIO.JSON.valueToTree(state.project());
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var active = state.project();
        var token = state.document.sessionToken();
        var path = out.resolve(mode + "-saved.json").toAbsolutePath();
        Files.writeString(path, "previous destination");
        state.document.setValidator(
            project -> {
              worker.set(Thread.currentThread());
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Save gate timeout");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Cancelled Save gate", interrupted);
              }
              if (mode.equals("COMPILER") || mode.equals("RETRY_EXIT"))
                throw new AssertionError("Controlled Save validation failure");
              return NoiseCompiler.validate(project);
            });
        dialog =
            ProjectFileDialog.background(
                state, () -> replacements[0]++, () -> exits[0]++, () -> false);
        if (mode.equals("NEW") || mode.equals("RETRY_EXIT")) {
          dialog.workflow.request(mode.equals("NEW") ? Intent.NEW : Intent.EXIT);
          dialog.workflow.decideDraft(DraftDecision.DISCARD);
          dialog.workflow.decide(Decision.SAVE);
        } else dialog.workflow.save(true);
        frame();
        frame();
        text(path.toString());
        click("Save");
        require(entered.await(5, TimeUnit.SECONDS), "Save validation worker did not start");
        for (int i = 0; i < 12; i++) frame();
        require(
            dialog.workflow.phase() == Phase.SAVING
                && state.project() == active
                && state.document.sessionToken() == token
                && state.document.dirty(),
            "Blocked Save published UI state");
        require(
            Files.readString(path).equals("previous destination"),
            "Background worker replaced destination");
        if (mode.equals("CANCEL_READY")) {
          holdUi = true;
          release.countDown();
          await(() -> !state.uiQueue.isEmpty(), false);
          require(!temporaryFiles(out).isEmpty(), "Missing owned prepared sibling");
          click("Cancel Save");
          holdUi = false;
        } else if (mode.equals("ESCAPE")) key(ImGuiKey.Escape);
        else if (mode.equals("COMMITTED")) state.project().project.seed = 42;
        release.countDown();
        await(() -> dialog.workflow.phase() != Phase.SAVING, true);
        if (Set.of("COMPILER", "RETRY_EXIT").contains(mode)) {
          require(
              dialog.workflow.phase() == Phase.SAVE && !dialog.workflow.error().isEmpty(),
              "Save failure lost retry");
          frame();
          frame();
          require(dialog.probePath().equals(path.toString()), "Save failure changed entered path");
        }
        if (mode.equals("RETRY_EXIT")) {
          state.document.setValidator(NoiseCompiler::validate);
          click("Save");
          await(() -> dialog.workflow.phase() != Phase.SAVING, true);
          require(exits[0] == 0 && replacements[0] == 0, "Save retry resumed aborted Exit");
        }
        boolean published = Set.of("SAVE", "RETRY_EXIT", "NEW").contains(mode);
        if (published) {
          var saved = new ProjectIO().load(path);
          require(
              saved.success()
                  && saved.project().project.seed == 41
                  && saved.project().noise2D.getFirst().id.equals("owned"),
              "Save wrote private or wrong data");
          require(!state.document.dirty(), "Successful Save left wrong checkpoint");
        } else
          require(
              Files.readString(path).equals("previous destination"),
              "Cancelled/rejected Save overwrote destination");
        if (mode.equals("NEW")) {
          require(
              replacements[0] == 1 && state.project().project.seed == 0 && state.draft == null,
              "Saved New did not complete once");
        } else {
          var expected = before.deepCopy();
          if (mode.equals("COMMITTED"))
            ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("project"))
                .put("seed", 42);
          require(
              expected.equals(ProjectIO.JSON.valueToTree(state.project()))
                  && state.project() == active
                  && state.document.sessionToken() == token,
              "Save changed authored identity or data");
          require(((Noise) state.draft).id.equals("unapplied"), "Save discarded private draft");
        }
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)), "Save changed preferences");
        require(temporaryFiles(out).isEmpty(), "Unpublished Save sibling leaked");
        Files.writeString(
            out.resolve(mode + "-active.json"), ProjectIO.JSON.writeValueAsString(state.project()));
        rows.add(
            Map.of(
                "mode",
                mode,
                "phase",
                dialog.workflow.phase().name(),
                "published",
                published,
                "seed",
                state.project().project.seed,
                "replacements",
                replacements[0],
                "exits",
                exits[0],
                "workerOffUi",
                worker.get() != uiThread,
                "responsiveFrames",
                12,
                "preferencesPreserved",
                true));
        dialog.workflow.cancel();
        frame();
        frame();
      } finally {
        holdUi = false;
        release.countDown();
        if (dialog != null) dialog.workflow.close();
      }
    }
    Files.writeString(
        out.resolve("project-save-native.json"),
        ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "cases", rows)));
  }

  private static List<Path> temporaryFiles(Path out) throws Exception {
    try (var paths = Files.list(out)) {
      return paths.filter(p -> p.getFileName().toString().startsWith(".voxel-save-")).toList();
    }
  }

  private static void await(BooleanSupplier condition, boolean draw) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Save completion timeout");
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
    require(center != null, "Missing Save control " + label);
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
    if (!holdUi) state.drain();
    ImGui.getIO().setDisplaySize(1100, 800);
    ImGui.getIO().setFontGlobalScale(1);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 800, ImGuiCond.Always);
    ImGui.begin("Background Save reference");
    ImGui.text("Owned staged project Save");
    ImGui.end();
    dialog.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
