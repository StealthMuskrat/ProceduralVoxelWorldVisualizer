package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred native definition for owned seed text and parent/guarded Apply rejection. */
public final class SeedTextProbe {
  private static EditorState state;
  private static PropertyPanel panel;
  private static boolean focus;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1200, 1000);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(SeedTextProbe::fixture), "Fixture rejected");
        UUID noiseId = state.project().noise2D.getFirst().uuid;
        UUID layerId = state.project().worldGenerator.layers.getFirst().uuid;
        UUID operationId =
            state.project().worldGenerator.layers.getFirst().operations.getFirst().uuid;
        var user = ProjectIO.JSON.valueToTree(state.user);
        state.select("noise2D", noiseId);
        panel = new PropertyPanel(state);
        frames(4);
        var source = (Noise) state.draft;
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        reveal("seedOffset", "seedOffset");
        type("-");
        require(
            !state.applyDraft() && source.seedOffset == 0 && state.draft == source,
            "Invalid source seed applied old value");
        var navigation = new SearchNavigation(state);
        var target =
            new EditorEvents.Target(state.project().project.uuid, layerId, null, null, "", false);
        require(
            navigation.request(target) == SearchNavigation.Result.NEEDS_DECISION,
            "Raw text bypassed draft guard");
        require(
            navigation.decide(SearchNavigation.Decision.APPLY)
                == SearchNavigation.Result.NEEDS_DECISION,
            "Guarded Apply ignored raw text");
        navigation.decide(SearchNavigation.Decision.CANCEL);
        require(
            state.seedInputs.text("seedOffset").equals("-")
                && before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Rejected input changed ownership/history");
        require(
            panel.goToProblem(
                EditorEvents.Target.diagnostic(
                    state.project().project.uuid, state.issues.getFirst())),
            "Source seed diagnostic");
        until("seedOffset");
        type("17");
        require(source.seedOffset == 17, "Source seed repair");
        commit(before);

        state.select("layer", layerId);
        frames(3);
        var layer = (Layer) state.draft;
        before = ProjectIO.JSON.valueToTree(state.project());
        reveal("settings/seedOffset", "settings/seedOffset");
        type(Long.toString(Long.MIN_VALUE));
        require(layer.defaults.seedOffset == Long.MIN_VALUE, "Layer seed exactness");
        reveal("settings/fieldGates/seedOffset", "settings/fieldGates/seedOffset");
        type("9223372036854775808");
        require(
            !state.applyDraft() && layer.defaults.fieldGates.seedOffset == 0,
            "Overflow gate seed applied");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Gate input published a partial layer");
        require(
            panel.goToProblem(
                EditorEvents.Target.diagnostic(
                    state.project().project.uuid, state.issues.getFirst())),
            "Gate seed diagnostic");
        until("settings/fieldGates/seedOffset");
        type(Long.toString(Long.MAX_VALUE));
        require(layer.defaults.fieldGates.seedOffset == Long.MAX_VALUE, "Gate seed exactness");
        commit(before);

        state.select("operation", operationId);
        frames(3);
        var operation = (Operation) state.draft;
        before = ProjectIO.JSON.valueToTree(state.project());
        reveal("settings/seedOffset", "override/seedOffset");
        click(panel.probeWorldControl("override/seedOffset"));
        frames(3);
        reveal("settings/seedOffset", "settings/seedOffset");
        type("+");
        require(
            !state.applyDraft()
                && operation.overrides.get("seedOffset").longValue() == Long.MIN_VALUE,
            "Placement invalid seed fallback");
        panel.probeSeedProblems();
        frames(3);
        click(panel.probeSeedControl("restoreAll"));
        frames(3);
        require(
            !state.seedInputs.pending(state)
                && operation.overrides.containsKey("seedOffset")
                && operation.overrides.get("seedOffset").longValue() == Long.MIN_VALUE,
            "Restore removed override presence");
        reveal("settings/seedOffset", "settings/seedOffset");
        type("43");
        commit(before);
        require(
            user.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Seed editing changed preferences");
        state.document.save(out.resolve("seed-text-project.json"));
        Files.writeString(
            out.resolve("seed-text-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "sourceSeed",
                    17,
                    "layerSeed",
                    Long.MIN_VALUE,
                    "gateSeed",
                    Long.MAX_VALUE,
                    "overrideSeed",
                    43,
                    "rawTextRetained",
                    true,
                    "guardedApplyRejected",
                    true,
                    "restoreKeptOverride",
                    true,
                    "privateDrafts",
                    true,
                    "oneUndoRedo",
                    true)));
        System.out.println(
            "SEED_TEXT_NATIVE_PASS raw input / guarded Apply / exact long extrema / explicit"
                + " restore / private transactions");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void commit(com.fasterxml.jackson.databind.JsonNode before) {
    require(state.applyDraft(), "Seed Apply rejected: " + state.issues);
    var applied = ProjectIO.JSON.valueToTree(state.project());
    state.document.undo();
    state.revision++;
    require(before.equals(ProjectIO.JSON.valueToTree(state.project())), "One seed Undo");
    state.document.redo();
    state.revision++;
    require(applied.equals(ProjectIO.JSON.valueToTree(state.project())), "One seed Redo");
  }

  private static void fixture(ProjectData p) {
    var noise = new Noise();
    noise.id = "seeded";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", 0.0);
    var hash = ProcessingRegistry.create("COORDINATE_HASH", "hash");
    noise.processing.add(hash);
    noise.outputBinding = Binding.earlier(hash.uuid);
    p.noise2D.add(noise);
    var voxel = new VoxelType();
    voxel.id = "stone";
    p.voxelTypes.add(voxel);
    var layer = new Layer();
    layer.id = "seeds";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = voxel.uuid;
    layer.defaults.fieldGates.kind = "PROBABILITY";
    layer.defaults.fieldGates.probability = 1;
    var operation = new Operation();
    operation.id = "all";
    operation.interpretation = "ALL";
    operation.noiseGeneratorUuid = noise.uuid;
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
  }

  private static void reveal(String key, String expected) {
    require(
        panel.goToProblem(
            new EditorEvents.Target(
                state.project().project.uuid, state.selected, null, key, "", false)),
        "Seed route unavailable");
    focus = true;
    until(expected);
  }

  private static void until(String key) {
    for (int i = 0; i < 20; i++) {
      frame();
      if (("null:" + key).equals(panel.probeFocusedProperty())) break;
    }
    require(("null:" + key).equals(panel.probeFocusedProperty()), "Seed focus missing: " + key);
    frames(3);
  }

  private static void type(String value) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
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
    frame();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 900, ImGuiCond.Always);
    if (focus) {
      ImGui.setNextWindowFocus();
      focus = false;
    }
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
