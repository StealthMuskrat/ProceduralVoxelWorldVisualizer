package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred actual Resources/Manager rendering of accepted, guarded and retired navigation. */
final class ResourceRevealProbe {
  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    for (String mode :
        List.of("NOISE", "OPERATION", "VOXEL", "SET", "GROUP", "GUARD", "LIFETIME", "SUPERSEDE")) {
      try (var state = new EditorState(new UserConfig(), false)) {
        require(state.change(ResourceRevealProbe::fixture), "Reveal fixture rejected");
        var properties = new PropertyPanel(state);
        var panel = new ResourcePanel(state, properties);
        panel.probeFilter("not-present", "not-present");
        panel.probeCloseRoot("noise3D");
        panel.probeCloseRoot("world");
        frame(panel);
        frame(panel);
        var before = ProjectIO.JSON.valueToTree(state.project());
        long history = state.document.historyBytes();
        long revision = state.revision;
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        UUID target =
            switch (mode) {
              case "OPERATION" ->
                  state.project().worldGenerator.layers.getFirst().operations.getLast().uuid;
              case "VOXEL" -> state.project().voxelTypes.getLast().uuid;
              case "SET" -> state.project().voxelSets.getLast().uuid;
              case "GROUP" -> state.project().voxelGroups.getLast().uuid;
              default -> state.project().noise3D.getLast().uuid;
            };
        var route =
            new EditorEvents.Target(state.project().project.uuid, target, null, null, "", false);
        if (mode.equals("GUARD")) {
          state.select("noise3D", state.project().noise3D.getFirst().uuid);
          ((Noise) state.draft).id = "private";
          properties.probeOpenReference(target);
          frame(panel);
          require(
              !panel.probeRevealPending() && panel.probeRevealed() == null,
              "Guard opened a reveal before a decision");
          require(
              properties.decideReferenceNavigation(SearchNavigation.Decision.CANCEL)
                  == SearchNavigation.Result.CANCELLED,
              "Reference Cancel rejected");
          properties.probeOpenReference(target);
          require(
              properties.decideReferenceNavigation(SearchNavigation.Decision.DISCARD)
                  == SearchNavigation.Result.NAVIGATED,
              "Reference Discard did not navigate");
        } else require(state.navigate(route), "Accepted reveal route rejected");
        if (mode.equals("LIFETIME")) {
          var path = out.resolve("same-identities.json");
          state.document.save(path);
          require(state.document.open(path).isEmpty(), "Same-UUID reopen rejected");
          for (int i = 0; i < 4; i++) frame(panel);
          require(
              !panel.probeRevealPending() && panel.probeRevealed() == null,
              "Retired reveal crossed document lifetime");
        } else {
          if (mode.equals("SUPERSEDE")) {
            target = state.project().worldGenerator.layers.getFirst().operations.getLast().uuid;
            require(
                state.navigate(
                    new EditorEvents.Target(
                        state.project().project.uuid, target, null, null, "", false)),
                "Successor reveal rejected");
          }
          for (int i = 0; i < 12 && panel.probeRevealPending(); i++) frame(panel);
          require(
              target.equals(panel.probeRevealed()) && !panel.probeRevealPending(),
              "Accepted target did not become visible in its actual clipped browser: " + mode);
          require(panel.probeSelectionRow(target) != null, "Revealed row was not submitted");
          require(
              history == state.document.historyBytes() && revision == state.revision,
              "Reveal entered project history/revision");
          // Reveal is consumed: a later filter change is retained instead of replaying navigation.
          panel.probeFilter("not-present", "not-present");
          frame(panel);
          frame(panel);
          require(
              panel.probeSelectionRow(target) == null && !panel.probeRevealPending(),
              "Completed reveal reopened a later filter");
        }
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Reveal changed committed authoring or preference values");
        cases.add(
            Map.of(
                "case",
                mode,
                "target",
                target.toString(),
                "retired",
                !panel.probeRevealPending(),
                "projectUnchanged",
                true));
      }
    }
    Files.writeString(
        out.resolve("resource-reveal.json"),
        ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "cases", cases)));
  }

  private static void fixture(ProjectData p) {
    var folder = new Folder();
    folder.id = "fields";
    folder.rootKind = "noise3D";
    p.folders.add(folder);
    var world = new Folder();
    world.id = "world";
    world.rootKind = "world";
    p.folders.add(world);
    for (int i = 0; i < 400; i++) {
      var noise = new Noise();
      noise.id = "field-" + i;
      noise.dimension = 3;
      noise.parentFolderUuid = folder.uuid;
      noise.algorithm.key = "CONSTANT";
      noise.algorithm.parameters.clear();
      noise.algorithm.parameters.put("value", .25);
      p.noise3D.add(noise);
      var type = new VoxelType();
      type.id = "type-" + i;
      p.voxelTypes.add(type);
      var set = new Membership();
      set.id = "set-" + i;
      p.voxelSets.add(set);
      var group = new Membership();
      group.id = "group-" + i;
      p.voxelGroups.add(group);
    }
    var layer = new Layer();
    layer.id = "layer";
    layer.parentFolderUuid = world.uuid;
    for (int i = 0; i < 400; i++) {
      var op = new Operation();
      op.id = "placement-" + i;
      op.noiseGeneratorUuid = p.noise3D.getFirst().uuid;
      op.interpretation = "DENSITY";
      layer.operations.add(op);
    }
    p.worldGenerator.layers.add(layer);
  }

  private static void frame(ResourcePanel panel) {
    ImGui.getIO().setDisplaySize(1400, 900);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(600, 650, ImGuiCond.Always);
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
