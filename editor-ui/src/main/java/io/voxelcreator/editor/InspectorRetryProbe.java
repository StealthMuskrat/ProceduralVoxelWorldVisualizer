package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred production retry footer/presentation; dispatch uses a synchronous real-engine fixture.
 */
public final class InspectorRetryProbe {
  private static EditorState state;
  private static InspectorPanel panel;
  private static TracePanel tracePanel;
  private static TraceSnapshot shown, original;
  private static InspectorTraceRetry retry;
  private static InspectorRequests.Request pending;
  private static final InspectorRequests requests = new InspectorRequests();
  private static final InspectorCoordinates coordinates = new InspectorCoordinates();
  private static final Map<String, ImString> buffers = new LinkedHashMap<>();
  private static final List<Map<String, Object>> cases = new ArrayList<>(),
      deliveries = new ArrayList<>();
  private static Runnable duringFooter;
  private static boolean visible = true;
  private static int width = 1200, height = 900;
  private static float global = 1, scale = 1;

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
        var file = out.resolve("retry-project.json");
        state.document.save(file);
        require(state.document.open(file).isEmpty(), "Fixture reopen failed");
        original = capture(new BlockPos(2, 0, 3));
        shown = original;
        coordinates.set(new BlockPos(-17, 0, 3));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = state.draft;
        ((ProjectData.Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        panel =
            new InspectorPanel(
                state,
                coordinates,
                InspectorRetryProbe::buffer,
                InspectorRetryProbe::edit,
                key -> {
                  if (key.equals("trace-page") && duringFooter != null) {
                    var change = duringFooter;
                    duringFooter = null;
                    change.run();
                  }
                });
        tracePanel =
            new TracePanel(
                state, new GuardedNavigation(state, new PropertyPanel(state)), value -> {});
        var oldScale = state.user.appearance.windowScales.get("Voxel Inspector");
        retry = newRetry();
        captureCases(false);
        captureCases(true);
        width = 1200;
        height = 900;
        global = scale = 1;
        if (oldScale == null) state.user.appearance.windowScales.remove("Voxel Inspector");
        else state.user.appearance.windowScales.put("Voxel Inspector", oldScale);
        frames(3);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();

        ready();
        press();
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
        release();
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
        frames(2);
        none();
        ready();
        press();
        retry = newRetry();
        frame();
        release();
        none();
        ready();
        press();
        pending = requests.begin(state.document.sessionToken(), state.revision, false);
        frame();
        release();
        pending = null;
        frames(2);
        none();
        ready();
        press();
        require(state.document.edit(p -> p.project.seed++).isEmpty(), "Canonical edit rejected");
        frame();
        release();
        none();
        require(state.document.open(file).isEmpty(), "Fixture restore failed");
        ready();
        press();
        require(state.document.open(file).isEmpty(), "Held retry reopen failed");
        frame();
        release();
        none();
        ready();
        press();
        edit("X", "8");
        edit("X", "-17");
        frame();
        release();
        none();
        ready();
        press();
        duringFooter = () -> retry = newRetry();
        release();
        none();
        ready();
        press();
        visible = false;
        frames(3);
        visible = true;
        frame();
        release();
        none();

        shown = null;
        ready();
        click();
        require(
            deliveries.size() == 1
                && retry == null
                && shown.trace().position().equals(new BlockPos(7, 0, 3)),
            "First-result retry failed");
        shown = original;
        ready();
        click();
        require(
            deliveries.size() == 2
                && retry == null
                && shown.trace().position().equals(new BlockPos(7, 0, 3)),
            "Retry adopted the older displayed coordinate");
        require(
            coordinates.coordinate().equals(new BlockPos(-17, 0, 3)), "Retry changed entered XYZ");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Retry changed authored state");
        require(
            state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Retry changed private draft/raw seed");
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Retry changed preferences");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("retry-result.json"), ProjectIO.JSON.writeValueAsString(shown.trace()));
        Files.writeString(
            out.resolve("inspector-retry-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("deliveries", deliveries),
                    Map.entry("escapePreserved", true),
                    Map.entry("attemptScoped", true),
                    Map.entry("pendingScoped", true),
                    Map.entry("canonicalScoped", true),
                    Map.entry("documentScoped", true),
                    Map.entry("rawRoundTripScoped", true),
                    Map.entry("postDrawRechecked", true),
                    Map.entry("hiddenScoped", true),
                    Map.entry("privatePreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("backgroundRetryVerified", false))));
        System.out.println(
            "INSPECTOR_RETRY_NATIVE_PASS exact failed target / owned retry footer / no-result"
                + " recovery / entered XYZ and private drafts preserved");
      }
    } finally {
      requests.close();
      ImGui.destroyContext();
    }
  }

  private static InspectorTraceRetry newRetry() {
    return InspectorTraceRetry.capture(
        state,
        requests.begin(state.document.sessionToken(), state.revision, false),
        new BlockPos(7, 0, 3),
        coordinates.version());
  }

  private static InspectorPanel.Status status() {
    return new InspectorPanel.Status(shown, pending, "Failed coordinate attempt", "", "", retry);
  }

  private static TraceSnapshot capture(BlockPos position) {
    var source = ProjectIO.copy(state.project());
    try (var world = state.engine.openWorld(new CompiledProject(source))) {
      var result = world.trace(position);
      require(result.finalType() == world.getVoxelTypeId(position), "Trace/voxel mismatch");
      return new TraceSnapshot(state.document.sessionToken(), source, state.revision, result);
    }
  }

  private static void dispatch(InspectorPanel.Action action) {
    if (action == InspectorPanel.Action.NONE) return;
    require(
        action == InspectorPanel.Action.REFRESH
            && retry != null
            && retry.current(state, coordinates.version())
            && pending == null,
        "Unexpected retry action");
    var owned = retry;
    var prior = shown;
    retry = null;
    shown = capture(owned.position());
    var row = new LinkedHashMap<String, Object>();
    row.put("serial", owned.serial());
    row.put("target", owned.position());
    row.put("entered", coordinates.coordinate());
    row.put(
        "displayed",
        prior == null
            ? List.of()
            : List.of(
                prior.trace().position().x(),
                prior.trace().position().y(),
                prior.trace().position().z()));
    row.put("field", shown.trace().steps().getFirst().field());
    row.put("finalType", shown.trace().finalType());
    deliveries.add(row);
  }

  private static void captureCases(boolean trace) {
    if (trace) panel.showTrace();
    else panel.showCoordinates(false);
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      global = globals[i];
      scale = scales[i];
      state.user.appearance.windowScales.put("Voxel Inspector", scale);
      frames(6);
      if (ids == null) ids = panel.chrome.actionIds();
      require(ids.equals(panel.chrome.actionIds()), "Reflow changed retry owner");
      cases.add(
          Map.of(
              "page",
              panel.section(),
              "scenario",
              i,
              "globalScale",
              global,
              "windowScale",
              scale,
              "window",
              new DialogBounds.Rect(16, 16, width, height),
              "actions",
              panel.chrome.actions(),
              "ids",
              panel.chrome.actionIds(),
              "retrySerial",
              retry.serial()));
    }
  }

  private static ImString buffer(String axis) {
    return buffers.computeIfAbsent(
        axis, key -> new ImString(coordinates.text(key), CoordinateText.MAX_CHARACTERS + 1));
  }

  private static void edit(String axis, String text) {
    coordinates.edit(axis, text);
    buffer(axis).set(text);
  }

  private static void ready() {
    pending = null;
    retry = newRetry();
    panel.showTrace();
    frames(3);
  }

  private static void none() {
    require(deliveries.isEmpty(), "Stale or canceled Retry was dispatched");
  }

  private static void frame() {
    ImGui.getIO().setDisplaySize(width + 32, height + 32);
    ImGui.getIO().setFontGlobalScale(global);
    ImGui.newFrame();
    if (visible) {
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.begin("Voxel Inspector");
      UiScale.window(state.user, "Voxel Inspector");
      dispatch(panel.draw(InspectorRetryProbe::status, tracePanel));
      ImGui.end();
    } else {
      panel.suspend();
      tracePanel.hide(shown);
    }
    ImGui.render();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void press() {
    var at = panel.control("trace-refresh");
    require(at != null, "Missing Retry Trace action");
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

  private static void click() {
    press();
    release();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
