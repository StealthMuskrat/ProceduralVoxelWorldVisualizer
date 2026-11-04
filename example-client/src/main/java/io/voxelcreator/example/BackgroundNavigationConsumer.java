package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-JAR replay of saved source/Find/reference background Apply states. */
public final class BackgroundNavigationConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]));
  }

  static void run(Path out) throws Exception {
    int snapshots = 0, fields = 0, cells = 0;
    for (String route : List.of("source", "find", "reference")) {
      for (String mode :
          route.equals("source")
              ? List.of("success", "cancel", "escape", "retry", "stale", "after-stale", "close")
              : List.of("success", "cancel")) {
        var directory = out.resolve(route + "-" + mode);
        var before = new ProjectLoader().load(directory.resolve("before.json"));
        require(before.success(), "Navigation baseline rejected");
        boolean applied = List.of("success", "retry", "after-stale").contains(mode);
        for (String name :
            applied ? List.of("before", "after", "undone", "redone") : List.of("before", "after")) {
          var file = directory.resolve(name + ".json");
          byte[] bytes = Files.readAllBytes(file);
          var loaded = new ProjectLoader().load(file);
          require(loaded.success(), "Navigation saved project rejected");
          double value = applied && List.of("after", "redone").contains(name) ? .625 : .125;
          var expected = ProjectIO.JSON.valueToTree(before.project());
          ((ObjectNode) expected.at("/noise2D/0/algorithm/parameters")).put("value", value);
          require(
              expected.equals(ProjectIO.JSON.valueToTree(loaded.project())),
              "Navigation changed unrelated saved authoring values");
          require(
              loaded.project().noise2D.size() == 2
                  && loaded.project().noise2D.getFirst().id.equals("source")
                  && loaded.project().noise2D.getLast().id.equals("destination"),
              "Navigation fixture identities");
          int[] counts = observe(loaded.project(), value);
          fields += counts[0];
          cells += counts[1];
          snapshots++;
          require(
              Arrays.equals(bytes, Files.readAllBytes(file)),
              "Replay wrote saved navigation bytes");
        }
      }
    }
    require(snapshots == 32 && fields == 1344 && cells == 9408, "Navigation replay dimensions");
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "snapshots",
                snapshots,
                "fields",
                fields,
                "cells",
                cells,
                "roots",
                List.of(2, 4))));
    System.out.println(
        "BACKGROUND_NAVIGATION_ENGINE_ONLY_PASS 32 saved states / 1344 fields / 9408 EMPTY cells");
  }

  private static int[] observe(ProjectData project, double expected) {
    var values = ProjectIO.JSON.valueToTree(project);
    var compiled = new CompiledProject(project);
    int fields = 0, cells = 0;
    for (String id : List.of("source", "destination")) {
      var sampler = compiled.noise2D(id, compiled.defaultSeed());
      double value = id.equals("source") ? expected : .375;
      for (int z = -1; z <= 1; z++)
        for (int x = -3; x <= 3; x++) {
          require(
              Double.doubleToRawLongBits(sampler.sample(x, z)) == Double.doubleToRawLongBits(value),
              "Independent navigation field");
          fields++;
        }
    }
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (int z = -1; z <= 1; z++)
            for (int y = -3; y <= 3; y++)
              for (int x = -3; x <= 3; x++) {
                require(
                    world.getVoxelTypeId(new BlockPos(x, y, z)) == 0,
                    "Navigation created unauthored World voxels");
                cells++;
              }
        }
    }
    require(
        values.equals(ProjectIO.JSON.valueToTree(project)),
        "Navigation replay mutated source model");
    return new int[] {fields, cells};
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
