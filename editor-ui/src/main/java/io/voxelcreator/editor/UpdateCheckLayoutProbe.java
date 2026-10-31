package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred result-window layout/activation with queued fixtures and no transport/browser access.
 */
public final class UpdateCheckLayoutProbe {
  private static EditorState state;
  private static UpdateCheckSession session;
  private static UpdateCheckPanel panel;
  private static final ImBoolean open = new ImBoolean(true);
  private static final Deque<Runnable> workers = new ArrayDeque<>();
  private static final List<String> opened = new ArrayList<>(), copied = new ArrayList<>();
  private static int width = 1200,
      height = 900,
      fetches,
      fetchErrors,
      linkErrors,
      configured,
      reentrantCalls;
  private static float global = 1, multiplier = 1;
  private static boolean collapsed, rejectFetch, rejectOpen, replaceOnOpen;
  private static final String ENDPOINT = "https://updates.example.test/meta.json";
  private static final String LINK = "https://releases.example.test/" + "segment/".repeat(300);
  private static final UpdateChecker.Release RELEASE = new UpdateChecker.Release("0.2.0", LINK);

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
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        state.user.performance.updateEndpoint = ENDPOINT;
        state.user.performance.trustedOrigins =
            new ArrayList<>(
                List.of("https://updates.example.test", "https://releases.example.test"));
        state.user.appearance.windowScales.put("Check for Updates", 1f);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        state.user.save(out.resolve("preferences-before.json"));
        session =
            new UpdateCheckSession(
                state.user,
                "0.1.0",
                workers::add,
                state.uiQueue::offer,
                (endpoint, origins) -> {
                  fetches++;
                  if (rejectFetch)
                    throw new IOException("controlled failure " + "detail".repeat(500));
                  return RELEASE;
                },
                ignored -> fetchErrors++);
        panel =
            new UpdateCheckPanel(
                session,
                "0.1.0",
                () -> configured++,
                uri -> {
                  if (replaceOnOpen) {
                    reentrantCalls++;
                    session.cancel();
                    require(session.start(), "Callback replacement refused");
                    if (rejectOpen) throw new IOException("Reentrant browser rejection");
                    return;
                  }
                  if (rejectOpen) throw new IOException("Controlled browser rejection");
                  opened.add(uri.toString());
                },
                copied::add,
                ignored -> linkErrors++);
        panel.probeControls();
        var cases = new ArrayList<Map<String, Object>>();
        capture(cases, UpdateCheckSession.Phase.IDLE);
        normal();
        click("check");
        capture(cases, UpdateCheckSession.Phase.RUNNING);
        normal();
        click("cancel");
        drainWorkers();
        frames(3);
        require(fetches == 0, "Cancelled queued task invoked fetcher");
        capture(cases, UpdateCheckSession.Phase.CANCELLED);
        normal();
        ready();
        capture(cases, UpdateCheckSession.Phase.READY);
        state.user.performance.updateEndpoint = "https://updates.example.test/changed.json";
        frames(3);
        capture(cases, UpdateCheckSession.Phase.STALE);
        state.user.performance.updateEndpoint = ENDPOINT;
        rejectFetch = true;
        normal();
        click("check");
        drainWorkers();
        frames(4);
        capture(cases, UpdateCheckSession.Phase.FAILED);
        rejectFetch = false;
        normal();
        ready();
        require(fetches == 3 && fetchErrors == 1, "Controlled phase setup mismatch");
        // Ready Escape suppresses Check, routing, browser and clipboard actions without discarding
        // review.
        var reviewed = session.view().result();
        long readyScope = panel.actionScope.version();
        for (var key : List.of("check", "configure", "open", "copy")) {
          releaseWithEscape(key);
          require(
              session.view().result() == reviewed
                  && workers.isEmpty()
                  && fetches == 3
                  && configured == 0
                  && opened.isEmpty()
                  && copied.isEmpty()
                  && panel.actionScope.version() == readyScope,
              "Escape authorized a Ready action");
        }
        session.cancel();
        frames(3);
        releaseWithEscape("check");
        require(
            workers.isEmpty() && session.view().phase() == UpdateCheckSession.Phase.CANCELLED,
            "Escape started a new check");
        click("check");
        releaseWithEscape("cancel");
        drainWorkers();
        frames(3);
        require(
            fetches == 3 && session.view().phase() == UpdateCheckSession.Phase.CANCELLED,
            "Running Escape did not cancel queued request");
        ready();
        // Equal release data from a later request still gets different native action identity.
        for (var key : List.of("open", "copy", "configure", "check")) {
          var predecessor = session.view().result();
          long old = panel.actionScope.version();
          int oldId = panel.chrome.actionIds().get(key);
          press(control(key));
          session.cancel();
          require(session.start(), "Replacement request refused");
          drainWorkers();
          frame();
          release();
          frames(4);
          var successor = session.view().result();
          require(
              successor != predecessor
                  && successor.equals(predecessor)
                  && panel.actionScope.version() > old
                  && oldId != panel.chrome.actionIds().get(key)
                  && workers.isEmpty()
                  && configured == 0
                  && opened.isEmpty()
                  && copied.isEmpty(),
              "Held action affected equal-valued successor");
        }
        click("check");
        press(control("cancel"));
        session.cancel();
        require(session.start(), "Fresh pending request refused");
        frame();
        release();
        frames(3);
        require(
            session.view().phase() == UpdateCheckSession.Phase.RUNNING,
            "Held Cancel consumed successor request");
        drainWorkers();
        frames(4);
        // Collapsing does not cancel collection, but it releases armed presentation controls.
        long expanded = panel.actionScope.version();
        press(control("configure"));
        collapsed = true;
        frame();
        collapsed = false;
        frame();
        release();
        frames(3);
        require(
            panel.actionScope.version() > expanded
                && configured == 0
                && session.view().phase() == UpdateCheckSession.Phase.READY,
            "Collapsed window retained armed action");
        press(control("open"));
        open.set(false);
        frame();
        require(
            session.view().phase() == UpdateCheckSession.Phase.CANCELLED,
            "Hidden window retained result");
        open.set(true);
        require(session.start(), "Reopened check refused");
        drainWorkers();
        frame();
        release();
        frames(4);
        require(
            opened.isEmpty() && session.view().phase() == UpdateCheckSession.Phase.READY,
            "Held Open survived window closure");
        // A failed external boundary retains its exact reviewed result and native retry context.
        reviewed = session.view().result();
        long retry = panel.actionScope.version();
        var retryIds = panel.chrome.actionIds();
        rejectOpen = true;
        click("open");
        require(
            session.view().result() == reviewed
                && panel.actionScope.version() == retry
                && retryIds.equals(panel.chrome.actionIds())
                && opened.isEmpty()
                && linkErrors == 1,
            "Failed browser action lost result or retry IDs");
        rejectOpen = false;
        click("open");
        click("copy");
        click("configure");
        require(
            opened.equals(List.of(LINK))
                && copied.equals(List.of(LINK))
                && configured == 1
                && fetches == 10
                && fetchErrors == 1,
            "Manual routing or request count mismatch");
        for (boolean rejected : new boolean[] {false, true}) {
          replaceOnOpen = true;
          rejectOpen = rejected;
          click("open");
          replaceOnOpen = rejectOpen = false;
          require(
              session.view().phase() == UpdateCheckSession.Phase.RUNNING
                  && panel.probeMessage().isEmpty(),
              "Predecessor browser feedback leaked into successor request");
          drainWorkers();
          frames(4);
          require(
              session.view().phase() == UpdateCheckSession.Phase.READY
                  && panel.probeMessage().isEmpty(),
              "Predecessor feedback survived successor publication");
        }
        require(
            fetches == 12 && reentrantCalls == 2 && linkErrors == 2,
            "Callback fixture count mismatch");
        var finalRelease = session.view().result().release();
        panel.close();
        capture(cases, UpdateCheckSession.Phase.CLOSED);
        normal();
        require(
            workers.isEmpty()
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Update UI changed document/preferences/runtime ownership");
        state.user.save(out.resolve("preferences.json"));
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        Files.writeString(
            out.resolve("reviewed-metadata.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "version",
                    finalRelease.version(),
                    "releaseUrl",
                    finalRelease.releaseUrl())));
        Files.writeString(
            out.resolve("update-check-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("escapePreserved", true),
                    Map.entry("runningEscapeCancelled", true),
                    Map.entry("heldSuccessorsBlocked", true),
                    Map.entry("collapsedContextsReleased", true),
                    Map.entry("closedContextsReleased", true),
                    Map.entry("browserRetryRetained", true),
                    Map.entry("callbackFeedbackScoped", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("fetchCalls", fetches),
                    Map.entry("fetchErrors", fetchErrors),
                    Map.entry("linkErrors", linkErrors),
                    Map.entry("reentrantCalls", reentrantCalls),
                    Map.entry("configured", configured),
                    Map.entry("openedTargets", opened),
                    Map.entry("copiedTargets", copied),
                    Map.entry("networkRequests", 0),
                    Map.entry("externalBrowserLaunches", 0),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "UPDATE_CHECK_LAYOUT_NATIVE_PASS persistent actions / request-result contexts / Escape"
                + " / collapse / manual link retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void capture(List<Map<String, Object>> cases, UpdateCheckSession.Phase phase) {
    require(session.view().phase() == phase, "Wrong phase for layout capture");
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Long version = null;
    Map<String, Integer> ids = null;
    var result = session.view().result();
    var token = session.requestToken();
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      if (version == null) {
        version = panel.actionScope.version();
        ids = panel.chrome.actionIds();
      }
      require(
          session.view().phase() == phase
              && session.view().result() == result
              && session.requestToken() == token
              && version == panel.actionScope.version()
              && ids.equals(panel.chrome.actionIds()),
          "Reflow changed update ownership");
      cases.add(
          Map.ofEntries(
              Map.entry("phase", phase),
              Map.entry("scenario", i),
              Map.entry("globalScale", global),
              Map.entry("windowScale", multiplier),
              Map.entry("window", panel.windowLayout.bounds()),
              Map.entry("body", panel.chrome.body()),
              Map.entry("actions", panel.chrome.actions()),
              Map.entry("ids", panel.chrome.actionIds()),
              Map.entry("request", panel.actionScope.version())));
    }
  }

  private static void ready() {
    click("check");
    require(
        session.view().phase() == UpdateCheckSession.Phase.RUNNING, "Check did not queue request");
    drainWorkers();
    require(
        session.view().phase() == UpdateCheckSession.Phase.RUNNING,
        "Worker published outside UI queue");
    frames(4);
    require(
        session.view().phase() == UpdateCheckSession.Phase.READY, "UI result publication failed");
  }

  private static void drainWorkers() {
    while (!workers.isEmpty()) workers.removeFirst().run();
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
    state.user.appearance.windowScales.put("Check for Updates", multiplier);
  }

  private static float[] control(String key) {
    var at = panel.probeControl(key);
    require(at != null, "Missing update control " + key);
    return at;
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
    state.drain();
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    if (open.get()) {
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
      ImGui.setNextWindowFocus();
    }
    panel.draw(open);
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
