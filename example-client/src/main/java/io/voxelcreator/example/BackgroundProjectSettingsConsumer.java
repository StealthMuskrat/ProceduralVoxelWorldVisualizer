package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-JAR replay of complete native saved settings states and exact ambient values. */
public final class BackgroundProjectSettingsConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]));
  }

  static void run(Path out) throws Exception {
    int fields = 0, cells = 0, snapshots = 0;
    for (String mode : List.of("success", "cancel", "escape", "raw", "retry", "close")) {
      var directory = out.resolve(mode);
      var before = new ProjectLoader().load(directory.resolve("before.json"));
      require(
          before.success()
              && before.project().project.seed == 5
              && before.project().project.minY == -2
              && before.project().project.maxY == 2,
          "Settings baseline");
      boolean applied = List.of("success", "retry").contains(mode);
      for (String name :
          applied ? List.of("before", "after", "undone", "redone") : List.of("before", "after")) {
        var file = directory.resolve(name + ".json");
        byte[] bytes = Files.readAllBytes(file);
        var loaded = new ProjectLoader().load(file);
        require(loaded.success(), "Saved settings rejected");
        boolean target = applied && List.of("after", "redone").contains(name);
        long seed = target ? 17 : mode.equals("raw") && name.equals("after") ? 6 : 5;
        int minY = target ? -1 : -2, maxY = target ? 1 : 2;
        var expected = ProjectIO.JSON.valueToTree(before.project());
        var settings = (ObjectNode) expected.get("project");
        settings.put("seed", seed);
        settings.put("minY", minY);
        settings.put("maxY", maxY);
        require(
            expected.equals(ProjectIO.JSON.valueToTree(loaded.project())),
            "Settings changed unrelated authoring values or leaked private Properties");
        int[] counts = observe(loaded.project(), seed + minY + maxY + (maxY - minY + 1));
        fields += counts[0];
        cells += counts[1];
        snapshots++;
        require(
            Arrays.equals(bytes, Files.readAllBytes(file)), "Settings replay changed saved bytes");
      }
    }
    require(snapshots == 16 && fields == 336 && cells == 4704, "Settings replay dimensions");
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
        "BACKGROUND_PROJECT_SETTINGS_ENGINE_ONLY_PASS 16 saved states / 336 fields / 4704"
            + " inclusive-bound cells");
  }

  private static int[] observe(ProjectData project, long offset) {
    var values = ProjectIO.JSON.valueToTree(project);
    var compiled = new CompiledProject(project);
    var sampler = compiled.noise2D("ambient", compiled.defaultSeed());
    int fields = 0, cells = 0;
    for (int z = -1; z <= 1; z++)
      for (int x = -3; x <= 3; x++) {
        require(
            Double.doubleToRawLongBits(sampler.sample(x, z))
                == Double.doubleToRawLongBits((double) (x + offset)),
            "Independent settings ambient expression");
        fields++;
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
                    world.getVoxelTypeId(new BlockPos(x, y, z))
                        == (y >= project.project.minY && y <= project.project.maxY ? 1 : 0),
                    "Inclusive settings bounds");
                cells++;
              }
        }
    }
    require(values.equals(ProjectIO.JSON.valueToTree(project)), "Runtime changed settings model");
    return new int[] {fields, cells};
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
