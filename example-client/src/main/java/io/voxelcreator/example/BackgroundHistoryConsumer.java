package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferred exact saved history states plus public engine-JAR worker Undo/Redo and persistence. */
public final class BackgroundHistoryConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]));
  }

  static void run(Path out) throws Exception {
    int fields = 0, cells = 0, snapshots = 0;
    for (String mode : List.of("clean", "discard", "cancel", "escape", "retry", "close")) {
      var directory = out.resolve(mode);
      var before = new ProjectLoader().load(directory.resolve("before.json"));
      require(before.success(), "History baseline rejected");
      boolean moved = List.of("clean", "discard", "retry").contains(mode);
      for (String name :
          moved ? List.of("before", "after", "redone") : List.of("before", "after")) {
        var file = directory.resolve(name + ".json");
        byte[] bytes = Files.readAllBytes(file);
        var loaded = new ProjectLoader().load(file);
        require(loaded.success(), "History saved candidate rejected");
        double expected = moved && name.equals("after") ? .125 : .625;
        var values = ProjectIO.JSON.valueToTree(before.project());
        ((ObjectNode) values.at("/noise2D/0/algorithm/parameters")).put("value", expected);
        require(
            values.equals(ProjectIO.JSON.valueToTree(loaded.project())),
            "History changed unrelated authoring values or applied private input");
        int[] counts = observe(loaded.project(), expected);
        fields += counts[0];
        cells += counts[1];
        snapshots++;
        require(Arrays.equals(bytes, Files.readAllBytes(file)), "Replay changed saved bytes");
      }
    }
    var original = new ProjectLoader().load(out.resolve("clean/before.json"));
    require(original.success(), "Public history baseline");
    var document = new DocumentSession();
    document.setValidator(NoiseCompiler::validate);
    require(document.open(out.resolve("clean/before.json")).isEmpty(), "Public history Open");
    require(
        document.edit(p -> p.noise2D.getFirst().algorithm.parameters.put("value", .875)).isEmpty(),
        "Public authored change");
    int[] authored = observe(document.data(), .875);
    fields += authored[0];
    cells += authored[1];
    long bytes = document.historyBytes();
    try (var worker = Executors.newSingleThreadExecutor()) {
      var undo = worker.submit(document.requestUndo()::prepare).get(10, TimeUnit.SECONDS);
      require(
          undo.success()
              && document.currentHistory(undo)
              && document.data().noise2D.getFirst().algorithm.parameters.get("value") == .875,
          "Prepared Undo moved before publication");
      document.applyHistory(undo);
      int[] undone = observe(document.data(), .625);
      fields += undone[0];
      cells += undone[1];
      var redo = worker.submit(document.requestRedo()::prepare).get(10, TimeUnit.SECONDS);
      require(
          redo.success()
              && document.currentHistory(redo)
              && document.data().noise2D.getFirst().algorithm.parameters.get("value") == .625,
          "Prepared Redo moved before publication");
      document.applyHistory(redo);
      int[] redone = observe(document.data(), .875);
      fields += redone[0];
      cells += redone[1];
      require(
          document.historyBytes() == bytes && !document.currentHistory(redo),
          "Prepared movement changed accounting/consumption");
    }
    document.save(out.resolve("api-redone.json"));
    var loaded = new ProjectLoader().load(out.resolve("api-redone.json"));
    require(loaded.success(), "Public history save/load");
    var expected = ProjectIO.JSON.valueToTree(original.project());
    ((ObjectNode) expected.at("/noise2D/0/algorithm/parameters")).put("value", .875);
    require(
        expected.equals(ProjectIO.JSON.valueToTree(loaded.project())),
        "Public history changed unrelated saved data");
    int[] persisted = observe(loaded.project(), .875);
    fields += persisted[0];
    cells += persisted[1];
    require(
        snapshots == 15 && fields == 399 && cells == 5586, "Background history replay dimensions");
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "snapshots",
                snapshots,
                "preparedSteps",
                2,
                "apiStates",
                4,
                "fields",
                fields,
                "cells",
                cells,
                "roots",
                List.of(2, 4))));
    System.out.println(
        "BACKGROUND_HISTORY_ENGINE_ONLY_PASS 15 saved states / 2 prepared steps / 399 fields / 5586"
            + " EMPTY cells");
  }

  private static int[] observe(ProjectData project, double expected) {
    var values = ProjectIO.JSON.valueToTree(project);
    var compiled = new CompiledProject(project);
    var sampler = compiled.noise2D("field", compiled.defaultSeed());
    int fields = 0, cells = 0;
    for (int z = -1; z <= 1; z++)
      for (int x = -3; x <= 3; x++) {
        require(
            Double.doubleToRawLongBits(sampler.sample(x, z))
                == Double.doubleToRawLongBits(expected),
            "Exact independent history field");
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
                    world.getVoxelTypeId(new BlockPos(x, y, z)) == 0,
                    "History created unauthored World output");
                cells++;
              }
        }
    }
    require(values.equals(ProjectIO.JSON.valueToTree(project)), "Runtime changed project values");
    return new int[] {fields, cells};
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
