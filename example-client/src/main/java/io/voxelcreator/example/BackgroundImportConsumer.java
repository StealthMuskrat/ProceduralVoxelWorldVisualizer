package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only replay of saved background import/native review states. */
final class BackgroundImportConsumer {
  static void run(Path out) throws Exception {
    long samples = 0, cells = 0;
    for (String mode : List.of("success", "cancel", "escape", "raw", "retry", "close", "ready-cancel", "ready-escape", "ready-close", "ready-invalid-cancel", "ready-failure-cancel")) {
      var directory = out.resolve(mode);
      var beforeFile = directory.resolve("before.json");
      var afterFile = directory.resolve("after.json");
      byte[] beforeBytes = Files.readAllBytes(beforeFile),
          afterBytes = Files.readAllBytes(afterFile);
      var before = new ProjectLoader().load(beforeFile);
      var after = new ProjectLoader().load(afterFile);
      require(before.success() && after.success(), "Background import snapshots rejected");
      boolean imported = mode.equals("success") || mode.equals("retry");
      require(
          before.project().noise2D.size() == 1
              && after.project().noise2D.size() == (imported ? 2 : 1),
          "Import review changed resource counts");
      var old = before.project().noise2D.getFirst();
      var kept =
          after.project().noise2D.stream()
              .filter(n -> n.uuid.equals(old.uuid))
              .findFirst()
              .orElseThrow();
      require(
          ProjectIO.JSON.valueToTree(old).equals(ProjectIO.JSON.valueToTree(kept))
              && before.project().project.uuid.equals(after.project().project.uuid)
              && before.project().project.seed == 17
              && after.project().project.seed == (mode.equals("raw") ? 18 : 17),
          "Import replaced source definitions or project identity/metadata");
      var expected = ProjectIO.JSON.valueToTree(before.project());
      if (mode.equals("raw"))
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("project")).put("seed", 18);
      var actual = ProjectIO.JSON.valueToTree(after.project());
      if (imported) {
        var incoming =
            after.project().noise2D.stream()
                .filter(n -> n.id.equals("incoming"))
                .findFirst()
                .orElseThrow();
        require(!incoming.uuid.equals(old.uuid), "Import reused an existing identity");
        require(
            after
                .project()
                .project
                .rootOrder
                .equals(Map.of("noise2D", List.of(old.uuid, incoming.uuid))),
            "Import changed the exact root order");
        var pack =
            ProjectIO.JSON.readTree(directory.resolve("资源 package.pvresources.json").toFile());
        require(
            !incoming.uuid.toString().equals(pack.at("/resources/noise2D/0/uuid").asText()),
            "Import did not duplicate its incoming identity");
        ((com.fasterxml.jackson.databind.node.ArrayNode) actual.get("noise2D")).remove(1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual.get("project"))
            .remove("rootOrder");
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("project"))
            .remove("rootOrder");
      }
      require(expected.equals(actual), "Import changed unrelated authoring values");
      try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
        for (var project : List.of(before.project(), after.project())) {
          var values = ProjectIO.JSON.valueToTree(project);
          var compiled = new CompiledProject(project);
          for (var noise : project.noise2D) {
            double scalar =
                noise.id.equals("existing") ? .125 : noise.id.equals("incoming") ? .75 : Double.NaN;
            var sampler = compiled.noise2D(noise.uuid, compiled.defaultSeed());
            for (int z = -1; z <= 1; z++)
              for (int x = -3; x <= 3; x++) {
                require(
                    Double.doubleToRawLongBits(sampler.sample(x, z))
                        == Double.doubleToRawLongBits(scalar),
                    "Import changed exact scalar output");
                samples++;
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
                        "Import introduced World output");
                    cells++;
                  }
            }
          require(
              values.equals(ProjectIO.JSON.valueToTree(project)),
              "Runtime sampling changed authoring data");
        }
      }
      require(
          Arrays.equals(beforeBytes, Files.readAllBytes(beforeFile))
              && Arrays.equals(afterBytes, Files.readAllBytes(afterFile)),
          "Runtime replay changed saved snapshots");
    }
    require(samples == 504 && cells == 6468, "Unexpected background import observation count");
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "fields", samples, "cells", cells, "roots", List.of(2, 4))));
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
