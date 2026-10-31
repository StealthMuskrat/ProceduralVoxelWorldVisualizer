package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.Path;
import java.util.List;

/**
 * Deferred independent persistence transformations and exact engine replay after draft decisions.
 */
public final class GuardedSourceConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    TraceConsumer.main(new String[] {out.resolve("source-project.json").toString()});
    var source = ProjectIO.JSON.readTree(out.resolve("source-project.json").toFile());
    var child = source.deepCopy();
    var operations = child.path("worldGenerator").path("layers").get(0).path("operations");
    ((ObjectNode) operations.get(operations.size() - 1).path("overrides")).put("threshold", 20);
    var renamed = source.deepCopy();
    ((ObjectNode) renamed.path("worldGenerator").path("layers").get(0)).put("id", "applied-layer");
    for (var name : List.of("child", "unchanged", "precheck", "postcheck", "reveal", "successor")) {
      var expected = name.equals("child") ? child : name.equals("precheck") ? source : renamed;
      require(
          expected.equals(ProjectIO.JSON.readTree(out.resolve(name + "-after.json").toFile())),
          "Unexpected complete persisted transformation for " + name);
      var load = new ProjectLoader().load(out.resolve(name + "-after.json"));
      require(load.success(), load.diagnostics().toString());
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20);
          var world = engine.openWorld(new CompiledProject(load.project()))) {
        var pos = new BlockPos(2, 0, 3);
        var trace = world.trace(pos);
        var second = trace.steps().getLast();
        require(
            trace.finalType() == 1 && world.getVoxelTypeId(pos) == 1 && trace.steps().size() == 2,
            "World/trace mismatch for " + name);
        require(
            trace.steps().stream().allMatch(step -> step.field() == 22),
            "Independent (2+2*(2+1)+3)*2 oracle changed for " + name);
        require(
            trace.steps().getFirst().action().equals("SET")
                && second.action().equals(name.equals("child") ? "SET" : "NO_CHANGE"),
            "Threshold semantics changed for " + name);
        require(
            second.details().comparisons().getLast().right() == (name.equals("child") ? 20 : 30),
            "Wrong applied threshold for " + name);
        require(
            ProjectIO.JSON
                .valueToTree(trace)
                .equals(ProjectIO.JSON.readTree(out.resolve(name + "-trace.json").toFile())),
            "Complete independent trace replay differs for " + name);
      }
    }
    require(
        source.equals(ProjectIO.JSON.readTree(out.resolve("child-undo.json").toFile())),
        "One Undo did not restore the complete original project");
    require(
        ProjectIO.JSON
            .readTree(out.resolve("preferences-before.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())),
        "Source decisions modified preferences");
    System.out.println(
        "Guarded source engine-only PASS; six exact project transformations / one Undo / analytical"
            + " field 22 / applied 20 versus historical 30 / complete trace replay");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
