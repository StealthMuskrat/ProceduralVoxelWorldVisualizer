package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred production-selector input/bounds workflow; no GLFW, renderer or physical monitor claim.
 */
public final class PopupLayoutProbe {
  private static EditorState state;
  private static ResourcePicker resources;
  private static BookmarkPanel bookmarks;
  private static boolean bookmarkMode;
  private static int width = 360, height = 240, selections, teleports;
  private static float windowScale = 1;
  private static UUID selected;
  private static Coordinates teleported;

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  for (int i = 0; i < 96; i++) {
                    var noise = new Noise();
                    noise.id = String.format(Locale.ROOT, "field-%03d", i);
                    noise.algorithm.key = "CONSTANT";
                    noise.algorithm.parameters.clear();
                    noise.algorithm.parameters.put("value", .125);
                    p.noise2D.add(noise);
                    var bookmark = new Bookmark();
                    bookmark.name = String.format(Locale.ROOT, "marker-%03d", i);
                    bookmark.x = i;
                    bookmark.y = null;
                    bookmark.z = -i;
                    p.bookmarks.noise2D.add(bookmark);
                  }
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        resources = new ResourcePicker(state);
        bookmarks =
            new BookmarkPanel(
                state,
                new BookmarkPanel.Navigation() {
                  public Coordinates current(Tab tab) {
                    return new Coordinates(0, null, 0);
                  }

                  public void teleport(Tab tab, Coordinates coordinates) {
                    teleported = coordinates;
                    teleports++;
                  }
                });
        var cases = new ArrayList<Map<String, Object>>();
        for (float scale : new float[] {.5f, 1, 2.5f}) {
          width = 360;
          height = 240;
          windowScale = 1;
          state.user.appearance.globalScale = scale;
          exercise(cases, "resource-" + scale, false);
          exercise(cases, "bookmark-" + scale, true);
        }
        // Maximum supported global-times-window font product, with enough height for the
        // query/list.
        width = 720;
        height = 480;
        windowScale = 2.5f;
        exercise(cases, "resource-product-6.25", false);
        exercise(cases, "bookmark-product-6.25", true);
        require(selections == 4 && teleports == 4, "Selection callbacks were duplicated");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.draft == draft
                && draft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset"))
                && state.engine.profiler().current().isEmpty(),
            "Selector changed authoring state");
        Files.writeString(
            out.resolve("popup-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "cases",
                    cases,
                    "selections",
                    selections,
                    "teleports",
                    teleports,
                    "projectPreserved",
                    true,
                    "escapePreservedSelection",
                    true,
                    "physicalDetachedVerified",
                    false,
                    "physicalDpiVerified",
                    false)));
        System.out.println(
            "POPUP_LAYOUT_NATIVE_PASS narrow/scaled resource/bookmark bounds, actual"
                + " search/selection/Escape; physical detached/DPI separate");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void exercise(List<Map<String, Object>> cases, String name, boolean mode) {
    bookmarkMode = mode;
    frames(3);
    open();
    frames(4);
    type(""); // Each widget retains its query across closing; explicitly clear it for this case.
    frames(3);
    var layout = layout();
    var bounds = layout.bounds();
    require(bounds != null, "No selector: " + name);
    var work = new DialogBounds.Rect(0, 0, width, height);
    require(DialogBounds.contains(DialogBounds.inset(work), bounds), "Selector outside work area");
    require(DialogBounds.contains(bounds, layout.queryBounds()), "Query outside selector");
    require(DialogBounds.contains(bounds, layout.listBounds()), "List outside selector");
    int submitted = mode ? bookmarks.submitted() : resources.submitted();
    require(submitted > 0 && submitted < 96, "Selector stopped clipping rows");
    cases.add(
        Map.of(
            "name",
            name,
            "bounds",
            bounds,
            "query",
            layout.queryBounds(),
            "list",
            layout.listBounds(),
            "workArea",
            work,
            "globalScale",
            state.user.appearance.globalScale,
            "windowScale",
            windowScale,
            "submitted",
            submitted));
    type(mode ? "marker-095" : "field-095");
    frames(3);
    UUID last =
        mode
            ? state.project().bookmarks.noise2D.getLast().uuid
            : state.project().noise2D.getLast().uuid;
    require(
        (mode ? bookmarks.submitted() : resources.submitted()) == 1, "Filter did not isolate UUID");
    click(mode ? bookmarks.option(last) : resources.optionCenter(last));
    require(layout.bounds() == null, "Selection did not close popup");
    require(
        mode ? last.equals(bookmarks.selected(Tab.NOISE_2D)) : last.equals(selected),
        "Wrong UUID selected");
    if (mode)
      require(new Coordinates(95, null, -95).equals(teleported), "Wrong bookmark coordinates");
    int before = mode ? teleports : selections;
    open();
    frames(3);
    require(layout.bounds() != null, "Reopen failed");
    type("no-such-option");
    frames(2);
    require(
        (mode ? bookmarks.submitted() : resources.submitted()) == 0, "Empty search retained rows");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(3);
    require(
        layout.bounds() == null && before == (mode ? teleports : selections),
        "Escape changed selection");
    require(
        mode ? last.equals(bookmarks.selected(Tab.NOISE_2D)) : last.equals(selected),
        "Escape cleared UUID");
  }

  private static PopupLayout layout() {
    return bookmarkMode ? bookmarks.choicesLayout : resources.layout;
  }

  private static void open() {
    if (bookmarkMode) click(bookmarks.control("picker"));
    else {
      resources.probeOpen();
      frames(2);
    }
  }

  private static void type(String text) {
    var query = layout().queryBounds();
    click(new float[] {query.x() + query.width() / 2, query.y() + query.height() / 2});
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(2);
  }

  private static void click(float[] point) {
    require(point != null, "Missing selector control");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
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
    state.drain();
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin(
        "Selector layout fixture host",
        ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoTitleBar);
    ImGui.setWindowFontScale(windowScale);
    if (bookmarkMode) bookmarks.draw(Tab.NOISE_2D);
    else
      resources.draw(
          "Field",
          "noise2D",
          selected,
          null,
          id -> {
            selected = id;
            selections++;
          });
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
