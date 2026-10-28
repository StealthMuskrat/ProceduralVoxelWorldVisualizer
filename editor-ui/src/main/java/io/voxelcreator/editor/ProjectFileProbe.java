package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;
import static org.lwjgl.glfw.GLFW.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Real production modal buttons/path text, with isolated editor sessions and actual file I/O. */
final class ProjectFileProbe {
  private static EditorState state;
  private static ProjectFileDialog dialog;
  private static long window;
  private static int replacements, exits;
  private static final List<Map<String, Object>> cases = new ArrayList<>();

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    window = handle;
    glfwShowWindow(window);
    glfwFocusWindow(window);
    glfwPollEvents();
    require(glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0, "Project file window focus");
    var candidate = new ProjectData();
    candidate.project.seed = 99;
    Path incoming = out.resolve("incoming.json").toAbsolutePath();
    new ProjectIO().save(incoming, candidate);
    for (var intent : Intent.values())
      for (var decision : Decision.values()) {
        try (var editor = fixture()) {
          init(editor);
          var before = snapshot();
          var draft = state.draft;
          request(intent);
          frames(3);
          require(dialog.workflow.phase() == Phase.GUARD, "Missing dirty guard");
          click(
              decision == Decision.DISCARD
                  ? "Don't Save"
                  : decision == Decision.CANCEL ? "Cancel" : "Save");
          Path saved = out.resolve(intent + "-saved.json").toAbsolutePath();
          if (decision == Decision.SAVE) {
            require(dialog.workflow.phase() == Phase.SAVE, "Missing Save As path");
            text(saved.toString());
            click("Save");
            var load = new ProjectIO().load(saved);
            require(
                load.success() && load.project().project.seed == 41, "Save preceding transition");
            require(load.project().noise2D.getFirst().id.equals("owned"), "Saved unapplied draft");
          }
          if (decision == Decision.CANCEL) {
            unchanged(before, draft);
            require(exits == 0 && replacements == 0, "Canceled transition ran");
          } else if (intent == Intent.OPEN) {
            require(dialog.workflow.phase() == Phase.OPEN, "Open did not follow decision");
            text(incoming.toString());
            click("Open");
            require(
                state.project().project.seed == 99 && !state.document.dirty(),
                "Open candidate not committed");
            require(
                state.draft == null && state.selected == null && state.multiSelection.isEmpty(),
                "Open retained old selection");
            require(
                !state.document.canUndo() && replacements == 1 && exits == 0, "Open history/reset");
          } else if (intent == Intent.NEW) {
            require(
                state.project().noise2D.isEmpty() && state.project().project.seed == 0,
                "New not empty");
            require(
                !state.document.dirty() && state.document.path() == null && replacements == 1,
                "New reset");
          } else {
            require(exits == 1 && replacements == 0, "Exit callback count");
            require(state.project().project.seed == 41, "Exit changed project");
            require(state.document.dirty() == (decision == Decision.DISCARD), "Exit save state");
          }
          record(intent + "-" + decision);
          require(dialog.workflow.phase() == Phase.IDLE, "Completed dialog still owns input");
        }
        frames(2);
      }
    for (var intent : Intent.values())
      try (var editor = fixture()) {
        init(editor);
        var before = snapshot();
        var draft = state.draft;
        request(intent);
        frames(3);
        click("Save");
        click("Cancel");
        unchanged(before, draft);
        require(exits == 0 && replacements == 0, "Canceled path ran transition");
        record(intent + "-PATH_CANCEL");
      }
    try (var editor = fixture()) {
      init(editor);
      var before = snapshot();
      var draft = state.draft;
      request(Intent.EXIT);
      frames(3);
      request(Intent.NEW);
      dialog.workflow.save(true);
      require(dialog.workflow.phase() == Phase.GUARD, "Second command replaced guard");
      key(ImGuiKey.Escape);
      unchanged(before, draft);
      require(exits == 0 && replacements == 0, "Escape ran action");
      record("GUARD_ESCAPE");
      dialog.workflow.save(true);
      frames(3);
      key(ImGuiKey.Escape);
      unchanged(before, draft);
      record("PATH_ESCAPE");
    }
    for (var intent : Intent.values())
      try (var editor = fixture()) {
        init(editor);
        var before = snapshot();
        var draft = state.draft;
        Path blocked = out.resolve(intent + "-blocked").toAbsolutePath();
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("sentinel"), "retained");
        request(intent);
        frames(3);
        click("Save");
        text(blocked.toString());
        click("Save");
        unchanged(before, draft);
        require(
            dialog.workflow.phase() == Phase.SAVE && !dialog.workflow.error().isEmpty(),
            "Failed save lost inline retry");
        require(
            Files.readString(blocked.resolve("sentinel")).equals("retained"),
            "Failed save altered destination");
        Path retry = out.resolve(intent + "-retry.json").toAbsolutePath();
        text(retry.toString());
        click("Save");
        require(
            exits == 0 && replacements == 0 && dialog.workflow.phase() == Phase.IDLE,
            "Save retry resurrected transition");
        require(
            !state.document.dirty() && state.project().project.seed == 41,
            "Standalone retry not saved");
        require(
            state.draft == draft && state.revision == ((Number) before.get("revision")).longValue(),
            "Retry altered draft/revision");
        record(intent + "-FAILED_SAVE_RETRY");
      }
    try (var editor = fixture()) {
      init(editor);
      var before = snapshot();
      var draft = state.draft;
      request(Intent.OPEN);
      frames(3);
      click("Don't Save");
      Path bad = out.resolve("malformed.json").toAbsolutePath();
      Files.writeString(bad, "{broken");
      for (String location :
          List.of("", out.resolve("missing.json").toAbsolutePath().toString(), bad.toString())) {
        text(location);
        click("Open");
        unchanged(before, draft);
        require(
            dialog.workflow.phase() == Phase.OPEN && !dialog.workflow.error().isEmpty(),
            "Open failure lost session or retry");
      }
      text(incoming.toString());
      click("Open");
      require(
          state.project().project.seed == 99 && replacements == 1 && !state.document.dirty(),
          "Open retry failed");
      record("FAILED_OPEN_RETRY");
    }
    try (var editor = fixture()) {
      init(editor);
      Path named = out.resolve("named.json").toAbsolutePath();
      state.document.save(named);
      require(state.change(p -> p.project.seed = 42), "Named edit");
      Files.delete(named);
      Files.createDirectory(named);
      Files.writeString(named.resolve("sentinel"), "retained");
      var before = snapshot();
      var draft = state.draft;
      request(Intent.EXIT);
      frames(3);
      click("Save");
      unchanged(before, draft);
      require(
          dialog.workflow.phase() == Phase.IDLE && exits == 0 && !dialog.workflow.error().isEmpty(),
          "Failed named save ran exit");
      record("NAMED_SAVE_FAILURE");
    }
    try (var editor = fixture()) {
      init(editor);
      Path path = out.resolve("clean.json").toAbsolutePath();
      state.document.save(path);
      dialog.workflow.save(true);
      frames(3);
      click("Cancel", false);
      // A fresh same-kind request can arrive before an intervening idle draw.
      dialog.workflow.save(true);
      frames(3);
      click("Cancel");
      require(
          path.equals(state.document.path()) && !state.document.dirty(),
          "Save As Cancel changed saved path");
      request(Intent.EXIT);
      frames(2);
      require(exits == 1 && dialog.workflow.phase() == Phase.IDLE, "Clean exit prompted");
      record("CLEAN_EXIT");
    }
    Files.writeString(
        out.resolve("project-files-native.json"),
        ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "cases", cases)));
    glfwHideWindow(window);
    System.out.println(
        "PROJECT_FILES_NATIVE_PASS production Save-Discard-Cancel / New-Open-Exit / path Escape /"
            + " failed save abort / failed open retention / actual retry files");
  }

  // Preserve this fixture's original committed-document scenarios after explicit draft discard.
  private static void request(Intent intent) {
    dialog.workflow.request(intent);
    if (dialog.workflow.phase() == Phase.DRAFT) dialog.workflow.decideDraft(DraftDecision.DISCARD);
  }

  private static EditorState fixture() {
    var editor = new EditorState(new UserConfig(), false);
    require(
        editor.change(
            p -> {
              p.project.seed = 41;
              var n = new Noise();
              n.id = "owned";
              n.algorithm.key = "CONSTANT";
              p.noise2D.add(n);
            }),
        "File fixture validation");
    editor.select("noise2D", editor.project().noise2D.getFirst().uuid);
    ((Noise) editor.draft).id = "unapplied";
    return editor;
  }

  private static void init(EditorState editor) {
    state = editor;
    replacements = exits = 0;
    dialog = new ProjectFileDialog(state, () -> replacements++, () -> exits++);
    frames(2);
  }

  private static Map<String, Object> snapshot() {
    var row = new LinkedHashMap<String, Object>();
    row.put("project", ProjectIO.JSON.valueToTree(state.project()));
    row.put("revision", state.revision);
    row.put("history", state.document.historyBytes());
    row.put("dirty", state.document.dirty());
    row.put("selected", state.selected);
    row.put("path", state.document.path() == null ? "" : state.document.path().toString());
    return row;
  }

  private static void unchanged(Map<String, Object> before, Object draft) {
    require(
        before.equals(snapshot()) && state.draft == draft, "File failure/cancel mutated session");
  }

  private static void record(String id) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", id);
    row.put("seed", state.project().project.seed);
    row.put("noiseCount", state.project().noise2D.size());
    row.put("dirty", state.document.dirty());
    row.put("phase", dialog.workflow.phase().name());
    row.put("replacements", replacements);
    row.put("exits", exits);
    cases.add(row);
  }

  private static void click(String label) {
    click(label, true);
  }

  private static void click(String label, boolean settle) {
    frames(2);
    var center = dialog.probeControl(label);
    require(
        center != null, "Missing file dialog control " + label + " in " + dialog.workflow.phase());
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    if (settle) frames(2);
  }

  private static void text(String text) {
    click("Path");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addKeyEvent(ImGuiKey.Backspace, true);
    frame();
    io.addKeyEvent(ImGuiKey.Backspace, false);
    frame();
    if (!text.isEmpty()) {
      io.addInputCharactersUTF8(text);
      frame();
    }
  }

  private static void key(int key) {
    ImGui.getIO().addKeyEvent(key, true);
    frame();
    ImGui.getIO().addKeyEvent(key, false);
    frame();
    frames(2);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    glfwPollEvents();
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 800, ImGuiCond.Always);
    ImGui.begin("Project file reference");
    ImGui.text("Project file workflow");
    ImGui.end();
    dialog.draw();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
