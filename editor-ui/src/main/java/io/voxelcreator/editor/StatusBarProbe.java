package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred real chrome/action workflow and old/new docking INI identity; no OS window or renderer.
 */
public final class StatusBarProbe {
  private static EditorState state;
  private static StatusBarPanel bar;
  private static DiagnosticsPanel diagnostics;
  private static ImBoolean notices = new ImBoolean(false);
  private static int width = 1280, height = 720, settingsCalls, notificationCalls, expectedDock;
  private static StatusBarLayout.Plan plan;
  private static final Map<String, DialogBounds.Rect> docked = new LinkedHashMap<>();
  private static final Map<String, Boolean> associations = new LinkedHashMap<>();

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    initialize();
    String legacy;
    try {
      for (int i = 0; i < 4; i++) {
        ImGui.newFrame();
        menu();
        int dock = ImGui.dockSpaceOverViewport();
        if (i == 0) {
          expectedDock = dock;
          WorkspaceDocking.defaults(dock);
        }
        placeholders();
        ImGui.render();
      }
      require(
          associations.values().stream().allMatch(Boolean::booleanValue),
          "Legacy fixture did not dock");
      legacy = ImGui.saveIniSettingsToMemory();
    } finally {
      ImGui.destroyContext();
    }
    initialize();
    try {
      ImGui.loadIniSettingsFromMemory(legacy);
      try (var editor = new EditorState(new UserConfig(), false)) {
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
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        state.events.publish(
            "STATUS_TEST",
            Diagnostic.Severity.WARNING,
            EditorEvents.Subsystem.APPLICATION,
            "An actual actionable notification",
            null,
            true);
        state.events.publish(
            "STATUS_INFO",
            Diagnostic.Severity.INFORMATION,
            EditorEvents.Subsystem.APPLICATION,
            "An actual information notification",
            null,
            false);
        state.status = "Ready 😀\n" + "Long status ".repeat(30);
        String originalStatus = state.status;
        var project = ProjectIO.JSON.valueToTree(state.project());
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var user = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        panels();
        var cases = new ArrayList<Map<String, Object>>();
        exercise(cases, "wide-1", 1280, 720, 1);
        exercise(cases, "narrow-2.5", 360, 240, 2.5f);
        exercise(cases, "small-0.5", 480, 320, .5f);
        String saved = ImGui.saveIniSettingsToMemory();
        ImGui.destroyContext();
        initialize();
        ImGui.loadIniSettingsFromMemory(saved);
        panels();
        exercise(cases, "restart-1", 800, 600, 1);
        require(settingsCalls == 4 && notificationCalls == 4, "Duplicate/missing status actions");
        require(
            state.events.counts().unread() == 2 && state.events.counts().actionRequired() == 1,
            "Opening notifications implicitly marked them read");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.draft == draft
                && user.equals(ProjectIO.JSON.valueToTree(state.user))
                && originalStatus.equals(state.status)
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Chrome changed user/project/private state");
        Files.writeString(
            out.resolve("status-bar-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "cases",
                    cases,
                    "legacyDockId",
                    Integer.toUnsignedString(expectedDock),
                    "settingsCalls",
                    settingsCalls,
                    "notificationCalls",
                    notificationCalls,
                    "projectPreserved",
                    true,
                    "oldAndNewIniRestored",
                    true,
                    "unreadPreserved",
                    true,
                    "physicalDetachedVerified",
                    false,
                    "physicalDpiVerified",
                    false)));
        System.out.println(
            "STATUS_BAR_NATIVE_PASS measured dock/status partition, real action clicks and"
                + " Notifications, compatible old/new INI; physical DPI separate");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void initialize() {
    ImGui.createContext();
    var io = ImGui.getIO();
    io.setIniFilename(null);
    io.setDisplaySize(width, height);
    io.setDeltaTime(1f / 60);
    io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
    io.getFonts().addFontDefault();
    io.getFonts().build();
    ThemeStyle.apply(ThemePalette.starting("Dark"));
  }

  private static void panels() {
    bar =
        new StatusBarPanel(
            state,
            () -> settingsCalls++,
            () -> {
              notificationCalls++;
              notices.set(true);
            });
    diagnostics =
        new DiagnosticsPanel(
            state,
            target -> {
              throw new AssertionError("Unexpected navigation");
            });
  }

  private static void exercise(
      List<Map<String, Object>> cases, String name, int w, int h, float scale) {
    width = w;
    height = h;
    // This frame-local scale is deliberately not published as a preference edit.
    frameScale = scale;
    frames(6);
    require(
        associations.values().stream().allMatch(Boolean::booleanValue),
        "Saved dock association lost");
    var actual = bar.bounds();
    require(actual.equals(plan.bar()), "Native bar differs from planned pixel bounds");
    require(plan.dock().y() + plan.dock().height() == actual.y(), "Dock/status gap or overlap");
    for (var bounds : docked.values())
      require(bounds.y() + bounds.height() <= actual.y() + .02, "Docked panel under status");
    var settings = bar.control("settings");
    var notification = bar.control("notifications");
    require(settings != null && notification != null, "Status actions unreachable");
    require(
        notification.x() + notification.width() <= actual.x() + actual.width() - 8 + .02,
        "Notification right edge outside bar");
    cases.add(
        Map.of(
            "name",
            name,
            "displayWidth",
            w,
            "displayHeight",
            h,
            "scale",
            scale,
            "plan",
            plan,
            "actual",
            actual,
            "settings",
            settings,
            "notification",
            notification));
    click(settings);
    click(bar.control("notifications"));
    require(notices.get(), "Notification action did not open actual production panel");
    frames(5);
    var utility = diagnostics.noticesLayout.bounds();
    var vp = ImGui.getMainViewport();
    require(
        DialogBounds.contains(
            DialogBounds.inset(
                new DialogBounds.Rect(
                    vp.getWorkPosX(), vp.getWorkPosY(), vp.getWorkSizeX(), vp.getWorkSizeY())),
            utility),
        "Notifications utility did not recover into main client work area");
    notices.set(false);
    frames(3);
  }

  private static float frameScale = 1;

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(frameScale);
    ImGui.newFrame();
    menu();
    plan = bar.prepare();
    int dock = DockWorkspace.draw(plan.dock());
    require(dock == expectedDock, "Dock identity changed");
    placeholders();
    bar.draw();
    diagnostics.drawNotifications(notices, () -> {});
    ImGui.render();
  }

  private static void menu() {
    if (ImGui.beginMainMenuBar()) {
      ImGui.text("Menu fixture");
      ImGui.endMainMenuBar();
    }
  }

  private static void placeholders() {
    docked.clear();
    for (String name : List.of("Resources", "Properties", "Visualizers")) {
      ImGui.begin(name);
      associations.put(name, ImGui.isWindowDocked());
      docked.put(
          name,
          new DialogBounds.Rect(
              ImGui.getWindowPosX(),
              ImGui.getWindowPosY(),
              ImGui.getWindowSizeX(),
              ImGui.getWindowSizeY()));
      ImGui.text(name + " fixture / no renderer");
      ImGui.end();
    }
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void click(DialogBounds.Rect point) {
    var io = ImGui.getIO();
    io.addMousePosEvent(point.x() + point.width() / 2, point.y() + point.height() / 2);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
