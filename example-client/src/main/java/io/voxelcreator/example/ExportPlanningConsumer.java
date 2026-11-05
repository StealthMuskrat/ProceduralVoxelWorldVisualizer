package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-JAR replay of exact dependency payloads after background preview preparation. */
public final class ExportPlanningConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]));
  }

  static void run(Path out) throws Exception {
    int fields = 0, cells = 0;
    for (String mode :
        List.of("initial", "selection", "reload", "cancel", "escape", "retry", "close", "ready-cancel", "ready-selection-cancel", "ready-reload-escape", "ready-close")) {
      var directory = out.resolve(mode);
      var sourceFile = directory.resolve("source-after.json");
      var targetFile = directory.resolve("selection.pvresources.json");
      byte[] sourceBytes = Files.readAllBytes(sourceFile),
          targetBytes = Files.readAllBytes(targetFile);
      var before = new ProjectLoader().load(directory.resolve("source.json"));
      var source = new ProjectLoader().load(sourceFile);
      var envelope = ProjectIO.JSON.readTree(targetFile.toFile());
      require(
          envelope.size() == 2 && envelope.path("packageVersion").asInt() == 1, "Export envelope");
      var target =
          new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(envelope.get("resources")));
      require(
          before.success() && source.success() && target.success(),
          "Export planning snapshots rejected");
      var expected = ProjectIO.JSON.valueToTree(before.project());
      if (mode.equals("reload") || mode.equals("ready-reload-escape")) {
        ((ObjectNode) expected.get("project")).put("seed", 18);
        ((ObjectNode) expected.at("/noise2D/0/algorithm/parameters")).put("value", .25);
      }
      require(
          expected.equals(ProjectIO.JSON.valueToTree(source.project())),
          "Planning changed source definitions or metadata");
      boolean published = List.of("initial", "selection", "reload", "retry").contains(mode);
      var payload = target.project();
      if (published) {
        int count = mode.equals("selection") ? 1 : 2;
        require(payload.noise2D.size() == count, "Dependency closure count");
        for (int i = 0; i < count; i++)
          require(
              ProjectIO.JSON
                  .valueToTree(payload.noise2D.get(i))
                  .equals(ProjectIO.JSON.valueToTree(source.project().noise2D.get(i))),
              "Export changed exact dependency/source identities or values");
        require(
            payload.project.rootOrder.equals(
                Map.of(
                    "noise2D",
                    source.project().noise2D.subList(0, count).stream().map(n -> n.uuid).toList())),
            "Payload order");
      } else
        require(
            Arrays.equals(targetBytes, Files.readAllBytes(directory.resolve("prior-package.json"))),
            "Retired planning changed prior bytes");
      require(
          payload.noise3D.isEmpty()
              && payload.worldGenerator.layers.isEmpty()
              && payload.voxelTypes.isEmpty()
              && payload.voxelGroups.isEmpty()
              && payload.voxelSets.isEmpty()
              && payload.folders.isEmpty(),
          "Unselected resources leaked into package");
      try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
        for (var p : List.of(source.project(), payload)) {
          var values = ProjectIO.JSON.valueToTree(p);
          var compiled = new CompiledProject(p);
          for (var n : p.noise2D) {
            double value =
                switch (n.id) {
                  case "base" -> (mode.equals("reload") || mode.equals("ready-reload-escape")) ? .25 : .125;
                  case "derived" -> (mode.equals("reload") || mode.equals("ready-reload-escape")) ? .75 : .625;
                  case "prior" -> .25;
                  default -> Double.NaN;
                };
            var sample = compiled.noise2D(n.uuid, compiled.defaultSeed());
            for (int z = -1; z <= 1; z++)
              for (int x = -3; x <= 3; x++) {
                require(
                    Double.doubleToRawLongBits(value)
                        == Double.doubleToRawLongBits(sample.sample(x, z)),
                    "Independent constant/reference arithmetic");
                fields++;
              }
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
              values.equals(ProjectIO.JSON.valueToTree(p)), "Replay mutated source/payload values");
        }
      }
      require(
          Arrays.equals(sourceBytes, Files.readAllBytes(sourceFile))
              && Arrays.equals(targetBytes, Files.readAllBytes(targetFile)),
          "Replay changed saved bytes");
    }
    require(fields == 756 && cells == 6468, "Export planning observation count");
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "fields", fields, "cells", cells, "roots", List.of(2, 4))));
    System.out.println("EXPORT_PLANNING_ENGINE_ONLY_PASS 756 exact fields / 6468 EMPTY cells");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
