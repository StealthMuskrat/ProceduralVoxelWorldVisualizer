package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production file/draft modal and filesystem definition; exit is a counted callback. */
public final class ProjectDraftTransitionProbe {
  private static EditorState state;
  private static ProjectFileDialog dialog;
  private static int exits, replacements, cases;
  private static Path out;

  public static void main(String[] args) throws Exception {
    out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1400, 1000);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      for (var intent : Intent.values()) {
        try (var editor = fixture(true)) {
          init(editor);
          var before = ProjectIO.JSON.valueToTree(state.project());
          ((Noise) state.draft).seedOffset = 17;
          var draft = state.draft;
          dialog.workflow.request(intent);
          frames(4);
          require(dialog.workflow.phase() == Phase.DRAFT, "Clean document skipped draft decision");
          click("Cancel");
          require(
              state.draft == draft
                  && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                  && !state.document.dirty()
                  && exits == 0
                  && replacements == 0,
              "Draft Cancel changed session");
          cases++;
        }
        try (var editor = fixture(true)) {
          init(editor);
          state.seedInputs.edit(state, "seedOffset", "Seed", "-");
          var draft = state.draft;
          long revision = state.revision, history = state.document.historyBytes();
          dialog.workflow.request(intent);
          frames(4);
          click("Apply Draft");
          require(
              dialog.workflow.phase() == Phase.DRAFT
                  && state.draft == draft
                  && state.seedInputs.text("seedOffset").equals("-")
                  && revision == state.revision
                  && history == state.document.historyBytes()
                  && exits == 0
                  && replacements == 0,
              "Invalid draft advanced transition");
          escape();
          cases++;
        }
      }
      try (var editor = fixture(true)) {
        init(editor);
        var user = ProjectIO.JSON.valueToTree(state.user);
        var before = ProjectIO.JSON.valueToTree(state.project());
        ((Noise) state.draft).seedOffset = Long.MIN_VALUE;
        dialog.workflow.request(Intent.OPEN);
        frames(4);
        click("Apply Draft");
        require(dialog.workflow.phase() == Phase.GUARD, "Apply skipped committed save guard");
        click("Cancel");
        require(
            state.project().noise2D.getFirst().seedOffset == Long.MIN_VALUE,
            "Later Cancel rolled back explicit Apply");
        var applied = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(before.equals(ProjectIO.JSON.valueToTree(state.project())), "One draft Undo");
        state.document.redo();
        state.revision++;
        require(applied.equals(ProjectIO.JSON.valueToTree(state.project())), "One draft Redo");
        state.refreshDraft();
        dialog.workflow.request(Intent.OPEN);
        frames(4);
        click("Save");
        require(dialog.workflow.phase() == Phase.OPEN, "Named required save did not continue");
        escape();
        require(
            !state.document.dirty() && user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Save/preferences isolation");
        Files.copy(
            state.document.path(),
            out.resolve("transition-applied-project.json"),
            StandardCopyOption.REPLACE_EXISTING);
        cases++;
      }
      try (var editor = fixture(true)) {
        init(editor);
        state.seedInputs.edit(state, "seedOffset", "Seed", "+");
        var before = ProjectIO.JSON.valueToTree(state.project());
        var draft = state.draft;
        dialog.workflow.request(Intent.OPEN);
        frames(4);
        click("Discard Draft");
        require(
            dialog.workflow.phase() == Phase.OPEN, "Discard on clean document added save prompt");
        path(out.resolve("missing.json").toString());
        click("Open");
        require(
            dialog.workflow.phase() == Phase.OPEN && !dialog.workflow.error().isEmpty(),
            "Missing Open handling");
        click("Cancel");
        require(
            state.draft == draft
                && state.seedInputs.text("seedOffset").equals("+")
                && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && replacements == 0,
            "Failed/canceled Open disposed draft");
        cases++;
      }
      try (var editor = fixture(false)) {
        init(editor);
        ((Noise) state.draft).seedOffset = 17;
        dialog.workflow.request(Intent.EXIT);
        frames(4);
        click("Apply Draft");
        click("Save");
        var blocked = out.resolve("blocked-directory");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("sentinel"), "retained");
        path(blocked.toString());
        click("Save");
        require(
            dialog.workflow.phase() == Phase.SAVE && exits == 0 && state.document.dirty(),
            "Failed save retained Exit");
        require(
            Files.readString(blocked.resolve("sentinel")).equals("retained"),
            "Failed save damaged destination");
        path(out.resolve("transition-retry-project.json").toString());
        click("Save");
        require(
            dialog.workflow.phase() == Phase.IDLE && exits == 0 && !state.document.dirty(),
            "Retry unexpectedly exited");
        dialog.workflow.request(Intent.EXIT);
        frames(3);
        require(exits == 1, "Explicit clean Exit after retry");
        cases++;
      }
      try (var editor = fixture(true)) {
        init(editor);
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        dialog.workflow.request(Intent.EXIT);
        frames(4);
        state.seedInputs.edit(state, "seedOffset", "Seed", "--");
        click("Discard Draft");
        require(
            dialog.workflow.phase() == Phase.IDLE
                && exits == 0
                && state.seedInputs.text("seedOffset").equals("--"),
            "Stale draft Discard executed");
        cases++;
      }
      try (var editor = fixture(true)) {
        init(editor);
        var incoming = new ProjectData();
        incoming.project.seed = 99;
        var incomingPath = out.resolve("incoming.json");
        new ProjectIO().save(incomingPath, incoming);
        dialog.workflow.request(Intent.OPEN);
        frames(4);
        state.change(p -> p.project.seed = 42);
        var draft = state.draft;
        path(incomingPath.toString());
        click("Open");
        require(
            dialog.workflow.phase() == Phase.IDLE
                && replacements == 0
                && state.project().project.seed == 42
                && state.draft == draft,
            "Stale path replaced newer project");
        cases++;
      }
      try (var editor = fixture(false)) {
        init(editor);
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        dialog = new ProjectFileDialog(state, () -> replacements++, () -> exits++, () -> true);
        dialog.workflow.save(true);
        frames(4);
        path(out.resolve("transition-standalone-project.json").toString());
        click("Save");
        require(
            dialog.workflow.phase() == Phase.IDLE
                && state.seedInputs.text("seedOffset").equals("-")
                && !state.document.dirty()
                && exits == 0,
            "Standalone Save applied private text");
        cases++;
      }
      try (var editor = fixture(true)) {
        init(editor);
        var blocked = new AtomicBoolean(true);
        dialog = new ProjectFileDialog(state, () -> replacements++, () -> exits++, blocked::get);
        dialog.workflow.request(Intent.EXIT);
        frames(3);
        require(dialog.workflow.phase() == Phase.IDLE && exits == 0, "Private editor bypass");
        blocked.set(false);
        ((Noise) state.draft).id = "pending";
        dialog.workflow.request(Intent.EXIT);
        frames(4);
        blocked.set(true);
        click("Discard Draft");
        require(
            dialog.workflow.phase() == Phase.IDLE
                && exits == 0
                && ((Noise) state.draft).id.equals("pending"),
            "New private editor lost its state");
        cases++;
      }
      require(cases == 13, "Workflow count");
      Files.writeString(
          out.resolve("project-draft-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "formatVersion",
                  1,
                  "cases",
                  cases,
                  "draftCancel",
                  true,
                  "invalidApply",
                  true,
                  "applyUndoRedo",
                  true,
                  "discardRetained",
                  true,
                  "failedSaveAborted",
                  true,
                  "staleRejected",
                  true,
                  "standaloneSave",
                  true,
                  "privateBlocked",
                  true)));
      System.out.println(
          "PROJECT_DRAFT_NATIVE_PASS 13 production modal scenarios / explicit Apply / retained"
              + " cancellation / stale rejection / real files");
    } finally {
      ImGui.destroyContext();
    }
  }

  private static EditorState fixture(boolean saved) throws Exception {
    var editor = new EditorState(new UserConfig(), false);
    require(
        editor.change(
            p -> {
              p.project.seed = 41;
              var noise = new Noise();
              noise.id = "owned";
              noise.algorithm.key = "CONSTANT";
              noise.algorithm.parameters.clear();
              noise.algorithm.parameters.put("value", .25);
              p.noise2D.add(noise);
            }),
        "Fixture validation");
    editor.select("noise2D", editor.project().noise2D.getFirst().uuid);
    if (saved) editor.document.save(out.resolve("fixture-" + UUID.randomUUID() + ".json"));
    return editor;
  }

  private static void init(EditorState editor) {
    state = editor;
    exits = replacements = 0;
    dialog = new ProjectFileDialog(state, () -> replacements++, () -> exits++);
    frames(3);
  }

  private static void path(String text) {
    click("Path");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
  }

  private static void click(String label) {
    var at = dialog.probeControl(label);
    require(at != null, "Missing control " + label);
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(4);
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
    ImGui.newFrame();
    dialog.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
