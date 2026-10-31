package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred shared modal delivery, scope unwind, successor and nested-popup observations. */
public final class NavigationDeliveryProbe {
  private static final String TITLE = "Draft Delivery", NESTED = "Delivery Destination";
  private static EditorState state;
  private static NavigationGuardDialog dialog;
  private static String phase;
  private static boolean opening, replace, successor, explanationFailure, nestedSeen, closeNested;
  private static int width = 1200, height = 900, root, calls, failures;
  private static float global = 1;
  private static SearchNavigation.Decision last;
  private static final List<Map<String, Object>> cases = new ArrayList<>(),
      routes = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(TraceWorkflowProbe::fixture), "Fixture rejected");
        state.document.save(out.resolve("source-project.json"));
        state.user.save(out.resolve("preferences-before.json"));
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        dialog = new NavigationGuardDialog(state);
        start("retained");
        capture();
        click("APPLY");
        require(
            calls == 1 && dialog.message().equals("Retained decision"),
            "Rejected decision replayed or lost feedback");
        click("CANCEL");
        record();
        start("terminal");
        capture();
        click("APPLY");
        require(
            calls == 1 && !popup() && dialog.message().isEmpty(),
            "Terminal decision did not retire its popup");
        record();

        start("callback-throw");
        click("APPLY");
        require(
            calls == 1 && failures == 1 && popup(), "Throwing delivery replayed or lost its modal");
        click("CANCEL");
        record();
        start("explanation-throw");
        require(failures == 1 && calls == 0, "Explanation failure became a decision");
        click("CANCEL");
        record();
        for (var mode : List.of("successor-terminal", "successor-retained")) {
          start(mode);
          long old = dialog.requestVersion();
          click("APPLY");
          require(
              calls == 1 && dialog.requestVersion() > old && popup() && dialog.message().isEmpty(),
              "Old completion closed or annotated a successor");
          click("CANCEL");
          record();
        }
        start("explicit-close");
        click("APPLY");
        require(
            calls == 1 && !popup() && dialog.message().isEmpty(),
            "Old callback restored an explicitly closed dialog");
        record();
        start("nested");
        click("APPLY");
        require(
            calls == 1 && nestedSeen && !popup(), "Destination popup was closed by the old modal");
        closeNested = true;
        frames(2);
        record();
        start("escape");
        press("APPLY");
        var io = ImGui.getIO();
        io.addMouseButtonEvent(0, false);
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        frames(2);
        require(
            calls == 1 && last == SearchNavigation.Decision.CANCEL && !popup(),
            "Escape did not supersede Apply");
        record();
        start("held");
        long old = dialog.requestVersion();
        press("APPLY");
        replace = true;
        frame();
        release();
        frames(2);
        require(
            calls == 0 && popup() && dialog.requestVersion() > old,
            "Held predecessor activated a successor");
        click("CANCEL");
        record();

        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history,
            "Dialog delivery changed canonical project/history");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        try (var world = state.engine.openWorld(new CompiledProject(state.project()))) {
          Files.writeString(
              out.resolve("source-trace.json"),
              ProjectIO.JSON.writeValueAsString(world.trace(new BlockPos(2, 0, 3))));
        }
        Files.writeString(
            out.resolve("navigation-delivery-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "fontSource",
                    "synthetic-global-window-fixture",
                    "cases",
                    cases,
                    "routes",
                    routes,
                    "callbacksAfterPopup",
                    true,
                    "scopeRestored",
                    true,
                    "unchangedDocument",
                    true,
                    "physicalDpiVerified",
                    false)));
        System.out.println(
            "NAVIGATION_DELIVERY_NATIVE_PASS post-popup delivery / one action per frame / successor"
                + " and nested popup ownership / balanced failures");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void start(String mode) {
    phase = mode;
    calls = failures = 0;
    last = null;
    successor = nestedSeen = closeNested = false;
    explanationFailure = mode.equals("explanation-throw");
    opening = true;
    frames(4);
  }

  private static SearchNavigation.Result decide(SearchNavigation.Decision decision) {
    require(root == ImGui.getID("delivery-root"), "Decision delivered inside the popup");
    calls++;
    last = decision;
    if (decision == SearchNavigation.Decision.CANCEL) return SearchNavigation.Result.CANCELLED;
    if (phase.equals("callback-throw")) throw new IllegalStateException("callback boundary");
    if (phase.startsWith("successor-") && !successor) {
      successor = true;
      dialog.open(TITLE);
      return phase.equals("successor-retained")
          ? SearchNavigation.Result.NEEDS_DECISION
          : SearchNavigation.Result.NAVIGATED;
    }
    if (phase.equals("explicit-close")) {
      dialog.close(TITLE);
      return SearchNavigation.Result.NEEDS_DECISION;
    }
    if (phase.equals("nested")) {
      ImGui.openPopup(NESTED);
      return SearchNavigation.Result.NAVIGATED;
    }
    return phase.equals("retained")
        ? SearchNavigation.Result.NEEDS_DECISION
        : SearchNavigation.Result.NAVIGATED;
  }

  private static void draw() {
    try {
      dialog.draw(
          TITLE,
          () -> {
            if (explanationFailure) {
              explanationFailure = false;
              throw new IllegalStateException("explanation boundary");
            }
            ImGui.textWrapped("Shared draft decision delivery.");
          },
          NavigationDeliveryProbe::decide,
          "Retained decision");
    } catch (IllegalStateException failure) {
      require(
          (phase.equals("callback-throw") && failure.getMessage().equals("callback boundary"))
              || (phase.equals("explanation-throw")
                  && failure.getMessage().equals("explanation boundary")),
          "Unexpected native failure: " + failure);
      failures++;
    }
    require(root == ImGui.getID("delivery-root"), "Modal failure leaked popup/child/ID scopes");
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Navigation Delivery");
    UiScale.window(state.user, "Properties");
    root = ImGui.getID("delivery-root");
    if (replace) {
      dialog.close(TITLE);
      opening = true;
      replace = false;
    }
    if (opening) {
      dialog.open(TITLE);
      opening = false;
    }
    draw();
    draw(); // Repeated rendering must not redeliver the same native activation.
    if (UiScale.beginPopupModal(NESTED)) {
      try {
        nestedSeen = true;
        ImGui.text("Destination popup");
        if (closeNested) ImGui.closeCurrentPopup();
      } finally {
        ImGui.endPopup();
      }
    }
    require(root == ImGui.getID("delivery-root"), "Destination popup leaked its scope");
    ImGui.end();
    ImGui.render();
  }

  private static void capture() {
    var old = state.user.appearance.windowScales.get("Properties");
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      global = globals[i];
      state.user.appearance.windowScales.put("Properties", scales[i]);
      frames(6);
      if (ids == null) ids = dialog.chrome.actionIds();
      require(ids.equals(dialog.chrome.actionIds()), "Font reflow changed modal action ownership");
      cases.add(
          Map.of(
              "phase",
              phase,
              "scenario",
              i,
              "globalScale",
              global,
              "windowScale",
              scales[i],
              "window",
              dialog.layout.bounds(),
              "body",
              dialog.chrome.body(),
              "actions",
              dialog.chrome.actions(),
              "ids",
              dialog.chrome.actionIds()));
    }
    width = 1200;
    height = 900;
    global = 1;
    if (old == null) state.user.appearance.windowScales.remove("Properties");
    else state.user.appearance.windowScales.put("Properties", old);
    frames(3);
  }

  private static void record() {
    routes.add(
        Map.of(
            "phase",
            phase,
            "calls",
            calls,
            "failures",
            failures,
            "last",
            last.name(),
            "successor",
            successor,
            "nested",
            nestedSeen));
    frames(2);
  }

  private static boolean popup() {
    return !dialog.buttons().isEmpty();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void press(String key) {
    var at = dialog.button(key);
    require(at != null, "Missing modal action " + key);
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
    press(key);
    release();
    frames(2);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
