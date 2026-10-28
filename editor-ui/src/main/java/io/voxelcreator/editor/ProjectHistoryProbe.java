package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectHistorySession.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred production history-modal events, explicit saved snapshots and injected failure. */
public final class ProjectHistoryProbe {
  private static ProjectHistoryPanel panel;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
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
      try (var state = new EditorState(new UserConfig(), false)) {
        require(state.change(p -> p.noise2D.add(noise("owned", .25))), "Fixture rejected");
        UUID source = state.project().noise2D.getFirst().uuid;
        var user = ProjectIO.JSON.valueToTree(state.user);
        state.document.save(out.resolve("history-before.json"));
        var before = ProjectIO.JSON.valueToTree(state.project());
        require(
            state.change(
                p -> {
                  p.noise2D.getFirst().algorithm.parameters.put("value", .75);
                  p.noise2D.getFirst().seedOffset = 17;
                  p.noise2D.add(noise("temporary", .5));
                }),
            "Joint history edit rejected");
        UUID temporary = state.project().noise2D.getLast().uuid;
        var edited = ProjectIO.JSON.valueToTree(state.project());
        state.document.save(out.resolve("history-edited.json"));
        state.select("noise2D", temporary);
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var draft = state.draft;
        long revision = state.revision, bytes = state.document.historyBytes();
        var blocked = new AtomicBoolean();
        panel = new ProjectHistoryPanel(state, blocked::get);
        panel.request(Direction.UNDO);
        frames(4);
        require(panel.session.pending(), "Raw-only Undo bypassed guard");
        click("CANCEL");
        require(
            state.draft == draft
                && state.seedInputs.text("seedOffset").equals("-")
                && edited.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && bytes == state.document.historyBytes(),
            "Undo Cancel changed state");
        panel.request(Direction.UNDO);
        frames(4);
        state.seedInputs.edit(state, "seedOffset", "Seed", "--");
        click("DISCARD");
        require(
            !panel.session.pending()
                && state.draft == draft
                && state.seedInputs.text("seedOffset").equals("--")
                && state.revision == revision,
            "Stale discard cleared newer input");
        panel.request(Direction.UNDO);
        frames(4);
        click("DISCARD");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.selected == null
                && state.draft == null
                && state.multiSelection.isEmpty()
                && !state.seedInputs.pending(state)
                && state.revision == revision + 1
                && state.document.dirty()
                && bytes == state.document.historyBytes(),
            "Undo selection/history/dirty reconciliation");
        state.document.save(out.resolve("history-undone.json"));
        state.select("noise2D", source);
        ((Noise) state.draft).id = "private-name";
        draft = state.draft;
        panel.request(Direction.REDO);
        frames(4);
        escape();
        require(
            state.draft == draft
                && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.document.canRedo(),
            "Redo Cancel lost work or future");
        panel.request(Direction.REDO);
        frames(4);
        click("DISCARD");
        require(
            edited.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.selected.equals(source)
                && ((Noise) state.draft).id.equals("owned")
                && state.document.dirty()
                && bytes == state.document.historyBytes(),
            "Redo applied private name or changed history");
        state.document.save(out.resolve("history-redone.json"));
        state.seedInputs.edit(state, "seedOffset", "Seed", "+");
        draft = state.draft;
        revision = state.revision;
        panel.request(Direction.UNDO);
        frames(4);
        state.document.setValidator(
            p ->
                List.of(
                    new Diagnostic(
                        "HISTORY_FIXTURE",
                        Diagnostic.Severity.ERROR,
                        "Injected history rejection",
                        "",
                        null,
                        null,
                        null)));
        click("DISCARD");
        require(
            !panel.session.pending()
                && state.draft == draft
                && state.seedInputs.text("seedOffset").equals("+")
                && edited.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && bytes == state.document.historyBytes()
                && state.document.canUndo()
                && !state.document.canRedo(),
            "Rejected history consumed state or private input");
        state.document.setValidator(NoiseCompiler::validate);
        blocked.set(true);
        panel.request(Direction.UNDO);
        frames(3);
        require(!panel.session.pending() && state.draft == draft, "Private editor bypass");
        blocked.set(false);
        panel.request(Direction.UNDO);
        frames(4);
        blocked.set(true);
        click("DISCARD");
        require(
            !panel.session.pending()
                && state.draft == draft
                && state.revision == revision
                && user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Late blocker or preference isolation");
        Files.writeString(
            out.resolve("project-history-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "undoCancel",
                    true,
                    "staleDiscardRejected",
                    true,
                    "undoDeletedSelection",
                    true,
                    "redoCancel",
                    true,
                    "redoRestoredModel",
                    true,
                    "failedHistoryRetained",
                    true,
                    "privateBlock",
                    true,
                    "preferencesUnchanged",
                    true,
                    "dirtyBaseline",
                    true)));
        System.out.println(
            "PROJECT_HISTORY_NATIVE_PASS production modal / explicit discard / stale input /"
                + " selection / failure retention / saved snapshots");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Noise noise(String id, double value) {
    var noise = new Noise();
    noise.id = id;
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", value);
    return noise;
  }

  private static void click(String key) {
    var at = panel.control(key);
    require(at != null, "Missing history control " + key);
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
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
