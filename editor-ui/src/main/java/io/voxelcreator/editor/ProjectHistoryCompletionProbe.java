package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectHistorySession.*;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred native history delivery, guard reentry, movement fence and refresh-failure recovery. */
public final class ProjectHistoryCompletionProbe {
  private static EditorState state;
  private static ProjectHistoryPanel panel;
  private static Path out;
  private static String mode = "normal";
  private static boolean inFrame, failRefresh;
  private static int root, checks, refreshes, observations, width = 1200, height = 900;
  private static float global = 1;
  private static long historyBytes;
  private static final List<Map<String, Object>> cases = new ArrayList<>(),
      routes = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    out = Path.of(args[0]).toAbsolutePath();
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
        state.document.save(out.resolve("source-base.json"));
        require(
            state.change(p -> p.noise2D.getFirst().algorithm.parameters.put("value", 4.)),
            "History edit rejected");
        state.document.save(out.resolve("source-edited.json"));
        historyBytes = state.document.historyBytes();
        state.user.save(out.resolve("preferences-before.json"));
        state.document.setValidator(
            p -> {
              if (inFrame) {
                observe();
                require(
                    panel.session.busy() && !panel.session.pending(),
                    "Model validation lost the move fence");
                require(
                    panel.session.request(Direction.UNDO) == Result.UNAVAILABLE,
                    "Validation nested another history move");
              }
              return NoiseCompiler.validate(p);
            });

        prepare(Direction.UNDO, "normal");
        capture("undo");
        long revision = state.revision;
        click("DISCARD");
        record("undo", revision, true, "base");
        saveTrace("undo");
        prepare(Direction.REDO, "normal");
        capture("redo");
        revision = state.revision;
        click("DISCARD");
        record("redo", revision, true, "edited");
        saveTrace("redo");
        prepare(Direction.UNDO, "normal");
        failRefresh = true;
        revision = state.revision;
        click("DISCARD");
        record("refresh-failure", revision, true, "base");
        saveTrace("refresh-failure");
        int refreshed = refreshes;
        require(
            panel.session.decide(Decision.DISCARD) == Result.UNAVAILABLE && refreshes == refreshed,
            "Consumed refresh failure replayed history");

        prepare(Direction.UNDO, "decision-failure");
        revision = state.revision;
        click("DISCARD");
        record("decision-failure", revision, false, "edited");
        prepare(Direction.UNDO, "decision-mutation");
        revision = state.revision;
        click("DISCARD");
        require(((Noise) state.draft).id.equals("gate-private"), "Guard mutation was lost");
        record("decision-mutation", revision, false, "edited");
        prepare(Direction.UNDO, "decision-successor");
        revision = state.revision;
        long old = panel.session.requestVersion();
        click("DISCARD");
        require(
            panel.session.pending()
                && panel.session.requestVersion() > old
                && "--".equals(state.seedInputs.text("seedOffset")),
            "Older decision cleared its successor");
        click("CANCEL");
        record("decision-successor", revision, false, "edited");
        prepare(Direction.UNDO, "request-successor");
        revision = state.revision;
        require(
            panel.session.requestVersion() == 1
                && ((Noise) state.draft).id.equals("successor-private"),
            "Older request replaced its successor");
        click("CANCEL");
        record("request-successor", revision, false, "edited");
        revision = state.revision;
        prepare(Direction.UNDO, "request-failure");
        require(
            !panel.session.pending() && panel.session.requestVersion() == 0,
            "Throwing request admitted history");
        record("request-failure", revision, false, "edited");
        prepare(Direction.UNDO, "normal");
        revision = state.revision;
        press("DISCARD");
        var io = ImGui.getIO();
        io.addMouseButtonEvent(0, false);
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        frames(2);
        record("escape", revision, false, "edited");
        prepare(Direction.UNDO, "normal");
        revision = state.revision;
        old = panel.session.requestVersion();
        press("DISCARD");
        panel.session.decide(Decision.CANCEL);
        state.seedInputs.edit(state, "seedOffset", "Seed", "--");
        panel.request(Direction.UNDO);
        frame();
        release();
        frames(2);
        require(
            panel.session.pending() && panel.session.requestVersion() > old,
            "Held predecessor moved successor history");
        click("CANCEL");
        record("held", revision, false, "edited");

        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        require(
            read("source-edited.json").equals(read("after.json"))
                && read("preferences-before.json").equals(read("preferences.json")),
            "History completion changed final project/preferences");
        var errors =
            state.events.view().entries().stream()
                .sorted(Comparator.comparingLong(EditorEvents.Entry::id))
                .toList();
        require(
            errors.size() == 3 && errors.stream().allMatch(error -> error.count() == 1),
            "History failure events replayed");
        require(observations == 13, "Missing production post-popup callback observations");
        write(
            "project-history-completion-native.json",
            Map.of(
                "formatVersion",
                1,
                "fontSource",
                "synthetic-global-window-fixture",
                "cases",
                cases,
                "routes",
                routes,
                "errors",
                errors,
                "callbacksAfterPopup",
                observations,
                "movementFence",
                true,
                "physicalDpiVerified",
                false));
        System.out.println(
            "PROJECT_HISTORY_COMPLETION_NATIVE_PASS post-popup history / exact successor intents /"
                + " move fence / retained refresh failure / explicit Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void prepare(Direction direction, String phase) throws Exception {
    boolean edited = state.project().noise2D.getFirst().algorithm.parameters.get("value") == 4.;
    if (direction == Direction.UNDO && !edited) {
      state.document.redo();
      state.revision++;
    } else if (direction == Direction.REDO && edited) {
      state.document.undo();
      state.revision++;
    }
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    mode = phase;
    checks = refreshes = 0;
    failRefresh = false;
    panel =
        new ProjectHistoryPanel(
            state,
            ProjectHistoryCompletionProbe::blocked,
            () -> {
              observe();
              refreshes++;
              require(
                  panel.session.busy() && !panel.session.pending(),
                  "Refresh lost the accepted move fence");
              require(
                  panel.session.request(Direction.REDO) == Result.UNAVAILABLE,
                  "Refresh started another history move");
              state.reconcileSelection(state.multiSelection, state.selected);
              if (failRefresh) throw new IllegalStateException("history refresh boundary");
            });
    panel.request(direction);
    frames(4);
    if (!phase.equals("request-failure"))
      require(
          panel.session.pending() && panel.control("DISCARD") != null,
          "Missing production history decision");
  }

  private static boolean blocked() {
    checks++;
    observe();
    if (mode.equals("request-failure") && checks == 1)
      throw new IllegalStateException("history request boundary");
    if (mode.equals("decision-failure") && checks == 2)
      throw new IllegalStateException("history decision boundary");
    if (mode.equals("decision-mutation") && checks == 2) ((Noise) state.draft).id = "gate-private";
    if (mode.equals("request-successor") && checks == 1) {
      mode = "normal";
      ((Noise) state.draft).id = "successor-private";
      panel.request(Direction.UNDO);
    } else if (mode.equals("decision-successor") && checks == 2) {
      mode = "normal";
      require(
          panel.session.decide(Decision.CANCEL) == Result.CANCELLED,
          "Could not retire predecessor");
      state.seedInputs.edit(state, "seedOffset", "Seed", "--");
      panel.request(Direction.UNDO);
    }
    return false;
  }

  private static void record(String phase, long revision, boolean moved, String canonical)
      throws Exception {
    require(
        !panel.session.busy()
            && state.revision == revision + (moved ? 1 : 0)
            && state.document.historyBytes() == historyBytes,
        "Unexpected history move count or retained busy state for " + phase);
    require(
        read("source-" + canonical + ".json")
            .equals(ProjectIO.JSON.readTree(ProjectIO.JSON.writeValueAsString(state.project()))),
        "Wrong canonical history document for " + phase);
    if (moved)
      require(
          refreshes == 1
              && !state.seedInputs.pending(state)
              && ((Noise) state.draft).id.equals("field"),
          "Moved history did not consume its authorized draft once");
    else
      require(
          refreshes == 0
              && Set.of("-", "--").contains(state.seedInputs.text("seedOffset"))
              && !((Noise) state.draft).id.equals("field"),
          "Unmoved history lost private input");
    routes.add(
        Map.of(
            "phase",
            phase,
            "revisionDelta",
            state.revision - revision,
            "canonical",
            canonical,
            "refreshes",
            refreshes,
            "privateRetained",
            !moved,
            "pending",
            panel.session.pending(),
            "errorCount",
            state.events.view().entries().size()));
    frames(2);
  }

  private static void saveTrace(String phase) throws Exception {
    new ProjectIO().save(out.resolve(phase + ".json"), state.project());
    try (var world = state.engine.openWorld(new CompiledProject(state.project()))) {
      write(phase + "-trace.json", world.trace(new BlockPos(2, 0, 3)));
    }
  }

  private static void observe() {
    if (inFrame) {
      require(
          root == ImGui.getID("history-caller-root"), "History callback ran inside popup scopes");
      observations++;
    }
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("History completion caller");
    UiScale.window(state.user, "Logs");
    root = ImGui.getID("history-caller-root");
    inFrame = true;
    try {
      panel.draw();
      panel.draw();
    } finally {
      inFrame = false;
    }
    require(root == ImGui.getID("history-caller-root"), "History draw leaked native scopes");
    ImGui.end();
    ImGui.render();
  }

  private static void capture(String phase) {
    var old = state.user.appearance.windowScales.get("Properties");
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      global = globals[i];
      state.user.appearance.windowScales.put("Properties", scales[i]);
      frames(6);
      if (ids == null) ids = panel.chrome.actionIds();
      require(ids.equals(panel.chrome.actionIds()), "History reflow replaced action ownership");
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
              panel.layout.bounds(),
              "body",
              panel.chrome.body(),
              "actions",
              panel.chrome.actions(),
              "ids",
              panel.chrome.actionIds()));
    }
    width = 1200;
    height = 900;
    global = 1;
    if (old == null) state.user.appearance.windowScales.remove("Properties");
    else state.user.appearance.windowScales.put("Properties", old);
    frames(3);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void press(String key) {
    var at = panel.control(key);
    require(at != null, "Missing history action " + key);
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
    frames(2);
  }

  private static com.fasterxml.jackson.databind.JsonNode read(String name) throws Exception {
    return ProjectIO.JSON.readTree(out.resolve(name).toFile());
  }

  private static void write(String name, Object value) throws Exception {
    Files.writeString(out.resolve(name), ProjectIO.JSON.writeValueAsString(value));
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
