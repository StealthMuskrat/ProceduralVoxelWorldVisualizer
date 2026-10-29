package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Deferred production result controls with controlled worker/HTTP/browser/clipboard boundaries. */
public final class UpdateCheckProbe {
  private static EditorState state;
  private static UpdateCheckPanel panel;
  private static UpdateCheckSession session;
  private static final ImBoolean open = new ImBoolean(true);
  private static boolean collapsed, failOpen = true, configured;
  private static final Deque<Runnable> workers = new ArrayDeque<>();
  private static final List<String> opened = new ArrayList<>(), copied = new ArrayList<>();
  private static int fetched;
  private static final String LINK = "https://releases.example.test/0.2.0";
  private static UpdateChecker.Release next = new UpdateChecker.Release("0.2.0", LINK);

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1200, 850);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      var user = new UserConfig();
      user.performance.updateEndpoint = "https://updates.example.test/meta.json";
      user.performance.trustedOrigins =
          new ArrayList<>(List.of("https://updates.example.test", "https://releases.example.test"));
      try (var editor = new EditorState(user, false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var noise = new Noise();
                  noise.id = "field";
                  noise.algorithm.key = "CONSTANT";
                  noise.algorithm.parameters.clear();
                  noise.algorithm.parameters.put("value", .125);
                  p.noise2D.add(noise);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var privateDraft = (Noise) state.draft;
        privateDraft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        session =
            new UpdateCheckSession(
                user,
                "0.1.0",
                workers::add,
                state.uiQueue::offer,
                (endpoint, origins) -> {
                  fetched++;
                  return next;
                },
                message ->
                    state.error(
                        "UPDATE_CHECK_FAILED", EditorEvents.Subsystem.APPLICATION, message));
        panel =
            new UpdateCheckPanel(
                session,
                "0.1.0",
                () -> configured = true,
                uri -> {
                  if (failOpen) throw new IOException("Browser fixture unavailable");
                  opened.add(uri.toString());
                },
                copied::add,
                message ->
                    state.error("UPDATE_LINK_FAILED", EditorEvents.Subsystem.APPLICATION, message));
        panel.probeControls();
        frames(3);
        require(fetched == 0 && workers.isEmpty(), "Opening results auto-started a check");
        click("check");
        click("check");
        require(
            workers.size() == 1 && session.view().phase() == UpdateCheckSession.Phase.RUNNING,
            "Repeated Check queued duplicate work");
        click("cancel");
        workers.removeFirst().run();
        frames(2);
        require(
            fetched == 0 && session.view().phase() == UpdateCheckSession.Phase.CANCELLED,
            "Queued cancellation invoked transport");
        click("check");
        workers.removeFirst().run();
        require(
            session.view().phase() == UpdateCheckSession.Phase.RUNNING,
            "Worker bypassed UI publication");
        user.performance.updateEndpoint = "https://updates.example.test/other.json";
        frames(2);
        require(
            session.view().phase() == UpdateCheckSession.Phase.STALE
                && session.view().result() == null,
            "Changed applied settings accepted queued result");
        var preferences = ProjectIO.JSON.valueToTree(user);
        click("check");
        workers.removeFirst().run();
        frames(2);
        var reviewed = session.view().result();
        require(
            reviewed != null
                && reviewed.comparison() == UpdateCheckSession.Comparison.NEWER
                && fetched == 2
                && opened.isEmpty()
                && copied.isEmpty(),
            "Newer result or manual-only link ownership failed");
        click("copy");
        require(copied.equals(List.of(LINK)), "Copy did not use verified reviewed URL");
        click("open");
        require(
            opened.isEmpty()
                && session.view().result() == reviewed
                && state.events.snapshot().stream()
                    .anyMatch(e -> e.code().equals("UPDATE_LINK_FAILED")),
            "Failed browser action lost result/feedback");
        failOpen = false;
        click("open");
        require(opened.equals(List.of(LINK)), "Browser retry did not use exact verified URL");
        click("configure");
        require(configured, "Configure action did not route");
        Files.writeString(out.resolve("metadata-newer.json"), metadata(reviewed.release()));
        click("check");
        open.set(false);
        frame();
        workers.removeFirst().run();
        frames(2);
        require(
            fetched == 2 && session.view().phase() == UpdateCheckSession.Phase.CANCELLED,
            "Closing did not cancel queued request");
        open.set(true);
        frames(2);
        next = new UpdateChecker.Release("0.1.0+build.7", "https://releases.example.test/0.1.0");
        click("check");
        collapsed = true;
        frame();
        workers.removeFirst().run();
        frames(2);
        require(
            session.view().phase() == UpdateCheckSession.Phase.READY
                && session.view().result().comparison() == UpdateCheckSession.Comparison.CURRENT,
            "Collapsed completion/current-version comparison failed");
        collapsed = false;
        frames(2);
        Files.writeString(
            out.resolve("metadata-current.json"), metadata(session.view().result().release()));
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(user))
                && project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.draft == privateDraft
                && privateDraft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Update workflow changed preferences/project/private input");
        panel.close();
        require(
            session.view().phase() == UpdateCheckSession.Phase.CLOSED,
            "Shutdown did not consume request owner");
        Files.writeString(
            out.resolve("update-check-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("requestDeduplicated", true),
                    Map.entry("queuedCancellation", true),
                    Map.entry("stalePublicationDiscarded", true),
                    Map.entry("verifiedLinkRouting", true),
                    Map.entry("failedOpenRetry", true),
                    Map.entry("closedRequestCancelled", true),
                    Map.entry("currentVersionCompared", true),
                    Map.entry("collapsedCompletion", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("networkRequests", 0),
                    Map.entry("externalBrowserLaunches", 0),
                    Map.entry("fetchCalls", fetched),
                    Map.entry("openedTargets", opened),
                    Map.entry("copiedTargets", copied),
                    Map.entry("installed", "0.1.0"))));
        System.out.println(
            "UPDATE_CHECK_NATIVE_PASS real result controls / cancellation / stale publication /"
                + " verified URL routing / no network or browser launch");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static String metadata(UpdateChecker.Release release) throws Exception {
    return ProjectIO.JSON.writeValueAsString(
        Map.of(
            "formatVersion", 1, "version", release.version(), "releaseUrl", release.releaseUrl()));
  }

  private static void click(String key) {
    var point = panel.probeControl(key);
    require(point != null, "Missing update-result control " + key);
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    frame();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    ImGui.newFrame();
    if (open.get()) {
      ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
      ImGui.setNextWindowSize(1000, 650, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
    }
    panel.draw(open);
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
