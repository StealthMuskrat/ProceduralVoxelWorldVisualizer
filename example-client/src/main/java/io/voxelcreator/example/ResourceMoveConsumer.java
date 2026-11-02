package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred public engine-JAR replay of ordered moves, live cuts and captured copies. */
public final class ResourceMoveConsumer {
  private record Case(String name, ProjectData project, CompiledProject compiled, boolean narrow) {}

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var document = new DocumentSession();
    require(document.edit(ResourceMoveConsumer::fixture).isEmpty(), "Fixture rejected");
    var source = document.data().worldGenerator.layers.getFirst();
    var target = document.data().worldGenerator.layers.get(1);
    var copies = document.data().worldGenerator.layers.getLast().uuid;
    UUID sourceId = source.uuid, targetId = target.uuid;
    UUID set = source.operations.getFirst().uuid, clear = source.operations.getLast().uuid;
    UUID sourceNoop = source.operations.get(1).uuid, targetNoop = target.operations.getFirst().uuid;
    UUID field = document.data().noise2D.getFirst().uuid;
    var cases = new ArrayList<Case>();
    cases.add(capture("original", document, true));
    String original = text(document);
    require(
        document
            .edit(p -> ResourceGraph.move(p, List.of(clear, set), "operation", targetId))
            .isEmpty(),
        "Move rejected");
    require(order(document, sourceId).equals(List.of(sourceNoop)), "Move retained source entries");
    require(
        order(document, targetId).equals(List.of(targetNoop, set, clear)),
        "Selection reversed execution order");
    var expectedMove = ProjectIO.copy(cases.getFirst().project());
    var expectedSource = expectedMove.worldGenerator.layers.getFirst();
    var expectedTarget = expectedMove.worldGenerator.layers.get(1);
    var expectedSet = expectedSource.operations.getFirst();
    var expectedClear = expectedSource.operations.getLast();
    expectedSource.operations.removeIf(o -> o.uuid.equals(set) || o.uuid.equals(clear));
    expectedTarget.operations.addAll(List.of(expectedSet, expectedClear));
    require(
        ProjectIO.JSON
            .valueToTree(expectedMove)
            .equals(ProjectIO.JSON.valueToTree(document.data())),
        "Move changed values, references, settings or metadata");
    String moved = text(document);
    document.undo();
    require(original.equals(text(document)), "Move was not one complete undo");
    document.redo();
    require(moved.equals(text(document)), "Move redo changed complete authoring state");
    cases.add(capture("moved", document, true));

    var cut = new ResourceClipboard(document.data(), List.of(set, clear), true);
    var copy = new ResourceClipboard(document.data(), List.of(clear, set), false);
    require(
        document.edit(p -> ResourceGraph.reorderBefore(p, List.of(clear), set)).isEmpty(),
        "Live reorder rejected");
    require(
        order(document, targetId).equals(List.of(targetNoop, clear, set)), "Live reorder failed");
    String reordered = text(document);
    require(document.edit(p -> cut.paste(p, "operation", sourceId)).isEmpty(), "Live cut rejected");
    require(
        order(document, sourceId).equals(List.of(sourceNoop, clear, set)),
        "Cut replayed its old captured order");
    require(
        order(document, targetId).equals(List.of(targetNoop)),
        "Cut did not remove its source block");
    String cutText = text(document);
    document.undo();
    require(reordered.equals(text(document)), "Cut was not one complete undo");
    document.redo();
    require(cutText.equals(text(document)), "Cut redo changed definitions");
    cases.add(capture("live-cut", document, false));

    var beforeCopy = ProjectIO.JSON.valueToTree(document.data());
    require(
        document.edit(p -> copy.paste(p, "operation", copies)).isEmpty(), "Captured copy rejected");
    var copied = document.data().worldGenerator.layers.getLast().operations;
    require(
        copied.stream().map(o -> o.id).toList().equals(List.of("set", "clear")),
        "Copy lost its captured order");
    require(
        !copied.getFirst().uuid.equals(set)
            && !copied.getLast().uuid.equals(clear)
            && !copied.getFirst().uuid.equals(copied.getLast().uuid),
        "Copy retained original identities");
    var originalPair = cases.getFirst().project().worldGenerator.layers.getFirst().operations;
    for (int i = 0; i < 2; i++) {
      var expected = originalPair.get(i == 0 ? 0 : 2);
      var value = ProjectIO.JSON.valueToTree(copied.get(i));
      ((com.fasterxml.jackson.databind.node.ObjectNode) value)
          .put("uuid", expected.uuid.toString());
      require(
          value.equals(ProjectIO.JSON.valueToTree(expected)),
          "Copy changed captured values or external references");
    }
    var withoutCopies = ProjectIO.copy(document.data());
    withoutCopies.worldGenerator.layers.getLast().operations.clear();
    require(
        beforeCopy.equals(ProjectIO.JSON.valueToTree(withoutCopies)),
        "Copy changed existing authoring state");
    cases.add(capture("captured-copy", document, true));

    long cells = 0, fields = 0;
    try (var engine = new NoiseGeneratorEngine(2, 32, 16L << 20)) {
      for (var c : cases) {
        String frozen = ProjectIO.JSON.writeValueAsString(c.project());
        var sampler = c.compiled().noise2D(field, c.project().project.seed);
        for (int z = -1; z <= 1; z++)
          for (int x = -3; x <= 3; x++) {
            require(
                Double.doubleToRawLongBits(sampler.sample(x, z))
                    == Double.doubleToRawLongBits(x + .125),
                "Move changed an external field");
            fields++;
          }
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  c.compiled(),
                  new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int z = -1; z <= 1; z++)
              for (int y = -3; y <= 3; y++)
                for (int x = -3; x <= 3; x++) {
                  int expected = y >= -2 && y <= 2 && x >= 1 && (!c.narrow() || x < 2) ? 1 : 0;
                  require(
                      world.getVoxelTypeId(new BlockPos(x, y, z)) == expected,
                      "Ordered SET/CLEAR expectation failed: " + c.name());
                  cells++;
                }
          }
        require(
            frozen.equals(ProjectIO.JSON.writeValueAsString(c.project())),
            "Runtime mutated authoring data");
        var path = out.resolve(c.name() + ".pvproj.json");
        new ProjectIO().save(path, c.project());
        var loaded = new ProjectLoader().load(path);
        require(
            loaded.success() && frozen.equals(ProjectIO.JSON.writeValueAsString(loaded.project())),
            "Move state did not round trip exactly");
      }
    }
    require(cases.size() == 4 && fields == 84 && cells == 1176, "Unexpected replay dimensions");
    Files.writeString(
        out.resolve("resource-move.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "states", cases.size(), "fields", fields, "cells", cells, "roots", List.of(2, 4))));
    System.out.println("RESOURCE_MOVE_CONSUMER_PASS");
  }

  private static Case capture(String name, DocumentSession document, boolean narrow) {
    var project = ProjectIO.copy(document.data());
    return new Case(name, project, new CompiledProject(project), narrow);
  }

  private static List<UUID> order(DocumentSession document, UUID layer) {
    return document.data().worldGenerator.layers.stream()
        .filter(l -> l.uuid.equals(layer))
        .findFirst()
        .orElseThrow()
        .operations
        .stream()
        .map(o -> o.uuid)
        .toList();
  }

  private static String text(DocumentSession document) throws Exception {
    return ProjectIO.JSON.writeValueAsString(document.data());
  }

  private static void fixture(ProjectData p) {
    p.project.seed = 17;
    p.project.minY = -2;
    p.project.maxY = 2;
    var stone = new VoxelType();
    stone.id = "stone";
    p.voxelTypes.add(stone);
    var noise = new Noise();
    noise.id = "coordinate";
    noise.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "coordinate-expression", 2);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x + 0.125"));
    noise.processing.add(expression);
    noise.outputBinding = Binding.earlier(expression.uuid);
    p.noise2D.add(noise);
    for (String name : List.of("source", "target", "copies")) {
      var layer = new Layer();
      layer.id = name;
      layer.defaults.voxelTypeUuid = stone.uuid;
      p.worldGenerator.layers.add(layer);
    }
    var source = p.worldGenerator.layers.getFirst();
    source.operations.add(operation("set", noise.uuid, "SET", .25));
    source.operations.add(operation("source-noop", noise.uuid, "NO_CHANGE", .25));
    source.operations.add(operation("clear", noise.uuid, "CLEAR", 1.25));
    p.worldGenerator
        .layers
        .get(1)
        .operations
        .add(operation("target-noop", noise.uuid, "NO_CHANGE", .25));
  }

  private static Operation operation(String id, UUID field, String action, double threshold) {
    var operation = new Operation();
    operation.id = id;
    operation.noiseGeneratorUuid = field;
    operation.interpretation = "SELECTION";
    operation.overrides.put("action", ProjectIO.JSON.valueToTree(action));
    operation.overrides.put("threshold", ProjectIO.JSON.valueToTree(threshold));
    return operation;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
