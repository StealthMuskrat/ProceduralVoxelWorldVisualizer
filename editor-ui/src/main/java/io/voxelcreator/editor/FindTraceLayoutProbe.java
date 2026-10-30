package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred production Find/Trace input, reflow and owned navigation workflow, without GLFW/GPU. */
public final class FindTraceLayoutProbe {
  private static EditorState state;
  private static ResourceFindPanel find;
  private static TracePanel trace;
  private static GuardedNavigation guard;
  private static TraceSnapshot shown;
  private static final ImBoolean open = new ImBoolean(true);
  private static int width = 1400, height = 1200;
  private static String mode = "find", copied = "";
  private static DialogBounds.Rect window;

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
                  TraceWorkflowProbe.fixture(p);
                  for (int i = 0; i < 400; i++) {
                    var n = new Noise();
                    n.id = i == 399 ? "needle" : "search-" + i;
                    n.algorithm.key = "CONSTANT";
                    n.algorithm.parameters.clear();
                    n.algorithm.parameters.put("value", .125);
                    p.noise2D.add(n);
                  }
                }),
            "Fixture rejected");
        var source = ProjectIO.copy(state.project());
        GenerationTrace real;
        try (var world = state.engine.openWorld(new CompiledProject(source))) {
          real = world.trace(new BlockPos(2, 0, 3));
        }
        shown =
            new TraceSnapshot(
                source,
                state.revision,
                new GenerationTrace(
                    real.position(),
                    real.finalType(),
                    Collections.nCopies(200, real.steps().getFirst())));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(state.project());
        var user = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        var properties = new PropertyPanel(state);
        find = new ResourceFindPanel(state, properties);
        guard = new GuardedNavigation(state, properties);
        trace = new TracePanel(state, guard, text -> copied = text);
        frames(7);
        int unfiltered = find.submitted();
        float unfilteredHeight = find.resultsBounds().height(), unfilteredStride = find.rowStride();
        require(unfiltered > 0 && unfiltered < 400, "Find rows were not clipped");
        click(find.controlAt("query"));
        type("needle");
        var exact =
            new ResourceSearchIndex.Filter(
                "needle",
                "noise2D",
                "All",
                "noise2D",
                null,
                null,
                false,
                ResourceSearchIndex.Mode.ID);
        find.filter(exact);
        frames(6);
        var hit = new ResourceSearchIndex(state.project()).search(exact).getFirst();
        click(find.center(hit.key()));
        frames(3);
        require(
            find.navigationPending() && find.guardDialog.buttons().size() == 3,
            "Find row bypassed its bounded guard");
        click(find.guardDialog.button("APPLY"));
        frames(3);
        require(
            find.navigationPending()
                && !find.guardDialog.message().isEmpty()
                && state.draft == draft,
            "Rejected Find Apply lost its owned draft");
        click(find.guardDialog.button("CANCEL"));
        frames(3);
        require(
            !find.navigationPending() && find.selectedHit() != null,
            "Find Cancel lost the selected hit");
        mode = "trace";
        frames(7);
        trace.filter("Compare threshold");
        frames(7);
        var review = trace.review();
        require(
            review != null && review.value().label().equals("Compare threshold"),
            "Trace filter failed");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1200}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 300}};
        float[] global = {1, .5f, 1, 2.5f, 2.5f, 1}, multiplier = {1, 1, 1, 1, 2.5f, 1};
        Integer findId = null, valueId = null;
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = global[i];
          state.user.appearance.windowScales.put("Find", multiplier[i]);
          state.user.appearance.windowScales.put("Logs", multiplier[i]);
          mode = "find";
          if (!find.section().equals("Results")) {
            frames(4);
            click(find.controlAt("section"));
          }
          frames(7);
          require(
              find.submitted() == 1 && find.selectedHit().key().equals(hit.key()),
              "Find reflow lost exact result/selection");
          Integer id = find.rowIds().get(hit.key());
          require(id != null && (findId == null || findId.equals(id)), "Find row identity changed");
          findId = id;
          cases.add(findReceipt(i, "find-results", global[i], multiplier[i]));
          click(find.controlAt("section"));
          frames(6);
          require(
              find.section().equals("Filters") && find.selectedHit().key().equals(hit.key()),
              "Filter page replaced selection");
          cases.add(findReceipt(i, "find-filters", global[i], multiplier[i]));
          mode = "trace";
          trace.filter("Compare threshold");
          frames(7);
          require(
              trace.review().equals(review) && trace.submittedRows() == 1,
              "Trace reflow replaced exact observation");
          id = trace.rowIds().get("value:" + review.row());
          require(
              id != null && (valueId == null || valueId.equals(id)), "Trace row identity changed");
          valueId = id;
          for (var action : trace.chrome.actions().values())
            require(
                DialogBounds.contains(trace.workspaceBounds(), action),
                "Trace action outside workspace");
          var receipt = new LinkedHashMap<String, Object>();
          receipt.put("scenario", i);
          receipt.put("form", "trace");
          receipt.put("window", window);
          receipt.put("workspace", trace.workspaceBounds());
          receipt.put("body", trace.chrome.body());
          receipt.put("actions", trace.chrome.actions());
          receipt.put("steps", trace.stepsBounds());
          receipt.put("values", trace.valuesBounds());
          receipt.put("stepRows", trace.submittedSteps());
          receipt.put("rows", trace.submittedRows());
          receipt.put("stride", trace.rowStride());
          receipt.put("selectedRow", review.row());
          receipt.put("rowId", id);
          receipt.put("globalScale", global[i]);
          receipt.put("windowScale", multiplier[i]);
          cases.add(receipt);
        }
        width = 1400;
        height = 1200;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.clear();
        mode = "find";
        frames(5);
        click(find.controlAt("section"));
        frames(5);
        long builds = find.builds(), queries = find.queries();
        frames(5);
        require(
            find.builds() == builds && find.queries() == queries,
            "Find reflow rebuilt index/query");
        click(find.controlAt("navigate"));
        frames(3);
        require(find.navigationPending(), "Persistent Find source action bypassed draft guard");
        width = 360;
        height = 640;
        frames(7);
        var modal = find.layout.bounds();
        var work = find.layout.workArea().bounds();
        require(
            DialogBounds.contains(work, modal) && find.guardDialog.buttons().size() == 3,
            "Narrow Find decision outside work area");
        for (var button : find.guardDialog.buttons().values())
          require(
              DialogBounds.contains(modal, button), "Narrow Find decision action outside modal");
        escape();
        require(!find.navigationPending(), "Find Escape did not cancel");
        width = 1400;
        height = 1200;
        mode = "trace";
        trace.filter("Compare threshold");
        frames(7);
        long traceQueries = trace.queries();
        frames(4);
        require(traceQueries == trace.queries(), "Trace projection rebuilt on idle draw");
        click(trace.controlAt("control"));
        frames(3);
        require(guard.pending(), "Trace source action bypassed draft guard");
        trace.filter("Primary field");
        frames(4);
        click(guard.button("APPLY"));
        frames(3);
        require(
            !guard.pending() && state.draft == draft && state.document.historyBytes() == history,
            "Changed observation reused a pending trace source link");
        var s = real.steps().getFirst();
        String longReason = "page 🌋\\n".repeat(2000);
        var longStep =
            new GenerationTrace.Step(
                s.layerUuid(),
                s.operationUuid(),
                s.noiseUuid(),
                s.layerId(),
                s.operationId(),
                s.field(),
                s.previous(),
                s.result(),
                s.action(),
                longReason,
                s.settingsSources(),
                s.details());
        shown =
            new TraceSnapshot(
                source,
                state.revision,
                new GenerationTrace(real.position(), real.finalType(), List.of(longStep)));
        frames(5);
        trace.filter("Outcome");
        frames(5);
        click(trace.controlAt("next"));
        frames(3);
        require(trace.page() == 1, "Persistent trace paging failed");
        click(trace.controlAt("copy-value"));
        require(copied.equals(longReason), "Copy Selected Value lost unshown text pages");
        trace.filter("absent-observation");
        frames(5);
        copied = "unchanged";
        click(trace.controlAt("copy-value"));
        require(
            copied.equals("unchanged") && trace.review() == null,
            "Empty trace filter copied a previous observation");
        click(trace.controlAt("copy-trace"));
        require(copied.equals(shown.details()), "Copy Trace did not use shown snapshot");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && user.equals(ProjectIO.JSON.valueToTree(state.user))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.engine == engine
                && state.draft == draft
                && draft.id.equals("private")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Find/Trace changed project/private/runtime ownership");
        new ProjectIO().save(out.resolve("find-trace-project.json"), state.project());
        Files.writeString(
            out.resolve("trace-result.json"), ProjectIO.JSON.writeValueAsString(real));
        Files.writeString(
            out.resolve("find-trace-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("unfilteredRows", unfiltered),
                    Map.entry("unfilteredHeight", unfilteredHeight),
                    Map.entry("unfilteredStride", unfilteredStride),
                    Map.entry("findGuardRetryCancel", true),
                    Map.entry("narrowGuard", Map.of("modal", modal, "work", work)),
                    Map.entry("traceSelectionGuard", true),
                    Map.entry("fullCopyCallbacks", true),
                    Map.entry("emptyCopyBlocked", true),
                    Map.entry("cachedQueries", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("physicalClipboardVerified", false),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "FIND_TRACE_LAYOUT_NATIVE_PASS persistent measured lists / owned navigation / retry and"
                + " Cancel / full value copy");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Map<String, Object> findReceipt(
      int scenario, String form, float global, float multiplier) {
    require(
        DialogBounds.contains(window, find.chrome.body())
            && DialogBounds.contains(window, find.searchBounds())
            && DialogBounds.contains(window, find.sectionBounds()),
        "Find body/header outside client");
    for (var action : find.chrome.actions().values())
      require(DialogBounds.contains(window, action), "Find action outside client");
    var row = new LinkedHashMap<String, Object>();
    row.put("scenario", scenario);
    row.put("form", form);
    row.put("window", window);
    row.put("body", find.chrome.body());
    row.put("actions", find.chrome.actions());
    row.put("search", find.searchBounds());
    row.put("selector", find.sectionBounds());
    row.put("results", find.resultsBounds());
    row.put("rows", find.submitted());
    row.put("stride", find.rowStride());
    row.put("ids", find.rowIds());
    row.put("selected", find.selectedHit().key());
    row.put("globalScale", global);
    row.put("windowScale", multiplier);
    return row;
  }

  private static void click(float[] at) {
    require(at != null, "Missing native control");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void type(String text) {
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

  private static void escape() {
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    if (mode.equals("find")) {
      find.draw(open);
      window = find.windowLayout.bounds();
    } else {
      ImGui.begin("Trace Layout Fixture", ImGuiWindowFlags.NoSavedSettings);
      UiScale.window(state.user, "Logs");
      window =
          new DialogBounds.Rect(
              ImGui.getWindowPosX(),
              ImGui.getWindowPosY(),
              ImGui.getWindowSizeX(),
              ImGui.getWindowSizeY());
      trace.draw(shown);
      ImGui.end();
      guard.draw();
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
