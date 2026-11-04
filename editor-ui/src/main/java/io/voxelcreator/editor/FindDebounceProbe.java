package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred production Find typing, pending navigation and native held-row retirement. */
public final class FindDebounceProbe {
  private static ResourceFindPanel find;
  private static final ImBoolean open = new ImBoolean(true);
  private static long now;
  private static int width = 1400, height = 1000;
  private static float scale = 1;
  private static boolean collapsed;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        require(
            state.change(
                p -> {
                  for (int i = 0; i < 1100; i++) {
                    var n = new Noise();
                    n.id = i == 1099 ? "needle" : "field-" + i;
                    n.algorithm.key = "CONSTANT";
                    n.algorithm.parameters.clear();
                    n.algorithm.parameters.put("value", .125);
                    p.noise2D.add(n);
                  }
                }),
            "Fixture rejected");
        state.document.save(out.resolve("before.json"));
        state.user.save(out.resolve("preferences-before.json"));
        state.select("noise2D", state.project().noise2D.get(1098).uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var source = ProjectIO.JSON.valueToTree(state.project());
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        find = new ResourceFindPanel(state, new PropertyPanel(state), () -> now);
        find.filter(filter(""));
        frames(6);
        long initialQueries = find.queries();
        int initialRows = find.submitted();
        String firstKey = find.visibleKeys().getFirst();
        click(find.center(firstKey));
        require(find.navigationPending(), "Private draft was not protected");
        find.decideNavigation(SearchNavigation.Decision.CANCEL);
        frames(3);
        var selected = find.selectedHit();
        require(selected != null, "Guard Cancel lost the selected row");
        for (String query : List.of("n", "ne", "nee", "need", "needl", "needle")) {
          find.query(query);
          frames(2);
          require(find.searchPending() && find.queries() == initialQueries, "Typing queried early");
        }
        var pendingIds = find.rowIds();
        click(find.center(firstKey));
        click(find.controlAt("navigate"));
        require(
            !find.navigationPending() && find.selectedHit() == selected,
            "Pending row/footer activated old results");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1000}, {360, 640}, {720, 1600}};
        float[] scales = {1, 2.5f, 6.25f};
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          scale = scales[i];
          frames(6);
          require(
              find.searchPending() && find.queries() == initialQueries,
              "Drawing completed frozen-clock query");
          require(find.submitted() > 0 && find.submitted() <= 40, "Pending rows were not clipped");
          cases.add(
              Map.of(
                  "scenario",
                  i,
                  "scale",
                  scale,
                  "results",
                  find.resultsBounds(),
                  "query",
                  find.searchBounds(),
                  "submitted",
                  find.submitted(),
                  "stride",
                  find.rowStride()));
        }
        width = 1400;
        height = 1000;
        scale = 1;
        frames(5);
        require(pendingIds.equals(find.rowIds()), "Reflow replaced current row identities");
        now = ResourceSearchSession.DEBOUNCE_NANOS - 1;
        frames(3);
        require(find.searchPending(), "Query completed before 150 ms");
        // Hold an old row while the result set is replaced, then release on its old location.
        var center = find.center(firstKey);
        io.addMousePosEvent(center[0], center[1]);
        frame();
        io.addMouseButtonEvent(0, true);
        frame();
        now++;
        frame();
        io.addMouseButtonEvent(0, false);
        frames(3);
        require(
            !find.searchPending() && find.queries() == initialQueries + 1 && find.submitted() == 1,
            "Final typed query did not complete exactly once");
        require(
            find.selectedHit() == selected && !find.navigationPending(),
            "Held predecessor row activated a replacement result");
        var finalIds = find.rowIds();
        require(
            finalIds.values().stream().noneMatch(pendingIds.values()::contains),
            "Replacement reused native row IDs");
        click(find.center(find.visibleKeys().getFirst()));
        require(find.navigationPending(), "Current result did not support fresh activation");
        find.decideNavigation(SearchNavigation.Decision.CANCEL);
        frames(3);
        typeAt(find.controlAt("query"), "field-1098");
        require(
            find.searchPending() && find.queries() == initialQueries + 1,
            "Native typing bypassed debounce");
        find.filter(filter("field-1098"));
        frames(3);
        require(
            !find.searchPending() && find.submitted() == 1 && find.queries() == initialQueries + 2,
            "Explicit filter did not supersede pending typing");
        find.query("unmatched");
        frames(2);
        collapsed = true;
        frames(2);
        require(!find.searchPending(), "Collapsed Find retained pending work");
        now += ResourceSearchSession.DEBOUNCE_NANOS * 2;
        collapsed = false;
        frames(4);
        require(
            find.searchPending() && find.queries() == initialQueries + 2,
            "Collapsed query deadline survived reopening");
        open.set(false);
        frame();
        long builds = find.builds();
        require(!find.searchPending(), "Closed Find retained pending work");
        open.set(true);
        frames(5);
        require(
            !find.searchPending() && find.submitted() == 0 && find.builds() == builds + 1,
            "Reopened Find did not rebuild its current source immediately");
        click(find.controlAt("clear"));
        frames(4);
        require(
            find.query().isEmpty() && !find.searchPending() && find.submitted() > 0,
            "Clear Filters waited for typing or retained stale results");
        require(
            source.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.revision == revision
                && state.document.historyBytes() == history
                && !state.document.dirty()
                && state.seedInputs.text("seedOffset").equals("-"),
            "Find typing changed project/history/private/raw input");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("resources", 1100);
        report.put("initialQueries", initialQueries);
        report.put("initialRows", initialRows);
        report.put("typingQueries", 1);
        report.put("quietNanos", ResourceSearchSession.DEBOUNCE_NANOS);
        report.put("cases", cases);
        report.put("pendingNavigationBlocked", true);
        report.put("heldRowRetired", true);
        report.put("nativeTypingDebounced", true);
        report.put("explicitImmediate", true);
        report.put("hiddenCancelled", true);
        report.put("rawInput", state.seedInputs.text("seedOffset"));
        report.put("physicalDpiVerified", false);
        BackgroundFindProbe.run(out.resolve("background"));
        Files.writeString(
            out.resolve("find-debounce-native.json"), ProjectIO.JSON.writeValueAsString(report));
        System.out.println(
            "FIND_DEBOUNCE_NATIVE_PASS trailing typing / unavailable old rows / held replacement /"
                + " explicit and hidden routes");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static ResourceSearchIndex.Filter filter(String query) {
    return new ResourceSearchIndex.Filter(
        query, "noise2D", "All", "All", null, null, false, ResourceSearchIndex.Mode.ID);
  }

  private static void click(float[] point) {
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void typeAt(float[] point, String text) {
    click(point);
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(scale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
    find.draw(open);
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
