package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production transition Apply/Cancel footers; Exit is a counted callback. */
final class BackgroundTransitionDraftProbe {
  private static ProjectFileDialog dialog;
  private static EditorState state;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("apply", "cancel", "escape", "queued", "retry", "queue",
        "stale", "close", "guard", "exit")) {
      var directory = out.resolve(mode); Files.createDirectories(directory);
      var user = new UserConfig(); user.performance.workers = 2;
      user.save(directory.resolve("preferences.json")); var preferences = ProjectIO.JSON.valueToTree(user);
      try (var s = new EditorState(user, false)) {
        state = s;
        require(s.change(p -> {
          p.project.seed = 41;
          var n = new Noise(); n.id = "owned"; n.algorithm.key = "CONSTANT";
          n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", .125); p.noise2D.add(n);
        }), "Transition fixture rejected");
        s.select("noise2D", s.project().noise2D.getFirst().uuid);
        s.document.save(directory.resolve("before.json"));
        ((Noise) s.draft).algorithm.parameters.put("value", .625);
        var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
        long revision = s.revision, history = s.document.historyBytes(); var engine = s.engine;
        var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
        var broken = new AtomicBoolean(mode.equals("retry") || mode.equals("queue"));
        s.document.setValidator(p -> {
          if (mode.equals("retry") && broken.get()) throw new AssertionError("draft worker fixture");
          return NoiseCompiler.validate(p);
        });
        Executor worker = task -> {
          if (mode.equals("queue") && broken.get()) throw new RejectedExecutionException("draft queue fixture");
          tasks.add(task);
        };
        int[] calls = {0, 0};
        dialog = ProjectFileDialog.background(s, () -> calls[0]++, () -> calls[1]++, () -> {
          if (mode.equals("guard") && s.revision > revision) throw new IllegalStateException("post-Apply guard fixture");
          return false;
        }, worker, ui::add);
        frames(4); dialog.workflow.request(mode.equals("exit") ? Intent.EXIT : Intent.OPEN);
        frames(4); click("Apply Draft");
        if (mode.equals("queue")) {
          require(dialog.workflow.phase() == Phase.DRAFT && dialog.workflow.error().contains("failed"), "Queue retry missing");
          frames(12); require(tasks.isEmpty(), "Draft Apply retried unchanged frames");
          broken.set(false); click("Apply Draft");
        }
        frames(12); require(dialog.workflow.phase() == Phase.APPLYING && tasks.size() == 1, "Draft bypassed worker");
        if (mode.equals("queued")) click("Cancel Apply");
        s.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS); frames(12);
        require(before.equals(ProjectIO.JSON.valueToTree(s.project())) && s.draft == draft
            && s.revision == revision && s.document.historyBytes() == history, "Preparation published private draft");
        if (mode.equals("queued")) require(ui.isEmpty(), "Cancelled queued worker delivered");
        else {
          require(ui.size() == 1, "Draft bypassed UI delivery");
          if (mode.equals("cancel")) {
            hold("Cancel Apply"); ui.remove().run();
            ImGui.getIO().addMouseButtonEvent(0, false); frames(3);
          } else if (mode.equals("escape")) {
            ui.remove().run(); ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true); frame();
            ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false); frames(3);
          } else {
            if (mode.equals("stale")) s.seedInputs.edit(s, "seedOffset", "Seed", "-");
            if (mode.equals("close")) dialog.workflow.close();
            var completion = ui.remove(); completion.run(); completion.run();
            require(before.equals(ProjectIO.JSON.valueToTree(s.project())), "Delivery published before modal input decision");
            frames(3);
          }
          if (mode.equals("retry")) {
            require(dialog.workflow.phase() == Phase.DRAFT && dialog.workflow.error().contains("failed"), "Worker retry missing");
            frames(12); require(tasks.isEmpty(), "Failed Apply retried automatically");
            broken.set(false); click("Apply Draft"); frames(12);
            s.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS); frames(12);
            ui.remove().run(); frames(3);
          }
        }
        boolean applied = Set.of("apply", "retry", "queue", "guard", "exit").contains(mode);
        if (applied) {
          require(s.revision == revision + 1 && value() == .625 && s.draft != draft, "Wrong transition Apply result");
          if (mode.equals("guard")) {
            require(dialog.workflow.phase() == Phase.IDLE && dialog.workflow.error().startsWith("Properties applied."),
                "Post-Apply guard failure retained transition");
          } else {
            require(dialog.workflow.phase() == Phase.GUARD, "Draft Apply skipped separate Save decision");
            if (mode.equals("exit")) click("Don't Save"); else click("Cancel");
          }
          if (Set.of("apply", "retry", "queue").contains(mode)) {
            s.document.undo(); s.revision++; s.refreshDraft();
            require(before.equals(ProjectIO.JSON.valueToTree(s.project())), "Draft Undo was not one edit");
            s.document.save(directory.resolve("undone.json"));
            s.document.redo(); s.revision++; s.refreshDraft();
            require(value() == .625, "Draft Redo lost accepted value");
            s.document.save(directory.resolve("redone.json"));
          }
        } else {
          require(dialog.workflow.phase() == Phase.IDLE && s.draft == draft
              && before.equals(ProjectIO.JSON.valueToTree(s.project())), "Retired draft transition changed state");
          if (mode.equals("stale")) require("-".equals(s.seedInputs.text("seedOffset")), "Newer raw input discarded");
        }
        require(calls[0] == 0 && calls[1] == (mode.equals("exit") ? 1 : 0), "Transition callback repeated or escaped");
        require(s.engine == engine && preferences.equals(ProjectIO.JSON.valueToTree(user)), "Transition Apply changed preferences/engine");
        dialog.workflow.close(); frames(2); s.document.save(directory.resolve("after.json"));
        cases.add(Map.of("mode", mode, "applied", applied, "queuedFrames", 12, "heldFrames", 12,
            "replacements", calls[0], "exits", calls[1], "preferencesRetained", true, "engineRetained", true));
      } finally { if (dialog != null) dialog.workflow.close(); }
    }
    Files.writeString(out.resolve("transition-draft-background-native.json"), ProjectIO.JSON.writeValueAsString(
        Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static double value() { return ((Number) state.project().noise2D.getFirst().algorithm.parameters.get("value")).doubleValue(); }
  private static void hold(String label) {
    var at = dialog.probeControl(label); require(at != null, "Missing transition action: " + label);
    var io = ImGui.getIO(); io.addMousePosEvent(at[0], at[1]); frame(); io.addMouseButtonEvent(0, true); frame();
  }
  private static void click(String label) { hold(label); ImGui.getIO().addMouseButtonEvent(0, false); frames(3); }
  private static void frames(int count) { for (int i = 0; i < count; i++) frame(); }
  private static void frame() { ImGui.newFrame(); dialog.draw(); ImGui.render(); }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
