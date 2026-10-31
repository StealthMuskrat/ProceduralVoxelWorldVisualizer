package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.Path;
import java.util.List;

/** Deferred independent history documents and analytical field 22/26 with full trace replay. */
public final class ProjectHistoryCompletionConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    TraceConsumer.main(new String[] {out.resolve("source-base.json").toString()});
    var base = ProjectIO.JSON.readTree(out.resolve("source-base.json").toFile());
    var edited = base.deepCopy();
    ((ObjectNode) edited.path("noise2D").get(0).path("algorithm").path("parameters"))
        .put("value", 4.);
    require(
        edited.equals(ProjectIO.JSON.readTree(out.resolve("source-edited.json").toFile())),
        "History edit changed other project values");
    require(
        edited.equals(ProjectIO.JSON.readTree(out.resolve("after.json").toFile())),
        "Guard/failure routes moved the final history document");
    for (var phase : List.of("undo", "redo", "refresh-failure")) {
      boolean forward = phase.equals("redo");
      require(
          (forward ? edited : base)
              .equals(ProjectIO.JSON.readTree(out.resolve(phase + ".json").toFile())),
          "Wrong complete history document for " + phase);
      var load = new ProjectLoader().load(out.resolve(phase + ".json"));
      require(load.success(), load.diagnostics().toString());
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20);
          var world = engine.openWorld(new CompiledProject(load.project()))) {
        var pos = new BlockPos(2, 0, 3);
        var trace = world.trace(pos);
        double expected = ((forward ? 4 : 2) + 2 * (2 + 1) + 3) * 2;
        require(
            trace.finalType() == 1
                && world.getVoxelTypeId(pos) == 1
                && trace.steps().size() == 2
                && trace.steps().stream().allMatch(step -> step.field() == expected),
            "Independent history field/world mismatch for " + phase);
        require(
            trace.steps().getFirst().action().equals("SET")
                && trace.steps().getLast().action().equals("NO_CHANGE"),
            "History changed threshold semantics");
        require(
            ProjectIO.JSON
                .readTree(ProjectIO.JSON.writeValueAsString(trace))
                .equals(ProjectIO.JSON.readTree(out.resolve(phase + "-trace.json").toFile())),
            "Complete independent trace mismatch for " + phase);
      }
    }
    require(
        ProjectIO.JSON
            .readTree(out.resolve("preferences-before.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())),
        "History completion changed preferences");
    System.out.println(
        "History completion engine-only PASS; exact Undo/Redo/refresh-failure documents /"
            + " independent fields 22 and 26 / complete trace replay / original preferences");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
