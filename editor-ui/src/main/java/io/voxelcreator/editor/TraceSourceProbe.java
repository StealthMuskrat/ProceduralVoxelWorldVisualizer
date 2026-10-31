package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred real source footer state/definition reuse; no GLFW, GL or worker dispatch. */
public final class TraceSourceProbe {
  private static EditorState state;
  private static TracePanel panel;
  private static TraceSnapshot shown;
  private static GuardedNavigation guard;
  private static int width = 1200, height = 900;
  private static float global = 1;
  private static final List<Map<String, Object>> states = new ArrayList<>(),
      cases = new ArrayList<>();
  private static final List<String> copies = new ArrayList<>();

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
        var file = out.resolve("source-project.json");
        state.document.save(file);
        require(state.document.open(file).isEmpty(), "Fixture reopen failed");
        var source = ProjectIO.copy(state.project());
        GenerationTrace result;
        try (var world = state.engine.openWorld(new CompiledProject(source))) {
          result = world.trace(new BlockPos(2, 0, 3));
        }
        shown = new TraceSnapshot(state.document.sessionToken(), source, state.revision, result);
        var details = shown.details();
        var noise = state.project().noise2D.getFirst();
        state.select("noise2D", noise.uuid);
        var draft = (ProjectData.Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var properties = new PropertyPanel(state);
        guard = new GuardedNavigation(state, properties);
        panel = new TracePanel(state, guard, copies::add);
        frames(2);
        panel.filter("Noise PROCESSING / expression");
        frames(4);
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        observe("current", "AVAILABLE", "AVAILABLE");
        capture("current");
        long checks = panel.sourceChecks();
        frames(40);
        require(checks == panel.sourceChecks(), "Unchanged frames rehashed source definitions");

        press("control");
        var removed = draft.processing.removeFirst();
        frame();
        release();
        frames(2);
        require(
            !guard.pending() && panel.goToCenter() == null,
            "Removed private control retained held source navigation");
        observe("private-removed", "AVAILABLE", "CONTROL_UNAVAILABLE");
        require(checks == panel.sourceChecks(), "Private control change rehashed committed data");
        draft.processing.addFirst(removed);
        frames(3);
        observe("private-restored", "AVAILABLE", "AVAILABLE");
        require(checks == panel.sourceChecks(), "Private restoration rehashed committed data");
        click("control");
        frames(2);
        require(
            !guard.pending() && state.draft == draft && state.selected.equals(noise.uuid),
            "Restored same-resource control replaced its private draft");

        press("voxel");
        require(state.change(p -> p.voxelTypes.getFirst().id = "renamed"), "Rename rejected");
        frame();
        release();
        frames(2);
        require(!guard.pending(), "Changed source admitted a held navigation");
        observe("renamed", "SOURCE_CHANGED", "AVAILABLE");
        capture("changed");
        click("voxel");
        require(!guard.pending(), "Disabled changed-type link opened a source decision");
        state.document.undo();
        state.revision++;
        frames(3);
        observe("undone", "AVAILABLE", "AVAILABLE");
        require(
            state.change(
                p -> {
                  p.worldGenerator.layers.clear();
                  p.voxelTypes.clear();
                }),
            "Removal rejected");
        frames(3);
        observe("deleted", "MISSING_SOURCE", "AVAILABLE");
        click("voxel");
        require(!guard.pending(), "Disabled missing-type link opened a source decision");
        state.document.undo();
        state.revision++;
        frames(3);
        observe("restored", "AVAILABLE", "AVAILABLE");
        require(state.document.open(file).isEmpty(), "Identical reopen failed");
        frames(3);
        observe("reopened", "OTHER_DOCUMENT", "OTHER_DOCUMENT");
        require(panel.retainedSources() == 0, "Expired document retained cached source entries");
        click("copy-trace");
        require(
            copies.equals(List.of(details)) && shown.details().equals(details),
            "Source state changes altered historical copying");
        require(
            state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Source checks changed private draft or raw seed");
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Source state checks changed preferences");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("source-trace.json"), ProjectIO.JSON.writeValueAsString(result));
        Files.writeString(out.resolve("source-details.txt"), details);
        Files.writeString(
            out.resolve("trace-source-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("states", states),
                    Map.entry("cases", cases),
                    Map.entry("definitionReuse", true),
                    Map.entry("privateAvailabilityLive", true),
                    Map.entry("heldSourceRejected", true),
                    Map.entry("disabledSourcesInactive", true),
                    Map.entry("sameResourceDraftPreserved", true),
                    Map.entry("historicalCopyPreserved", true),
                    Map.entry("privatePreserved", true),
                    Map.entry("physicalDpiVerified", false))));
        System.out.println(
            "TRACE_SOURCE_NATIVE_PASS committed-definition cache / live private control eligibility"
                + " / changed-missing-reopened links / immutable history");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void observe(String phase, String voxel, String control) {
    var project = ProjectIO.JSON.valueToTree(state.project());
    long revision = state.revision, history = state.document.historyBytes();
    frames(2);
    require(
        panel.sourceStates().equals(Map.of("voxel", voxel, "control", control)),
        "Unexpected source state for " + phase + ": " + panel.sourceStates());
    require(
        (panel.goToCenter() != null) == control.equals("AVAILABLE"),
        "Control availability disagrees with native footer");
    states.add(
        Map.of(
            "phase",
            phase,
            "sources",
            panel.sourceStates(),
            "definitionChecks",
            panel.sourceChecks(),
            "retained",
            panel.retainedSources()));
    require(
        project.equals(ProjectIO.JSON.valueToTree(state.project()))
            && revision == state.revision
            && history == state.document.historyBytes(),
        "Source presentation changed authored state");
  }

  private static void capture(String phase) {
    var oldScale = state.user.appearance.windowScales.get("Logs");
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    long checks = panel.sourceChecks();
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      global = globals[i];
      state.user.appearance.windowScales.put("Logs", scales[i]);
      frames(6);
      if (ids == null) ids = panel.chrome.actionIds();
      require(ids.equals(panel.chrome.actionIds()), "Reflow changed source action owner");
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
              new DialogBounds.Rect(16, 16, width, height),
              "actions",
              panel.chrome.actions(),
              "ids",
              panel.chrome.actionIds(),
              "sources",
              panel.sourceStates()));
    }
    require(checks == panel.sourceChecks(), "Font reflow rehashed unchanged definitions");
    width = 1200;
    height = 900;
    global = 1;
    if (oldScale == null) state.user.appearance.windowScales.remove("Logs");
    else state.user.appearance.windowScales.put("Logs", oldScale);
    frames(3);
  }

  private static void frame() {
    ImGui.getIO().setDisplaySize(width + 32, height + 32);
    ImGui.getIO().setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Trace Source Probe");
    UiScale.window(state.user, "Logs");
    panel.draw(shown);
    ImGui.end();
    guard.draw();
    ImGui.render();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void press(String key) {
    var at = panel.controlAt(key);
    require(at != null, "Missing source action " + key);
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
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
