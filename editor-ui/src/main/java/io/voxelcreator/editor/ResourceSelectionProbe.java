package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production browser mouse/modal definition; fixture edits are staged explicitly. */
public final class ResourceSelectionProbe {
  private static EditorState state;
  private static ResourcePanel resources;

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
        require(state.change(ResourceSelectionProbe::fixture), "Fixture rejected");
        UUID a = state.project().noise2D.get(0).uuid;
        UUID b = state.project().noise2D.get(1).uuid;
        UUID c = state.project().noise2D.get(2).uuid;
        UUID voxel = state.project().voxelTypes.getFirst().uuid;
        var user = ProjectIO.JSON.valueToTree(state.user);
        state.select("noise2D", a);
        resources = new ResourcePanel(state, new PropertyPanel(state));
        frames(4);
        var before = ProjectIO.JSON.valueToTree(state.project());
        var draft = (Noise) state.draft;
        draft.algorithm.parameters.put("value", .875);
        long revision = state.revision, history = state.document.historyBytes();
        click(resources.probeSelectionRow(b));
        require(resources.probeSelectionPending() && state.draft == draft, "Tree lost draft");
        escape();
        require(
            !resources.probeSelectionPending()
                && state.draft == draft
                && state.selected.equals(a)
                && state.revision == revision
                && state.document.historyBytes() == history
                && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Cancel changed state");
        click(resources.probeSelectionRow(b));
        click(resources.probeSelectionButton("APPLY"));
        require(
            state.selected.equals(b)
                && state.project().noise2D.getFirst().algorithm.parameters.get("value") == .875,
            "Apply did not publish before selection");
        var applied = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())) && state.selected.equals(b),
            "One Undo or selection isolation");
        state.document.redo();
        state.revision++;
        require(applied.equals(ProjectIO.JSON.valueToTree(state.project())), "One Redo");
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        draft = (Noise) state.draft;
        click(resources.probeSelectionRow(c));
        click(resources.probeSelectionButton("APPLY"));
        require(
            resources.probeSelectionPending()
                && state.draft == draft
                && state.seedInputs.text("seedOffset").equals("-")
                && state.selected.equals(b),
            "Raw-only Apply guard");
        escape();
        click(resources.probeSelectionRow(c));
        click(resources.probeSelectionButton("DISCARD"));
        require(
            state.selected.equals(c)
                && !state.seedInputs.pending(state)
                && applied.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Discard changed model");
        draft = (Noise) state.draft;
        draft.seedOffset = 43;
        click(resources.probeSelectionRow(c));
        require(state.draft == draft && !resources.probeSelectionPending(), "Same owner refreshed");
        modifierClick(c, ImGuiKey.ModCtrl);
        require(
            resources.probeSelectionPending() && state.selected.equals(c),
            "Dirty empty selection bypass");
        escape();
        modifierClick(a, ImGuiKey.ModShift);
        require(resources.probeSelectionPending() && state.draft == draft, "Dirty range bypass");
        click(resources.probeSelectionButton("DISCARD"));
        require(
            state.selected.equals(a) && List.copyOf(state.multiSelection).equals(List.of(a, b, c)),
            "Range changed or unowned");
        click(resources.probeSelectionRow(b));
        modifierClick(c, ImGuiKey.ModCtrl);
        require(List.copyOf(state.multiSelection).equals(List.of(b, c)), "Ctrl sibling addition");
        modifierClick(c, ImGuiKey.ModCtrl);
        require(
            state.selected.equals(b) && state.multiSelection.equals(Set.of(b)),
            "Ctrl removal active");
        modifierClick(b, ImGuiKey.ModCtrl);
        require(
            state.selected == null && state.draft == null && state.multiSelection.isEmpty(),
            "Ctrl final removal");
        click(resources.probeSelectionRow(a));
        ((Noise) state.draft).id = "pending-manager";
        resources.showManager();
        frames(4);
        ImGui.setWindowPos("Voxel Manager", 780, 0, ImGuiCond.Always);
        frames(4);
        draft = (Noise) state.draft;
        click(resources.probeSelectionRow(voxel));
        require(
            resources.probeSelectionPending() && state.draft == draft, "Manager selection bypass");
        escape();
        click(resources.probeSelectionRow(voxel));
        state.refreshDraft();
        var replacement = state.draft;
        click(resources.probeSelectionButton("DISCARD"));
        require(
            !resources.probeSelectionPending()
                && state.draft == replacement
                && state.selected.equals(a),
            "Stale Discard replaced newer draft");
        require(
            applied.equals(ProjectIO.JSON.valueToTree(state.project()))
                && user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Navigation/preferences changed model");
        state.document.save(out.resolve("resource-selection-project.json"));
        Files.writeString(
            out.resolve("resource-selection-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "sameOwnerRetained",
                    true,
                    "cancelRetained",
                    true,
                    "rawApplyRejected",
                    true,
                    "rangeDiscard",
                    true,
                    "ctrlSiblings",
                    true,
                    "managerGuarded",
                    true,
                    "staleDiscardRejected",
                    true,
                    "oneUndoRedo",
                    true,
                    "preferencesUnchanged",
                    true)));
        System.out.println(
            "RESOURCE_SELECTION_NATIVE_PASS mouse / modifiers / modal guard / raw seed / ownership"
                + " / one Undo-Redo");
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
    var voxel = new VoxelType();
    voxel.id = "stone";
    p.voxelTypes.add(voxel);
  }

  private static void modifierClick(UUID id, int key) {
    ImGui.getIO().addKeyEvent(key, true);
    frame();
    click(resources.probeSelectionRow(id));
    ImGui.getIO().addKeyEvent(key, false);
    frames(3);
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
    resources.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
