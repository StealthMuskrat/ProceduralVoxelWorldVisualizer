package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred bookmark modal geometry, footer ownership, callback handoff and exact history replay.
 */
public final class BookmarkLayoutProbe {
  private static EditorState state;
  private static BookmarkPanel panel;
  private static Tab tab = Tab.WORLD;
  private static String form = "Add";
  private static int width = 1200, height = 900, navigations;
  private static float global = 1, multiplier = 1;
  private static boolean rejectNavigation, successorNavigation, popup;
  private static Coordinates navigated;
  private static final Map<Tab, UUID> targets = new EnumMap<>(Tab.class);

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
                  for (var scope : Tab.values()) {
                    var b = new Bookmark();
                    b.name = "original-" + scope.key();
                    b.x = -3;
                    b.y = scope.threeDimensional() ? 5 : null;
                    b.z = -7;
                    BookmarkTransactions.list(p, scope).add(b);
                    targets.put(scope, b.uuid);
                  }
                }),
            "Bookmark fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var engine = state.engine;
        panel =
            new BookmarkPanel(
                state,
                new BookmarkPanel.Navigation() {
                  public Coordinates current(Tab owner) {
                    return coordinates(owner);
                  }

                  public void teleport(Tab owner, Coordinates value) {
                    if (successorNavigation) {
                      panel.cancel();
                      panel.openAdd(Tab.NOISE_2D, new Coordinates(11, null, 13));
                      panel.probeEdit("Name", "successor");
                    }
                    if (rejectNavigation)
                      throw new IllegalStateException("Navigation fixture rejected");
                    navigations++;
                    navigated = value;
                  }
                });
        save(out, "before");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
        for (var scope : Tab.values()) {
          tab = scope;
          for (var mode : List.of("Add", "Rename", "Teleport")) {
            form = mode;
            open(false);
            var raw = raw();
            Long request = null;
            Map<String, Integer> ids = null, fields = null;
            for (int i = 0; i < sizes.length; i++) {
              dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
              frames(8);
              if (request == null) {
                request = panel.actionScope.version();
                ids = panel.chrome.actionIds();
                fields = panel.fieldIds();
              }
              require(
                  request == panel.actionScope.version()
                      && ids.equals(panel.chrome.actionIds())
                      && fields.equals(panel.fieldIds())
                      && raw.equals(raw()),
                  "Reflow replaced bookmark input or request");
              cases.add(receipt(i, "Fields"));
            }
            click("Cancel");
            require(!panel.pending(), "Cancel retained bookmark owner");
          }
        }
        // Long diagnostics remain bounded and reachable independently of coordinate fields.
        tab = Tab.WORLD;
        form = "Add";
        dimensions(1200, 900, 1, 1);
        open(false);
        frames(5);
        var retryUuid = panel.probeUuid();
        long retryScope = panel.actionScope.version();
        var rejection =
            java.util.stream.IntStream.range(0, 300)
                .mapToObj(
                    i ->
                        Diagnostic.error(
                            "BOOKMARK_LAYOUT_FIXTURE", "/fixture/" + i, "detail".repeat(500)))
                .toList();
        state.document.setValidator(p -> rejection);
        click("Apply");
        require(
            panel.pending()
                && panel.candidateIssueCount() == 300
                && panel.diagnosticRows() == 8
                && retryUuid.equals(panel.probeUuid())
                && retryScope == panel.actionScope.version(),
            "Diagnostic rejection replaced input or request");
        for (int i = 0; i < sizes.length; i++) {
          dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
          frames(6);
          click("Show Feedback");
          cases.add(receipt(i, "Feedback"));
          require(
              panel.diagnosticRows() == 8 && retryScope == panel.actionScope.version(),
              "Feedback reflow changed ownership");
        }
        state.document.setValidator(NoiseCompiler::validate);
        click("Cancel");
        dimensions(1200, 900, 1, 1);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && navigations == 0,
            "Layout browsing published a bookmark");
        for (var scope : Tab.values()) {
          tab = scope;
          for (var mode : List.of("Add", "Rename", "Teleport")) {
            form = mode;
            open(false);
            frames(5);
            releaseWithEscape("Apply");
            require(
                !panel.pending()
                    && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                    && navigations == 0,
                "Escape lost to bookmark Apply");
            for (var action : List.of("Apply", "Cancel")) {
              open(false);
              frames(4);
              long old = panel.actionScope.version();
              press(panel.control(action));
              panel.cancel();
              open(true);
              var successor = raw();
              frame();
              release();
              frames(4);
              require(
                  panel.pending()
                      && panel.actionScope.version() > old
                      && successor.equals(raw())
                      && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                      && navigations == 0,
                  "Held bookmark action affected a successor");
              click("Cancel");
            }
          }
        }
        // Same-session rejected callbacks retain UUID/raw input/native retry IDs.
        tab = Tab.WORLD;
        form = "Teleport";
        open(false);
        frames(4);
        var pendingRaw = raw();
        long pendingScope = panel.actionScope.version();
        var pendingIds = panel.chrome.actionIds();
        rejectNavigation = true;
        click("Apply");
        require(
            panel.pending()
                && pendingRaw.equals(raw())
                && pendingScope == panel.actionScope.version()
                && pendingIds.equals(panel.chrome.actionIds())
                && navigations == 0,
            "Failed navigation consumed retry owner");
        rejectNavigation = false;
        click("Apply");
        require(
            !panel.pending()
                && navigations == 1
                && navigated.equals(coordinates(Tab.WORLD))
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Teleport retry changed authored project");

        // Completion/error cleanup from an old callback must not cancel the newly admitted Add.
        for (boolean rejected : new boolean[] {false, true}) {
          open(false);
          frames(4);
          successorNavigation = true;
          rejectNavigation = rejected;
          click("Apply");
          successorNavigation = rejectNavigation = false;
          require(
              panel.pending()
                  && popup
                  && panel.rawText("Name").equals("successor")
                  && panel.rawText("X").equals("11")
                  && panel.rawText("Z").equals("13"),
              "Old callback completion consumed its successor");
          click("Cancel");
        }
        // Owned Rename changes invalidate Apply while unrelated revisions retain the same form.
        form = "Rename";
        tab = Tab.WORLD;
        open(false);
        frames(4);
        long mergeScope = panel.actionScope.version();
        require(state.change(p -> p.project.seed = 17), "Unrelated change rejected");
        frames(3);
        require(
            panel.actionScope.version() == mergeScope, "Unrelated edit replaced bookmark owner");
        state.document.undo();
        state.revision++;
        long old = panel.actionScope.version();
        var raw = raw();
        press(panel.control("Apply"));
        require(
            state.change(p -> p.bookmarks.world.getFirst().name = "external"),
            "Target change rejected");
        frame();
        release();
        frames(3);
        require(
            panel.pending()
                && panel.actionScope.version() > old
                && raw.equals(raw())
                && state.project().bookmarks.world.getFirst().name.equals("external"),
            "Stale Rename published");
        click("Cancel");
        state.document.undo();
        state.revision++;
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Source fixtures were not restored");
        // Invalid coordinate feedback reveals an owned field for actual native correction.
        form = "Add";
        tab = Tab.NOISE_2D;
        open(false);
        panel.probeEdit("X", "-");
        frames(4);
        click("Apply");
        require(panel.pending() && panel.rawText("X").equals("-"), "Invalid coordinate fell back");
        type("X", "17");
        click("Apply");
        require(!panel.pending(), "Corrected Add failed");
        recordStep(out, "added", before);
        form = "Rename";
        tab = Tab.WORLD;
        open(true);
        frames(4);
        var beforeRename = ProjectIO.JSON.valueToTree(state.project());
        click("Apply");
        require(!panel.pending(), "Rename Apply failed");
        recordStep(out, "renamed", beforeRename);
        require(
            state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && state.engine == engine
                && state.engine.profiler().current().isEmpty()
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Bookmark workflow consumed private/runtime ownership");
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("bookmark-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("escapeCancelled", true),
                    Map.entry("heldSuccessorsBlocked", true),
                    Map.entry("boundedFeedback", true),
                    Map.entry("navigationRetry", true),
                    Map.entry("callbackSuccessorsPreserved", true),
                    Map.entry("unrelatedEditsMergeable", true),
                    Map.entry("staleTargetBlocked", true),
                    Map.entry("nativeCorrection", true),
                    Map.entry("oneUndoRedoPerEdit", true),
                    Map.entry("projectPrivatePreserved", true),
                    Map.entry("navigations", navigations),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "BOOKMARK_LAYOUT_NATIVE_PASS persistent actions / ownership / callback successor /"
                + " correction / one Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Coordinates coordinates(Tab owner) {
    return new Coordinates(-3, owner.threeDimensional() ? 5 : null, -7);
  }

  private static void open(boolean successor) {
    switch (form) {
      case "Add" -> panel.openAdd(tab, coordinates(tab));
      case "Rename" -> panel.openRename(tab, targets.get(tab));
      case "Teleport" -> panel.openTeleport(tab);
      default -> throw new AssertionError();
    }
    if (!form.equals("Teleport"))
      panel.probeEdit("Name", successor ? "successor-" + tab.key() : "private-" + tab.key());
    if (successor && !form.equals("Rename")) panel.probeEdit("X", "23");
  }

  private static Map<String, String> raw() {
    var result = new LinkedHashMap<String, String>();
    if (!form.equals("Teleport")) result.put("Name", panel.rawText("Name"));
    if (!form.equals("Rename")) {
      result.put("X", panel.rawText("X"));
      if (tab.threeDimensional()) result.put("Y", panel.rawText("Y"));
      result.put("Z", panel.rawText("Z"));
    }
    return result;
  }

  private static Map<String, Object> receipt(int scenario, String page) {
    return Map.ofEntries(
        Map.entry("scenario", scenario),
        Map.entry("form", form),
        Map.entry("tab", tab.key()),
        Map.entry("page", page),
        Map.entry("globalScale", global),
        Map.entry("windowScale", multiplier),
        Map.entry("work", panel.layout.workArea().bounds()),
        Map.entry("modal", panel.layout.bounds()),
        Map.entry("body", panel.chrome.body()),
        Map.entry("actions", panel.chrome.actions()),
        Map.entry("ids", panel.chrome.actionIds()),
        Map.entry("fields", panel.fieldIds()),
        Map.entry("request", panel.actionScope.version()));
  }

  private static void save(Path out, String name) throws Exception {
    new ProjectIO().save(out.resolve(name + ".json"), state.project());
  }

  private static void recordStep(
      Path out, String name, com.fasterxml.jackson.databind.JsonNode before) throws Exception {
    var after = ProjectIO.JSON.valueToTree(state.project());
    save(out, name);
    state.document.undo();
    state.revision++;
    require(before.equals(ProjectIO.JSON.valueToTree(state.project())), "One Undo changed payload");
    save(out, name + "-undone");
    state.document.redo();
    state.revision++;
    require(after.equals(ProjectIO.JSON.valueToTree(state.project())), "One Redo changed payload");
    save(out, name + "-redone");
  }

  private static void dimensions(int w, int h, float scale, float windowScale) {
    width = w;
    height = h;
    global = scale;
    multiplier = windowScale;
    state.user.appearance.globalScale = global;
    state.user.appearance.windowScales.put("Visualizers", multiplier);
  }

  private static void type(String key, String value) {
    click("Show Fields");
    frames(3);
    press(panel.control(key));
    release();
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frames(3);
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
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
    press(panel.control(key));
    release();
    frames(4);
  }

  private static void releaseWithEscape(String key) {
    press(panel.control(key));
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
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Bookmark layout fixture", ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, "Visualizers");
    DialogLayout.observe(state, "Visualizers");
    panel.drawDialogs();
    popup = ImGui.isPopupOpen("Bookmark Coordinates");
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
