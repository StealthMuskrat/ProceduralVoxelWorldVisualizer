package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.Path;
import java.util.List;

/** Deferred independent exact project edits, one Undo and full deterministic trace replay. */
public final class NavigationEligibilityConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    TraceSourceConsumer.main(args);
    var source = ProjectIO.JSON.readTree(out.resolve("source-project.json").toFile());
    var expected = source.deepCopy();
    ((ObjectNode) expected.path("noise2D").get(0)).put("id", "private-field");
    for (var mode : List.of("find", "reference")) {
      require(
          expected.equals(ProjectIO.JSON.readTree(out.resolve(mode + "-applied.json").toFile())),
          "Unexpected complete applied project for " + mode);
      require(
          source.equals(ProjectIO.JSON.readTree(out.resolve(mode + "-undo.json").toFile())),
          "One Undo changed other values for " + mode);
      var load = new ProjectLoader().load(out.resolve(mode + "-applied.json"));
      require(load.success(), load.diagnostics().toString());
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20);
          var world = engine.openWorld(new CompiledProject(load.project()))) {
        var pos = new BlockPos(2, 0, 3);
        var trace = world.trace(pos);
        require(
            trace.finalType() == 1
                && world.getVoxelTypeId(pos) == 1
                && trace.steps().size() == 2
                && trace.steps().stream().allMatch(step -> step.field() == 22),
            "Analytical field-22/world oracle changed");
        require(
            trace.steps().getFirst().action().equals("SET")
                && trace.steps().getLast().action().equals("NO_CHANGE"),
            "Navigation changed ordered threshold semantics");
        require(
            ProjectIO.JSON
                .valueToTree(trace)
                .equals(ProjectIO.JSON.readTree(out.resolve(mode + "-trace.json").toFile())),
            "Complete independent trace replay differs for " + mode);
      }
    }
    BackgroundNavigationConsumer.run(out.resolve("background-apply"));
    System.out.println(
        "Navigation eligibility engine-only PASS; exact Find/reference source rename / one Undo /"
            + " complete field-22 trace replay / unchanged final project/preferences");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
