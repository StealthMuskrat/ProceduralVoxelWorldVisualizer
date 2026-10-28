package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred native definition: real capture toggle/buttons, attribution exports and private draft.
 */
final class WorldWorkProbe {
  private static EditorState state;
  private static Viewports views;
  private static ProfilePanel panel;
  private static final ImBoolean open = new ImBoolean(true);

  static void run(Path out, long handle) throws Exception {
    Files.createDirectories(out);
    try (var editor = new EditorState(new UserConfig(), false)) {
      state = editor;
      var p =
          ProjectIO.JSON.readValue(
              Path.of("fixtures/work-profile-project.json").toFile(), ProjectData.class);
      require(state.change(target -> ResourceGraph.assign(target, p)), "Work-profile fixture");
      state.select("layer", state.project().worldGenerator.layers.getFirst().uuid);
      var draft = (Layer) state.draft;
      draft.id = "private-unapplied-layer";
      var before = ProjectIO.JSON.valueToTree(state.project());
      var user = ProjectIO.JSON.valueToTree(state.user);
      long revision = state.revision, history = state.document.historyBytes();
      views = new Viewports(state, new PropertyPanel(state), handle);
      panel = new ProfilePanel(state, target -> state.navigate(target));
      panel.probeWorkControls();
      open.set(true);
      try {
        frames(3);
        click(panel.probeWorkControl("enabled"));
        click(panel.probeWorkControl("start"));
        var capture = state.engine.profiler().current().orElseThrow();
        require(
            capture.snapshot().options().worldContributions(),
            "Actual work-total checkbox did not configure capture");
        try (var world =
            state.engine.openWorld(
                new CompiledProject(state.project()),
                new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          var chunk = world.generateChunk(new ChunkPos(0, 0, 0));
          for (int z = 0; z < 4; z++)
            for (int y = 0; y < 4; y++)
              for (int x = 0; x < 4; x++)
                require(chunk.getVoxelTypeId(x, y, z) == 1, "Profile control changed a voxel");
        }
        frames(2);
        click(panel.probeWorkControl("stop"));
        var snapshot = capture.snapshot();
        require(snapshot.state() == Profiler.State.STOPPED, "Actual Stop button");
        require(
            snapshot.samples().stream()
                    .filter(s -> s.kind() == Profiler.Kind.OPERATION_WORK)
                    .count()
                == 3,
            "Placement aggregate count");
        require(
            snapshot.samples().stream().filter(s -> s.kind() == Profiler.Kind.LAYER_WORK).count()
                == 2,
            "Layer aggregate count");
        var rows = ProfilePanel.filter(snapshot, Profiler.Kind.LAYER_WORK, "base");
        require(rows.size() == 1, "Layer aggregate UI filter");
        var target = ProfilePanel.target(rows.getFirst());
        require(
            state.canNavigate(target) && state.navigate(target) && state.draft == draft,
            "Layer source navigation replaced private draft");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && user.equals(ProjectIO.JSON.valueToTree(state.user))
                && revision == state.revision
                && history == state.document.historyBytes(),
            "Capture controls changed authoring or preferences");
        ProfileIO.writeJSON(out.resolve("world-work-capture.json"), snapshot);
        ProfileIO.writeCSV(out.resolve("world-work-capture.csv"), snapshot);
        new ProjectIO().save(out.resolve("world-work-project.json"), state.project());
        Files.writeString(
            out.resolve("world-work-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "actualCaptureControls",
                    true,
                    "privateDraftPreserved",
                    true,
                    "viewOnly",
                    true,
                    "stagingReleased",
                    state.engine.metrics().stagingBytes() == 0)));
        System.out.println(
            "WORLD_WORK_NATIVE_PASS real opt-in capture controls / layer filtering and UUID"
                + " navigation / preserved private draft / explicit exports");
      } finally {
        views.close();
      }
    } finally {
      state = null;
      views = null;
      panel = null;
    }
  }

  private static void click(float[] center) {
    require(center != null, "Missing work-profile control");
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1050, 880, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(false, ImGuiCond.Always);
    panel.draw(open, views.streamingStatistics());
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
