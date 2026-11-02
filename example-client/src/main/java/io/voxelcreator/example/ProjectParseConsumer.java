package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred public engine-JAR replay of whole-document loading and rejected-open ownership. */
public final class ProjectParseConsumer {
  private record Rejected(String text, String code, String pointer) {}

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var document = new DocumentSession();
    require(document.edit(ProjectParseConsumer::fixture).isEmpty(), "Fixture rejected");
    var originalPath = out.resolve("original.pvproj.json");
    document.save(originalPath);
    String valid = Files.readString(originalPath);
    require(document.edit(p -> p.project.seed = 29).isEmpty(), "Current edit rejected");
    var active = document.data();
    var token = document.sessionToken();
    String current = ProjectIO.JSON.writeValueAsString(active);
    long history = document.historyBytes();
    var compiledBefore = new CompiledProject(active);
    var field = active.noise2D.getFirst().uuid;
    var failures = new ArrayList<Rejected>();
    for (String tail :
        List.of("{}", "[]", "2", "true", "null", "\"tail\"", "garbage", "{", "/*comment*/"))
      failures.add(new Rejected(valid + "\n" + tail, "INVALID_JSON", "/"));
    for (String version :
        List.of(
            "3",
            "2.0",
            "\"2\"",
            "true",
            "[]",
            "{}",
            "4294967298",
            "-4294967294",
            "340282366920938463463374607431768211458")) {
      ObjectNode tree = ProjectIO.JSON.readTree(valid).deepCopy();
      tree.set("formatVersion", ProjectIO.JSON.readTree(version));
      failures.add(
          new Rejected(
              ProjectIO.JSON.writeValueAsString(tree), "UNSUPPORTED_VERSION", "/formatVersion"));
    }
    ObjectNode unknown = ProjectIO.JSON.readTree(valid).deepCopy();
    ((ObjectNode) unknown.path("project")).put("unknown~/field", "bad");
    failures.add(
        new Rejected(
            ProjectIO.JSON.writeValueAsString(unknown),
            "INVALID_JSON",
            "/project/unknown~0~1field"));
    ObjectNode malformed = ProjectIO.JSON.readTree(valid).deepCopy();
    var noise = (ObjectNode) malformed.path("noise2D").get(0);
    var disabled = new Item();
    disabled.id = "disabled";
    disabled.enabled = false;
    disabled.kind = "EXPRESSION";
    disabled.inputs.put("a~/b", Binding.noiseField(field));
    ObjectNode disabledTree = ProjectIO.JSON.valueToTree(disabled);
    ((ObjectNode) disabledTree.path("inputs").path("a~/b")).put("noiseGeneratorUuid", "malformed");
    ((com.fasterxml.jackson.databind.node.ArrayNode) noise.path("processing")).add(disabledTree);
    failures.add(
        new Rejected(
            ProjectIO.JSON.writeValueAsString(malformed),
            "INVALID_STRUCTURE",
            "/noise2D/0/processing/1/inputs/a~0~1b/noiseGeneratorUuid"));
    int rejected = 0;
    for (var failure : failures) {
      var path = out.resolve("rejected-" + rejected + ".json");
      Files.writeString(path, failure.text());
      var load = new ProjectLoader().load(path);
      require(!load.success() && load.project() == null, "Rejected candidate exposed a project");
      check(load.diagnostics(), failure);
      check(document.open(path), failure);
      require(
          document.data() == active
              && document.sessionToken() == token
              && document.path().equals(originalPath)
              && document.historyBytes() == history
              && document.dirty()
              && current.equals(ProjectIO.JSON.writeValueAsString(document.data())),
          "Rejected open changed current session");
      require(
          valid.equals(Files.readString(originalPath))
              && failure.text().equals(Files.readString(path)),
          "Read changed file bytes");
      if (!failure.pointer().equals("/") && !failure.pointer().equals("/formatVersion"))
        require(
            !ProjectIO.JSON.readTree(failure.text()).at(failure.pointer()).isMissingNode(),
            "Diagnostic pointer does not resolve the rejected value");
      rejected++;
    }
    document.undo();
    require(
        document.data().project.seed == 17 && !document.dirty(),
        "Rejected open changed saved checkpoint/history");
    document.redo();
    require(
        current.equals(ProjectIO.JSON.writeValueAsString(document.data())) && document.dirty(),
        "Rejected open lost complete redo");
    var acceptedPath = out.resolve("accepted.pvproj.json");
    Files.writeString(acceptedPath, " \t\r\n" + valid + "\r\n\t ");
    var accepted = new ProjectLoader().load(acceptedPath);
    require(
        accepted.success() && valid.equals(ProjectIO.JSON.writeValueAsString(accepted.project())),
        "Legal whitespace changed project values");
    require(
        document.open(acceptedPath).isEmpty()
            && !document.dirty()
            && document.path().equals(acceptedPath)
            && document.sessionToken() != token
            && document.historyBytes() == 0,
        "Successful retry did not replace the complete document");
    var compiledAfter = new CompiledProject(accepted.project());
    long fields = 0, cells = 0;
    try (var engine = new NoiseGeneratorEngine(2, 32, 16L << 20)) {
      for (var compiled : List.of(compiledBefore, compiledAfter)) {
        var sampler = compiled.noise2D(field, compiled.defaultSeed());
        for (int z = -1; z <= 1; z++)
          for (int x = -3; x <= 3; x++) {
            require(
                Double.doubleToRawLongBits(sampler.sample(x, z))
                    == Double.doubleToRawLongBits(x + .125),
                "Loading changed exact field values");
            fields++;
          }
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int z = -1; z <= 1; z++)
              for (int y = -3; y <= 3; y++)
                for (int x = -3; x <= 3; x++) {
                  int expected = y >= -2 && y <= 2 && x >= 1 ? 1 : 0;
                  require(
                      world.getVoxelTypeId(new BlockPos(x, y, z)) == expected,
                      "Rejected load or retry changed exact voxels");
                  cells++;
                }
          }
      }
    }
    require(rejected == 20 && fields == 42 && cells == 588, "Unexpected replay dimensions");
    require(
        valid.equals(ProjectIO.JSON.writeValueAsString(document.data()))
            && valid.equals(Files.readString(originalPath)),
        "Runtime changed authoring state");
    Files.writeString(
        out.resolve("project-parse.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "rejected", rejected, "fields", fields, "cells", cells, "roots", List.of(2, 4))));
    System.out.println("PROJECT_PARSE_CONSUMER_PASS");
  }

  private static void check(List<Diagnostic> issues, Rejected expected) {
    require(
        issues.size() == 1
            && issues.getFirst().code().equals(expected.code())
            && issues.getFirst().jsonPointer().equals(expected.pointer()),
        "Unexpected rejection diagnostic: " + issues);
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
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = stone.uuid;
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
