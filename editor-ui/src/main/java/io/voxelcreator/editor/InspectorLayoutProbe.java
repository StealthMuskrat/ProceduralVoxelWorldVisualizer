package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred real Inspector presentation with native input and a committed analytical trace. */
public final class InspectorLayoutProbe {
  private static EditorState state;
  private static InspectorPanel panel;
  private static TracePanel trace;
  private static GuardedNavigation guard;
  private static TraceSnapshot shown;
  private static final InspectorCoordinates coordinates = new InspectorCoordinates();
  private static final Map<String, ImString> buffers = new LinkedHashMap<>();
  private static final InspectorRequests requests = new InspectorRequests();
  private static final InspectorTask task = new InspectorTask();
  private static String error = "", feedback = "", hint = "", copied = "";
  private static int width = 1200, height = 900, traceStarts, bookmarkRequests;
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
        require(state.change(TraceWorkflowProbe::fixture), "Inspector fixture rejected");
        var source = ProjectIO.copy(state.project());
        GenerationTrace result;
        try (var world = state.engine.openWorld(new CompiledProject(source))) {
          result = world.trace(new BlockPos(2, 0, 3));
        }
        shown = new TraceSnapshot(source, state.revision, result);
        setCoordinate(new BlockPos(-17, 0, 3));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private-inspector";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        var properties = new PropertyPanel(state);
        guard = new GuardedNavigation(state, properties);
        trace = new TracePanel(state, guard, text -> copied = text);
        panel =
            new InspectorPanel(
                state,
                coordinates,
                InspectorLayoutProbe::buffer,
                InspectorLayoutProbe::edit,
                key -> {});
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] global = {1, .5f, 1, 2.5f, 2.5f, 1}, multiplier = {1, 1, 1, 1, 2.5f, 1};
        Map<String, Integer> ids = null;
        TraceObservationSelection.Review review = null;
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = global[i];
          state.user.appearance.windowScales.put("Voxel Inspector", multiplier[i]);
          panel.showCoordinates(false);
          frames(7);
          var current = panel.fields.ids();
          require(
              current.size() == 3 && (ids == null || ids.equals(current)),
              "XYZ identity changed on reflow");
          ids = current;
          require(coordinates.text("X").equals("-17"), "Reflow changed raw XYZ");
          cases.add(receipt(i, global[i], multiplier[i]));
          click(panel.control("trace-page"));
          require(
              panel.section().equals("Trace") && panel.traceVisible(), "Trace page unavailable");
          trace.filter("Compare threshold");
          frames(7);
          if (review == null) review = trace.review();
          require(
              review != null && review.equals(trace.review()),
              "Page change replaced observation ownership");
          cases.add(receipt(i, global[i], multiplier[i]));
          click(panel.control("trace-page"));
          require(panel.section().equals("Coordinates"), "Coordinates page unavailable");
          click(panel.control("trace-page"));
          require(review.equals(trace.review()), "Returning to Trace lost the selected row");
        }
        width = 1200;
        height = 900;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.put("Voxel Inspector", 1f);
        panel.showCoordinates(false);
        frames(6);
        click(panel.control("trace-x"));
        type("-");
        click(panel.control("trace-page"));
        click(panel.control("trace-coordinate"));
        frames(5);
        require(
            panel.section().equals("Coordinates")
                && traceStarts == 0
                && !error.isEmpty()
                && panel.fields.pendingFocus() == null,
            "Rejected trace did not focus malformed X");
        // Type without another click: the recovery action must have focused X itself.
        type("-2147483648");
        require(coordinates.coordinate().x() == Integer.MIN_VALUE, "Recovery focus did not own X");
        click(panel.control("trace-y"));
        type("+");
        click(panel.control("trace-bookmark"));
        frames(5);
        require(
            bookmarkRequests == 0 && !error.isEmpty(), "Malformed bookmark used a fallback cell");
        type("+2147483647");
        require(
            coordinates.coordinate().y() == Integer.MAX_VALUE, "Bookmark recovery did not focus Y");
        click(panel.control("trace-coordinate"));
        require(
            task.pending() && traceStarts == 1 && panel.section().equals("Trace"),
            "Valid request did not open Trace");
        var owner = task.request();
        click(panel.control("trace-page"));
        require(task.request() == owner, "Page navigation canceled the request");
        click(panel.control("trace-page"));
        click(panel.control("trace-cancel"));
        require(!task.pending() && shown.trace() == result, "Cancel lost the retained trace");
        click(panel.control("trace-restore"));
        require(
            coordinates.coordinate().equals(result.position()) && traceStarts == 1,
            "Use Traced implicitly started work or failed to restore all axes");
        frames(5);
        require(
            panel.control("trace-restore") == null && panel.control("trace-refresh") == null,
            "Unavailable restore/refresh remained enabled");
        panel.showCoordinates(false);
        frames(4);
        click(panel.control("trace-bookmark"));
        require(
            bookmarkRequests == 1 && !task.pending(),
            "Bookmark request implicitly started trace work");
        click(panel.control("trace-page"));
        frames(4);
        click(trace.controlAt("copy-trace"));
        require(
            copied.equals(shown.details()), "Contained trace did not copy its complete snapshot");

        trace.filter("Compare threshold");
        frames(5);
        click(trace.controlAt("control"));
        require(guard.pending(), "Source link bypassed private draft guard");
        panel.showCoordinates(false);
        shown = new TraceSnapshot(source, state.revision, result);
        frames(4);
        click(guard.button("APPLY"));
        require(
            !guard.pending() && state.draft == draft,
            "Hidden trace replacement left the previous source decision current");
        panel.showTrace();
        frames(4);

        // A historical revision is reviewable without refreshing the entered cell.
        var retained = shown;
        shown = new TraceSnapshot(source, state.revision - 1, result);
        frames(5);
        require(
            panel.control("trace-refresh") != null && panel.freshnessNoticeVisible(),
            "Historical refresh missing");
        setCoordinate(new BlockPos(7, 8, 9));
        frames(4);
        click(panel.control("trace-refresh"));
        require(
            task.coordinate().equals(result.position())
                && coordinates.coordinate().equals(new BlockPos(7, 8, 9)),
            "Refresh retargeted the entered cell or rewrote XYZ");
        click(panel.control("trace-cancel"));
        var foreignSource = ProjectIO.copy(source);
        foreignSource.project.uuid = UUID.randomUUID();
        shown = new TraceSnapshot(foreignSource, state.revision, result);
        frames(4);
        require(
            panel.control("trace-refresh") == null && panel.freshnessNoticeVisible(),
            "Foreign trace offered Refresh");
        shown = null;
        hint = "No occupied voxel at this ray. Enter XYZ to inspect EMPTY.";
        panel.showCoordinates(false);
        frames(5);
        require(
            !panel.traceVisible()
                && panel.hintVisible()
                && panel.control("trace-coordinate") != null,
            "Miss/empty state lost coordinate recovery");
        shown = retained;
        hint = "";
        panel.showTrace();
        frames(5);
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && engine == state.engine
                && state.draft == draft
                && draft.id.equals("private-inspector")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Inspector changed authored/private/runtime ownership");
        new ProjectIO().save(out.resolve("inspector-layout-project.json"), state.project());
        Files.writeString(
            out.resolve("trace-result.json"), ProjectIO.JSON.writeValueAsString(result));
        Files.writeString(
            out.resolve("inspector-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("invalidFocusRecovery", true),
                    Map.entry("pagePreservedRequest", true),
                    Map.entry("cancelRetainedTrace", true),
                    Map.entry("restoreWithoutTrace", true),
                    Map.entry("bookmarkSeparated", true),
                    Map.entry("refreshPreservedEnteredXYZ", true),
                    Map.entry("foreignRefreshBlocked", true),
                    Map.entry("missRecovery", true),
                    Map.entry("fullCopyCallback", true),
                    Map.entry("hiddenPublicationInvalidated", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("physicalClipboardVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "INSPECTOR_LAYOUT_NATIVE_PASS two pages / persistent actions / exact XYZ recovery /"
                + " request and trace ownership");
      }
    } finally {
      task.close();
      requests.close();
      ImGui.destroyContext();
    }
  }

  private static ImString buffer(String axis) {
    return buffers.computeIfAbsent(
        axis, key -> new ImString(coordinates.text(key), CoordinateText.MAX_CHARACTERS + 1));
  }

  private static Map<String, String> displayed() {
    var result = new LinkedHashMap<String, String>();
    coordinates.axes().forEach(axis -> result.put(axis, buffer(axis).get()));
    return result;
  }

  private static void setCoordinate(BlockPos point) {
    coordinates.set(point);
    coordinates.axes().forEach(axis -> buffer(axis).set(coordinates.text(axis)));
    error = "";
  }

  private static void edit(String axis, String text) {
    coordinates.edit(axis, text);
    error = "";
    task.cancel();
    requests.cancelCurrent();
    feedback = "";
  }

  private static void start(BlockPos point) {
    task.begin(requests.begin(state.revision, false), point);
    traceStarts++;
    feedback = "Tracing entered coordinate...";
    panel.showTrace();
  }

  private static void dispatch(InspectorPanel.Action action) {
    try {
      switch (action) {
        case NONE -> {}
        case TRACE -> start(coordinates.coordinate(displayed()));
        case BOOKMARK -> {
          coordinates.coordinate(displayed());
          bookmarkRequests++;
        }
        case CANCEL -> {
          task.cancel();
          requests.cancelCurrent();
          feedback = "Trace canceled.";
        }
        case RESTORE -> {
          task.cancel();
          requests.cancelCurrent();
          setCoordinate(shown.trace().position());
        }
        case REFRESH -> start(shown.trace().position());
      }
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      error = invalid.getMessage();
      panel.showCoordinates(true);
    }
  }

  private static Map<String, Object> receipt(int scenario, float global, float multiplier) {
    require(DialogBounds.contains(window, panel.chrome.body()), "Inspector body outside window");
    for (var rect : panel.chrome.actions().values())
      require(DialogBounds.contains(window, rect), "Inspector action outside window");
    var row = new LinkedHashMap<String, Object>();
    row.put("scenario", scenario);
    row.put("form", panel.section());
    row.put("window", window);
    row.put("body", panel.chrome.body());
    row.put("actions", panel.chrome.actions());
    row.put("globalScale", global);
    row.put("windowScale", multiplier);
    if (panel.section().equals("Coordinates")) {
      row.put("ids", panel.fields.ids());
      row.put("columns", panel.fields.plan().columns());
      var fields = new LinkedHashMap<String, DialogBounds.Rect>();
      for (var axis : coordinates.axes()) fields.put(axis, panel.fields.bounds(axis));
      row.put("fields", fields);
    } else {
      require(
          DialogBounds.contains(panel.chrome.body(), trace.workspaceBounds()),
          "Trace workspace outside Inspector body");
      row.put("traceWorkspace", trace.workspaceBounds());
      row.put("traceBody", trace.chrome.body());
      row.put("traceActions", trace.chrome.actions());
      for (var rect : trace.chrome.actions().values())
        require(
            DialogBounds.contains(panel.chrome.body(), rect),
            "Contained trace action outside Inspector body");
    }
    return row;
  }

  private static void click(float[] at) {
    require(at != null, "Missing Inspector native control");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(4);
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

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Voxel Inspector", ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, "Voxel Inspector");
    window =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    var action =
        panel.draw(
            () -> new InspectorPanel.Status(shown, task.request(), feedback, hint, error), trace);
    ImGui.end();
    guard.draw();
    ImGui.render();
    dispatch(action);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
