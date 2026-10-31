package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.Path;

/** Deferred independent oracle separates failed target, older displayed result and entered XYZ. */
public final class InspectorRetryConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    TraceConsumer.main(new String[] {out.resolve("retry-project.json").toString()});
    if (!ProjectIO.JSON
            .readTree(out.resolve("retry-project.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("after.json").toFile()))
        || !ProjectIO.JSON
            .readTree(out.resolve("preferences-before.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Retry changed project/preferences");
    var load = new ProjectLoader().load(out.resolve("after.json"));
    if (!load.success()) throw new AssertionError(load.diagnostics());
    var compiled = new CompiledProject(load.project());
    for (int x : new int[] {-17, 2, 7})
      if (compiled.noise2D("field", 42).sample(x, 3) != (2 + 2 * (x + 1) + 3) * 2)
        throw new AssertionError("Independent retry coordinate field mismatch");
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20);
        var world = engine.openWorld(compiled)) {
      if (world.getVoxelTypeId(new BlockPos(-17, 0, 3)) != 0
          || world.getVoxelTypeId(new BlockPos(2, 0, 3)) != 1)
        throw new AssertionError("Entered/displayed canonical controls changed");
      var trace = world.trace(new BlockPos(7, 0, 3));
      if (trace.finalType() != 1
          || trace.steps().size() != 2
          || trace.steps().getFirst().field() != 42
          || trace.steps().getLast().field() != 42)
        throw new AssertionError("Retry did not evaluate the exact attempted target");
      if (!ProjectIO.JSON
          .valueToTree(trace)
          .equals(ProjectIO.JSON.readTree(out.resolve("retry-result.json").toFile())))
        throw new AssertionError("Native retry receipt differs from independent canonical trace");
    }
    System.out.println(
        "Inspector retry engine-only PASS; entered field -54/EMPTY, displayed field 22, exact"
            + " retried field 42 and complete trace replay");
  }
}
