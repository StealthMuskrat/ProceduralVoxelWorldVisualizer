package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.Path;

/** Deferred engine-only source/current trace agreement, independent of document tokens and GUI. */
public final class TraceLifetimeConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    if (!ProjectIO.JSON
            .readTree(out.resolve("trace-current.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("current-after.json").toFile()))
        || !ProjectIO.JSON
            .readTree(out.resolve("preferences-before.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Trace presentation changed persisted project/preferences");
    for (boolean current : new boolean[] {false, true}) {
      var load =
          new ProjectLoader()
              .load(out.resolve(current ? "trace-current.json" : "trace-source.json"));
      if (!load.success()) throw new AssertionError(load.diagnostics());
      var compiled = new CompiledProject(load.project());
      if (compiled.noise2D("field", 42).sample(2, 3) != (2 + 2 * (2 + 1) + 3) * 2)
        throw new AssertionError("Canonical analytical field changed");
      try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20);
          var world = engine.openWorld(compiled)) {
        var pos = new BlockPos(2, 0, 3);
        var trace = world.trace(pos);
        if (trace.finalType() != (current ? 0 : 1)
            || world.getVoxelTypeId(pos) != trace.finalType())
          throw new AssertionError("Current/original exact voxel mismatch");
        if (trace.steps().size() != 2
            || !trace.steps().getFirst().action().equals(current ? "CLEAR" : "SET")
            || !trace.steps().getLast().action().equals("NO_CHANGE"))
          throw new AssertionError("Ordered current/original actions changed");
        if (!ProjectIO.JSON
            .valueToTree(trace)
            .equals(
                ProjectIO.JSON.readTree(
                    out.resolve(current ? "current-trace.json" : "original-trace.json").toFile())))
          throw new AssertionError(
              "Native trace receipt differs from an independent engine replay");
        for (int i = 0; i < 2; i++) {
          var step = trace.steps().get(i);
          var comparisons = step.details().comparisons();
          var threshold = comparisons.getLast();
          if (step.field() != 22
              || threshold.left() != 22
              || threshold.right() != (i == 0 ? 10 : 30)
              || threshold.passed() != (i == 0))
            throw new AssertionError("Independent comparison oracle changed");
        }
      }
    }
    System.out.println(
        "Trace lifetime engine-only PASS; original/current exact trace JSON, analytical field 22,"
            + " SET/CLEAR final voxels and comparison provenance");
  }
}
