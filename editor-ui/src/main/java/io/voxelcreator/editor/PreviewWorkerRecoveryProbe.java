package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.GridExtent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Deferred complete-payload/terminal-worker failure recovery across actual preview families. */
final class PreviewWorkerRecoveryProbe {
  private static EditorState state;
  private static Viewports views;

  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    var rows = new ArrayList<Map<String, Object>>();
    for (String family :
        List.of("IMAGE", "MESH", "WORLD", "VOLUME_WINDOW", "NOISE_SOURCE", "WORLD_SOURCE")) {
      int kind = family.equals("IMAGE") ? 0 : family.startsWith("WORLD") ? 2 : 1;
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        state.user.editor.autoRegenerate = true;
        state.user.editor.opportunitiesPerSecond = 20;
        state.user.visualization.streaming = false;
        state.user.visualization.unlit = true;
        state.user.visualization.grid = false;
        state.user.performance.renderDistance = state.user.performance.verticalDistance = 0;
        state.user.performance.streamChunks = 27;
        require(
            state.change(PreviewWorkerRecoveryProbe::fixture), "Preview recovery fixture rejected");
        var properties = new PropertyPanel(state);
        try (var viewport = new Viewports(state, properties, window)) {
          views = viewport;
          configure(family);
          until(() -> views.probePreviewCurrent(kind));
          until(
              () ->
                  state.background.getActiveCount() == 0 && state.background.getQueue().isEmpty());
          var folder = out.resolve(family);
          Files.createDirectories(folder);
          state.document.save(folder.resolve("before.json"));
          var before = ProjectIO.JSON.valueToTree(state.project());
          var preferences = ProjectIO.JSON.valueToTree(state.user);
          long revision = state.revision, history = state.document.historyBytes();
          state.select("noise2D", state.project().noise2D.getFirst().uuid);
          var draft = state.draft;
          ((Noise) draft).id = "private";
          state.seedInputs.edit(state, "seedOffset", "Seed", "-");
          var privateValues = ProjectIO.JSON.valueToTree(draft);
          var points = new ArrayList<>(List.of("START", "FINISH"));
          if (family.equals("IMAGE") || family.equals("NOISE_SOURCE")) points.add("BODY");
          for (String point : points) {
            long accepted = views.probeAcceptedPreviewEpoch(kind);
            var scene = views.probeAcceptedScene(kind);
            int volumeTexture =
                family.equals("VOLUME_WINDOW") ? views.probeVolumeResidentTexture() : 0;
            Error failure =
                point.equals("FINISH")
                    ? new AssertionError("terminal-preview-worker")
                    : new AssertionError() {
                      public String getMessage() {
                        throw new AssertionError("unreadable worker");
                      }
                    };
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            views.probePreviewWorkerGate(kind, entered, release);
            views.probePreviewWorkerFailure(kind, point, failure);
            views.probePreviewRegenerate(kind);
            try {
              until(
                  () ->
                      views.probePreviewCpuSubmitted(kind) || views.probePreviewWorkerFailed(kind));
              require(entered.await(5, TimeUnit.SECONDS), "Preview worker did not reach its gate");
            } finally {
              release.countDown();
            }
            // Leave complete mailbox results untouched until the executor has captured FINISH.
            withoutFrames(
                () ->
                    state.background.getActiveCount() == 0
                        && state.background.getQueue().isEmpty());
            until(() -> views.probePreviewWorkerFailed(kind));
            require(
                views.probeAcceptedPreviewEpoch(kind) == accepted
                    && views.probeAcceptedScene(kind) == scene,
                "Failed worker replaced accepted display ownership");
            if (volumeTexture != 0)
              require(
                  views.probeVolumeResidentTexture() == volumeTexture
                      && !views.probeVolumeStatistics().pending(),
                  "Failed worker replaced or retained pending volume upload");
            require(
                views.probeSourceStatus(kind).contains("Regenerate"),
                "Failure lost explicit retry guidance");
            long failedStamp = views.probeSubmissionStamp(kind);
            long noRetryUntil = System.nanoTime() + 100_000_000L;
            do {
              frame();
            } while (System.nanoTime() < noRetryUntil);
            require(
                failedStamp == views.probeSubmissionStamp(kind),
                "Failed preview retried automatically");
            require(
                failure.getSuppressed().length == 0, "Worker reporting mutated the original error");
            views.probePreviewRegenerate(kind);
            until(() -> views.probePreviewCurrent(kind));
            require(
                views.probeAcceptedScene(kind) != scene
                    || views.probeAcceptedPreviewEpoch(kind) > accepted,
                "Explicit retry did not publish a new accepted result");
            rows.add(
                Map.of(
                    "family",
                    family,
                    "point",
                    point,
                    "kind",
                    kind,
                    "acceptedBefore",
                    accepted,
                    "displayRetained",
                    true,
                    "retryPublished",
                    true));
          }
          require(
              before.equals(ProjectIO.JSON.valueToTree(state.project()))
                  && revision == state.revision
                  && history == state.document.historyBytes()
                  && !state.document.dirty(),
              "Preview recovery changed project/history");
          require(
              state.draft == draft
                  && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                  && state.seedInputs.text("seedOffset").equals("-"),
              "Preview recovery changed private input");
          require(
              preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
              "Preview recovery changed preferences");
          new ProjectIO().save(folder.resolve("after.json"), state.project());
        }
      }
    }
    require(rows.size() == 14, "Unexpected preview recovery matrix");
    Files.writeString(
        out.resolve("preview-worker-native.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "cases",
                rows,
                "projectPreserved",
                true,
                "privatePreserved",
                true,
                "preferencesPreserved",
                true,
                "physicalInputVerified",
                false)));
  }

  private static void configure(String family) {
    var two = state.project().noise2D.getFirst().uuid;
    var three = state.project().noise3D.getFirst().uuid;
    var layer = state.project().worldGenerator.layers.getFirst().uuid;
    switch (family) {
      case "IMAGE" -> {
        views.probeAdmissionSource(two);
        views.probeNoiseTab(0);
      }
      case "MESH" -> views.probeFixedExtent(three, "Threshold Voxels", GridExtent.cube(8));
      case "WORLD" -> views.probeWorld(layer, "Final", 8, .25);
      case "VOLUME_WINDOW" -> views.probeVolumeStream(three, 32, 16, 16, 16);
      case "NOISE_SOURCE" -> {
        views.probeFixedExtent(three, "Threshold Voxels", GridExtent.cube(8));
        views.probeStreaming3D();
      }
      case "WORLD_SOURCE" -> views.probeWorldStream(layer, "Final");
      default -> throw new AssertionError(family);
    }
  }

  private static void fixture(ProjectData project) {
    project.project.minY = 0;
    project.project.maxY = 31;
    var two = new Noise();
    two.id = "two";
    two.algorithm.key = "CONSTANT";
    two.algorithm.parameters.clear();
    two.algorithm.parameters.put("value", .25);
    project.noise2D.add(two);
    var three = new Noise();
    three.id = "three";
    three.dimension = 3;
    three.algorithm.key = "CONSTANT";
    three.algorithm.parameters.clear();
    three.algorithm.parameters.put("value", .9);
    project.noise3D.add(three);
    var type = new VoxelType();
    type.id = "solid";
    project.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "world";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    var operation = new Operation();
    operation.id = "fill";
    operation.noiseGeneratorUuid = three.uuid;
    operation.interpretation = "DENSITY";
    layer.operations.add(operation);
    project.worldGenerator.layers.add(layer);
  }

  private static void until(BooleanSupplier ready) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!ready.getAsBoolean()) {
      frame();
      if (System.nanoTime() > end) throw new AssertionError("Preview recovery timeout");
      Thread.yield();
    }
  }

  private static void withoutFrames(BooleanSupplier ready) {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!ready.getAsBoolean()) {
      if (System.nanoTime() > end) throw new AssertionError("Preview worker exit timeout");
      Thread.yield();
    }
  }

  private static void frame() {
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
    ImGui.setNextWindowSize(950, 750, ImGuiCond.Always);
    views.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
