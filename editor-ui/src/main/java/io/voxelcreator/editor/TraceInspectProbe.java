package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred real optional TracePanel footer; worker/GL delivery is covered by ViewportDocumentProbe.
 */
public final class TraceInspectProbe {
  private static EditorState state;
  private static TracePanel panel;
  private static TraceSnapshot shown, original;
  private static GuardedNavigation guard;
  private static int width = 1200, height = 900, parentId, attempts;
  private static float global = 1, scale = 1;
  private static boolean visible = true, fail, successor;
  private static final List<Map<String, Object>> cases = new ArrayList<>(),
      deliveries = new ArrayList<>();

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
        var file = out.resolve("trace-project.json");
        state.document.save(file);
        var source = ProjectIO.copy(state.project());
        GenerationTrace trace;
        try (var world = state.engine.openWorld(new CompiledProject(source))) {
          trace = world.trace(new BlockPos(2, 0, 3));
        }
        original = new TraceSnapshot(state.document.sessionToken(), source, state.revision, trace);
        shown = original;
        guard = new GuardedNavigation(state, new PropertyPanel(state));
        panel = new TracePanel(state, guard, value -> {}, TraceInspectProbe::deliver);
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = state.draft;
        ((ProjectData.Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var oldScale = state.user.appearance.windowScales.get("Logs");
        capture("current");
        require(state.document.open(file).isEmpty(), "Same-UUID reopen failed");
        capture("reopened");
        width = 1200;
        height = 900;
        global = scale = 1;
        if (oldScale == null) state.user.appearance.windowScales.remove("Logs");
        else state.user.appearance.windowScales.put("Logs", oldScale);
        frames(4);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();

        press();
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
        release();
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
        frames(2);
        require(attempts == 0, "Escape accepted Inspect");
        press();
        require(state.document.open(file).isEmpty(), "Held document reopen failed");
        frame();
        release();
        frames(2);
        require(attempts == 0, "Held Inspect crossed document lifetime");
        press();
        shown = duplicate();
        frame();
        release();
        frames(2);
        require(attempts == 0, "Held Inspect adopted equal-looking snapshot");
        press();
        require(
            state.document.edit(p -> p.project.seed++).isEmpty(), "Direct canonical edit rejected");
        frame();
        release();
        frames(2);
        require(attempts == 0, "Held Inspect crossed canonical identity at equal revision");
        require(state.document.open(file).isEmpty(), "Canonical restore failed");
        frames(3);
        press();
        visible = false;
        frames(3);
        visible = true;
        frame();
        release();
        frames(2);
        require(attempts == 0, "Hidden host retained Inspect gesture");
        var foreign = ProjectIO.copy(source);
        foreign.project.uuid = UUID.randomUUID();
        shown = new TraceSnapshot(state.document.sessionToken(), foreign, state.revision, trace);
        frames(3);
        click();
        require(attempts == 0, "Foreign project offered current-project Inspect");
        shown = original;
        frames(3);

        fail = true;
        click();
        frames(5);
        require(attempts == 1, "Failed callback replayed without a new gesture");
        fail = false;
        click();
        require(attempts == 2, "Explicit retry failed");
        fail = successor = true;
        click();
        frames(5);
        require(attempts == 3 && shown != original, "Failure lost its successor or replayed");
        fail = successor = false;
        click();
        require(attempts == 4, "Successor explicit retry failed");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Inspect changed authored state");
        require(
            state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Inspect changed private draft/raw seed");
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Inspect changed preferences");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("trace-inspect-events.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        Files.writeString(
            out.resolve("trace-inspect-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("deliveries", deliveries),
                    Map.entry("escapePreserved", true),
                    Map.entry("documentScoped", true),
                    Map.entry("snapshotScoped", true),
                    Map.entry("canonicalIdentityScoped", true),
                    Map.entry("hiddenHostScoped", true),
                    Map.entry("foreignDisabled", true),
                    Map.entry("failedIntentConsumed", true),
                    Map.entry("successorPreserved", true),
                    Map.entry("callbacksBalanced", true),
                    Map.entry("privatePreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("workersVerified", false))));
        System.out.println(
            "TRACE_INSPECT_NATIVE_PASS bounded footer / exact document and snapshot intent /"
                + " consumed callback failure / successor retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static TraceSnapshot duplicate() {
    return new TraceSnapshot(
        state.document.sessionToken(),
        ProjectIO.copy(state.project()),
        state.revision,
        shown.trace());
  }

  private static void deliver(TraceCoordinateIntent intent) {
    require(
        parentId == ImGui.getID("inspect-callback-host")
            && intent.current(state)
            && intent.snapshot() == shown,
        "Inspect callback escaped its balanced exact owner");
    attempts++;
    deliveries.add(
        Map.of(
            "attempt",
            attempts,
            "position",
            intent.position(),
            "revision",
            intent.revision(),
            "reopened",
            intent.snapshot().reopened(state),
            "current",
            intent.current(state)));
    if (fail) {
      if (successor) shown = duplicate();
      throw new IllegalStateException(
          successor ? "trace inspect successor" : "trace inspect boundary");
    }
  }

  private static void capture(String phase) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      global = globals[i];
      scale = scales[i];
      state.user.appearance.windowScales.put("Logs", scale);
      frames(6);
      if (ids == null) ids = panel.chrome.actionIds();
      require(ids.equals(panel.chrome.actionIds()), "Reflow changed Inspect/footer identity");
      cases.add(
          Map.of(
              "phase",
              phase,
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
              "reopened",
              shown.reopened(state)));
    }
  }

  private static void frame() {
    ImGui.getIO().setDisplaySize(width + 32, height + 32);
    ImGui.getIO().setFontGlobalScale(global);
    ImGui.newFrame();
    if (visible) {
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.begin("Trace Inspect Probe");
      UiScale.window(state.user, "Logs");
      parentId = ImGui.getID("inspect-callback-host");
      panel.draw(shown);
      ImGui.end();
    } else panel.hide(shown);
    guard.draw();
    ImGui.render();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void press() {
    var at = panel.controlAt("inspect");
    require(at != null, "Missing Inspect control");
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
