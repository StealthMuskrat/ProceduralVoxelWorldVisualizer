package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deferred production review/footer scenarios with held final-preparation and UI boundaries. */
final class BackgroundImportApplyProbe {
  private static ResourceImportPanel panel;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode :
        List.of("success", "cancel", "escape", "retry", "rejected", "stale", "close")) {
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
            "Import Apply fixture");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var draft = state.draft;
        var user = ProjectIO.JSON.valueToTree(state.user);
        state.document.save(directory.resolve("before.json"));
        var beforeBytes = Files.readAllBytes(directory.resolve("before.json"));
        var incoming = new ProjectData();
        incoming.noise2D.add(noise("added", .75));
        new ProjectIO().save(directory.resolve("incoming.json"), incoming);
        var tasks = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var rejectQueue = new AtomicBoolean();
        var rejectCandidate = new AtomicBoolean(mode.equals("rejected"));
        var entered = new AtomicReference<Thread>();
        var caller = Thread.currentThread();
        panel =
            new ResourceImportPanel(
                state,
                () -> false,
                () -> {},
                task -> {
                  if (rejectQueue.get()) throw new RejectedExecutionException("held Import queue");
                  tasks.add(task);
                },
                ui::add);
        require(
            panel.openPrepared(
                ImportEditSession.prepare(ProjectIO.copy(state.project()), incoming), () -> true),
            "Import Apply review");
        frames(4);
        var session = panel.session();
        require(session.canApply(), "Import plan was not displayed");
        UUID target = session.preview().rows().getFirst().targetUuid();
        state.document.setValidator(
            p -> {
              entered.set(Thread.currentThread());
              return rejectCandidate.get()
                  ? List.of(
                      Diagnostic.error("NATIVE_IMPORT_REJECT", "/fixture", "Correct and retry"))
                  : NoiseCompiler.validate(p);
            });
        if (mode.equals("retry")) rejectQueue.set(true);
        click(panel.control("apply"));
        if (mode.equals("retry")) {
          require(
              session.applyPhase() == ImportEditSession.ApplyPhase.FAILED && !session.busy(),
              "Scheduler failure trapped Import");
          frames(12);
          require(tasks.isEmpty(), "Import automatically retried queue failure");
          rejectQueue.set(false);
          click(panel.control("apply"));
        }
        for (int i = 0; i < 12; i++) {
          frame();
          require(
              session.busy() && panel.pending() && state.project().noise2D.size() == 2,
              "Queued Import published");
          require(
              panel.chrome.body() != null && !panel.chrome.actions().isEmpty(),
              "Pending Import lost its review/footer");
        }
        // Inspection remains available during preparation.
        click(panel.control("filters"));
        click(panel.control("issues"));
        click(panel.control("resources"));
        finish(state, tasks);
        require(
            entered.get() != caller && entered.get() != null,
            "Import validation did not run on a worker");
        frames(12);
        require(
            session.busy() && state.project().noise2D.size() == 2 && ui.size() == 1,
            "Import skipped held UI delivery");
        if (mode.equals("cancel")) click(panel.control("cancel"));
        else if (mode.equals("escape")) {
          ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
          frame();
          ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
          frames(3);
        } else if (mode.equals("close")) panel.close();
        else if (mode.equals("stale")) ((Noise) state.draft).id = "new-private";
        ui.remove().run();
        frames(2);
        if (mode.equals("rejected")) {
          require(
              session.applyPhase() == ImportEditSession.ApplyPhase.REJECTED
                  && panel.pending()
                  && session.issueCount() == 1
                  && state.project().noise2D.size() == 2,
              "Rejected candidate lost review/feedback");
          rejectCandidate.set(false);
          click(panel.control("apply"));
          finish(state, tasks);
          ui.remove().run();
          frames(2);
        }
        boolean imported = List.of("success", "retry", "rejected").contains(mode);
        if (imported) {
          require(
              !panel.pending()
                  && session.closed()
                  && session.applyPhase() == ImportEditSession.ApplyPhase.APPLIED
                  && state.project().noise2D.size() == 3
                  && target.equals(state.project().noise2D.getLast().uuid),
              "Import final publication/copy identity");
          var after = ProjectIO.JSON.valueToTree(state.project());
          state.document.undo();
          require(state.project().noise2D.size() == 2, "Import one Undo");
          state.document.redo();
          require(after.equals(ProjectIO.JSON.valueToTree(state.project())), "Import one Redo");
        } else {
          require(state.project().noise2D.size() == 2, "Cancelled/stale Import committed");
          if (mode.equals("stale"))
            require(
                session.applyPhase() == ImportEditSession.ApplyPhase.STALE && panel.pending(),
                "Stale review was consumed");
        }
        require(
            state.draft == draft
                && ((Noise) draft).id.equals(mode.equals("stale") ? "new-private" : "private")
                && state.seedInputs.text("seedOffset").equals("-")
                && user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Import changed separate private/preferences input");
        require(
            Arrays.equals(beforeBytes, Files.readAllBytes(directory.resolve("before.json"))),
            "Import overwrote source checkpoint");
        state.document.save(directory.resolve("after.json"));
        Files.writeString(
            directory.resolve("preferences.json"), ProjectIO.JSON.writeValueAsString(state.user));
        cases.add(
            Map.of(
                "mode",
                mode,
                "imported",
                imported,
                "queuedFrames",
                12,
                "heldFrames",
                12,
                "privateRetained",
                true,
                "preferencesRetained",
                true,
                "copyIdentityRetained",
                true,
                "originalBytesRetained",
                true));
        panel.close();
      }
    }
    Files.writeString(
        out.resolve("import-apply-native.json"),
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
    require(at != null, "Import Apply control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
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
