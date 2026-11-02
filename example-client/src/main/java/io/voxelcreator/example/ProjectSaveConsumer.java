package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Deferred public model/engine-JAR save, failed Save As, history and immutable-snapshot replay. */
public final class ProjectSaveConsumer {
  private static final double OFFSET = .12345678901234566;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var document = new DocumentSession();
    require(document.edit(ProjectSaveConsumer::fixture).isEmpty(), "Save fixture rejected");
    var original = out.resolve("saved.pvproj.json");
    document.save(original);
    String before = Files.readString(original);
    Files.writeString(out.resolve("before.json"), before);
    var old = load(original);
    var compiledOld = new CompiledProject(old);
    require(
        document
            .edit(
                p -> {
                  p.project.seed = 29;
                  p.worldGenerator.layers.getFirst().defaults.threshold = 1.25;
                })
            .isEmpty(),
        "Candidate edit rejected");
    var active = document.data();
    var token = document.sessionToken();
    long history = document.historyBytes();
    String candidateText = ProjectIO.JSON.writeValueAsString(active);
    var blocked = out.resolve("blocked-save");
    Files.createDirectories(blocked);
    var marker = blocked.resolve("keep");
    Files.writeString(marker, "original destination");
    try {
      document.save(blocked);
      throw new AssertionError("Save As unexpectedly replaced a nonempty directory");
    } catch (IOException expected) {
    }
    require(
        document.data() == active
            && document.sessionToken() == token
            && document.path().equals(original)
            && document.historyBytes() == history
            && document.dirty()
            && candidateText.equals(ProjectIO.JSON.writeValueAsString(document.data())),
        "Failed save changed document identity, path, history or checkpoint");
    require(
        before.equals(Files.readString(original))
            && Files.readString(marker).equals("original destination"),
        "Failed save changed an existing destination");
    var savedAs = out.resolve("saved-as.pvproj.json");
    document.save(savedAs);
    require(
        !document.dirty() && document.path().equals(savedAs) && document.sessionToken() == token,
        "Save As changed document lifetime or failed to mark the published checkpoint");
    var changed = load(savedAs);
    var expected = ProjectIO.JSON.valueToTree(old).deepCopy();
    ((ObjectNode) expected.path("project")).put("seed", 29L);
    ((ObjectNode) expected.path("worldGenerator").path("layers").get(0).path("defaults"))
        .put("threshold", 1.25);
    require(
        expected.equals(ProjectIO.JSON.valueToTree(changed)),
        "Unexpected authoring change beyond seed/threshold");
    var compiledNew = new CompiledProject(changed);
    document.undo();
    require(
        document.dirty() && before.equals(ProjectIO.JSON.writeValueAsString(document.data())),
        "Undo did not restore the complete old project");
    document.redo();
    require(
        !document.dirty()
            && candidateText.equals(ProjectIO.JSON.writeValueAsString(document.data())),
        "Redo lost the saved candidate");
    require(document.historyBytes() == history, "Save changed transaction history");
    document.save(original);
    require(
        candidateText.equals(Files.readString(original)),
        "Replacing an existing project changed its payload");
    long cells = 0, fields = 0;
    try (var engine = new NoiseGeneratorEngine(2, 32, 16L << 20)) {
      for (int state = 0; state < 2; state++) {
        var p = state == 0 ? old : changed;
        var compiled = state == 0 ? compiledOld : compiledNew;
        var sampler = compiled.noise2D(p.noise2D.getFirst().uuid, p.project.seed);
        for (int z = -1; z <= 1; z++)
          for (int x = -3; x <= 3; x++) {
            require(
                Double.doubleToRawLongBits(sampler.sample(x, z))
                    == Double.doubleToRawLongBits(x + OFFSET),
                "Saved precision changed the sampled field");
            fields++;
          }
        double threshold = state == 0 ? .25 : 1.25;
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int z = -1; z <= 1; z++)
              for (int y = -3; y <= 3; y++)
                for (int x = -3; x <= 3; x++) {
                  int value = y >= -2 && y <= 2 && x + OFFSET >= threshold ? 1 : 0;
                  require(
                      world.getVoxelTypeId(new BlockPos(x, y, z)) == value,
                      "Saved snapshot changed exact voxels");
                  cells++;
                }
          }
      }
    }
    require(fields == 42 && cells == 588, "Unexpected replay dimensions");
    Files.writeString(
        out.resolve("project-save.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "states",
                2,
                "fields",
                fields,
                "cells",
                cells,
                "failedSaves",
                1,
                "roots",
                List.of(2, 4))));
    System.out.println("PROJECT_SAVE_CONSUMER_PASS");
  }

  private static ProjectData load(Path path) throws IOException {
    var load = new ProjectLoader().load(path);
    require(load.success(), "Saved project load rejected: " + load.diagnostics());
    return load.project();
  }

  private static void fixture(ProjectData p) {
    p.project.seed = 17;
    p.project.minY = -2;
    p.project.maxY = 2;
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var noise = new Noise();
    noise.id = "coordinate";
    noise.dimension = 2;
    noise.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "coordinate-expression", 2);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x + 0.12345678901234566"));
    noise.processing.add(expression);
    noise.outputBinding = Binding.earlier(expression.uuid);
    p.noise2D.add(noise);
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    layer.defaults.threshold = .25;
    var operation = new Operation();
    operation.id = "selection";
    operation.noiseGeneratorUuid = noise.uuid;
    operation.interpretation = "SELECTION";
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
