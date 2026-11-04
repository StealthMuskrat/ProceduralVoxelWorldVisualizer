package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only replay of actual staged/replaced native package snapshots. */
public final class BackgroundExportConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    long fields = 0, cells = 0;
    for (String mode : List.of("success", "cancel", "escape", "raw", "retry", "close")) {
      var directory = out.resolve(mode);
      var sourceFile = directory.resolve("source-after.json");
      var targetFile = directory.resolve("selection 资源.pvresources.json");
      byte[] sourceBytes = Files.readAllBytes(sourceFile),
          targetBytes = Files.readAllBytes(targetFile);
      var original = new ProjectLoader().load(directory.resolve("source.json"));
      var source = new ProjectLoader().load(sourceFile);
      var envelope = ProjectIO.JSON.readTree(targetFile.toFile());
      require(
          envelope.size() == 2 && envelope.path("packageVersion").asInt() == 1,
          "Wrong package format");
      var loaded =
          new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(envelope.get("resources")));
      require(
          original.success() && source.success() && loaded.success(), "Export snapshots rejected");
      var expected = ProjectIO.JSON.valueToTree(original.project());
      if (mode.equals("raw"))
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("project")).put("seed", 18);
      require(
          expected.equals(ProjectIO.JSON.valueToTree(source.project())),
          "Export changed source project");
      boolean published = mode.equals("success") || mode.equals("retry");
      var payload = loaded.project();
      require(
          payload.noise2D.size() == 1 && source.project().noise2D.size() == 1,
          "Export changed resource counts");
      if (published) {
        var field = payload.noise2D.getFirst();
        require(
            field.id.equals("field")
                && ProjectIO.JSON
                    .valueToTree(field)
                    .equals(ProjectIO.JSON.valueToTree(source.project().noise2D.getFirst())),
            "Export differs from committed definition");
      } else
        require(
            Arrays.equals(
                Files.readAllBytes(directory.resolve("prior-package.pvresources.json")),
                targetBytes),
            "Retired export changed prior package bytes");
      try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
        for (var project : List.of(source.project(), payload)) {
          var values = ProjectIO.JSON.valueToTree(project);
          var compiled = new CompiledProject(project);
          var field = project.noise2D.getFirst();
          double scalar =
              field.id.equals("field") ? .625 : field.id.equals("prior") ? .25 : Double.NaN;
          var sampler = compiled.noise2D(field.uuid, compiled.defaultSeed());
          for (int z = -1; z <= 1; z++)
            for (int x = -3; x <= 3; x++) {
              require(
                  Double.doubleToRawLongBits(sampler.sample(x, z))
                      == Double.doubleToRawLongBits(scalar),
                  "Export changed exact field output");
              fields++;
            }
          for (int root : new int[] {2, 4})
            try (var world =
                engine.openWorld(
                    compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
              for (int z = -1; z <= 1; z++)
                for (int y = -3; y <= 3; y++)
                  for (int x = -3; x <= 3; x++) {
                    require(
                        world.getVoxelTypeId(new BlockPos(x, y, z)) == 0,
                        "Export introduced World output");
                    cells++;
                  }
            }
          require(
              values.equals(ProjectIO.JSON.valueToTree(project)),
              "Sampling changed authoring data");
        }
      }
      require(
          Arrays.equals(sourceBytes, Files.readAllBytes(sourceFile))
              && Arrays.equals(targetBytes, Files.readAllBytes(targetFile)),
          "Replay changed saved bytes");
    }
    require(fields == 252 && cells == 3528, "Unexpected background export replay dimensions");
    ExportPlanningConsumer.run(out.resolveSibling("planning"));
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "fields", fields, "cells", cells, "roots", List.of(2, 4))));
    System.out.println("BACKGROUND_EXPORT_ENGINE_ONLY_PASS 252 exact fields / 3528 EMPTY cells");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
