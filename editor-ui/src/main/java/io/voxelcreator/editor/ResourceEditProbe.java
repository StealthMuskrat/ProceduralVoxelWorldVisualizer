package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production inline-rename, shortcut, delete-confirmation and edit-guard workflow. */
public final class ResourceEditProbe {
  private static EditorState state;
  private static ResourcePanel resources;
  private static boolean focusResources;

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
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(ResourceEditProbe::fixture), "Fixture rejected");
        UUID a = state.project().noise2D.getFirst().uuid;
        UUID folder = state.project().folders.getFirst().uuid;
        var user = ProjectIO.JSON.valueToTree(state.user);
        state.select("noise2D", a);
        resources = new ResourcePanel(state, new PropertyPanel(state));
        focusResources = true;
        frames(4);
        var before = ProjectIO.JSON.valueToTree(state.project());
        ((Noise) state.draft).seedOffset = 17;
        rename("renamed");
        require(
            resources.probeEditPending() && state.project().noise2D.getFirst().id.equals("a"),
            "Rename bypassed draft guard");
        click(resources.probeEditButton("APPLY"));
        require(
            state.project().noise2D.getFirst().id.equals("renamed")
                && state.project().noise2D.getFirst().seedOffset == 17,
            "Combined rename Apply");
        var renamed = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(before.equals(ProjectIO.JSON.valueToTree(state.project())), "One combined Undo");
        state.document.redo();
        state.revision++;
        require(renamed.equals(ProjectIO.JSON.valueToTree(state.project())), "One combined Redo");
        state.refreshDraft();
        ((Noise) state.draft).seedOffset = 23;
        var privateDraft = state.draft;
        long revision = state.revision, history = state.document.historyBytes();
        rename("b");
        click(resources.probeEditButton("APPLY"));
        require(
            resources.probeEditPending()
                && state.draft == privateDraft
                && renamed.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history,
            "Rejected action partially published Properties");
        escape();
        escape();
        state.refreshDraft();
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        resources.shortcut("Duplicate");
        frames(4);
        click(resources.probeEditButton("APPLY"));
        require(
            resources.probeEditPending()
                && state.seedInputs.text("seedOffset").equals("-")
                && state.project().noise2D.size() == 3,
            "Duplicate ignored raw seed");
        escape();
        resources.shortcut("Duplicate");
        frames(4);
        click(resources.probeEditButton("DISCARD"));
        UUID clone = state.selected;
        require(
            !clone.equals(a)
                && state.project().noise2D.size() == 4
                && ((Noise) state.draft).seedOffset == 17
                && !state.seedInputs.pending(state),
            "Duplicate Discard changed source");
        state.seedInputs.edit(state, "seedOffset", "Seed", "+");
        resources.shortcut("Delete");
        frames(4);
        click(resources.probeEditButton("confirmDelete"));
        click(resources.probeEditButton("APPLY"));
        require(
            resources.probeEditPending() && EditorState.find(state.project(), clone) != null,
            "Delete laundered invalid Properties");
        escape();
        resources.shortcut("Delete");
        frames(4);
        click(resources.probeEditButton("confirmDelete"));
        click(resources.probeEditButton("DISCARD"));
        require(
            EditorState.find(state.project(), clone) == null
                && state.selected == null
                && !state.seedInputs.pending(state),
            "Delete did not reconcile selection");
        state.document.undo();
        state.revision++;
        require(
            ((Noise) EditorState.find(state.project(), clone)).seedOffset == 17,
            "Delete Undo restored provisional seed");
        state.document.redo();
        state.revision++;
        require(EditorState.find(state.project(), clone) == null, "Delete Redo");
        state.select("noise2D", a);
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        resources.shortcut("Cut");
        resources.shortcut("Paste");
        frames(4);
        require(resources.probeEditPending(), "Cut/Paste bypassed guard");
        escape();
        state.select("folder", folder);
        resources.shortcut("Paste");
        frames(4);
        require(
            ((Noise) EditorState.find(state.project(), a)).parentFolderUuid.equals(folder),
            "Cancel consumed cut payload");
        revision = state.revision;
        resources.shortcut("Paste");
        frames(3);
        require(state.revision == revision, "Successful cut payload was not consumed");
        require(
            user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Browser edits changed preferences");
        state.document.save(out.resolve("resource-edit-project.json"));
        Files.writeString(
            out.resolve("resource-edit-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "combinedUndoRedo",
                    true,
                    "failedCandidateRetained",
                    true,
                    "rawDuplicateRejected",
                    true,
                    "duplicateDiscard",
                    true,
                    "rawDeleteRejected",
                    true,
                    "deleteUndoRedo",
                    true,
                    "cancelKeptCut",
                    true,
                    "successfulCutConsumed",
                    true,
                    "preferencesUnchanged",
                    true)));
        System.out.println(
            "RESOURCE_EDIT_NATIVE_PASS inline text / atomic Apply / rollback / duplicate / delete /"
                + " cut lifecycle");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    for (int i = 0; i < 3; i++) {
      var noise = new Noise();
      noise.id = String.valueOf((char) ('a' + i));
      noise.algorithm.key = "CONSTANT";
      noise.algorithm.parameters.clear();
      noise.algorithm.parameters.put("value", (i + 1) * .25);
      p.noise2D.add(noise);
    }
    var folder = new Folder();
    folder.id = "folder";
    folder.rootKind = "noise2D";
    p.folders.add(folder);
  }

  private static void rename(String name) {
    resources.shortcut("Rename");
    focusResources = true;
    frames(3);
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(name);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
    require(resources.probeEditPending(), "Inline rename did not reach the edit guard");
  }

  private static void escape() {
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
    frames(3);
  }

  private static void click(float[] at) {
    require(at != null, "Control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(700, 850, ImGuiCond.Always);
    if (focusResources) {
      ImGui.setNextWindowFocus();
      focusResources = false;
    }
    resources.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
