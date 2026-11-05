package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production Find indexing/scanning, row ownership and lifecycle boundaries. */
final class BackgroundFindProbe {
  private static ResourceFindPanel find;
  private static final ImBoolean open = new ImBoolean(true);
  private static boolean collapsed;
  private static long now;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    float previousScale = ImGui.getIO().getFontGlobalScale();
    var cases = new ArrayList<Map<String, Object>>();
    try {
      for (String mode : List.of("ready", "coalesced", "retry", "cancel", "collapse", "revision", "live-query", "cancelled-worker", "close", "inline-handoff-retry")) {
        var directory = out.resolve(mode); Files.createDirectories(directory);
        now = 0; collapsed = false; open.set(true);
        try (var s = new EditorState(new UserConfig(), false)) {
          require(s.change(p -> {
            for (int i = 0; i < 256; i++) {
              var n = new Noise(); n.id = i == 255 ? "needle" : "field-" + i;
              n.algorithm.key = "CONSTANT"; n.algorithm.parameters.clear();
              n.algorithm.parameters.put("value", .125); p.noise2D.add(n);
            }
          }), "Find fixture rejected");
          s.document.save(directory.resolve("before.json"));
          s.select("noise2D", s.project().noise2D.getFirst().uuid);
          var draft = (Noise) s.draft; draft.id = "private";
          s.seedInputs.edit(s, "seedOffset", "Seed", "-");
          var expected = ProjectIO.JSON.valueToTree(s.project());
          var preferences = ProjectIO.JSON.valueToTree(s.user);
          Files.writeString(directory.resolve("preferences-before.json"), preferences.toString());
          long revision = s.revision, history = s.document.historyBytes();
          var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
          var reject = new AtomicBoolean(mode.equals("retry"));
          var inlineFault = new AtomicBoolean(mode.equals("inline-handoff-retry"));
          boolean inlineFailureObserved = false;
          try (var properties = new PropertyPanel(s)) {
            find = new ResourceFindPanel(s, properties, () -> now, task -> {
              if (reject.get()) throw new RejectedExecutionException("held Find queue");
              tasks.add(task);
            }, callback -> {
              if (inlineFault.get()) {
                callback.run(); throw new AssertionError("Find handoff failed after readiness");
              }
              ui.add(callback);
            }, true);
            find.filter(filter(mode.equals("coalesced") ? "" : "needle"));
            frames(4);
            if (mode.equals("inline-handoff-retry")) {
              finish(s, tasks); frame();
              require(find.searchFailed(), "Inline Find handoff failure was hidden by readiness");
              inlineFailureObserved = true;
              frames(12); require(tasks.isEmpty(), "Find retried inline failure automatically");
              inlineFault.set(false); click(find.controlAt("retry"));
            }
            if (mode.equals("retry") || mode.equals("cancelled-worker")) {
              if (mode.equals("cancelled-worker")) {
                ((Future<?>) tasks.remove()).cancel(false); frame();
              }
              require(find.searchFailed(), "Find failure did not expose retry");
              frames(12);
              require(tasks.isEmpty(), "Find retried unchanged failed frames");
              reject.set(false); click(find.controlAt("retry"));
            }
            frames(12); require(find.searchPending() && find.submitted() == 0, "Find bypassed index worker");
            finish(s, tasks); frames(12);
            require(find.searchPending() && ui.size() == 1 && find.submitted() == 0, "Find bypassed UI delivery");
            if (mode.equals("cancel")) { open.set(false); frames(2); }
            else if (mode.equals("collapse")) { collapsed = true; frames(2); }
            else if (mode.equals("close")) find.close();
            else if (mode.equals("revision")) {
              require(s.change(p -> p.project.seed++), "Find revision fixture rejected");
              expected = ProjectIO.JSON.valueToTree(s.project());
              revision = s.revision; history = s.document.historyBytes();
            } else if (mode.equals("live-query")) find.query("field-1");
            ui.remove().run(); frames(3);
            boolean retired = List.of("cancel", "collapse", "close").contains(mode);
            if (retired) require(find.selectedHit() == null && !find.navigationPending(), "Retired Find navigated");
            else {
              if (mode.equals("revision") || mode.equals("live-query")) {
                now += ResourceSearchSession.DEBOUNCE_NANOS; frames(2);
                finish(s, tasks); ui.remove().run(); frames(3);
              }
              require(!find.searchPending() && find.submitted() > 0, "Current Find rows unavailable");
              if (mode.equals("coalesced")) {
                long queries = find.queries();
                String oldKey = find.visibleKeys().getFirst();
                var at = find.center(oldKey); var io = ImGui.getIO();
                io.addMousePosEvent(at[0], at[1]); frame();
                io.addMouseButtonEvent(0, true); frame();
                for (String query : List.of("n", "ne", "nee", "need", "needl", "needle")) {
                  now++; find.query(query); frame();
                }
                require(find.searchPending() && find.queries() == queries, "Find typed burst scanned early");
                require(find.submitted() > 0, "Find lost inspectable previous rows");
                now += ResourceSearchSession.DEBOUNCE_NANOS; frame();
                finish(s, tasks); ui.remove().run();
                io.addMouseButtonEvent(0, false); frames(3);
                require(!find.navigationPending() && find.builds() == 1 && find.queries() == queries + 1,
                    "Held predecessor navigated or rebuilt the source index");
              }
              String fresh = find.visibleKeys().getFirst();
              click(find.center(fresh));
              require(find.navigationPending(), "Find bypassed the private draft guard");
              find.decideNavigation(SearchNavigation.Decision.CANCEL); frames(2);
            }
            require(expected.equals(ProjectIO.JSON.valueToTree(s.project()))
                && preferences.equals(ProjectIO.JSON.valueToTree(s.user))
                && revision == s.revision && history == s.document.historyBytes()
                && s.draft == draft && draft.id.equals("private")
                && s.seedInputs.text("seedOffset").equals("-"), "Find changed document/private/preferences");
            s.document.save(directory.resolve("after.json"));
            Files.writeString(directory.resolve("preferences-after.json"),
                ProjectIO.JSON.writeValueAsString(s.user));
            cases.add(Map.of("mode", mode, "queuedFrames", 12, "heldFrames", 12,
                "privateRetained", true, "preferencesRetained", true, "navigationGuarded", true,
                "builds", find.builds(), "queries", find.queries(),
                "inlineFailureObserved", inlineFailureObserved,
                "failures", s.events.snapshot().stream().filter(e -> e.code().equals("FIND_SEARCH_FAILED"))
                    .mapToLong(EditorEvents.Entry::count).sum()));
            find.close(); frames(2);
          }
        }
      }
      Files.writeString(out.resolve("find-background-native.json"), ProjectIO.JSON.writeValueAsString(
          Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
    } finally { ImGui.getIO().setFontGlobalScale(previousScale); }
  }

  private static ResourceSearchIndex.Filter filter(String text) {
    return new ResourceSearchIndex.Filter(text, "noise2D", "All", "All", null, null, false, ResourceSearchIndex.Mode.ID);
  }
  private static void finish(EditorState s, ArrayDeque<Runnable> tasks) throws Exception {
    tasks.removeIf(r -> r instanceof Future<?> f && f.isCancelled());
    s.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS);
  }
  private static void click(float[] point) {
    require(point != null, "Find control missing"); var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]); frame();
    io.addMouseButtonEvent(0, true); frame(); io.addMouseButtonEvent(0, false); frames(3);
  }
  private static void frames(int count) { for (int i = 0; i < count; i++) frame(); }
  private static void frame() {
    var io = ImGui.getIO(); io.setDisplaySize(1280, 1000); io.setFontGlobalScale(1);
    ImGui.newFrame(); ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1200, 900, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
    find.draw(open); ImGui.render();
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
