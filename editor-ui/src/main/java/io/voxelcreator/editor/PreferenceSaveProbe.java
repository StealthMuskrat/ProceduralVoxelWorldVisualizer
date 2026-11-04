package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred Workspace save footer, coalescing, private boundaries and final-exit persistence. */
final class PreferenceSaveProbe {
  private static EditorState state;
  private static WorkspacePanel panel;
  private static WorkspaceLayout layout;
  private static PreferenceSaveSession saves;
  private static Map<String, ImBoolean> tools;
  private static boolean visible;

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode : List.of("manual-save", "periodic-save", "coalesce", "ready-edit",
        "queue-retry", "ready-close", "exit-save", "exit-failure", "apply-race")) {
      var directory = out.resolve(mode); Files.createDirectories(directory);
      var broken = new AtomicBoolean();
      var user = new UserConfig(mode.equals("exit-failure") ? new PreferenceFiles(new PreferenceFiles.Storage() {
        public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); }
        public void replace(Path temp, Path target) throws IOException {
          if (broken.get()) throw new IOException("exit save fixture");
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
      }) : new PreferenceFiles());
      user.performance.workers = 2; user.workspace.width = 1234;
      user.appearance.windowScales.put("Extension Pane", 1.75f);
      var file = directory.resolve("user-config.json"); user.save(file);
      user.save(directory.resolve("user-before.json")); byte[] original = Files.readAllBytes(file);
      try (var s = new EditorState(user, false)) {
        state = s;
        require(s.change(p -> {
          var n = new Noise(); n.id = "field"; n.algorithm.key = "CONSTANT";
          n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", .125); p.noise2D.add(n);
        }), "Active save fixture rejected");
        s.document.save(directory.resolve("before.json"));
        s.select("noise2D", s.project().noise2D.getFirst().uuid);
        var draft = (Noise) s.draft; draft.id = "private";
        s.seedInputs.edit(s, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(s.project());
        long revision = s.revision, history = s.document.historyBytes(); var engine = s.engine;
        var theme = new ThemeEditSession(user); theme.edit(ThemePalette.starting("Light"));
        var bindings = new ShortcutEditSession(user.input.shortcuts); bindings.edit("Profiler", "alt+f9");
        var scales = new UiScaleEditSession(user); scales.editGlobal("1.2500");
        var limits = new PerformanceEditSession(user); limits.edit(PerformancePreferences.Field.CACHE_MIB, "+256");
        var trust = new UpdateTrustSession(user); trust.editEndpoint("https://updates.example.invalid/latest.json");
        trust.editOrigins("https://updates.example.invalid");
        var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
        var reject = new AtomicBoolean(mode.equals("queue-retry"));
        var notices = new ArrayList<String>(); var errors = new ArrayList<String>();
        Executor worker = task -> { if (reject.get()) throw new RejectedExecutionException("save queue"); tasks.add(task); };
        saves = new PreferenceSaveSession(user, file, worker, ui::add, notices::add, errors::add);
        tools = new LinkedHashMap<>(); WorkspaceVisibility.WINDOWS.forEach(name -> tools.put(name, new ImBoolean(false)));
        layout = new WorkspaceLayout(user.workspace);
        panel = new WorkspacePanel(user, tools, () -> layout.reset(user.workspace), PreferenceSaveProbe::capture);
        panel.probeControls(); visible = true; frames(4);
        if (mode.equals("periodic-save")) { capture(); saves.request(false); }
        else click("save-preferences");
        if (mode.equals("queue-retry")) {
          require(!saves.pending() && errors.size() == 1, "Save failure feedback missing");
          for (int i = 0; i < 12; i++) { saves.request(false); frame(); }
          require(tasks.isEmpty() && errors.size() == 1, "Unchanged periodic failure retried");
          reject.set(false); click("save-preferences");
        }
        for (int i = 0; i < 12; i++) {
          if (mode.equals("coalesce")) {
            user.workspace.width = 1366; user.visualization.grid = false;
            tools.get("Logs").set(true); capture(); saves.request(false);
          }
          frame();
        }
        require(saves.pending() && tasks.size() == 1 && !((Future<?>) tasks.getFirst()).isCancelled(),
            "Active save bypassed/coalesced queued worker incorrectly");
        s.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS); frames(12);
        require(Arrays.equals(original, Files.readAllBytes(file)) && ui.size() == 1,
            "Preparation published before UI input decision");
        if (mode.equals("ready-close")) { saves.close(); ui.remove().run(); frames(3); }
        else if (mode.startsWith("exit-")) {
          user.workspace.width = 1280; user.workspace.x = -100; user.workspace.y = 75; capture();
          broken.set(mode.equals("exit-failure"));
          boolean failed = false;
          try { saves.finish(); } catch (IllegalStateException expected) { failed = true; }
          require(failed == mode.equals("exit-failure"), "Wrong final-save result");
          ui.remove().run(); frames(3);
        } else {
          ui.remove().run();
          if (mode.equals("ready-edit")) {
            user.visualization.grid = false; user.workspace.width = 1366; capture();
          } else if (mode.equals("apply-race")) {
            var request = user.requestTheme(ThemePalette.starting("Light"));
            try (var prepared = s.background.submit(() -> request.prepare(file)).get(10, TimeUnit.SECONDS)) {
              request.publish(prepared, user);
            }
          }
          frames(3);
          if (!tasks.isEmpty()) {
            if (!mode.equals("apply-race")) require(Arrays.equals(original, Files.readAllBytes(file)), "Stale save replaced file");
            s.background.submit(tasks.remove()).get(10, TimeUnit.SECONDS); ui.remove().run(); frames(3);
          }
        }
        boolean persisted = !mode.equals("ready-close") && !mode.equals("exit-failure");
        require(!saves.pending(), "Active save stayed pending");
        if (persisted) require(ProjectIO.JSON.valueToTree(user).equals(ProjectIO.JSON.readTree(file.toFile())), "Saved/active values differ");
        else require(Arrays.equals(original, Files.readAllBytes(file)), "Retired/failed save replaced original");
        int expectedNotices = mode.equals("periodic-save") || mode.startsWith("exit-") || mode.equals("ready-close") ? 0 : 1;
        require(notices.size() == expectedNotices, "Manual/periodic completion feedback repeated or missing");
        require(theme.draft().equals(ThemePalette.starting("Light"))
            && bindings.values().get("Profiler").equals("alt+f9") && scales.text().global().equals("1.2500")
            && limits.text().get(PerformancePreferences.Field.CACHE_MIB).equals("+256")
            && trust.text().endpoint().equals("https://updates.example.invalid/latest.json"), "Active save applied/discarded private form");
        require(project.equals(ProjectIO.JSON.valueToTree(s.project())) && revision == s.revision
            && history == s.document.historyBytes() && s.engine == engine && s.draft == draft
            && draft.id.equals("private") && s.seedInputs.text("seedOffset").equals("-"),
            "Active save changed project/history/engine/private source");
        Files.writeString(directory.resolve("workspace.ini"), user.workspace.docking);
        s.document.save(directory.resolve("after.json")); saves.close(); visible = false; frames(2);
        try (var paths = Files.list(directory)) {
          require(paths.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Active-save temporary leaked");
        }
        cases.add(Map.of("mode", mode, "persisted", persisted, "queuedFrames", 12, "heldFrames", 12,
            "notifications", expectedNotices, "projectRetained", true, "privateRetained", true, "temporaryCleaned", true));
      } finally { if (saves != null) saves.close(); }
    }
    Files.writeString(out.resolve("active-save-native.json"), ProjectIO.JSON.writeValueAsString(
        Map.of("formatVersion", 1, "cases", cases, "physicalInputVerified", false)));
  }

  private static void capture() {
    var values = new LinkedHashMap<String, Boolean>(); tools.forEach((name, flag) -> values.put(name, flag.get()));
    WorkspaceVisibility.capture(state.user.workspace, values);
    layout.capture(state.user.workspace, ImGui::saveIniSettingsToMemory);
  }
  private static void requestSave() { capture(); saves.request(true); }
  private static void click(String name) {
    var at = panel.probeControl(name); require(at != null, "Missing active-save control: " + name);
    var io = ImGui.getIO(); io.addMousePosEvent(at[0], at[1]); frame();
    io.addMouseButtonEvent(0, true); frame(); io.addMouseButtonEvent(0, false); frames(3);
  }
  private static void frames(int count) { for (int i = 0; i < count; i++) frame(); }
  private static void frame() {
    var io = ImGui.getIO(); io.setDisplaySize(1600, 1300); io.setFontGlobalScale(1);
    ImGui.newFrame(); ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1500, 1200, ImGuiCond.Always); ImGui.begin("Active preference save reference");
    UiScale.window(state.user, "User Settings"); layout.build(() -> {});
    if (visible) panel.draw(PreferenceSaveProbe::requestSave); ImGui.end();
    saves.frame(); ImGui.render();
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
