package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.Path;

/** Deferred independent generation and complete persistence checks for source-link presentation. */
public final class TraceSourceConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    TraceConsumer.main(new String[] {out.resolve("source-project.json").toString()});
    if (!ProjectIO.JSON
            .readTree(out.resolve("source-project.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("after.json").toFile()))
        || !ProjectIO.JSON
            .readTree(out.resolve("preferences-before.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Source presentation changed complete project/preferences");
    var load = new ProjectLoader().load(out.resolve("after.json"));
    if (!load.success()) throw new AssertionError(load.diagnostics());
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20);
        var world = engine.openWorld(new CompiledProject(load.project()))) {
      var trace = world.trace(new BlockPos(2, 0, 3));
      if (!ProjectIO.JSON
          .valueToTree(trace)
          .equals(ProjectIO.JSON.readTree(out.resolve("source-trace.json").toFile())))
        throw new AssertionError("Source checks changed the canonical trace");
    }
    System.out.println(
        "Trace source engine-only PASS; canonical field 22, complete original trace JSON and"
            + " project/preferences equality");
  }
}
