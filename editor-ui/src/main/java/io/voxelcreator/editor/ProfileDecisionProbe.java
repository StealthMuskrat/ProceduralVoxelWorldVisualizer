package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred shared profiler decisions; no GLFW, GL, renderer or GPU query instance is created. */
public final class ProfileDecisionProbe {
  private static EditorState state;
  private static ProfilePanel profile;
  private static DebugSettingsPanel debug;
  private static final ImBoolean open = new ImBoolean(true);
  private static final Viewports.StreamingStatistics EMPTY =
      new Viewports.StreamingStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, false);
  private static String mode = "Profiler";
  private static int width = 1200, height = 900, starts, opens;
  private static float global = 1, multiplier = 1;
  private static boolean collapsed;
  private static DialogBounds.Rect debugWindow;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "existing";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Profiler fixture rejected");
        var projectFile = out.resolve("protected-project.json");
        state.document.save(projectFile);
        var projectBytes = Files.readAllBytes(projectFile);
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(state.project());
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        profile =
            new ProfilePanel(
                state,
                ignored -> {
                  throw new AssertionError("Unexpected source navigation");
                });
        profile.probeWorkControls();
        profile.probeCaptureInput(ProfileCaptureInputs.Field.DURATION, "+0060000");
        profile.probeCaptureInput(ProfileCaptureInputs.Field.SAMPLES, "2048");
        profile.probeCaptureInput(ProfileCaptureInputs.Field.SERIES, "128");
        profile.probeCaptureInput(ProfileCaptureInputs.Field.STRIDE, "1");
        debug =
            new DebugSettingsPanel(
                state,
                () -> EMPTY,
                () -> {
                  starts++;
                  profile.start();
                },
                () -> opens++);
        seed(123, false);
        frames(4);
        click("prepare-export");
        var original = profile.exportReview();
        var frozen = original.snapshot();
        var raw = profile.captureText();
        var cases = new ArrayList<Map<String, Object>>();
        for (var section : ProfilePanel.SECTIONS) {
          profile.section(section);
          capture(cases, section);
        }
        mode = "Debug";
        for (var section : DebugSettingsPanel.SECTIONS) {
          debug.section(section);
          capture(cases, section);
        }
        normal();
        mode = "Profiler";
        profile.section("Export");
        frames(4);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        require(
            profile.exportReview() == original
                && original.available()
                && frozen == original.snapshot()
                && raw.equals(profile.captureText())
                && starts == 0
                && opens == 0,
            "Reflow changed profiler ownership");
        var guard = out.resolve("escape-output.json");
        profile.probeExportPath(guard.toString());
        var running = seed(999, true);
        frames(4);
        for (var key :
            List.of(
                "start",
                "stop",
                "clear",
                "prepare-export",
                "discard-export",
                "export-json",
                "export-csv",
                "problem")) {
          profile.probeCaptureInput(
              ProfileCaptureInputs.Field.STRIDE, key.equals("problem") ? "-" : "1");
          profile.section("Export");
          frames(3);
          releaseWithEscape(key);
          require(
              state.engine.profiler().current().orElseThrow() == running
                  && running.recording()
                  && profile.exportReview() == original
                  && original.available()
                  && original.snapshot() == frozen
                  && !Files.exists(guard)
                  && profile.section().equals("Export"),
              "Escape authorized profiler mutation or navigation");
        }
        profile.probeCaptureInput(ProfileCaptureInputs.Field.STRIDE, "1");
        // Debug's toggle must not retarget a replacement capture or survive a hidden presentation.
        state.engine.profiler().stop();
        mode = "Debug";
        frames(4);
        var stopped = state.engine.profiler().current().orElseThrow();
        releaseWithEscape("capture");
        releaseWithEscape("profiler");
        require(
            state.engine.profiler().current().orElseThrow() == stopped
                && !stopped.recording()
                && starts == 0
                && opens == 0,
            "Debug Escape started instrumentation");
        running = seed(777, true);
        frames(3);
        releaseWithEscape("capture");
        require(running.recording(), "Debug Escape stopped capture");
        for (var key : List.of("capture", "profiler")) {
          press(control(key));
          var successor = seed(778, true);
          frame();
          release();
          frames(3);
          require(
              state.engine.profiler().current().orElseThrow() == successor
                  && successor.recording()
                  && starts == 0
                  && opens == 0,
              "Held Debug action affected successor capture");
        }
        press(control("capture"));
        mode = "Hidden";
        frame();
        mode = "Debug";
        frame();
        release();
        frames(3);
        require(
            debug.recording() && starts == 0 && opens == 0,
            "Hidden Debug retained an armed toggle");
        click("capture");
        require(!debug.recording(), "Debug Stop failed");
        var previous = state.engine.profiler().current().orElseThrow();
        click("capture");
        require(
            debug.recording()
                && state.engine.profiler().current().orElseThrow() != previous
                && starts == 1
                && opens == 1,
            "Shared Debug Start failed");
        click("profiler");
        require(opens == 2, "Explicit Open Profiler failed");
        mode = "Profiler";
        profile.section("Export");
        frames(4);
        for (var key : List.of("start", "stop", "clear")) {
          seed(500, true);
          frames(4);
          long reviewVersion = profile.reviewActions.version();
          var reviewIds = reviewIds();
          press(control(key));
          var successor = seed(600, true);
          frame();
          release();
          frames(3);
          require(
              state.engine.profiler().current().orElseThrow() == successor
                  && successor.recording()
                  && profile.reviewActions.version() == reviewVersion
                  && reviewIds.equals(reviewIds())
                  && profile.exportReview() == original,
              "Held capture action affected successor or independent review");
        }
        seed(701, false);
        frames(4);
        press(control("prepare-export"));
        seed(702, false);
        frame();
        release();
        frames(3);
        require(
            profile.exportReview() == original, "Held Prepare froze an unseen successor snapshot");
        for (var key : List.of("discard-export", "export-json", "export-csv")) {
          press(control(key));
          profile.probeReview(frozen);
          var successor = profile.exportReview();
          frame();
          release();
          frames(3);
          require(
              profile.exportReview() == successor
                  && successor.available()
                  && successor.snapshot() == frozen
                  && !Files.exists(guard),
              "Held export action consumed a successor review");
        }
        var review = profile.exportReview();
        press(control("discard-export"));
        collapsed = true;
        frame();
        collapsed = false;
        frame();
        release();
        frames(3);
        require(
            profile.exportReview() == review && review.available(),
            "Collapse consumed independent review");
        press(control("discard-export"));
        open.set(false);
        frame();
        open.set(true);
        frame();
        release();
        frames(3);
        require(
            profile.exportReview() == review && review.available(),
            "Hidden Profiler consumed review");
        // File failures retain the exact immutable review, path and retry action IDs.
        profile.probeExportPath(projectFile.toString());
        frames(3);
        long retry = profile.reviewActions.version();
        var retryIds = reviewIds();
        click("export-json");
        require(
            review.available()
                && profile.reviewActions.version() == retry
                && retryIds.equals(reviewIds())
                && Arrays.equals(projectBytes, Files.readAllBytes(projectFile)),
            "Protected project path lost review or content");
        var destination = out.resolve("reviewed.json");
        profile.probeExportPath(destination.toString());
        Files.createDirectory(destination);
        Files.writeString(destination.resolve("keep"), "retained");
        click("export-json");
        require(
            review.available()
                && profile.reviewActions.version() == retry
                && retryIds.equals(reviewIds())
                && Files.readString(destination.resolve("keep")).equals("retained"),
            "Failed export lost retry context");
        click("clear");
        require(
            state.engine.profiler().current().isEmpty()
                && profile.exportReview() == review
                && review.available()
                && profile.reviewActions.version() == retry
                && retryIds.equals(reviewIds()),
            "Clear consumed review context");
        Files.delete(destination.resolve("keep"));
        Files.delete(destination);
        seed(800, true);
        frames(3);
        press(control("export-json"));
        seed(888, true);
        frame();
        release();
        frames(3);
        require(
            !review.available()
                && Files.exists(destination)
                && profile.reviewActions.version() > retry,
            "Unrelated capture replacement blocked authorized frozen-review export");
        ProfileIO.writeCSV(out.resolve("reviewed.csv"), frozen);
        var consumed = out.resolve("consumed.csv");
        profile.probeExportPath(consumed.toString());
        click("export-csv");
        require(!Files.exists(consumed), "Consumed review wrote another format");
        seed(999, false);
        frames(4);
        click("prepare-export");
        var fresh = profile.exportReview();
        require(
            fresh != review
                && fresh.available()
                && fresh.snapshot().samples().size() == 1
                && fresh.snapshot().samples().getFirst().value() == 999,
            "Explicit Prepare did not freeze displayed replacement");
        profile.probeExportPath(out.resolve("replacement.csv").toString());
        click("export-csv");
        require(!fresh.available(), "CSV publication did not consume review");
        ProfileIO.writeJSON(out.resolve("replacement.json"), fresh.snapshot());
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.engine == engine
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && Arrays.equals(projectBytes, Files.readAllBytes(projectFile)),
            "Profiler workflow changed project/preferences/private ownership");
        state.user.save(out.resolve("preferences.json"));
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        Files.writeString(
            out.resolve("profile-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("escapePreserved", true),
                    Map.entry("heldCaptureSuccessorsBlocked", true),
                    Map.entry("heldPrepareBlocked", true),
                    Map.entry("heldReviewSuccessorsBlocked", true),
                    Map.entry("independentReviewContext", true),
                    Map.entry("hiddenContextsReleased", true),
                    Map.entry("failedRetriesRetained", true),
                    Map.entry("consumedFormatsBlocked", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("debugStarts", starts),
                    Map.entry("debugOpens", opens),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "PROFILE_DECISIONS_NATIVE_PASS separate capture/display/review contexts / Escape /"
                + " shared Debug / frozen export retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Profiler.Capture seed(long value, boolean running) {
    var capture = state.engine.profiler().start(new Profiler.Options(60000, 2048, 128, 1));
    state
        .engine
        .profiler()
        .scope(7, state.project().project.uuid, null)
        .gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, value, "sample-" + value);
    if (!running) state.engine.profiler().stop();
    return capture;
  }

  private static Map<String, Integer> reviewIds() {
    var ids = profile.layout.actionIds();
    return Map.of(
        "discard-export",
        ids.get("discard-export"),
        "export-json",
        ids.get("export-json"),
        "export-csv",
        ids.get("export-csv"));
  }

  private static void capture(List<Map<String, Object>> cases, String section) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      var chrome = mode.equals("Profiler") ? profile.layout : debug.layout;
      if (ids == null) ids = chrome.actionIds();
      require(ids.equals(chrome.actionIds()), "Reflow replaced action owners");
      cases.add(
          Map.ofEntries(
              Map.entry("panel", mode),
              Map.entry("section", section),
              Map.entry("scenario", i),
              Map.entry("globalScale", global),
              Map.entry("windowScale", multiplier),
              Map.entry(
                  "window", mode.equals("Profiler") ? profile.windowLayout.bounds() : debugWindow),
              Map.entry("body", chrome.body()),
              Map.entry("actions", chrome.actions()),
              Map.entry("ids", chrome.actionIds())));
    }
  }

  private static void normal() {
    dimensions(1200, 900, 1, 1);
    frames(5);
  }

  private static void dimensions(int w, int h, float scale, float windowScale) {
    width = w;
    height = h;
    global = scale;
    multiplier = windowScale;
    state.user.appearance.globalScale = global;
    state.user.appearance.windowScales.put("Profiler", multiplier);
    state.user.appearance.windowScales.put("Debug & Profiling", multiplier);
  }

  private static float[] control(String key) {
    if (mode.equals("Profiler")) {
      var at = profile.probeWorkControl(key);
      require(at != null, "Missing profiler action " + key);
      return at;
    }
    var box = debug.layout.actions().get(key);
    require(box != null, "Missing Debug action " + key);
    return new float[] {box.x() + box.width() / 2, box.y() + box.height() / 2};
  }

  private static void press(float[] at) {
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static void click(String key) {
    press(control(key));
    release();
    frames(4);
  }

  private static void releaseWithEscape(String key) {
    press(control(key));
    var io = ImGui.getIO();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    if (mode.equals("Profiler")) {
      debug.suspendActions();
      if (open.get()) {
        ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
        ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
        ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
        ImGui.setNextWindowFocus();
      }
      profile.draw(open, EMPTY, null);
    } else if (mode.equals("Debug")) {
      profile.suspendActions();
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.setNextWindowFocus();
      ImGui.begin("Debug & Profiling", ImGuiWindowFlags.NoSavedSettings);
      UiScale.window(state.user, "Debug & Profiling");
      debugWindow =
          new DialogBounds.Rect(
              ImGui.getWindowPosX(),
              ImGui.getWindowPosY(),
              ImGui.getWindowSizeX(),
              ImGui.getWindowSizeY());
      debug.draw();
      ImGui.end();
    } else {
      profile.suspendActions();
      debug.suspendActions();
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
