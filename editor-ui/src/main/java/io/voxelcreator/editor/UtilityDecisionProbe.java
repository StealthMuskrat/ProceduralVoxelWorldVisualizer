package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.api.GenerationTrace;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred Find/Trace footer decisions through native IO; source data and failures are fixtures.
 */
public final class UtilityDecisionProbe {
  private static EditorState state;
  private static ResourceFindPanel find;
  private static TracePanel trace;
  private static GuardedNavigation guard;
  private static TraceSnapshot shown;
  private static final ImBoolean open = new ImBoolean(true), hidden = new ImBoolean(false);
  private static String form = "Results";
  private static int width = 1200, height = 900;
  private static float global = 1, multiplier = 1;
  private static boolean collapsed, failCopy, hosts, skipMain;
  private static Map<String, Integer> mainIds, secondIds;
  private static float[] mainCopy;
  private static final List<String> copied = new ArrayList<>();

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
                  var n = new Noise();
                  n.id = "needle";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        var projectFile = out.resolve("protected-project.json");
        state.document.save(projectFile);
        require(state.document.open(projectFile).isEmpty(), "Fixture reopen failed");
        var source = ProjectIO.copy(state.project());
        String reason = "reason 🌋 ### snow 雪\n".repeat(1000);
        var layer = source.worldGenerator.layers.getFirst();
        var operation = layer.operations.getFirst();
        var step =
            new GenerationTrace.Step(
                layer.uuid,
                operation.uuid,
                source.noise2D.getFirst().uuid,
                layer.id,
                operation.id,
                22,
                0,
                1,
                "SET",
                reason,
                Map.of());
        var synthetic = new GenerationTrace(new BlockPos(2, 0, 3), 1, List.of(step, step));
        shown = new TraceSnapshot(source, state.revision, synthetic);
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = state.draft;
        ((Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        var properties = new PropertyPanel(state);
        find = new ResourceFindPanel(state, properties);
        guard = new GuardedNavigation(state, properties);
        trace =
            new TracePanel(
                state,
                guard,
                text -> {
                  if (failCopy) throw new IllegalStateException("copy boundary");
                  copied.add(text);
                });
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
        require(find.navigationPending(), "Find result bypassed private draft guard");
        click(find.guardDialog.button("CANCEL"));
        require(
            !find.navigationPending() && find.selectedHit() != null, "Find Cancel lost selection");
        var cases = new ArrayList<Map<String, Object>>();
        capture(cases);
        click(find.controlAt("section"));
        form = "Filters";
        capture(cases);
        form = "Trace";
        trace.filter("Outcome");
        capture(cases);
        dimensions(1200, 900, 1, 1);
        frames(5);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        form = "Results";
        frames(4);
        click(find.controlAt("section"));
        require(find.section().equals("Results"), "Find section did not return");
        click(find.center(hit.key()));
        click(find.guardDialog.button("CANCEL"));
        for (var key : List.of("navigate", "section", "clear")) {
          String before = find.query(), section = find.section();
          escape(find.controlAt(key));
          require(
              !find.navigationPending()
                  && find.query().equals(before)
                  && find.section().equals(section),
              "Escape authorized Find footer");
        }
        for (var key : List.of("navigate", "section", "clear")) {
          find.query("needle");
          frames(4);
          int id = find.chrome.actionIds().get(key);
          press(find.controlAt(key));
          find.query(" NEEDLE ");
          frame();
          release();
          frames(3);
          require(
              id != find.chrome.actionIds().get(key)
                  && find.query().equals(" NEEDLE ")
                  && find.section().equals("Results")
                  && !find.navigationPending(),
              "Held Find action adopted new raw query");
        }
        click(find.controlAt("section"));
        require(find.section().equals("Filters"), "Explicit Show Filters failed");
        click(find.controlAt("clear"));
        require(
            find.section().equals("Results")
                && find.query().isEmpty()
                && find.selectedHit() != null,
            "Explicit Clear Filters lost selection or did not reset filters");
        find.query("needle");
        frames(4);
        press(find.controlAt("clear"));
        collapsed = true;
        frame();
        collapsed = false;
        frame();
        release();
        frames(3);
        require(find.query().equals("needle"), "Collapsed Find retained held Clear");
        press(find.controlAt("section"));
        open.set(false);
        frame();
        open.set(true);
        frame();
        release();
        frames(3);
        require(find.section().equals("Results"), "Hidden Find retained held section toggle");
        // Restore a selection via the actual result and cancel its private-draft decision.
        frames(4);
        click(find.center(hit.key()));
        click(find.guardDialog.button("CANCEL"));
        press(find.controlAt("navigate"));
        require(state.document.open(projectFile).isEmpty(), "Same-UUID reopen failed");
        frame();
        release();
        frames(3);
        require(
            !find.navigationPending() && find.selectedHit() == null,
            "Held Find navigation crossed document lifetime");
        form = "Trace";
        frames(5);
        click(trace.controlAt("next"));
        require(trace.page() == 1, "Trace fixture page advance failed");
        for (var key : traceKeys()) {
          int page = trace.page();
          escape(trace.controlAt(key));
          require(
              copied.isEmpty() && !guard.pending() && trace.page() == page,
              "Escape authorized Trace footer");
        }
        for (var key : traceKeys()) {
          trace.filter("Outcome");
          frames(4);
          int id = trace.chrome.actionIds().get(key), page = trace.page();
          long builds = trace.queries();
          press(trace.controlAt(key));
          trace.filter(" Outcome ");
          frame();
          release();
          frames(3);
          require(
              copied.isEmpty()
                  && !guard.pending()
                  && trace.page() == page
                  && builds == trace.queries()
                  && id != trace.chrome.actionIds().get(key),
              "Held Trace action adopted equal-normalized raw query");
        }
        press(trace.controlAt("copy-value"));
        trace.selectStep(1);
        frame();
        release();
        frames(3);
        require(
            copied.isEmpty() && trace.review().step() == 1, "Held Copy adopted another trace step");
        press(trace.controlAt("copy-trace"));
        shown = new TraceSnapshot(source, state.revision, synthetic);
        frame();
        release();
        frames(3);
        require(copied.isEmpty(), "Held Copy adopted equal-looking replacement snapshot");
        trace.filter("Outcome");
        frames(4);
        press(trace.controlAt("copy-value"));
        open.set(false);
        frame();
        open.set(true);
        frame();
        release();
        frames(3);
        require(copied.isEmpty(), "Hidden Trace kept predecessor action alive");
        press(trace.controlAt("copy-value"));
        collapsed = true;
        frame();
        collapsed = false;
        frame();
        release();
        frames(3);
        require(copied.isEmpty(), "Collapsed Trace kept predecessor action alive");
        // Both Logs and Notifications can present the same TracePanel in one native frame.
        hosts = true;
        frames(5);
        var first = mainIds;
        var second = secondIds;
        frames(5);
        require(
            first.equals(mainIds) && second.equals(secondIds) && !first.equals(second),
            "Two trace hosts churned or shared footer IDs");
        press(mainCopy);
        skipMain = true;
        frame();
        skipMain = false;
        frame();
        release();
        frames(3);
        require(
            copied.isEmpty() && !first.equals(mainIds) && second.equals(secondIds),
            "Visible second host kept hidden main gesture alive");
        hosts = false;
        frames(5);
        trace.filter("Outcome");
        frames(4);
        // Pending guarded navigation still owns exact raw query, independent of its projection
        // cache.
        click(trace.controlAt("control"));
        require(guard.pending(), "Trace control bypassed private draft guard");
        trace.filter(" Outcome ");
        frames(3);
        click(guard.button("DISCARD"));
        require(
            !guard.pending()
                && state.draft == draft
                && state.selected.equals(source.noise2D.getFirst().uuid),
            "Changed Trace query allowed stale Discard navigation");
        click(trace.controlAt("voxel"));
        require(guard.pending(), "Trace voxel bypassed private draft guard");
        click(guard.button("CANCEL"));
        require(!guard.pending(), "Trace voxel Cancel failed");
        for (var key : List.of("copy-value", "copy-trace")) {
          var ids = trace.chrome.actionIds();
          failCopy = true;
          click(trace.controlAt(key));
          failCopy = false;
          require(
              ids.equals(trace.chrome.actionIds()),
              "Clipboard failure invalidated reviewed Trace retry");
          click(trace.controlAt(key));
        }
        require(
            copied.equals(List.of(reason, shown.details())),
            "Trace copy retry changed full immutable payloads");
        trace.filter("absent-observation");
        frames(4);
        click(trace.controlAt("copy-value"));
        require(
            copied.size() == 2 && trace.review() == null,
            "Empty Trace copied previous observation");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Utility decisions changed project/preferences/private/runtime ownership");
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(out.resolve("trace-value.txt"), reason);
        Files.writeString(out.resolve("trace-details.txt"), shown.details());
        Files.writeString(
            out.resolve("utility-events.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        Files.writeString(
            out.resolve("utility-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("escapePreserved", true),
                    Map.entry("heldFindQueriesBlocked", true),
                    Map.entry("findClearRetainedSelection", true),
                    Map.entry("heldTraceOwnersBlocked", true),
                    Map.entry("hiddenContextsReleased", true),
                    Map.entry("documentLifetimeScoped", true),
                    Map.entry("independentTraceHosts", true),
                    Map.entry("pendingQueryScoped", true),
                    Map.entry("copyFailuresContained", true),
                    Map.entry("emptyCopyBlocked", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("copied", copied),
                    Map.entry("mainHostIds", first),
                    Map.entry("secondHostIds", second),
                    Map.entry("physicalClipboardVerified", false),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "UTILITY_DECISIONS_NATIVE_PASS Find raw owners / Trace query-page-host scopes / Escape"
                + " / clipboard retry / private guards");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static List<String> traceKeys() {
    return List.of("copy-trace", "voxel", "control", "copy-value", "previous", "next");
  }

  private static void capture(List<Map<String, Object>> cases) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      var chrome = form.equals("Trace") ? trace.chrome : find.chrome;
      if (ids == null) ids = chrome.actionIds();
      require(ids.equals(chrome.actionIds()), "Reflow changed utility action owner");
      cases.add(
          Map.ofEntries(
              Map.entry("form", form),
              Map.entry("scenario", i),
              Map.entry("globalScale", global),
              Map.entry("windowScale", multiplier),
              Map.entry(
                  "window",
                  form.equals("Trace") ? trace.workspaceBounds() : find.windowLayout.bounds()),
              Map.entry("body", chrome.body()),
              Map.entry("actions", chrome.actions()),
              Map.entry("ids", chrome.actionIds())));
    }
  }

  private static void dimensions(int w, int h, float scale, float windowScale) {
    width = w;
    height = h;
    global = scale;
    multiplier = windowScale;
    state.user.appearance.windowScales.put("Find", windowScale);
  }

  private static void press(float[] at) {
    require(at != null, "Missing utility control");
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

  private static void click(float[] at) {
    press(at);
    release();
    frames(4);
  }

  private static void escape(float[] at) {
    press(at);
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
    io.setDisplaySize(hosts ? 1480 : width + 32, hosts ? 932 : height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    if (form.equals("Trace")) {
      find.draw(hidden);
      if (hosts) {
        if (!skipMain) {
          traceRoot("Utility Trace", 16, 16, 700, 900);
          mainIds = trace.chrome.actionIds();
          mainCopy = trace.controlAt("copy-value");
        }
        traceRoot("Utility Secondary Trace", 748, 16, 700, 900);
        secondIds = trace.chrome.actionIds();
      } else if (open.get()) traceRoot("Utility Trace", 16, 16, width, height);
      else trace.hide(shown);
    } else {
      trace.hide(shown);
      if (open.get()) {
        ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
        ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
        ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
        ImGui.setNextWindowFocus();
      }
      find.draw(open);
    }
    guard.draw();
    ImGui.render();
  }

  private static void traceRoot(String title, int x, int y, int w, int h) {
    ImGui.setNextWindowPos(x, y, ImGuiCond.Always);
    ImGui.setNextWindowSize(w, h, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
    boolean visible = ImGui.begin(title);
    ImGui.setWindowFontScale(multiplier);
    if (visible) trace.draw(shown);
    else trace.hide(shown);
    ImGui.end();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
