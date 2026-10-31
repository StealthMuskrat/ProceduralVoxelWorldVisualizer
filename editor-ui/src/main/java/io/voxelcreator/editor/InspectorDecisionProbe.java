package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.*;
import io.voxelcreator.api.GenerationTrace;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/** Deferred exact Inspector footer decisions. Owned futures are attached but never run. */
public final class InspectorDecisionProbe {
  private static EditorState state;
  private static InspectorPanel panel;
  private static TracePanel trace;
  private static ProjectData source;
  private static GenerationTrace result;
  private static TraceSnapshot shown;
  private static final InspectorCoordinates coordinates = new InspectorCoordinates();
  private static final Map<String, ImString> buffers = new LinkedHashMap<>();
  private static final InspectorRequests requests = new InspectorRequests();
  private static final InspectorTask task = new InspectorTask();
  private static final ImBoolean open = new ImBoolean(true);
  private static final AtomicInteger workersRan = new AtomicInteger();
  private static final List<Delivery> delivered = new ArrayList<>();
  private static final List<Map<String, String>> rejected = new ArrayList<>();
  private static FutureTask<Integer> ownedFuture;
  private static Runnable duringFooter;
  private static String feedback = "", hint = "", error = "";
  private static int width = 1200, height = 900, traceStarts, bookmarks;
  private static float global = 1, multiplier = 1;
  private static boolean collapsed;
  private static DialogBounds.Rect window;

  private record Delivery(
      InspectorPanel.Action action, BlockPos coordinate, long request, boolean pick) {}

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
        require(state.change(TraceWorkflowProbe::fixture), "Fixture rejected");
        var projectFile = out.resolve("protected-project.json");
        state.document.save(projectFile);
        require(state.document.open(projectFile).isEmpty(), "Fixture reopen failed");
        source = ProjectIO.copy(state.project());
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
                "fixture result",
                Map.of());
        result = new GenerationTrace(new BlockPos(2, 0, 3), 1, List.of(step));
        shown = new TraceSnapshot(source, state.revision - 1, result);
        setCoordinates(new BlockPos(-17, 0, 3));
        replaceTask(false, false);
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = state.draft;
        ((Noise) draft).id = "private-inspector";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        panel = newPanel();
        trace =
            new TracePanel(
                state, new GuardedNavigation(state, new PropertyPanel(state)), text -> {});
        var cases = new ArrayList<Map<String, Object>>();
        capture(cases, false);
        capture(cases, true);
        dimensions(1200, 900, 1, 1);
        frames(5);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        prepare(true, false);
        for (var key : keys()) {
          var owner = task.request();
          var future = ownedFuture;
          var snapshot = shown;
          long version = coordinates.version();
          String page = panel.section();
          escape(at(key));
          require(
              decisions() == 0
                  && owner == task.request()
                  && !future.isCancelled()
                  && snapshot == shown
                  && version == coordinates.version()
                  && page.equals(panel.section()),
              "Escape authorized Inspector footer");
        }
        for (var key : keys()) {
          prepare(true, false);
          var owner = task.request();
          var future = ownedFuture;
          int id = panel.chrome.actionIds().get(key);
          press(at(key));
          coordinates.edit("X", "99");
          coordinates.edit("X", "-17");
          buffer("X").set("-17");
          frame();
          release();
          frames(3);
          require(
              decisions() == 0
                  && owner == task.request()
                  && !future.isCancelled()
                  && panel.section().equals("Coordinates")
                  && id != panel.chrome.actionIds().get(key),
              "Held Inspector action adopted equal-valued input round trip");
        }
        prepare(true, false);
        press(at("trace-coordinate"));
        buffer("X").set("73");
        frame();
        release();
        frames(3);
        require(
            decisions() == 0
                && coordinates.text("X").equals("-17")
                && buffer("X").get().equals("73"),
            "Held Trace adopted unaccepted native display text");
        for (var key : List.of("trace-restore", "trace-refresh")) {
          prepare(true, false);
          press(at(key));
          shown = new TraceSnapshot(source, state.revision - 1, result);
          frame();
          release();
          frames(3);
          require(
              decisions() == 0 && coordinates.coordinate().equals(new BlockPos(-17, 0, 3)),
              "Held snapshot action adopted replacement trace");
        }
        for (int variant = 0; variant < 3; variant++) {
          prepare(true, false);
          press(at("trace-cancel"));
          replaceTask(variant == 1, variant == 2);
          var successor = task.request();
          var future = ownedFuture;
          frame();
          release();
          frames(3);
          require(
              decisions() == 0 && successor == task.request() && !future.isCancelled(),
              "Held Cancel stopped successor Inspector request");
        }
        prepare(true, false);
        press(at("trace-cancel"));
        duringFooter = () -> replaceTask(false, false);
        release();
        frames(3);
        require(
            decisions() == 0 && task.pending() && !ownedFuture.isCancelled(),
            "Same-frame footer Cancel accepted a replaced request");
        prepare(true, false);
        press(at("trace-coordinate"));
        duringFooter = () -> buffer("X").set("91");
        release();
        frames(3);
        require(
            decisions() == 0
                && coordinates.text("X").equals("-17")
                && buffer("X").get().equals("91"),
            "Same-frame footer accepted changed display text");
        prepare(true, false);
        press(at("trace-coordinate"));
        panel.showTrace();
        frame();
        release();
        frames(3);
        require(
            decisions() == 0 && panel.section().equals("Trace"),
            "Held Trace crossed a page presentation");
        prepare(true, false);
        setCoordinates(result.position());
        frames(4);
        press(at("trace-restore"));
        setCoordinates(new BlockPos(7, 8, 9));
        frame();
        release();
        frames(3);
        require(
            decisions() == 0 && coordinates.coordinate().equals(new BlockPos(7, 8, 9)),
            "Disabled Restore became successor activation");
        shown = new TraceSnapshot(source, state.revision, result);
        frames(4);
        press(at("trace-refresh"));
        shown = new TraceSnapshot(source, state.revision - 1, result);
        frame();
        release();
        frames(3);
        require(decisions() == 0, "Disabled Refresh became successor activation");
        for (var key : keys()) {
          prepare(true, false);
          press(at(key));
          require(state.document.open(projectFile).isEmpty(), "Same-UUID reopen failed");
          frame();
          release();
          frames(3);
          require(
              decisions() == 0 && task.pending() && !ownedFuture.isCancelled(),
              "Held Inspector action crossed document lifetime");
        }
        for (boolean page : List.of(false, true)) {
          prepare(true, page);
          var owner = task.request();
          press(at("trace-coordinate"));
          open.set(false);
          frame();
          open.set(true);
          frame();
          release();
          frames(3);
          require(
              decisions() == 0 && owner == task.request() && !ownedFuture.isCancelled(),
              "Hidden Inspector retained held action or canceled owned work");
          press(at("trace-coordinate"));
          collapsed = true;
          frame();
          collapsed = false;
          frame();
          release();
          frames(3);
          require(
              decisions() == 0 && owner == task.request() && !ownedFuture.isCancelled(),
              "Collapsed Inspector retained held action or canceled owned work");
        }
        prepare(true, false);
        int old = panel.chrome.actionIds().get("trace-coordinate");
        press(at("trace-coordinate"));
        panel = newPanel();
        frame();
        release();
        frames(3);
        require(
            decisions() == 0 && old != panel.chrome.actionIds().get("trace-coordinate"),
            "Recreated Inspector adopted predecessor action");
        // Progress/feedback alone does not replace the exact pending request being canceled.
        prepare(true, false);
        var owner = task.request();
        var future = ownedFuture;
        var ids = panel.chrome.actionIds();
        press(at("trace-cancel"));
        feedback = "progress changed";
        hint = "different hint";
        error = "view-only feedback";
        frame();
        require(
            ids.equals(panel.chrome.actionIds()), "Feedback churned Inspector action ownership");
        release();
        frames(3);
        require(
            delivered.size() == 1
                && delivered.getFirst().action() == InspectorPanel.Action.CANCEL
                && delivered.getFirst().request() == owner.serial()
                && future.isCancelled()
                && !task.pending(),
            "Explicit current Cancel missed its owned future");
        // An explicit mismatched native display is rejected by the existing raw input authority.
        prepare(false, false);
        buffer("X").set("100");
        frames(4);
        click(at("trace-coordinate"));
        require(
            rejected.size() == 1
                && error.contains("Displayed X")
                && coordinates.text("X").equals("-17")
                && traceStarts == 0,
            "Explicit invalid Trace adopted display text or an old parsed point");
        type("-2147483648");
        require(
            coordinates.coordinate().x() == Integer.MIN_VALUE, "Mismatch recovery did not focus X");
        click(at("trace-coordinate"));
        require(
            traceStarts == 1
                && task.pending()
                && task.coordinate().equals(new BlockPos(Integer.MIN_VALUE, 0, 3)),
            "Explicit signed Trace routed wrong point");
        owner = task.request();
        click(at("trace-page"));
        require(
            panel.section().equals("Coordinates") && owner == task.request(),
            "Page switch canceled or replaced work");
        click(at("trace-bookmark"));
        require(bookmarks == 1 && owner == task.request(), "Bookmark started/replaced work");
        click(at("trace-page"));
        click(at("trace-cancel"));
        require(!task.pending(), "Explicit Trace cancel failed");
        click(at("trace-restore"));
        require(
            coordinates.coordinate().equals(result.position()) && traceStarts == 1,
            "Use Traced started work or restored wrong point");
        setCoordinates(new BlockPos(7, 8, 9));
        frames(4);
        click(at("trace-refresh"));
        require(
            traceStarts == 2
                && task.coordinate().equals(result.position())
                && coordinates.coordinate().equals(new BlockPos(7, 8, 9)),
            "Refresh replaced entered XYZ or traced wrong point");
        click(at("trace-cancel"));
        replaceTask(true, false);
        panel.showTrace();
        frames(4);
        future = ownedFuture;
        click(at("trace-cancel"));
        require(
            future.isCancelled() && !task.pending() && delivered.getLast().pick(),
            "Explicit Pick Cancel missed its request");
        var foreign = ProjectIO.copy(source);
        foreign.project.uuid = UUID.randomUUID();
        shown = new TraceSnapshot(foreign, state.revision - 1, result);
        frames(4);
        int count = decisions();
        click(at("trace-refresh"));
        require(decisions() == count, "Foreign snapshot offered Refresh");
        shown = new TraceSnapshot(source, state.revision - 1, result);
        panel.showCoordinates(false);
        frames(4);
        require(
            delivered.stream()
                .map(Delivery::action)
                .toList()
                .equals(
                    List.of(
                        InspectorPanel.Action.CANCEL,
                        InspectorPanel.Action.TRACE,
                        InspectorPanel.Action.BOOKMARK,
                        InspectorPanel.Action.CANCEL,
                        InspectorPanel.Action.RESTORE,
                        InspectorPanel.Action.REFRESH,
                        InspectorPanel.Action.CANCEL,
                        InspectorPanel.Action.CANCEL)),
            "Explicit Inspector decision sequence changed");
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
                && engine.profiler().current().isEmpty()
                && workersRan.get() == 0,
            "Inspector decisions changed project/preferences/private/runtime ownership or ran"
                + " fixture work");
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("reviewed-trace.json"), ProjectIO.JSON.writeValueAsString(result));
        Files.writeString(
            out.resolve("inspector-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("traceSource", "synthetic-presentation"),
                    Map.entry("cases", cases),
                    Map.entry("escapePreserved", true),
                    Map.entry("heldInputOwnersBlocked", true),
                    Map.entry("requestIdentityScoped", true),
                    Map.entry("snapshotOwnersScoped", true),
                    Map.entry("sameFrameRechecked", true),
                    Map.entry("disabledActionsBlocked", true),
                    Map.entry("documentLifetimeScoped", true),
                    Map.entry("hiddenContextsReleased", true),
                    Map.entry("instanceScopesDistinct", true),
                    Map.entry("feedbackIndependent", true),
                    Map.entry("invalidDisplayRejected", true),
                    Map.entry("explicitActionsRouted", true),
                    Map.entry("foreignRefreshBlocked", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("delivered", delivered),
                    Map.entry("rejected", rejected),
                    Map.entry("traceStarts", traceStarts),
                    Map.entry("bookmarks", bookmarks),
                    Map.entry("workersRan", workersRan.get()),
                    Map.entry("finalCoordinate", coordinates.coordinate()),
                    Map.entry("finalPage", panel.section()),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "INSPECTOR_DECISIONS_NATIVE_PASS exact raw/display/request/snapshot context / Escape /"
                + " current footer recheck / future cancellation / explicit routes");
      }
    } finally {
      task.close();
      requests.close();
      ImGui.destroyContext();
    }
  }

  private static InspectorPanel newPanel() {
    return new InspectorPanel(
        state,
        coordinates,
        InspectorDecisionProbe::buffer,
        InspectorDecisionProbe::edit,
        key -> {
          if (key.equals("trace-page") && duringFooter != null) {
            var change = duringFooter;
            duringFooter = null;
            change.run();
          }
        });
  }

  private static InspectorPanel.Status status() {
    return new InspectorPanel.Status(shown, task.request(), feedback, hint, error);
  }

  private static ImString buffer(String axis) {
    return buffers.computeIfAbsent(
        axis, key -> new ImString(coordinates.text(key), CoordinateText.MAX_CHARACTERS + 1));
  }

  private static Map<String, String> displayed() {
    var values = new LinkedHashMap<String, String>();
    for (var axis : coordinates.axes()) values.put(axis, buffer(axis).get());
    return values;
  }

  private static void setCoordinates(BlockPos point) {
    coordinates.set(point);
    for (var axis : coordinates.axes()) buffer(axis).set(coordinates.text(axis));
    error = "";
  }

  private static void edit(String axis, String text) {
    coordinates.edit(axis, text);
    error = "";
    task.cancel();
    requests.cancelCurrent();
  }

  private static void replaceTask(boolean pick, boolean clone) {
    var previous = task.request();
    var owner =
        clone
            ? new InspectorRequests.Request(
                previous.serial(),
                previous.revision(),
                previous.viewVersion(),
                previous.viewBound())
            : requests.begin(state.revision, pick);
    task.begin(owner, owner.viewBound() ? null : coordinates.coordinate());
    ownedFuture = new FutureTask<>(workersRan::incrementAndGet);
    task.attach(owner, ownedFuture);
  }

  private static void prepare(boolean pending, boolean page) {
    duringFooter = null;
    task.cancel();
    requests.cancelCurrent();
    setCoordinates(new BlockPos(-17, 0, 3));
    shown = new TraceSnapshot(source, state.revision - 1, result);
    feedback = "working";
    hint = "";
    error = "";
    if (pending) replaceTask(false, false);
    if (page) panel.showTrace();
    else panel.showCoordinates(false);
    frames(4);
  }

  private static int decisions() {
    return delivered.size() + rejected.size();
  }

  private static void dispatch(InspectorPanel.Action action) {
    if (action == InspectorPanel.Action.NONE) return;
    var owner = task.request();
    long serial = owner == null ? 0 : owner.serial();
    boolean pick = owner != null && owner.viewBound();
    BlockPos point = null;
    try {
      switch (action) {
        case TRACE -> {
          point = coordinates.coordinate(displayed());
          start(point);
        }
        case BOOKMARK -> {
          point = coordinates.coordinate(displayed());
          bookmarks++;
        }
        case CANCEL -> {
          point = task.coordinate();
          task.cancel();
          requests.cancelCurrent();
          feedback = "Canceled";
        }
        case RESTORE -> {
          point = shown.trace().position();
          task.cancel();
          requests.cancelCurrent();
          setCoordinates(point);
        }
        case REFRESH -> {
          point = shown.trace().position();
          start(point);
        }
        default -> throw new AssertionError(action);
      }
      delivered.add(new Delivery(action, point, serial, pick));
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      error = invalid.getMessage();
      task.cancel();
      requests.cancelCurrent();
      rejected.add(Map.of("action", action.name(), "message", error));
      panel.showCoordinates(true);
    }
  }

  private static void start(BlockPos point) {
    var owner = requests.begin(state.revision, false);
    task.begin(owner, point);
    ownedFuture = new FutureTask<>(workersRan::incrementAndGet);
    task.attach(owner, ownedFuture);
    traceStarts++;
    feedback = "Tracing";
    panel.showTrace();
  }

  private static List<String> keys() {
    return List.of(
        "trace-coordinate",
        "trace-cancel",
        "trace-bookmark",
        "trace-restore",
        "trace-refresh",
        "trace-page");
  }

  private static float[] at(String key) {
    var r = panel.chrome.actions().get(key);
    require(r != null, "Missing Inspector action " + key);
    return new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2};
  }

  private static void capture(List<Map<String, Object>> cases, boolean page) {
    if (page) panel.showTrace();
    else panel.showCoordinates(false);
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      if (ids == null) ids = panel.chrome.actionIds();
      require(ids.equals(panel.chrome.actionIds()), "Reflow changed Inspector footer owner");
      cases.add(
          Map.ofEntries(
              Map.entry("form", panel.section()),
              Map.entry("scenario", i),
              Map.entry("globalScale", global),
              Map.entry("windowScale", multiplier),
              Map.entry("window", window),
              Map.entry("body", panel.chrome.body()),
              Map.entry("actions", panel.chrome.actions()),
              Map.entry("ids", panel.chrome.actionIds()),
              Map.entry("fieldIds", page ? Map.of() : panel.fields.ids())));
    }
  }

  private static void dimensions(int w, int h, float scale, float windowScale) {
    width = w;
    height = h;
    global = scale;
    multiplier = windowScale;
    state.user.appearance.windowScales.put("Voxel Inspector", windowScale);
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
    frames(4);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    if (open.get()) {
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
      boolean visible = ImGui.begin("Voxel Inspector", open);
      UiScale.window(state.user, "Voxel Inspector");
      window =
          new DialogBounds.Rect(
              ImGui.getWindowPosX(),
              ImGui.getWindowPosY(),
              ImGui.getWindowSizeX(),
              ImGui.getWindowSizeY());
      if (visible && open.get()) dispatch(panel.draw(InspectorDecisionProbe::status, trace));
      else {
        panel.suspend();
        trace.hide(shown);
      }
      ImGui.end();
    } else {
      panel.suspend();
      trace.hide(shown);
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
