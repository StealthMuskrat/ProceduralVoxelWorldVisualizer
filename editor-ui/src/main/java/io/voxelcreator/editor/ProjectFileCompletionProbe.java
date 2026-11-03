package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred native file decisions, exact path delivery, publication callbacks and successor actions.
 */
public final class ProjectFileCompletionProbe {
  private static EditorState state;
  private static ProjectFileDialog dialog;
  private static Path out;
  private static String mode = "normal";
  private static boolean inFrame, gateSuccessor;
  private static int root,
      replacements,
      exits,
      validations,
      observations,
      width = 1200,
      height = 900;
  private static float global = 1;
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
        state.document.save(out.resolve("source-project.json"));
        String sourceBytes = Files.readString(out.resolve("source-project.json"));
        var candidate = ProjectIO.copy(state.project());
        candidate.noise2D.getFirst().algorithm.parameters.put("value", 4.);
        new ProjectIO().save(out.resolve("candidate-project.json"), candidate);
        state.user.save(out.resolve("preferences-before.json"));
        state.document.setValidator(
            p -> {
              if (inFrame) {
                observe();
                validations++;
                require(dialog.workflow.busy(), "File validation lost its busy fence");
                if (mode.equals("save-busy")) {
                  long request = dialog.workflow.requestVersion();
                  dialog.workflow.request(Intent.NEW);
                  dialog.workflow.save(true);
                  dialog.workflow.cancel();
                  require(
                      dialog.workflow.requestVersion() == request
                          && dialog.workflow.phase() == Phase.SAVE,
                      "File validation admitted another action");
                }
              }
              return NoiseCompiler.validate(p);
            });
        for (var phase : new Phase[] {Phase.DRAFT, Phase.GUARD, Phase.OPEN, Phase.SAVE}) {
          reset("normal", phase == Phase.GUARD);
          arm(phase);
          capture(phase.name());
          long revision = state.revision;
          click("Cancel");
          record(
              phase.name().toLowerCase(Locale.ROOT) + "-cancel",
              revision,
              phase == Phase.GUARD ? "dirty" : "source",
              true);
        }

        reset("open-refresh", false);
        arm(Phase.OPEN);
        long revision = state.revision;
        path(out.resolve("candidate-project.json"));
        click("Open");
        require(
            replacements == 1
                && validations == 1
                && dialog.workflow.phase() == Phase.IDLE
                && dialog.workflow.error().startsWith("Project opened;"),
            "Published Open was misclassified or replayed");
        dialog.workflow.submit(out.resolve("candidate-project.json").toString());
        require(replacements == 1 && validations == 1, "Consumed Open replayed its callback");
        saveTrace("opened-project");
        record("open-refresh", revision, "candidate", false);

        reset("new-successor", false);
        dialog.workflow.request(Intent.NEW);
        frames(4);
        revision = state.revision;
        long request = dialog.workflow.requestVersion();
        click("Discard Draft");
        require(
            replacements == 1
                && dialog.workflow.phase() == Phase.SAVE
                && dialog.workflow.requestVersion() == request + 1
                && dialog.workflow.error().isEmpty(),
            "New completion overwrote its Save successor");
        click("Cancel");
        saveTrace("new-project");
        record("new-successor", revision, "new", false);

        reset("exit-failure", false);
        dialog.workflow.request(Intent.EXIT);
        frames(4);
        revision = state.revision;
        click("Discard Draft");
        dialog.workflow.decide(Decision.DISCARD);
        require(
            exits == 1
                && replacements == 0
                && dialog.workflow.error().startsWith("Exit request callback failed:"),
            "Exit callback was replayed or misclassified");
        record("exit-failure", revision, "source", true);

        reset("normal", false);
        arm(Phase.OPEN);
        path(out.resolve("candidate-project.json"));
        revision = state.revision;
        request = dialog.workflow.requestVersion();
        gateSuccessor = true;
        click("Open");
        require(
            dialog.workflow.phase() == Phase.DRAFT
                && dialog.workflow.action().equals("Exit")
                && dialog.workflow.requestVersion() == request + 1
                && dialog.workflow.error().isEmpty()
                && "--".equals(state.seedInputs.text("seedOffset"))
                && replacements == 0
                && validations == 0,
            "Old gate opened a file or retired its successor");
        click("Cancel");
        record("gate-successor", revision, "source", true);

        reset("save-busy", false);
        arm(Phase.SAVE);
        path(out.resolve("saved-project.json"));
        revision = state.revision;
        click("Save");
        require(
            validations == 1 && replacements == 0 && exits == 0,
            "Standalone save completed another action");
        record("save-busy", revision, "source", true);
        reset("normal", false);
        arm(Phase.SAVE);
        var escaped = out.resolve("escape-must-not-write.json");
        path(escaped);
        revision = state.revision;
        press("Save");
        var io = ImGui.getIO();
        io.addMouseButtonEvent(0, false);
        io.addKeyEvent(ImGuiKey.Escape, true);
        frame();
        io.addKeyEvent(ImGuiKey.Escape, false);
        frames(2);
        require(!Files.exists(escaped) && validations == 0, "Escape saved the file");
        record("escape", revision, "source", true);
        reset("normal", false);
        arm(Phase.SAVE);
        var held = out.resolve("held-must-not-write.json");
        path(held);
        revision = state.revision;
        request = dialog.workflow.requestVersion();
        press("Save");
        dialog.workflow.cancel();
        dialog.workflow.save(true);
        frame();
        release();
        frames(2);
        require(
            dialog.workflow.phase() == Phase.SAVE
                && dialog.workflow.requestVersion() == request + 1
                && validations == 0
                && !Files.exists(held),
            "Held predecessor submitted a successor path");
        click("Cancel");
        record("held", revision, "source", true);

        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        require(
            sourceBytes.equals(Files.readString(out.resolve("source-project.json")))
                && read("source-project.json").equals(read("after.json"))
                && read("preferences-before.json").equals(read("preferences.json")),
            "File delivery altered original bytes/final project/preferences");
        var errors =
            state.events.view().entries().stream()
                .filter(error -> error.severity() == Diagnostic.Severity.ERROR)
                .sorted(Comparator.comparingLong(EditorEvents.Entry::id))
                .toList();
        require(
            errors.size() == 3 && errors.stream().allMatch(error -> error.count() == 1),
            "File completion errors replayed");
        require(observations == 14, "Missing post-popup file callback observations");
        write(
            "project-file-completion-native.json",
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
                "sourceBytesPreserved",
                true,
                "physicalDpiVerified",
                false));
        System.out.println(
            "PROJECT_FILE_COMPLETION_NATIVE_PASS post-popup decisions/I-O / completed replacement"
                + " errors / successor requests / one callback / busy validation / exact native"
                + " paths");
      }
      ProjectOpenProbe.run(out.resolve("background-open"));
      ProjectSaveProbe.run(out.resolve("background-save"));
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void reset(String phase, boolean dirty) throws Exception {
    require(
        state.document.open(out.resolve("source-project.json")).isEmpty(), "Fixture reopen failed");
    if (dirty) {
      require(state.change(p -> p.noise2D.getFirst().id = "dirty-field"), "Dirty fixture rejected");
      new ProjectIO().save(out.resolve("dirty-project.json"), state.project());
    }
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    mode = phase;
    gateSuccessor = false;
    replacements = exits = validations = 0;
    dialog =
        new ProjectFileDialog(
            state,
            () -> {
              observe();
              replacements++;
              if (mode.equals("new-successor")) {
                dialog.workflow.save(true);
                throw new IllegalStateException("new refresh boundary");
              }
              if (mode.equals("open-refresh"))
                throw new IllegalStateException("open refresh boundary");
            },
            () -> {
              observe();
              exits++;
              if (mode.equals("exit-failure")) throw new IllegalStateException("exit boundary");
            },
            () -> {
              observe();
              if (gateSuccessor) {
                gateSuccessor = false;
                dialog.workflow.cancel();
                state.seedInputs.edit(state, "seedOffset", "Seed", "--");
                dialog.workflow.request(Intent.EXIT);
              }
              return false;
            });
  }

  private static void arm(Phase phase) {
    if (phase == Phase.SAVE) dialog.workflow.save(true);
    else {
      dialog.workflow.request(Intent.OPEN);
      frames(4);
      if (phase != Phase.DRAFT) click("Discard Draft");
    }
    frames(4);
    require(dialog.workflow.phase() == phase, "Wrong file phase " + phase);
  }

  private static void record(String phase, long revision, String canonical, boolean privateInput)
      throws Exception {
    require(
        dialog.workflow.phase() == Phase.IDLE && !dialog.workflow.busy(),
        "File completion remained active for " + phase);
    boolean replaced = canonical.equals("candidate") || canonical.equals("new");
    require(
        state.revision == revision + (replaced ? 1 : 0), "Wrong replacement count for " + phase);
    if (!canonical.equals("new")) {
      String file =
          canonical.equals("candidate")
              ? "candidate-project.json"
              : canonical.equals("dirty") ? "dirty-project.json" : "source-project.json";
      require(
          read(file)
              .equals(ProjectIO.JSON.readTree(ProjectIO.JSON.writeValueAsString(state.project()))),
          "Unexpected complete canonical project for " + phase);
    } else
      require(
          state.project().noise2D.isEmpty() && state.project().worldGenerator.layers.isEmpty(),
          "New project retained resources");
    if (privateInput)
      require(
          state.draft instanceof Noise noise
              && noise.id.equals("private")
              && Set.of("-", "--").contains(state.seedInputs.text("seedOffset")),
          "File action lost private input");
    else
      require(
          state.draft == null && state.selected == null && !state.seedInputs.pending(state),
          "Replacement retained old private input");
    routes.add(
        Map.of(
            "phase",
            phase,
            "revisionDelta",
            state.revision - revision,
            "canonical",
            canonical,
            "replacements",
            replacements,
            "exits",
            exits,
            "validations",
            validations,
            "privateRetained",
            privateInput,
            "errorCount",
            state.events.view().entries().stream()
                .filter(e -> e.severity() == Diagnostic.Severity.ERROR)
                .count()));
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
          root == ImGui.getID("file-caller-root"), "File callback ran inside native popup scopes");
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
    ImGui.begin("File completion caller");
    UiScale.window(state.user, "Logs");
    root = ImGui.getID("file-caller-root");
    inFrame = true;
    try {
      dialog.draw();
      dialog.draw();
    } finally {
      inFrame = false;
    }
    require(root == ImGui.getID("file-caller-root"), "File dialog leaked native scopes");
    ImGui.end();
    ImGui.render();
  }

  private static void capture(String phase) {
    var old = state.user.appearance.windowScales.get("Logs");
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      global = globals[i];
      state.user.appearance.windowScales.put("Logs", scales[i]);
      frames(6);
      if (ids == null) ids = dialog.chrome().actionIds();
      require(ids.equals(dialog.chrome().actionIds()), "File reflow changed action IDs");
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
              dialog.layout.bounds(),
              "body",
              dialog.chrome().body(),
              "actions",
              dialog.chrome().actions(),
              "ids",
              dialog.chrome().actionIds()));
    }
    width = 1200;
    height = 900;
    global = 1;
    if (old == null) state.user.appearance.windowScales.remove("Logs");
    else state.user.appearance.windowScales.put("Logs", old);
    frames(3);
  }

  private static void path(Path target) {
    click("Path");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(target.toString());
    frames(3);
    require(dialog.probePath().equals(target.toString()), "Native path did not match typed input");
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void press(String label) {
    var at = dialog.probeControl(label);
    require(at != null, "Missing file action " + label);
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

  private static void click(String label) {
    press(label);
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
