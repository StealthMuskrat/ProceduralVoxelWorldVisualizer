package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred real review controls with held worker/UI boundaries and independent saved artifacts. */
final class ResourceImportPlanningProbe {
  private static ResourceImportPanel panel;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("choice", "reload", "cancel", "escape", "retry", "close")) {
      var directory = out.resolve(mode);
      Files.createDirectories(directory);
      try (var state = new EditorState(new UserConfig(), false)) {
        require(
            state.change(
                p -> {
                  p.project.seed = 17;
                  p.noise2D.add(noise("existing", .125));
                  p.noise2D.add(noise("shared", .25));
                }),
            "Planning source fixture");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var draft = state.draft;
        var user = ProjectIO.JSON.valueToTree(state.user);
        state.document.save(directory.resolve("before.json"));
        var beforeBytes = Files.readAllBytes(directory.resolve("before.json"));
        var incoming = new ProjectData();
        incoming.noise2D.add(noise("shared", .25));
        incoming.noise2D.add(noise("added", .75));
        new ProjectIO().save(directory.resolve("incoming.json"), incoming);
        var tasks = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var reject = new AtomicBoolean(mode.equals("retry"));
        Executor executor =
            task -> {
              if (reject.get()) throw new RejectedExecutionException("held preview fixture");
              tasks.add(task);
            };
        panel = new ResourceImportPanel(state, () -> false, () -> {}, executor, ui::add);
        require(
            panel.openPrepared(
                ImportEditSession.prepare(ProjectIO.copy(state.project()), incoming), () -> true),
            "Prepared review admission");
        var session = panel.session();
        var original = session.preview();
        UUID copied = original.rows().get(1).targetUuid();
        frames(4);
        UUID shared = incoming.noise2D.getFirst().uuid;
        click(panel.rowControl(shared));
        click(panel.control(shared + ":REUSE"));
        if (mode.equals("retry")) {
          require(
              session.canRetryPlan() && !session.canApply(), "Failure offered no explicit retry");
          reject.set(false);
          click(panel.control("retry"));
        }
        if (mode.equals("reload")) {
          finish(state, tasks);
          ui.remove().run();
          frames(3);
          require(state.change(p -> p.project.seed = 18), "Reload destination edit");
          frames(3);
          click(panel.control("reload"));
        }
        var held = session.preview();
        for (int i = 0; i < 12; i++) {
          frame();
          require(
              session.planning() && !session.canApply() && session.preview() == held,
              "Held worker review changed");
          var body = panel.chrome.body();
          require(body != null, "Review body missing");
          for (var box : panel.chrome.actions().values())
            require(box.width() > 0 && box.height() > 0, "Collapsed review action");
        }
        finish(
            state,
            tasks); // Actual editor executor computes the plan; hold its owner-thread callback.
        require(
            session.preview() == held && !session.canApply(),
            "Worker published before UI delivery");
        if (mode.equals("cancel")) click(panel.control("cancel"));
        else if (mode.equals("escape")) {
          ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
          frame();
          ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
          frames(3);
        } else if (mode.equals("close")) panel.close();
        ui.remove().run();
        boolean imported = List.of("choice", "reload", "retry").contains(mode);
        if (imported) {
          var fresh = session.preview();
          require(fresh != held && !session.canApply(), "New plan inherited shown authority");
          require(
              mode.equals("reload")
                  ? !copied.equals(fresh.rows().get(1).targetUuid())
                  : copied.equals(fresh.rows().get(1).targetUuid()),
              "COPY allocation ownership");
          frames(4);
          require(session.canApply(), "Displayed replacement did not permit Import");
          click(panel.control("apply"));
          require(
              !panel.pending() && state.project().noise2D.size() == 3,
              "Fresh review did not commit");
          var after = ProjectIO.JSON.valueToTree(state.project());
          state.document.undo();
          require(state.project().noise2D.size() == 2, "Import Undo");
          state.document.redo();
          require(after.equals(ProjectIO.JSON.valueToTree(state.project())), "Import Redo");
        } else
          require(
              !panel.pending() && state.project().noise2D.size() == 2, "Retired review committed");
        require(
            state.draft == draft
                && ((Noise) draft).id.equals("private")
                && state.seedInputs.text("seedOffset").equals("-")
                && user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Private inputs/preferences changed");
        require(
            Arrays.equals(beforeBytes, Files.readAllBytes(directory.resolve("before.json"))),
            "Original source bytes changed");
        state.document.save(directory.resolve("after.json"));
        Files.writeString(
            directory.resolve("preferences.json"), ProjectIO.JSON.writeValueAsString(state.user));
        cases.add(
            Map.of(
                "mode",
                mode,
                "imported",
                imported,
                "responsiveFrames",
                12,
                "privateRetained",
                true,
                "preferencesRetained",
                true,
                "originalBytesRetained",
                true,
                "freshReviewRequired",
                true));
        panel.close();
      }
    }
    Files.writeString(
        out.resolve("import-planning.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private static void finish(EditorState state, ArrayDeque<Runnable> tasks) throws Exception {
    state.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
  }

  private static void click(float[] at) {
    require(at != null, "Import planning control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(4);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
