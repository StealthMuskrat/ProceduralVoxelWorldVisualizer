package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Layer;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred native draft decisions at the pre-check, post-Apply and reveal boundaries. */
public final class GuardedSourceProbe {
  private static EditorState state;
  private static GuardedNavigation guard;
  private static TraceSnapshot trace;
  private static Path out;
  private static int width = 1200, height = 900, checks, reveals;
  private static float global = 1;
  private static String phase;
  private static boolean revealFailure;
  private static boolean inFrame;
  private static int callerRoot, callbackScopes;
  private static final List<Map<String, Object>> phases = new ArrayList<>(),
      cases = new ArrayList<>();

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
        state.document.save(out.resolve("source-project.json"));
        state.user.save(out.resolve("preferences-before.json"));
        run("child", true);
        state.document.undo();
        save("child-undo.json");
        require(
            read("source-project.json").equals(read("child-undo.json")),
            "One Undo did not restore the complete source project");
        run("unchanged", true);
        run("precheck", false);
        run("postcheck", false);
        run("reveal", false);
        run("successor", false);
        require(callbackScopes == 13, "Unexpected post-popup production callback coverage");
        state.user.save(out.resolve("preferences.json"));
        require(
            read("preferences-before.json").equals(read("preferences.json")),
            "Source completion changed preferences");
        var errors =
            state.events.view().entries().stream()
                .sorted(Comparator.comparingLong(EditorEvents.Entry::id))
                .toList();
        require(errors.size() == 3, "Unexpected failure events: " + errors);
        require(
            errors.stream()
                .map(EditorEvents.Entry::code)
                .toList()
                .equals(
                    List.of(
                        "SOURCE_NAVIGATION_FAILED",
                        "SOURCE_NAVIGATION_FAILED",
                        "SOURCE_REVEAL_FAILED")),
            "Failure codes lost boundary identity");
        require(
            errors.stream()
                .map(EditorEvents.Entry::message)
                .toList()
                .equals(
                    List.of(
                        "source check before apply",
                        "Draft applied; source navigation failed: source check after apply",
                        "source reveal boundary")),
            "Failure messages lost applied state");
        require(
            errors.stream()
                .allMatch(
                    error ->
                        error.count() == 1
                            && error.severity() == Diagnostic.Severity.ERROR
                            && error.subsystem() == EditorEvents.Subsystem.APPLICATION
                            && error.actionRequired()),
            "Failure reporting replayed or hid an error");
        write(
            "guarded-source-native.json",
            Map.of(
                "formatVersion",
                1,
                "fontSource",
                "synthetic-global-window-fixture",
                "phases",
                phases,
                "cases",
                cases,
                "errors",
                errors,
                "popupScopesRestored",
                true,
                "oneUndoRestored",
                true,
                "callbacksAfterPopup",
                callbackScopes,
                "physicalDpiVerified",
                false));
        System.out.println(
            "GUARDED_SOURCE_NATIVE_PASS post-Apply source eligibility / isolated failures / single"
                + " commit / successor ownership");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void run(String mode, boolean density) throws Exception {
    phase = mode;
    checks = 0;
    reveals = 0;
    revealFailure = mode.equals("reveal");
    require(
        state.document.open(out.resolve("source-project.json")).isEmpty(), "Fixture reopen failed");
    state.selected = null;
    state.draft = null;
    state.multiSelection.clear();
    state.seedInputs.synchronize(state);
    var parent = state.project().worldGenerator.layers.getFirst().uuid;
    state.select("layer", parent);
    var source = ProjectIO.copy(state.project());
    try (var world = state.engine.openWorld(new CompiledProject(source))) {
      trace =
          new TraceSnapshot(
              state.document.sessionToken(),
              source,
              state.revision,
              world.trace(new BlockPos(2, 0, 3)));
    }
    var destination =
        mode.equals("child")
            ? trace.rows(1).stream()
                .filter(row -> row.label().equals("Compare threshold"))
                .findFirst()
                .orElseThrow()
                .target()
            : noise();
    if (mode.equals("child"))
      ((Layer) state.draft)
          .operations
          .getLast()
          .overrides
          .put("threshold", ProjectIO.JSON.valueToTree(20));
    else ((Layer) state.draft).id = "applied-layer";
    var privateDraft = state.draft;
    long revision = state.revision;
    guard =
        new GuardedNavigation(
            state,
            new PropertyPanel(state),
            target -> {
              observeCallback();
              reveals++;
              if (revealFailure) throw new IllegalStateException("source reveal boundary");
              return false;
            });
    require(
        guard.request(
                destination,
                () -> {
                  checks++;
                  if (checks > 1) observeCallback();
                  if (mode.equals("precheck") && checks == 2)
                    throw new IllegalStateException("source check before apply");
                  if (mode.equals("postcheck") && checks == 3)
                    throw new IllegalStateException("source check after apply");
                  if (mode.equals("successor") && checks == 3) {
                    ((Layer) state.draft).id = "successor-private";
                    require(
                        guard.request(noise()) == SearchNavigation.Result.NEEDS_DECISION,
                        "Successor was not admitted");
                  }
                  return trace.available(state, destination);
                })
            == SearchNavigation.Result.NEEDS_DECISION,
        "Missing guarded source decision");
    frames(4);
    if (density) capture();
    long request = guard.dialog.requestVersion();
    click("APPLY");
    frames(3);
    boolean applied = !mode.equals("precheck"),
        navigated = mode.equals("unchanged") || mode.equals("reveal");
    require(state.revision == revision + (applied ? 1 : 0), "Apply count changed for " + mode);
    require(
        Objects.equals(state.selected, navigated ? destination.resource() : parent),
        "Wrong source selection for " + mode);
    require(reveals == (navigated ? 1 : 0), "Unexpected reveal for " + mode);
    require(guard.pending() == mode.equals("successor"), "Wrong pending owner for " + mode);
    if (mode.equals("precheck"))
      require(
          state.draft == privateDraft && ((Layer) state.draft).id.equals("applied-layer"),
          "Pre-check failure lost private draft");
    if (mode.equals("child"))
      require(
          !trace.available(state, destination) && state.status.startsWith("Draft applied."),
          "Changed source followed an old trace link");
    if (mode.equals("successor")) {
      require(
          guard.dialog.requestVersion() > request
              && ((Layer) state.draft).id.equals("successor-private"),
          "Old completion overwrote the successor");
      click("CANCEL");
      frames(2);
      require(
          !guard.pending() && ((Layer) state.draft).id.equals("successor-private"),
          "Successor Cancel changed private values");
    }
    int checked = checks, errorCount = state.events.view().entries().size();
    require(
        guard.decide(SearchNavigation.Decision.APPLY) == SearchNavigation.Result.UNAVAILABLE,
        "Consumed Apply replayed");
    require(
        checks == checked
            && state.revision == revision + (applied ? 1 : 0)
            && state.events.view().entries().size() == errorCount,
        "Consumed completion ran callbacks again");
    if (mode.equals("reveal")) {
      revealFailure = false;
      require(
          guard.request(destination) == SearchNavigation.Result.NAVIGATED
              && reveals == 2
              && state.revision == revision + 1,
          "Fresh reveal retry replayed Apply");
    }
    save(mode + "-after.json");
    try (var world = state.engine.openWorld(new CompiledProject(state.project()))) {
      write(mode + "-trace.json", world.trace(new BlockPos(2, 0, 3)));
    }
    phases.add(
        Map.of(
            "phase",
            phase,
            "revisionDelta",
            state.revision - revision,
            "selected",
            navigated ? "noise" : "layer",
            "checks",
            checks,
            "reveals",
            reveals,
            "pending",
            guard.pending(),
            "errorCount",
            state.events.view().entries().size()));
    frames(2);
  }

  private static EditorEvents.Target noise() {
    return new EditorEvents.Target(
        state.project().project.uuid,
        state.project().noise2D.getFirst().uuid,
        null,
        null,
        "",
        false);
  }

  private static void capture() {
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
      if (ids == null) ids = guard.dialog.chrome.actionIds();
      require(
          ids.equals(guard.dialog.chrome.actionIds()), "Reflow changed source decision ownership");
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
              guard.dialog.layout.bounds(),
              "body",
              guard.dialog.chrome.body(),
              "actions",
              guard.dialog.chrome.actions(),
              "ids",
              guard.dialog.chrome.actionIds()));
    }
    width = 1200;
    height = 900;
    global = 1;
    if (old == null) state.user.appearance.windowScales.remove("Properties");
    else state.user.appearance.windowScales.put("Properties", old);
    frames(4);
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Guarded Source Completion");
    UiScale.window(state.user, "Properties");
    int root = ImGui.getID("source-root");
    callerRoot = root;
    inFrame = true;
    try {
      guard.draw();
    } finally {
      inFrame = false;
    }
    require(root == ImGui.getID("source-root"), "Source callback left an unbalanced popup scope");
    ImGui.end();
    ImGui.render();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void observeCallback() {
    if (!inFrame) return;
    require(callerRoot == ImGui.getID("source-root"), "Source callback ran inside the draft popup");
    callbackScopes++;
  }

  private static void click(String key) {
    var at = guard.button(key);
    require(at != null, "Missing source decision " + key);
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void save(String name) throws Exception {
    new ProjectIO().save(out.resolve(name), state.project());
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
