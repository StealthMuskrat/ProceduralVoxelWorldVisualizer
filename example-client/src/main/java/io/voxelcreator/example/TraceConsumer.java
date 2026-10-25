package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;

/** Engine-only independent scalar, stage, provenance and immutable trace oracle. */
public final class TraceConsumer {
  public static void main(String[] args) throws Exception {
    var load = new ProjectLoader().load(Path.of(args[0]));
    require(load.success(), load.diagnostics().toString());
    var p = load.project();
    var compiled = new CompiledProject(p);
    var noise = p.noise2D.getFirst();
    var explanation = compiled.explainNoise(noise.uuid, 0, 2, 0, 3, 2);
    require(
        explanation.value() == 22 && explanation.events().size() == 2 && explanation.omitted() > 0,
        "Independent (2+2*(2+1)+3)*2 oracle / retention");
    try (var engine = new NoiseGeneratorEngine(2, 64, 256L << 20);
        var world = engine.openWorld(compiled)) {
      var pos = new BlockPos(2, 0, 3);
      var trace = world.trace(pos);
      require(
          trace.finalType() == 1 && world.getVoxelTypeId(pos) == 1, "Exact world/trace agreement");
      require(
          trace.steps().size() == 2
              && trace.steps().getFirst().action().equals("SET")
              && trace.steps().getLast().action().equals("NO_CHANGE"),
          "Ordered stages");
      var first = trace.steps().getFirst().details();
      var second = trace.steps().getLast().details();
      require(
          first.primaryField().value() == 22 && second.primaryField().value() == 22,
          "Canonical primary values");
      require(
          first
              .settingTargets()
              .get("threshold")
              .resourceUuid()
              .equals(p.worldGenerator.layers.getFirst().uuid),
          "Live layer origin");
      require(
          second
              .settingTargets()
              .get("threshold")
              .resourceUuid()
              .equals(p.worldGenerator.layers.getFirst().operations.getLast().uuid),
          "Override origin");
      require(
          first.comparisons().getLast().right() == 10 && first.comparisons().getLast().passed(),
          "22>=10");
      require(
          second.comparisons().getLast().right() == 30 && !second.comparisons().getLast().passed(),
          "22<30");
      require(
          second.gatePassed() == null && second.predicatePassed() == null,
          "Unreached outcomes remain null");
      require(
          world.trace(new BlockPos(2, 9, 3)).steps().isEmpty(), "Outside project Y remains EMPTY");
      noise.id = "mutated";
      require(
          first.primaryField().events().stream().anyMatch(e -> e.id().equals("field")),
          "Snapshot labels changed");
    }
    System.out.println(
        "TRACE_ENGINE_ONLY_PASS exact 22 / SET then rejected 30 threshold / immutable source / live"
            + " and override UUID origins / world agreement / bounded observations");
  }

  private static void require(boolean b, String m) {
    if (!b) throw new AssertionError(m);
  }
}
