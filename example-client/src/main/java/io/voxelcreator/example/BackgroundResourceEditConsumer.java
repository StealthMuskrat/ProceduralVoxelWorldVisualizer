package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferred saved browser transactions and public staged-edit replay using only the engine JAR. */
public final class BackgroundResourceEditConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]));
  }

  static void run(Path out) throws Exception {
    int snapshots = 0, fields = 0, cells = 0, solid = 0;
    for (String mode :
        List.of("clean", "apply", "discard", "cancel", "escape", "retry", "raw", "close")) {
      var directory = out.resolve(mode);
      var before = new ProjectLoader().load(directory.resolve("before.json"));
      require(before.success(), "Browser action baseline rejected");
      boolean applied = List.of("clean", "apply", "discard", "retry").contains(mode);
      for (String name :
          applied ? List.of("before", "after", "undone", "redone") : List.of("before", "after")) {
        var file = directory.resolve(name + ".json");
        byte[] bytes = Files.readAllBytes(file);
        var loaded = new ProjectLoader().load(file);
        require(loaded.success(), "Saved browser action rejected");
        boolean target = applied && List.of("after", "redone").contains(name);
        double value = target && List.of("apply", "retry").contains(mode) ? .625 : .125;
        var expected = ProjectIO.JSON.valueToTree(before.project());
        ((ObjectNode) expected.at("/noise2D/0")).put("id", target ? "renamed" : "a");
        ((ObjectNode) expected.at("/noise2D/0/algorithm/parameters")).put("value", value);
        ((ObjectNode) expected.get("project"))
            .put("seed", mode.equals("raw") && name.equals("after") ? 6 : 5);
        require(
            expected.equals(ProjectIO.JSON.valueToTree(loaded.project())),
            "Browser action changed unrelated authoring values");
        int[] counts = observe(loaded.project(), value);
        fields += counts[0];
        cells += counts[1];
        solid += counts[2];
        snapshots++;
        require(
            Arrays.equals(bytes, Files.readAllBytes(file)), "Replay changed browser saved bytes");
      }
    }
    var document = new DocumentSession();
    document.setValidator(NoiseCompiler::validate);
    require(document.open(out.resolve("clean/before.json")).isEmpty(), "Public staged edit Open");
    var baseline = ProjectIO.JSON.valueToTree(document.data());
    var id = document.data().noise2D.getFirst().uuid;
    var request =
        document.requestEdit(
            p -> p.noise2D.getFirst().algorithm.parameters.put("value", .625),
            NoiseCompiler::validate,
            p -> ResourceGraph.rename(p, id, "api-renamed"));
    try (var worker = Executors.newSingleThreadExecutor()) {
      var prepared = worker.submit(request::prepare).get(10, TimeUnit.SECONDS);
      require(
          prepared.success()
              && document.currentEdit(prepared)
              && baseline.equals(ProjectIO.JSON.valueToTree(document.data())),
          "Public staged edit published before owner adoption");
      document.applyEdit(prepared);
      int[] count = observe(document.data(), .625);
      fields += count[0];
      cells += count[1];
      solid += count[2];
      document.undo();
      require(
          baseline.equals(ProjectIO.JSON.valueToTree(document.data())),
          "Public staged edit one Undo");
      count = observe(document.data(), .125);
      fields += count[0];
      cells += count[1];
      solid += count[2];
      document.redo();
      count = observe(document.data(), .625);
      fields += count[0];
      cells += count[1];
      solid += count[2];
      require(!document.currentEdit(prepared), "Public staged edit remained consumable");
    }
    document.save(out.resolve("api-redone.json"));
    var reloaded = new ProjectLoader().load(out.resolve("api-redone.json"));
    require(reloaded.success(), "Public staged edit persistence");
    ((ObjectNode) baseline.at("/noise2D/0")).put("id", "api-renamed");
    ((ObjectNode) baseline.at("/noise2D/0/algorithm/parameters")).put("value", .625);
    require(
        baseline.equals(ProjectIO.JSON.valueToTree(reloaded.project())),
        "Public staged edit changed unrelated persisted values");
    require(
        snapshots == 24 && fields == 1134 && cells == 7938 && solid == 1260,
        "Browser action replay dimensions");
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "snapshots",
                snapshots,
                "apiStates",
                3,
                "preparedTransactions",
                1,
                "fields",
                fields,
                "cells",
                cells,
                "solidCells",
                solid,
                "roots",
                List.of(2, 4))));
    System.out.println(
        "BACKGROUND_RESOURCE_EDIT_ENGINE_ONLY_PASS 24 saved states / 3 API states / 1134 fields /"
            + " 7938 cells");
  }

  private static int[] observe(ProjectData project, double expected) {
    var values = ProjectIO.JSON.valueToTree(project);
    var compiled = new CompiledProject(project);
    int fields = 0, cells = 0, solid = 0;
    for (int i = 0; i < 2; i++) {
      var sampler = compiled.noise2D(project.noise2D.get(i).uuid, compiled.defaultSeed());
      for (int z = -1; z <= 1; z++)
        for (int x = -3; x <= 3; x++) {
          require(
              Double.doubleToRawLongBits(sampler.sample(x, z))
                  == Double.doubleToRawLongBits(i == 0 ? expected : .375),
              "Independent browser action field");
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
                int type = expected >= .5 && y >= -2 && y <= 2 ? 1 : 0;
                require(
                    world.getVoxelTypeId(new BlockPos(x, y, z)) == type,
                    "Independent staged threshold placement");
                cells++;
                if (type == 1) solid++;
              }
        }
    }
    require(
        values.equals(ProjectIO.JSON.valueToTree(project)),
        "Browser replay changed authoring model");
    return new int[] {fields, cells, solid};
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
